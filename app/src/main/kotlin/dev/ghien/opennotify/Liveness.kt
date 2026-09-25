package dev.ghien.opennotify

import android.content.Context
import android.os.Process
import java.util.Calendar

/** Khoảng [start, end) mà listener được coi là "sống" (Android đã bind và nhận được thông báo). */
class UpInterval(val start: Long, val end: Long)

class DayUptime(val dayStart: Long, val slots: DoubleArray, val fraction: Double)

class UptimeReport(
    val connected: Boolean,
    val connectedSince: Long?,
    val last24h: Double,
    val days: List<DayUptime>
)

/**
 * Đo "app có thực sự nhận được thông báo, không bị Android giới hạn" theo kiểu uptime của server.
 *
 * Nguồn dữ liệu là các "phiên" (bảng listener_sessions): mỗi lần Android bind listener vào app
 * (onListenerConnected) mở một phiên, gắn với tiến trình đang chạy; phiên đóng khi bị unbind
 * (onListenerDisconnected / onDestroy) hoặc khi probe nhịp tim thấy listener không còn nói chuyện
 * được với hệ thống. Nhịp tim mỗi phút (và mỗi thông báo tới) cập nhật last_seen_ts.
 *
 *  - Khoảng thời gian trong một phiên = SỐNG, kể cả lúc máy ngủ sâu làm nhịp tim ngưng: tiến trình
 *    vẫn còn, hệ thống sẽ đánh thức nó khi có thông báo.
 *  - Tiến trình bị Android giết không kịp báo -> phiên coi như kết thúc tại last_seen_ts và khoảng
 *    trống tới lần bind lại tính là MẤT (đúng thứ cần quan sát: thông báo tới lúc đó bị bỏ lỡ).
 *  - Tắt máy cũng tính là mất. Trước lúc bắt đầu theo dõi là "chưa có dữ liệu", không tính vào %.
 */
object Liveness {

    const val HEARTBEAT_MS = 60_000L
    const val SLOT_MS = 15 * 60_000L
    const val SLOTS_PER_DAY = 96
    const val DAY_MS = 24 * 60 * 60_000L
    const val NO_DATA = -1.0

    /** Định danh một lần chạy của tiến trình (pid + thời điểm tiến trình khởi động). */
    val procKey: String by lazy { "${Process.myPid()}:${Process.getStartElapsedRealtime()}" }

    private const val MIN_BEAT_GAP_MS = 20_000L

    @Volatile
    private var lastBeat = 0L

    // ---- ghi (gọi từ NotifyListenerService) ----

    fun onConnected(context: Context) {
        val now = System.currentTimeMillis()
        Db.get(context).sessionStarted(procKey, now)
        lastBeat = now
    }

    fun onDisconnected(context: Context) {
        Db.get(context).sessionEnded(procKey, System.currentTimeMillis())
        lastBeat = 0L
    }

    /**
     * Ghi nhận "vừa thấy sống". Nếu phiên đã bị đóng (probe từng thất bại) mà giờ lại sống thì
     * mở phiên mới. force = false: bỏ qua nếu vừa ghi < 20s (thông báo dồn dập không cần ghi DB liên tục).
     */
    fun markAlive(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastBeat < MIN_BEAT_GAP_MS) return
        val db = Db.get(context)
        if (!db.sessionHeartbeat(procKey, now)) db.sessionStarted(procKey, now)
        lastBeat = now
    }

    // ---- tính toán thuần (không đụng Android/DB) ----

    fun intervals(sessions: List<ListenerSession>, currentKey: String, now: Long): List<UpInterval> =
        sessions.mapNotNull { s ->
            val closedAt = s.endTs
            val end = when {
                closedAt != null -> closedAt
                s.procKey == currentKey -> now // phiên của chính tiến trình này, đang mở
                else -> s.lastSeenTs // tiến trình cũ đã chết không báo
            }
            val clipped = minOf(end, now)
            if (clipped > s.startTs) UpInterval(s.startTs, clipped) else null
        }

    /**
     * Tỉ lệ thời gian "sống" (0..1) của từng ô [from + i*slotMs, +slotMs). NO_DATA nếu ô nằm
     * trước trackStart hoặc trong tương lai; ô đang dở dang chỉ tính phần đã trôi qua.
     */
    fun slotUptime(
        intervals: List<UpInterval>, from: Long, slotMs: Long, slots: Int, trackStart: Long, now: Long
    ): DoubleArray {
        val out = DoubleArray(slots)
        for (i in 0 until slots) {
            val knownStart = maxOf(from + i * slotMs, trackStart)
            val knownEnd = minOf(from + (i + 1) * slotMs, now)
            val known = knownEnd - knownStart
            if (known <= 0) {
                out[i] = NO_DATA
                continue
            }
            var up = 0L
            for (iv in intervals) {
                val o = minOf(iv.end, knownEnd) - maxOf(iv.start, knownStart)
                if (o > 0) up += o
            }
            out[i] = (up.toDouble() / known).coerceIn(0.0, 1.0)
        }
        return out
    }

    fun startOfDay(now: Long, daysBack: Int = 0): Long =
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -daysBack)
        }.timeInMillis

    /** Đọc DB và dựng báo cáo: 24 giờ gần nhất + từng ngày (mỗi ngày 96 ô 15 phút), hôm nay ở đầu. */
    fun report(context: Context, now: Long = System.currentTimeMillis(), days: Int = 7): UptimeReport {
        val db = Db.get(context)
        val sessions = db.listenerSessions(since = startOfDay(now, days - 1))
        val ivs = intervals(sessions, procKey, now)
        val trackStart = db.trackingStart() ?: Long.MAX_VALUE

        val perDay = (0 until days).map { back ->
            val start = startOfDay(now, back)
            DayUptime(
                dayStart = start,
                slots = slotUptime(ivs, start, SLOT_MS, SLOTS_PER_DAY, trackStart, now),
                fraction = slotUptime(ivs, start, DAY_MS, 1, trackStart, now)[0]
            )
        }
        val open = sessions.lastOrNull { it.endTs == null && it.procKey == procKey }
        return UptimeReport(
            connected = open != null,
            connectedSince = open?.startTs,
            last24h = slotUptime(ivs, now - DAY_MS, DAY_MS, 1, trackStart, now)[0],
            days = perDay
        )
    }

    /** "99,87%" — cắt (không làm tròn) 2 chữ số để 99,996% không bị hiện thành 100%. */
    fun formatPercent(fraction: Double): String {
        if (fraction < 0) return "—"
        return String.format("%.2f%%", Math.floor(fraction * 10_000) / 100)
    }
}

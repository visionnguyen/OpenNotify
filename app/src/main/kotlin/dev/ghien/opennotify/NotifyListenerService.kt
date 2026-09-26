package dev.ghien.opennotify

import android.app.Notification
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/**
 * Chỉ ghi nhận thông báo của những ứng dụng đã được thêm vào danh sách
 * theo dõi (tracked_apps); mọi ứng dụng khác bị bỏ qua hoàn toàn. Với mỗi
 * thông báo được ghi, đối chiếu từng webhook đang bật của ứng dụng đó
 * (URL/secret + bộ pattern riêng) và gửi tới các webhook khớp.
 */
class NotifyListenerService : NotificationListenerService() {

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    /** Nhịp tim mỗi phút: chứng minh listener còn nói chuyện được với hệ thống (dữ liệu cho biểu đồ uptime). */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (probeConnected()) {
                Liveness.markAlive(applicationContext, force = true)
            } else {
                // Hệ thống đã cắt kết nối mà không báo: ghi nhận mất + xin bind lại.
                Liveness.onDisconnected(applicationContext)
                requestRebind(ComponentName(this@NotifyListenerService, NotifyListenerService::class.java))
            }
            handler.postDelayed(this, Liveness.HEARTBEAT_MS)
        }
    }

    /** Gọi rỗng (không kéo về thông báo nào) chỉ để kiểm tra binder tới hệ thống còn sống. */
    private fun probeConnected(): Boolean =
        try {
            getActiveNotifications(arrayOf<String>()) != null
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Có thông báo tới (của bất kỳ app nào) nghĩa là Android đang giao được cho mình.
        Liveness.markAlive(applicationContext)
        handle(sbn, live = true)
    }

    /**
     * Xử lý một thông báo: lưu nếu thuộc ứng dụng đang theo dõi, rồi gửi tới các webhook khớp.
     * live = false khi đọc bù lúc vừa kết nối lại: không phát âm thanh cho thông báo cũ.
     * Dedupe theo hash nên đọc bù không tạo bản trùng với thông báo đã ghi trước đó.
     */
    private fun handle(sbn: StatusBarNotification, live: Boolean) {
        // Bỏ qua thông báo của chính app này (ví dụ notification keep-alive).
        if (sbn.packageName == applicationContext.packageName) return

        val db = Db.get(applicationContext)
        if (!db.isTracked(sbn.packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }.orEmpty()

        val hash = Signer.sha1Hex("${sbn.packageName}|${sbn.key}|${sbn.postTime}|$text|$bigText")
        val record = NotificationRecord(
            hash = hash, pkg = sbn.packageName, key = sbn.key ?: "", postTime = sbn.postTime,
            title = title, text = text, bigText = bigText, subText = subText, lines = lines,
            ts = System.currentTimeMillis()
        )

        if (!db.insertNotification(record)) return // đã thấy thông báo này rồi

        if (live) SoundPlayer.playIfEnabled(applicationContext)

        val targets = db.webhooksForPackage(sbn.packageName, onlyEnabled = true)
            .filter { PatternMatcher.matches(it, PatternMatcher.inputFor(it, record)) }
            // Đọc bù thông báo đã nằm trên máy quá lâu: máy quầy mapchat không cần nữa.
            .filter { live || it.kind != WebhookKind.MAPCHAT || record.ts - record.postTime < Sender.MAPCHAT_MAX_AGE_MS }
        if (targets.isEmpty()) return

        // Ghi nhận trước khi gửi: nếu app bị kill giữa chừng, OutboxWorker vẫn gửi bù.
        targets.forEach { db.addDelivery(hash, it.id) }
        relay(record, targets)
    }

    private fun relay(record: NotificationRecord, targets: List<Webhook>) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "opennotify:deliver"
        ).apply { acquire(120_000L) }

        io.execute {
            try {
                val db = Db.get(applicationContext)
                for (w in targets) {
                    var outcome = SendOutcome.RETRY
                    for (delay in Sender.immediateDelaysMs(w)) {
                        if (delay > 0) Thread.sleep(delay)
                        outcome = Sender.send(w, record)
                        if (outcome != SendOutcome.RETRY) break
                    }
                    // RETRY: dòng deliveries sent=0 vẫn còn, OutboxWorker sẽ thử lại theo chu kỳ.
                    Sender.record(db, record.hash, w, outcome)
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        OutboxWorker.schedule(applicationContext)
        Liveness.onConnected(applicationContext)
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, Liveness.HEARTBEAT_MS)
        io.execute { backfill() }
    }

    /**
     * Lúc bị ngắt kết nối (app bị tắt, ROM chặn tự khởi chạy...) Android không giao thông báo cho
     * mình và cũng không giao bù. Những thông báo vẫn còn trên thanh thông báo thì đọc lại được ở đây;
     * cái người dùng đã vuốt bỏ hoặc app nguồn tự hủy trong lúc đó thì mất hẳn.
     */
    private fun backfill() {
        val active = try {
            activeNotifications
        } catch (_: RuntimeException) {
            null
        } ?: return
        for (sbn in active) {
            try {
                handle(sbn, live = false)
            } catch (_: RuntimeException) {
                // Một thông báo lỗi không được chặn các thông báo còn lại.
            }
        }
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(heartbeat)
        Liveness.onDisconnected(applicationContext)
        requestRebind(ComponentName(this, NotifyListenerService::class.java))
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        Liveness.onDisconnected(applicationContext)
        super.onDestroy()
    }
}

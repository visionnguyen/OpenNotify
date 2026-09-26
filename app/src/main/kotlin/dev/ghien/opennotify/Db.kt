package dev.ghien.opennotify

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class NotificationRecord(
    val hash: String,
    val pkg: String,
    val key: String,
    val postTime: Long,
    val title: String,
    val text: String,
    val bigText: String,
    val subText: String,
    val lines: String,
    val ts: Long,
    val read: Boolean = false
)

/** Ứng dụng người dùng đã thêm vào danh sách theo dõi. */
data class TrackedApp(val pkg: String, val label: String, val addedTs: Long)

/** Cách kết hợp nhiều pattern của một webhook. */
enum class MatchMode { AND, OR }

/**
 * Một webhook của một ứng dụng. Mỗi ứng dụng có thể có nhiều webhook, mỗi
 * webhook có URL/secret và bộ pattern (regex) riêng. patterns rỗng = nhận
 * mọi thông báo của ứng dụng đó.
 */
data class Webhook(
    val id: Long,
    val pkg: String,
    val name: String,
    val url: String,
    val secret: String,
    val mode: MatchMode,
    val enabled: Boolean,
    val patterns: List<String>
)

data class PendingDelivery(val notification: NotificationRecord, val webhook: Webhook)

data class DeliveryStatus(val webhookName: String, val sent: Boolean)

/**
 * Một lượt listener được Android bind vào tiến trình `procKey`. endTs = null
 * nghĩa là chưa có ai báo kết thúc (đang chạy, hoặc tiến trình đã chết mà
 * không kịp báo — khi đó lastSeenTs là lần cuối còn thấy sống).
 */
data class ListenerSession(val procKey: String, val startTs: Long, val lastSeenTs: Long, val endTs: Long?)

/**
 * Chỉ lưu thông báo của những ứng dụng đã được thêm vào (tracked_apps).
 * Mỗi thông báo được đối chiếu với từng webhook đang bật của ứng dụng đó;
 * webhook nào khớp thì có một dòng trong deliveries (sent=0 cho tới khi
 * gửi thành công) — xem NotifyListenerService và OutboxWorker.
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "opennotify.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """create table tracked_apps(
                package text primary key,
                label text not null,
                added_ts integer not null
            )"""
        )

        db.execSQL(
            """create table notifications(
                hash text primary key,
                package text not null,
                key text,
                post_time integer not null,
                title text,
                text text,
                big_text text,
                sub_text text,
                lines text,
                ts integer not null,
                is_read integer not null default 0,
                is_deleted integer not null default 0
            )"""
        )
        db.execSQL("create index idx_notif_package on notifications(package, ts)")

        db.execSQL(
            """create table webhooks(
                id integer primary key autoincrement,
                package text not null,
                name text not null,
                url text not null,
                secret text not null,
                match_mode text not null default 'OR',
                enabled integer not null default 1,
                created_ts integer not null
            )"""
        )
        db.execSQL("create index idx_webhook_package on webhooks(package)")

        db.execSQL(
            """create table webhook_patterns(
                id integer primary key autoincrement,
                webhook_id integer not null,
                pattern text not null
            )"""
        )
        db.execSQL("create index idx_pattern_webhook on webhook_patterns(webhook_id)")

        db.execSQL(
            """create table deliveries(
                hash text not null,
                webhook_id integer not null,
                sent integer not null default 0,
                primary key(hash, webhook_id)
            )"""
        )
        db.execSQL("create index idx_delivery_pending on deliveries(sent)")

        createSessionsTable(db)
    }

    private fun createSessionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """create table listener_sessions(
                id integer primary key autoincrement,
                proc_key text not null,
                start_ts integer not null,
                last_seen_ts integer not null,
                end_ts integer
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            // Mô hình cũ (ghi mọi thông báo + luật relay toàn cục, một webhook chung)
            // không tương thích mô hình mới (theo dõi từng ứng dụng, webhook riêng);
            // đây là công cụ nội bộ nên reset sạch, người dùng cấu hình lại.
            for (t in listOf(
                "events", "notifications", "relay_rules",
                "tracked_apps", "webhooks", "webhook_patterns", "deliveries", "listener_sessions"
            )) db.execSQL("drop table if exists $t")
            onCreate(db)
            return
        }
        if (oldVersion < 4) {
            // Giữ nguyên dữ liệu đang có; chỉ thêm cột xóa mềm + bảng đo uptime.
            db.execSQL("alter table notifications add column is_deleted integer not null default 0")
            createSessionsTable(db)
        }
    }

    // ---- tracked_apps ----

    fun trackedApps(): List<TrackedApp> {
        val out = mutableListOf<TrackedApp>()
        readableDatabase.rawQuery(
            "select package, label, added_ts from tracked_apps order by label collate nocase", null
        ).use { c -> while (c.moveToNext()) out.add(TrackedApp(c.getString(0), c.getString(1), c.getLong(2))) }
        return out
    }

    fun trackedApp(pkg: String): TrackedApp? =
        readableDatabase.rawQuery(
            "select package, label, added_ts from tracked_apps where package = ?", arrayOf(pkg)
        ).use { c -> if (c.moveToFirst()) TrackedApp(c.getString(0), c.getString(1), c.getLong(2)) else null }

    fun isTracked(pkg: String): Boolean =
        readableDatabase.rawQuery(
            "select 1 from tracked_apps where package = ?", arrayOf(pkg)
        ).use { it.moveToFirst() }

    fun trackedPackages(): Set<String> =
        trackedApps().mapTo(HashSet()) { it.pkg }

    fun addTrackedApps(apps: List<Pair<String, String>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for ((pkg, label) in apps) {
                val cv = ContentValues().apply {
                    put("package", pkg)
                    put("label", label)
                    put("added_ts", now)
                }
                db.insertWithOnConflict("tracked_apps", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Bỏ theo dõi: xóa luôn thông báo, webhook, pattern và lịch sử gửi của ứng dụng đó. */
    fun removeTrackedApp(pkg: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = "select id from webhooks where package = ?"
            db.execSQL("delete from deliveries where webhook_id in ($ids)", arrayOf(pkg))
            db.execSQL("delete from webhook_patterns where webhook_id in ($ids)", arrayOf(pkg))
            db.delete("webhooks", "package = ?", arrayOf(pkg))
            db.delete("notifications", "package = ?", arrayOf(pkg))
            db.delete("tracked_apps", "package = ?", arrayOf(pkg))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- notifications ----

    /** true nếu đây là bản ghi mới (hash chưa từng thấy). */
    fun insertNotification(r: NotificationRecord): Boolean {
        val cv = ContentValues().apply {
            put("hash", r.hash)
            put("package", r.pkg)
            put("key", r.key)
            put("post_time", r.postTime)
            put("title", r.title)
            put("text", r.text)
            put("big_text", r.bigText)
            put("sub_text", r.subText)
            put("lines", r.lines)
            put("ts", r.ts)
            put("is_read", if (r.read) 1 else 0)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "notifications", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        return rowId != -1L
    }

    /** package -> số thông báo chưa đọc (chỉ gồm package có ít nhất 1 chưa đọc). */
    fun unreadCounts(): Map<String, Int> {
        val out = HashMap<String, Int>()
        readableDatabase.rawQuery(
            "select package, count(*) from notifications where is_read = 0 and is_deleted = 0 group by package", null
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) }
        return out
    }

    fun markPackageRead(pkg: String) {
        val cv = ContentValues().apply { put("is_read", 1) }
        writableDatabase.update("notifications", cv, "package = ? and is_read = 0", arrayOf(pkg))
    }

    fun notificationsForPackage(pkg: String, limit: Int = 300): List<NotificationRecord> {
        val out = mutableListOf<NotificationRecord>()
        readableDatabase.rawQuery(
            "$NOTIF_COLUMNS from notifications where package = ? and is_deleted = 0 order by ts desc limit ?",
            arrayOf(pkg, limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(readRecord(c)) }
        return out
    }

    /**
     * Xóa mềm: ẩn khỏi danh sách nhưng giữ dòng gốc tới khi prune (30 ngày). Nhờ vậy
     * webhook chưa gửi được vẫn gửi bù, và thông báo bị app nguồn đăng lại không
     * bị ghi + gửi trùng (dedupe theo hash).
     */
    fun deleteNotification(hash: String) {
        val cv = ContentValues().apply { put("is_deleted", 1) }
        writableDatabase.update("notifications", cv, "hash = ?", arrayOf(hash))
    }

    fun deleteAllNotifications(pkg: String) {
        val cv = ContentValues().apply { put("is_deleted", 1) }
        writableDatabase.update("notifications", cv, "package = ? and is_deleted = 0", arrayOf(pkg))
    }

    private fun readRecord(c: Cursor) = NotificationRecord(
        hash = c.getString(0), pkg = c.getString(1), key = c.getString(2) ?: "",
        postTime = c.getLong(3), title = c.getString(4) ?: "", text = c.getString(5) ?: "",
        bigText = c.getString(6) ?: "", subText = c.getString(7) ?: "", lines = c.getString(8) ?: "",
        ts = c.getLong(9), read = c.getInt(10) == 1
    )

    /** Xóa bớt thông báo cũ hơn olderThanMs để DB không phình vô hạn. */
    fun prune(olderThanMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(
                "notifications", "ts < ?",
                arrayOf((System.currentTimeMillis() - olderThanMs).toString())
            )
            db.execSQL("delete from deliveries where hash not in (select hash from notifications)")
            db.delete(
                // cast bắt buộc: coalesce(...) không có affinity nên tham số chuỗi không được đổi
                // sang số, mà INTEGER < TEXT luôn đúng trong SQLite -> từng xóa sạch mọi phiên.
                "listener_sessions", "coalesce(end_ts, last_seen_ts) < cast(? as integer)",
                arrayOf((System.currentTimeMillis() - SESSION_RETENTION_MS).toString())
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- webhooks ----

    fun webhooksForPackage(pkg: String, onlyEnabled: Boolean = false): List<Webhook> =
        queryWebhooks(
            "where package = ?" + if (onlyEnabled) " and enabled = 1" else "", arrayOf(pkg)
        )

    fun webhook(id: Long): Webhook? = queryWebhooks("where id = ?", arrayOf(id.toString())).firstOrNull()

    private fun queryWebhooks(where: String, args: Array<String>): List<Webhook> {
        val db = readableDatabase
        val rows = mutableListOf<Webhook>()
        db.rawQuery(
            "select id, package, name, url, secret, match_mode, enabled from webhooks $where order by id",
            args
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Webhook(
                        id = c.getLong(0), pkg = c.getString(1), name = c.getString(2),
                        url = c.getString(3), secret = c.getString(4),
                        mode = if (c.getString(5) == MatchMode.AND.name) MatchMode.AND else MatchMode.OR,
                        enabled = c.getInt(6) == 1, patterns = emptyList()
                    )
                )
            }
        }
        return rows.map { it.copy(patterns = patternsOf(it.id)) }
    }

    private fun patternsOf(webhookId: Long): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "select pattern from webhook_patterns where webhook_id = ? order by id",
            arrayOf(webhookId.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** Thêm mới (id = 0) hoặc cập nhật webhook, thay toàn bộ pattern. Trả về id. */
    fun saveWebhook(w: Webhook): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("package", w.pkg)
                put("name", w.name)
                put("url", w.url)
                put("secret", w.secret)
                put("match_mode", w.mode.name)
                put("enabled", if (w.enabled) 1 else 0)
            }
            val id = if (w.id == 0L) {
                cv.put("created_ts", System.currentTimeMillis())
                db.insert("webhooks", null, cv)
            } else {
                db.update("webhooks", cv, "id = ?", arrayOf(w.id.toString()))
                w.id
            }
            db.delete("webhook_patterns", "webhook_id = ?", arrayOf(id.toString()))
            for (p in w.patterns) {
                val pv = ContentValues().apply {
                    put("webhook_id", id)
                    put("pattern", p)
                }
                db.insert("webhook_patterns", null, pv)
            }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun setWebhookEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        writableDatabase.update("webhooks", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteWebhook(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val arg = arrayOf(id.toString())
            db.delete("deliveries", "webhook_id = ?", arg)
            db.delete("webhook_patterns", "webhook_id = ?", arg)
            db.delete("webhooks", "id = ?", arg)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- deliveries ----

    fun addDelivery(hash: String, webhookId: Long) {
        val cv = ContentValues().apply {
            put("hash", hash)
            put("webhook_id", webhookId)
            put("sent", 0)
        }
        writableDatabase.insertWithOnConflict("deliveries", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun markDelivered(hash: String, webhookId: Long) {
        val cv = ContentValues().apply { put("sent", 1) }
        writableDatabase.update(
            "deliveries", cv, "hash = ? and webhook_id = ?", arrayOf(hash, webhookId.toString())
        )
    }

    /** Các lượt gửi chưa thành công tới webhook còn tồn tại và đang bật. */
    fun pendingDeliveries(limit: Int = 100): List<PendingDelivery> {
        val pairs = mutableListOf<Pair<String, Long>>()
        readableDatabase.rawQuery(
            """select d.hash, d.webhook_id
               from deliveries d
               join webhooks w on w.id = d.webhook_id and w.enabled = 1
               join notifications n on n.hash = d.hash
               where d.sent = 0
               order by n.ts asc limit ?""",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) pairs.add(c.getString(0) to c.getLong(1)) }

        val webhooks = HashMap<Long, Webhook?>()
        val out = mutableListOf<PendingDelivery>()
        for ((hash, wid) in pairs) {
            val w = webhooks.getOrPut(wid) { webhook(wid) } ?: continue
            val n = notification(hash) ?: continue
            out.add(PendingDelivery(n, w))
        }
        return out
    }

    private fun notification(hash: String): NotificationRecord? =
        readableDatabase.rawQuery("$NOTIF_COLUMNS from notifications where hash = ?", arrayOf(hash))
            .use { c -> if (c.moveToFirst()) readRecord(c) else null }

    fun deliveryStatuses(hash: String): List<DeliveryStatus> {
        val out = mutableListOf<DeliveryStatus>()
        readableDatabase.rawQuery(
            """select w.name, d.sent from deliveries d
               join webhooks w on w.id = d.webhook_id
               where d.hash = ? order by w.id""",
            arrayOf(hash)
        ).use { c -> while (c.moveToNext()) out.add(DeliveryStatus(c.getString(0), c.getInt(1) == 1)) }
        return out
    }

    // ---- listener_sessions (đo uptime, xem Liveness) ----

    /**
     * Listener vừa được bind. Phiên còn mở của tiến trình KHÁC là của tiến trình đã chết
     * mà không báo -> chốt tại lần cuối còn thấy sống; phiên còn mở của chính tiến trình
     * này (bind lặp không qua disconnect) -> chốt tại now.
     */
    fun sessionStarted(procKey: String, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "update listener_sessions set end_ts = case when proc_key = ? then ? else last_seen_ts end where end_ts is null",
                arrayOf(procKey, now)
            )
            val cv = ContentValues().apply {
                put("proc_key", procKey)
                put("start_ts", now)
                put("last_seen_ts", now)
            }
            db.insert("listener_sessions", null, cv)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** true nếu tiến trình này đang có phiên mở và đã được ghi nhận còn sống lúc now. */
    fun sessionHeartbeat(procKey: String, now: Long): Boolean {
        val cv = ContentValues().apply { put("last_seen_ts", now) }
        return writableDatabase.update(
            "listener_sessions", cv, "proc_key = ? and end_ts is null", arrayOf(procKey)
        ) > 0
    }

    fun sessionEnded(procKey: String, now: Long) {
        val cv = ContentValues().apply {
            put("end_ts", now)
            put("last_seen_ts", now)
        }
        writableDatabase.update("listener_sessions", cv, "proc_key = ? and end_ts is null", arrayOf(procKey))
    }

    /** Các phiên còn giao với khoảng [since, ∞), cũ -> mới. */
    fun listenerSessions(since: Long): List<ListenerSession> {
        val out = mutableListOf<ListenerSession>()
        readableDatabase.rawQuery(
            """select proc_key, start_ts, last_seen_ts, end_ts from listener_sessions
               where coalesce(end_ts, last_seen_ts) >= cast(? as integer) order by start_ts""",
            arrayOf(since.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ListenerSession(
                        c.getString(0), c.getLong(1), c.getLong(2),
                        if (c.isNull(3)) null else c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    /** Thời điểm bắt đầu có dữ liệu uptime (trước đó là "chưa có dữ liệu", không phải "mất"). */
    fun trackingStart(): Long? =
        readableDatabase.rawQuery("select min(start_ts) from listener_sessions", null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    companion object {
        private const val SESSION_RETENTION_MS = 14L * 24 * 60 * 60 * 1000

        private const val NOTIF_COLUMNS =
            "select hash, package, key, post_time, title, text, big_text, sub_text, lines, ts, is_read"

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) {
                instance ?: Db(context.applicationContext).also { instance = it }
            }
    }
}

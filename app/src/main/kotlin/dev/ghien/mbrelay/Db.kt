package dev.ghien.mbrelay

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
    val relayMatched: Boolean,
    val relaySent: Boolean
)

data class SourceSummary(val pkg: String, val count: Int, val lastTs: Long)

data class RelayRule(
    val id: Long,
    val pkg: String,
    val pattern: String?,
    val enabled: Boolean,
    val createdTs: Long
)

/**
 * Lưu TOÀN BỘ thông báo bắt được (mặc định, không lọc theo nguồn).
 * Việc lọc chỉ quyết định "có relay ra webhook hay không", dựa trên
 * bảng relay_rules — xem MbListenerService để biết logic khớp.
 */
class Db(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "mb_relay.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
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
                relay_matched integer not null default 0,
                relay_sent integer not null default 0
            )"""
        )
        db.execSQL("create index idx_notif_package on notifications(package)")
        db.execSQL("create index idx_notif_pending on notifications(relay_matched, relay_sent)")

        db.execSQL(
            """create table relay_rules(
                id integer primary key autoincrement,
                package text not null,
                pattern text,
                enabled integer not null default 1,
                created_ts integer not null
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Schema v1 (bảng "events") chỉ dùng cho giai đoạn thử nghiệm ban đầu,
        // chưa có dữ liệu quan trọng cần giữ — reset sạch sang schema v2.
        db.execSQL("drop table if exists events")
        db.execSQL("drop table if exists notifications")
        db.execSQL("drop table if exists relay_rules")
        onCreate(db)
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
            put("relay_matched", if (r.relayMatched) 1 else 0)
            put("relay_sent", if (r.relaySent) 1 else 0)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "notifications", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        return rowId != -1L
    }

    fun markRelaySent(hash: String) {
        val cv = ContentValues().apply { put("relay_sent", 1) }
        writableDatabase.update("notifications", cv, "hash = ?", arrayOf(hash))
    }

    fun pendingRelay(limit: Int = 100): List<NotificationRecord> {
        val out = mutableListOf<NotificationRecord>()
        readableDatabase.rawQuery(
            """select hash, package, key, post_time, title, text, big_text, sub_text, lines,
                      ts, relay_matched, relay_sent
               from notifications
               where relay_matched = 1 and relay_sent = 0
               order by ts asc limit ?""",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(readRecord(c)) }
        return out
    }

    fun sourceSummaries(): List<SourceSummary> {
        val out = mutableListOf<SourceSummary>()
        readableDatabase.rawQuery(
            """select package, count(*) c, max(ts) last
               from notifications group by package order by last desc""",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(SourceSummary(c.getString(0), c.getInt(1), c.getLong(2)))
        }
        return out
    }

    fun notificationsForPackage(pkg: String, limit: Int = 300): List<NotificationRecord> {
        val out = mutableListOf<NotificationRecord>()
        readableDatabase.rawQuery(
            """select hash, package, key, post_time, title, text, big_text, sub_text, lines,
                      ts, relay_matched, relay_sent
               from notifications where package = ? order by ts desc limit ?""",
            arrayOf(pkg, limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(readRecord(c)) }
        return out
    }

    private fun readRecord(c: Cursor) = NotificationRecord(
        hash = c.getString(0), pkg = c.getString(1), key = c.getString(2) ?: "",
        postTime = c.getLong(3), title = c.getString(4) ?: "", text = c.getString(5) ?: "",
        bigText = c.getString(6) ?: "", subText = c.getString(7) ?: "", lines = c.getString(8) ?: "",
        ts = c.getLong(9), relayMatched = c.getInt(10) == 1, relaySent = c.getInt(11) == 1
    )

    /** Xóa bớt thông báo cũ hơn olderThanMs để DB không phình vô hạn. */
    fun prune(olderThanMs: Long) {
        writableDatabase.delete(
            "notifications", "ts < ?",
            arrayOf((System.currentTimeMillis() - olderThanMs).toString())
        )
    }

    // ---- relay_rules ----

    fun addRule(pkg: String, pattern: String?, enabled: Boolean): Long {
        val cv = ContentValues().apply {
            put("package", pkg)
            put("pattern", pattern)
            put("enabled", if (enabled) 1 else 0)
            put("created_ts", System.currentTimeMillis())
        }
        return writableDatabase.insert("relay_rules", null, cv)
    }

    fun setRuleEnabled(id: Long, enabled: Boolean) {
        val cv = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        writableDatabase.update("relay_rules", cv, "id = ?", arrayOf(id.toString()))
    }

    fun deleteRule(id: Long) {
        writableDatabase.delete("relay_rules", "id = ?", arrayOf(id.toString()))
    }

    fun allRules(): List<RelayRule> {
        val out = mutableListOf<RelayRule>()
        readableDatabase.rawQuery(
            "select id, package, pattern, enabled, created_ts from relay_rules order by created_ts desc",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    RelayRule(
                        id = c.getLong(0), pkg = c.getString(1), pattern = c.getString(2),
                        enabled = c.getInt(3) == 1, createdTs = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    /** Chỉ rule đang bật, đúng package — dùng lúc quyết định có relay hay không. */
    fun rulesForPackage(pkg: String): List<RelayRule> =
        allRules().filter { it.pkg == pkg && it.enabled }
}

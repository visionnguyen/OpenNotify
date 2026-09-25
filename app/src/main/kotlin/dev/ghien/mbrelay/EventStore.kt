package dev.ghien.mbrelay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class StoredEvent(val hash: String, val payload: String)

/**
 * Hàng đợi sự kiện bền vững bằng SQLite, tương đương bảng events
 * trong bản Python trước đó. Mọi sự kiện được ghi ở đây trước khi
 * gửi đi, nên mất mạng hay app bị kill giữa chừng cũng không mất dữ liệu.
 */
class EventStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "mb_relay.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """create table events(
                hash text primary key,
                payload text not null,
                sent integer not null default 0,
                ts integer not null
            )"""
        )
        db.execSQL("create index idx_sent on events(sent)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** true nếu đây là sự kiện mới (chưa từng thấy hash này). */
    fun insertIfNew(hash: String, payload: String): Boolean {
        val cv = ContentValues().apply {
            put("hash", hash)
            put("payload", payload)
            put("sent", 0)
            put("ts", System.currentTimeMillis())
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "events", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        return rowId != -1L
    }

    fun markSent(hash: String) {
        val cv = ContentValues().apply { put("sent", 1) }
        writableDatabase.update("events", cv, "hash = ?", arrayOf(hash))
    }

    fun unsent(limit: Int = 50): List<StoredEvent> {
        val out = mutableListOf<StoredEvent>()
        readableDatabase.rawQuery(
            "select hash, payload from events where sent = 0 order by ts asc limit ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(StoredEvent(c.getString(0), c.getString(1)))
        }
        return out
    }

    fun recent(limit: Int = 30): List<StoredEvent> {
        val out = mutableListOf<StoredEvent>()
        readableDatabase.rawQuery(
            "select hash, payload from events order by ts desc limit ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(StoredEvent(c.getString(0), c.getString(1)))
        }
        return out
    }

    /** Dọn bớt sự kiện đã gửi thành công và cũ hơn olderThanMs. */
    fun prune(olderThanMs: Long) {
        writableDatabase.delete(
            "events", "sent = 1 and ts < ?",
            arrayOf((System.currentTimeMillis() - olderThanMs).toString())
        )
    }
}

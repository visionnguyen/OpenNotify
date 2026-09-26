package dev.ghien.opennotify

import org.json.JSONObject

/**
 * Payload chuẩn OpenNotify Webhook v1 (docs/webhook-standard.md) — bản rõ, dùng chung cho mọi chế
 * độ bảo mật và mọi đường gửi (gửi ngay, OutboxWorker, gửi thử) để không bao giờ lệch định dạng.
 */
object Payload {

    /** Các trường thô, bỏ trước tiên khi gói AES-GCM vượt trần kích thước. */
    val RAW_FIELDS = listOf("title", "body", "big_text", "sub_text", "lines")

    /** `text` của chuẩn: nối mọi phần chữ có nội dung. Cũng chính là chuỗi đem so pattern. */
    fun textOf(n: NotificationRecord): String =
        listOf(n.title, n.text, n.bigText, n.subText, n.lines).filter { it.isNotBlank() }.joinToString(" ")

    fun json(n: NotificationRecord): JSONObject = build(
        pkg = n.pkg, key = n.key, postTime = n.postTime, at = n.ts, text = textOf(n),
        title = n.title, body = n.text, bigText = n.bigText, subText = n.subText, lines = n.lines
    )

    fun from(n: NotificationRecord): String = json(n).toString()

    fun build(
        pkg: String, key: String, postTime: Long, at: Long, text: String,
        title: String = "", body: String = "", bigText: String = "", subText: String = "", lines: String = ""
    ): JSONObject = JSONObject().apply {
        put("v", 1)
        put("source", "opennotify")
        put("pkg", pkg)
        put("key", key)
        put("post_time", postTime)
        put("at", at)
        put("text", text)
        put("title", title)
        put("body", body)
        put("big_text", bigText)
        put("sub_text", subText)
        put("lines", lines)
    }

    /** Payload cho nút "Gửi thử": không chứa gì giống giao dịch hay mã đơn. */
    fun test(now: Long = System.currentTimeMillis()): JSONObject =
        build(
            pkg = "opennotify.test", key = "test-$now", postTime = now, at = now,
            text = "OpenNotify thử kết nối — không phải giao dịch", title = "OpenNotify",
            body = "OpenNotify thử kết nối — không phải giao dịch"
        )
}

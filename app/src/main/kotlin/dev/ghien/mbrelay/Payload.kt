package dev.ghien.mbrelay

import org.json.JSONObject

/** Payload gửi tới webhook — dùng chung giữa gửi ngay (MbListenerService)
 *  và gửi lại từ hàng đợi (OutboxWorker) để tránh lệch định dạng. */
object Payload {
    fun from(n: NotificationRecord): String = JSONObject().apply {
        put("source", "mb-relay")
        put("package", n.pkg)
        put("key", n.key)
        put("post_time", n.postTime)
        put("title", n.title)
        put("text", n.text)
        put("big_text", n.bigText)
        put("sub_text", n.subText)
        put("lines", n.lines)
    }.toString()
}

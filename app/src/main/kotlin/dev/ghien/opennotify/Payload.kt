package dev.ghien.opennotify

import org.json.JSONObject

/** Payload gửi tới webhook — dùng chung giữa gửi ngay (NotifyListenerService)
 *  và gửi lại từ hàng đợi (OutboxWorker) để tránh lệch định dạng. */
object Payload {
    fun from(n: NotificationRecord): String = JSONObject().apply {
        put("source", "opennotify")
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

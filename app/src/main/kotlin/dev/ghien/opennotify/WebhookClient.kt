package dev.ghien.opennotify

import java.net.HttpURLConnection
import java.net.URL

object WebhookClient {

    /**
     * POST một thân JSON, trả về mã HTTP, hoặc -1 nếu không tới được máy chủ (mất mạng, URL sai...).
     * Hàm blocking — luôn gọi từ background thread.
     */
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 8000): Int {
        var conn: HttpURLConnection? = null
        return try {
            // URL do người dùng nhập/quét theo từng webhook -> URL sai chỉ trả về -1, không văng lỗi.
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } catch (_: Exception) {
            -1
        } finally {
            conn?.disconnect()
        }
    }
}

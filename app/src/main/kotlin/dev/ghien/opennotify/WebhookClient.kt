package dev.ghien.opennotify

import java.net.HttpURLConnection
import java.net.URL

object WebhookClient {

    /** Hàm blocking — luôn gọi từ background thread. Trả về true nếu HTTP 2xx. */
    fun post(url: String, secret: String, body: String, timeoutMs: Int = 8000): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            // URL do người dùng nhập theo từng webhook -> URL sai chỉ trả về false, không văng lỗi.
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Signature", Signer.hmacHex(secret, body))
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }
}

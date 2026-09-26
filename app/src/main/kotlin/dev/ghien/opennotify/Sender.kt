package dev.ghien.opennotify

import org.json.JSONObject

/** Kết quả một lần gửi, quyết định số phận của lượt gửi trong bảng deliveries. */
enum class SendOutcome {
    OK,
    /** Lỗi tạm thời: giữ lại để thử lại. */
    RETRY,
    /** Gói tin bị từ chối hẳn: bỏ, không thử lại. */
    DROP,
    /** Endpoint không còn nhận (404/410): ngừng gửi cho tới khi người dùng sửa cấu hình / quét lại QR. */
    STOPPED
}

/**
 * Gửi theo chuẩn OpenNotify Webhook v1 — một đường duy nhất cho mọi webhook, dù được thiết lập bằng
 * tay hay bằng mã QR. Dùng chung cho gửi ngay, OutboxWorker và nút gửi thử.
 */
object Sender {

    /** Quá hạn này mà vẫn chưa gửi được thì bỏ: thông tin đã cũ, bên nhận không cần nữa. */
    const val MAX_AGE_MS = 6L * 60 * 60 * 1000

    /** Khoảng chờ trước mỗi lần thử khi gửi ngay; sau đó giao cho OutboxWorker (~15 phút/lần). */
    val IMMEDIATE_DELAYS_MS = listOf(0L, 5_000L, 15_000L)

    fun send(w: Webhook, n: NotificationRecord): SendOutcome = classify(post(w, Payload.json(n)))

    /** Gửi một payload tới webhook theo chế độ bảo mật của nó; trả mã HTTP, -1 nếu không tới được. */
    fun post(w: Webhook, payload: JSONObject): Int {
        val idHeader = w.endpointId?.takeIf { it.isNotBlank() }?.let { mapOf("X-OpenNotify-Id" to it) }.orEmpty()
        return when (w.security) {
            Security.HMAC -> {
                val body = payload.toString()
                WebhookClient.postJson(w.url, body, idHeader + ("X-Signature" to Signer.hmacHex(w.secret, body)))
            }
            Security.AES_GCM -> WebhookClient.postJson(w.url, AesGcmEnvelope.seal(w.endpointId.orEmpty(), w.secret, payload))
        }
    }

    /** Bảng "Trả lời" của chuẩn, áp như nhau cho mọi chế độ. */
    fun classify(code: Int): SendOutcome = when (code) {
        in 200..299 -> SendOutcome.OK
        404, 410 -> SendOutcome.STOPPED
        -1, 408, 429, in 500..599 -> SendOutcome.RETRY
        else -> SendOutcome.DROP
    }

    /** Ghi kết quả vào DB. RETRY để nguyên dòng deliveries sent=0 cho OutboxWorker. */
    fun record(db: Db, hash: String, w: Webhook, outcome: SendOutcome) {
        when (outcome) {
            SendOutcome.OK -> db.markDelivered(hash, w.id)
            SendOutcome.DROP -> db.markDropped(hash, w.id)
            SendOutcome.STOPPED -> db.markStopped(w.id)
            SendOutcome.RETRY -> Unit
        }
    }

    /** Câu báo kết quả nút "Gửi thử" cho người dùng. */
    fun describeTest(code: Int, url: String): String = when (classify(code)) {
        SendOutcome.OK -> "Thông suốt — bên nhận đã nhận gói thử (HTTP $code)"
        SendOutcome.STOPPED -> "Bên nhận không còn nhận ở địa chỉ/mã này (HTTP $code) — kiểm tra lại URL, id hoặc quét lại mã QR"
        SendOutcome.RETRY -> when (code) {
            -1 -> "Không tới được ${android.net.Uri.parse(url).host} — kiểm tra mạng / URL"
            429 -> "Gửi quá nhanh, thử lại sau ít phút"
            503 -> "Bên nhận tạm thời chưa sẵn sàng (HTTP 503) — ví dụ máy nhận đang tắt"
            else -> "Bên nhận lỗi tạm thời (HTTP $code) — sẽ tự thử lại"
        }
        SendOutcome.DROP -> "Bên nhận từ chối gói tin (HTTP $code) — kiểm tra secret/khóa và chế độ bảo mật"
    }
}

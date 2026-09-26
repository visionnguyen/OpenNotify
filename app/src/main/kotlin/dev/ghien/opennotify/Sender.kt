package dev.ghien.opennotify

/** Kết quả một lần gửi, quyết định số phận của lượt gửi trong bảng deliveries. */
enum class SendOutcome {
    OK,
    /** Lỗi tạm thời: giữ lại để OutboxWorker thử lại. */
    RETRY,
    /** Gói tin bị từ chối hẳn: bỏ, không thử lại. */
    DROP,
    /** mapchat không còn biết mã ghép này (máy quầy đã "Đổi mã"): ngừng gửi cho tới khi quét lại QR. */
    UNPAIRED
}

/** Gửi một thông báo tới một webhook — dùng chung cho gửi ngay, gửi lại và nút gửi thử. */
object Sender {

    /** Quá hạn này mà vẫn chưa gửi được cho mapchat thì bỏ: nhân viên đã xác nhận tay từ lâu. */
    const val MAPCHAT_MAX_AGE_MS = 6L * 60 * 60 * 1000

    fun send(w: Webhook, n: NotificationRecord): SendOutcome = when (w.kind) {
        // Giữ nguyên hành vi cũ của webhook HMAC: lỗi gì cũng thử lại.
        WebhookKind.HMAC -> if (WebhookClient.post(w.url, w.secret, Payload.from(n))) SendOutcome.OK else SendOutcome.RETRY
        WebhookKind.MAPCHAT -> classify(WebhookClient.postJson(w.url, MapchatEnvelope.seal(w, n)))
    }

    /** Bảng "Trả lời — và app phải làm gì" của mapchat. Chỉ 503, 429 và lỗi mạng mới thử lại. */
    fun classify(code: Int): SendOutcome = when (code) {
        in 200..299 -> SendOutcome.OK
        404 -> SendOutcome.UNPAIRED
        503, 429, -1 -> SendOutcome.RETRY
        else -> SendOutcome.DROP
    }

    /** Ghi kết quả vào DB. RETRY để nguyên dòng deliveries sent=0 cho OutboxWorker. */
    fun record(db: Db, hash: String, w: Webhook, outcome: SendOutcome) {
        when (outcome) {
            SendOutcome.OK -> db.markDelivered(hash, w.id)
            SendOutcome.DROP -> db.markDropped(hash, w.id)
            SendOutcome.UNPAIRED -> db.markUnpaired(w.id)
            SendOutcome.RETRY -> Unit
        }
    }

    /** Khoảng chờ trước mỗi lần thử khi gửi ngay; sau đó giao cho OutboxWorker (~15 phút/lần). */
    fun immediateDelaysMs(w: Webhook): List<Long> = when (w.kind) {
        WebhookKind.HMAC -> listOf(0L, 1_000L, 2_000L, 3_000L)
        WebhookKind.MAPCHAT -> listOf(0L, 5_000L, 15_000L)
    }
}

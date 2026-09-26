package dev.ghien.opennotify

/** Quyết định một thông báo có khớp bộ pattern của webhook hay không. */
object PatternMatcher {

    /**
     * patterns rỗng -> luôn khớp. AND: mọi pattern phải khớp; OR: chỉ cần một.
     * Pattern regex lỗi được coi là không khớp (không làm crash luồng nhận thông báo).
     */
    fun matches(webhook: Webhook, input: String): Boolean {
        val patterns = webhook.patterns.filter { it.isNotBlank() }
        if (patterns.isEmpty()) return true
        return when (webhook.mode) {
            MatchMode.AND -> patterns.all { safeMatches(it, input) }
            MatchMode.OR -> patterns.any { safeMatches(it, input) }
        }
    }

    /** Nội dung dùng để đối chiếu pattern: gộp mọi trường văn bản của thông báo. */
    fun searchableText(n: NotificationRecord): String =
        "${n.title} ${n.text} ${n.bigText} ${n.subText} ${n.lines}"

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]")

    /**
     * Chuỗi đem đối chiếu với pattern của webhook. Cặp mapchat: viết hoa và bỏ mọi ký tự không phải
     * chữ/số, vì ngân hàng hay cắt dòng và chèn dấu cách giữa mã đơn (docs/mapchat-pairing.md).
     * Chỉ xét đúng phần sẽ gửi đi (MapchatEnvelope.textOf): mã đơn nằm ngoài phần đó thì máy quầy
     * cũng không thấy.
     */
    fun inputFor(w: Webhook, n: NotificationRecord): String = when (w.kind) {
        WebhookKind.HMAC -> searchableText(n)
        WebhookKind.MAPCHAT -> MapchatEnvelope.textOf(n).uppercase().replace(NON_ALNUM, "")
    }

    fun isValidRegex(pattern: String): Boolean = try {
        Regex(pattern)
        true
    } catch (_: Exception) {
        false
    }

    private fun safeMatches(pattern: String, input: String): Boolean = try {
        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(input)
    } catch (_: Exception) {
        false
    }
}

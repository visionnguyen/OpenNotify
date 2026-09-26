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

    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]")

    /**
     * Chuỗi đem đối chiếu với pattern: đúng trường `text` của payload gửi đi (Payload.textOf).
     * normalize: viết hoa và bỏ mọi ký tự không phải chữ/số, vì ngân hàng hay cắt dòng và chèn dấu
     * cách giữa mã (docs/webhook-standard.md).
     */
    fun inputFor(w: Webhook, n: NotificationRecord): String {
        val text = Payload.textOf(n)
        return if (w.normalize) text.uppercase().replace(NON_ALNUM, "") else text
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

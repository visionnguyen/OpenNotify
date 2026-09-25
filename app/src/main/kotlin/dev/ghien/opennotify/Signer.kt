package dev.ghien.opennotify

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Signer {
    private const val ALGO = "HmacSHA256"

    /** HMAC-SHA256 hex của body, dùng chung thuật toán cho cả app và backend. */
    fun hmacHex(secret: String, body: String): String {
        val mac = Mac.getInstance(ALGO)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGO))
        val raw = mac.doFinal(body.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { String.format("%02x", it) }
    }

    /** Dùng để tạo khóa dedupe từ nội dung thông báo. */
    fun sha1Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        val raw = digest.digest(input.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { String.format("%02x", it) }
    }
}

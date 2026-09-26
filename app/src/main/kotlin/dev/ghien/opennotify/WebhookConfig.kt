package dev.ghien.opennotify

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cấu hình một webhook theo chuẩn OpenNotify Webhook v1 (docs/webhook-standard.md). Form nhập tay và
 * mã QR đều ra đúng kiểu này và đều qua cùng [validate] — hai cách thay thế được cho nhau.
 */
data class WebhookConfig(
    val name: String,
    val url: String,
    val security: Security,
    /** HMAC: chuỗi bí mật. AES_GCM: khóa 32 byte base64url. Không log, không hiện dạng rõ. */
    val secret: String,
    val endpointId: String?,
    val patterns: List<String>,
    val mode: MatchMode,
    val normalize: Boolean
) {
    /** Lỗi đầu tiên theo luật của chuẩn, hoặc null nếu hợp lệ. */
    fun validate(): String? = when {
        !(url.startsWith("http://") || url.startsWith("https://")) || Uri.parse(url).host.isNullOrBlank() ->
            "URL phải bắt đầu bằng http:// hoặc https://"
        security == Security.HMAC && secret.isBlank() -> "Cần secret để ký HMAC"
        security == Security.AES_GCM && AesGcmEnvelope.decodeKey(secret) == null ->
            "Khóa AES-GCM phải là 32 byte dạng base64url (43 ký tự A–Z a–z 0–9 - _)"
        security == Security.AES_GCM && endpointId.isNullOrBlank() -> "Chế độ AES-GCM cần id endpoint"
        !endpointId.isNullOrEmpty() && !ID_RE.matches(endpointId) ->
            "id endpoint chỉ gồm A–Z a–z 0–9 - _, tối đa 64 ký tự"
        else -> patterns.firstOrNull { !PatternMatcher.isValidRegex(it) }?.let { "Pattern regex không hợp lệ: $it" }
    }

    fun toWebhook(id: Long, pkg: String, enabled: Boolean, stopped: Boolean = false) = Webhook(
        id = id, pkg = pkg, name = name.ifBlank { Uri.parse(url).host ?: url }, url = url, secret = secret,
        mode = mode, enabled = enabled, patterns = patterns, security = security,
        endpointId = endpointId?.takeIf { it.isNotBlank() }, normalize = normalize, stopped = stopped
    )

    companion object {
        val ID_RE = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun of(w: Webhook) = WebhookConfig(
            name = w.name, url = w.url, security = w.security, secret = w.secret, endpointId = w.endpointId,
            patterns = w.patterns, mode = w.mode, normalize = w.normalize
        )
    }
}

sealed class QrParse {
    class Ok(val config: WebhookConfig) : QrParse()
    class Error(val message: String) : QrParse()
}

/**
 * Đọc mã QR cấu hình webhook: JSON `{"v":1, ...}` gồm đúng các trường của [WebhookConfig]
 * (docs/webhook-standard.md, mục "Mã QR"). QR chỉ là tiện ích nhập nhanh — không có gì ở đây mà
 * form nhập tay không nhập được.
 */
object WebhookQr {

    private const val NOT_CONFIG = "Mã QR này không phải mã cấu hình webhook OpenNotify"

    fun parse(raw: String): QrParse {
        val json = try {
            JSONObject(repairMojibake(raw.trim().removePrefix("﻿")))
        } catch (_: Exception) {
            return QrParse.Error(NOT_CONFIG)
        }
        // Trường lạ bị bỏ qua: chỉ đọc các trường đã biết.
        val v = json.opt("v") ?: return QrParse.Error(NOT_CONFIG)
        if (v !is Number || v.toDouble() != 1.0) {
            return QrParse.Error("Mã QR dùng phiên bản chuẩn mới hơn — cần cập nhật OpenNotify")
        }

        val k = json.optStringOrNull("k")
        val secret = json.optStringOrNull("secret")
        val security = when (json.optStringOrNull("security")?.lowercase()) {
            "aes-gcm" -> Security.AES_GCM
            "hmac" -> Security.HMAC
            null -> when {
                k != null -> Security.AES_GCM
                secret != null -> Security.HMAC
                else -> return QrParse.Error("Mã QR thiếu khóa/secret")
            }
            else -> return QrParse.Error("Mã QR có chế độ bảo mật không hỗ trợ — cần cập nhật OpenNotify")
        }

        val patterns = (json.opt("patterns") as? JSONArray)
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.opt(it) as? String } }
            ?: listOfNotNull(json.optStringOrNull("pattern"))
        val mode = when (json.optStringOrNull("match")?.lowercase()) {
            null, "any" -> MatchMode.OR
            "all" -> MatchMode.AND
            else -> return QrParse.Error("Mã QR có kiểu kết hợp pattern không hợp lệ")
        }

        val config = WebhookConfig(
            name = json.optStringOrNull("name")?.trim().orEmpty(),
            url = json.optStringOrNull("url").orEmpty(),
            security = security,
            secret = (if (security == Security.AES_GCM) k ?: secret else secret ?: k).orEmpty(),
            endpointId = json.optStringOrNull("id"),
            patterns = patterns.map { it.trim() }.filter { it.isNotEmpty() },
            mode = mode,
            normalize = json.opt("normalize") as? Boolean ?: true
        )
        return config.validate()?.let { QrParse.Error("Mã QR không hợp lệ: $it") } ?: QrParse.Ok(config)
    }

    private fun JSONObject.optStringOrNull(name: String): String? = opt(name) as? String

    /**
     * Mã QR không mang ECI nên máy quét có thể đã giải UTF-8 thành ISO-8859-1 ("Trà Sá»¯a").
     * Chuỗi như vậy chỉ gồm ký tự <= U+00FF; ghép lại thành byte rồi giải UTF-8, nếu ra chuỗi
     * hợp lệ thì dùng. Chuỗi đã giải đúng có dấu tiếng Việt (> U+00FF) nên không bị đụng tới.
     */
    fun repairMojibake(s: String): String {
        if (s.all { it.code < 0x80 } || s.any { it.code > 0xFF }) return s
        return try {
            Charsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(s.toByteArray(Charsets.ISO_8859_1))).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            s
        }
    }
}

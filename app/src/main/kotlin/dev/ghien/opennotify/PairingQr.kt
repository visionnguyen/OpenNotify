package dev.ghien.opennotify

import org.json.JSONObject
import java.util.Base64

/** Nội dung mã QR ghép đôi của máy quầy mapchat, đã qua kiểm tra. */
class MapchatPairing(
    val name: String,
    val url: String,
    val id: String,
    /** Khóa AES-256-GCM (base64url, 43 ký tự). Bí mật: không hiện, không log. */
    val key: String,
    val pattern: String
)

sealed class PairingParse {
    class Ok(val pairing: MapchatPairing) : PairingParse()
    class Error(val message: String) : PairingParse()
}

/** Đọc mã QR ghép đôi theo đúng "Luật đọc" trong docs/mapchat-pairing.md. */
object PairingQr {

    private val ID_RE = Regex("^[A-Za-z0-9_-]{22}$")
    private val KEY_RE = Regex("^[A-Za-z0-9_-]{43}$")

    private const val NOT_PAIRING = "Mã QR này không phải mã ghép đôi của máy quầy mapchat"

    fun parse(raw: String): PairingParse {
        val text = repairMojibake(raw.trim().removePrefix("﻿"))
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return PairingParse.Error(NOT_PAIRING)
        }
        // Luật 3: trường lạ bị bỏ qua — chỉ đọc đúng 6 trường đã biết.
        if (!json.has("v")) return PairingParse.Error(NOT_PAIRING)
        val v = json.opt("v")
        // Luật 2: v khác 1 -> từ chối, không đoán.
        if (v !is Number || v.toDouble() != 1.0) {
            return PairingParse.Error("Mã QR dùng định dạng mới hơn — cần cập nhật OpenNotify")
        }

        val name = json.opt("name") as? String
        val url = json.opt("url") as? String
        val id = json.opt("id") as? String
        val key = json.opt("k") as? String
        val pattern = json.opt("pattern") as? String

        if (name.isNullOrBlank()) return PairingParse.Error("Mã QR thiếu tên tiệm")
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return PairingParse.Error("Mã QR có địa chỉ gửi không hợp lệ")
        }
        // Luật 4 + 5: kiểm ngay lúc quét, đừng để tới lúc gửi mới biết.
        if (id == null || !ID_RE.matches(id)) return PairingParse.Error("Mã ghép trong QR bị hỏng — quét lại")
        if (key == null || !KEY_RE.matches(key) || decodeKey(key)?.size != 32) {
            return PairingParse.Error("Khóa trong QR bị hỏng — quét lại")
        }
        if (pattern.isNullOrBlank() || !PatternMatcher.isValidRegex(pattern)) {
            return PairingParse.Error("Mẫu lọc trong QR không hợp lệ")
        }
        return PairingParse.Ok(MapchatPairing(name.trim(), url, id, key, pattern))
    }

    /** base64url không đệm -> byte; null nếu không giải được. */
    fun decodeKey(key: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(key)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Mã QR không mang ECI nên máy quét có thể đã giải UTF-8 thành ISO-8859-1 ("Trà Sá»¯a").
     * Chuỗi như vậy chỉ gồm ký tự <= U+00FF; ghép lại thành byte rồi giải UTF-8, nếu ra chuỗi
     * hợp lệ thì dùng. Chuỗi đã giải đúng có dấu tiếng Việt (> U+00FF) nên không bị đụng tới.
     */
    fun repairMojibake(s: String): String {
        if (s.all { it.code < 0x80 } || s.any { it.code > 0xFF }) return s
        val bytes = s.toByteArray(Charsets.ISO_8859_1)
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            s
        }
    }
}

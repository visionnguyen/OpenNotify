package dev.ghien.opennotify

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Chế độ AES_GCM của chuẩn: bọc payload thành {id, iv, ct} (docs/webhook-standard.md, mục "Truyền"). */
object AesGcmEnvelope {

    /** Trần thân yêu cầu của chế độ AES_GCM. */
    const val MAX_BYTES = 2048

    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** base64url không đệm -> đúng 32 byte, hoặc null nếu không phải khóa hợp lệ. */
    fun decodeKey(key: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(key).takeIf { it.size == 32 }
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Mã hóa payload rồi bọc. Vượt 2 KB thì bỏ các trường thô trước (bên nhận vẫn còn `text`), sau
     * đó mới cắt dần đuôi `text` (mã đơn, số tiền luôn nằm ở đầu). IV mới cho mỗi lần gọi.
     */
    fun seal(endpointId: String, keyB64: String, payload: JSONObject): String {
        val key = decodeKey(keyB64) ?: error("khóa AES-GCM không hợp lệ")
        val p = JSONObject(payload.toString())
        var body = encrypt(endpointId, key, p)
        if (fits(body)) return body

        Payload.RAW_FIELDS.forEach { p.remove(it) }
        var text = p.optString("text")
        while (true) {
            p.put("text", text)
            body = encrypt(endpointId, key, p)
            if (fits(body) || text.isEmpty()) return body
            text = text.substring(0, text.length * 9 / 10)
                .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        }
    }

    private fun fits(body: String) = body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES

    private fun encrypt(endpointId: String, key: ByteArray, payload: JSONObject): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8)) // nhãn 16 byte nằm cuối
        return JSONObject().apply {
            put("id", endpointId)
            put("iv", b64.encodeToString(iv))
            put("ct", b64.encodeToString(ct))
        }.toString()
    }
}

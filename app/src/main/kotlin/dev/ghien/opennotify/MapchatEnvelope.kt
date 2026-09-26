package dev.ghien.opennotify

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Gói tin gửi cho mapchat: bản rõ {pkg, at, text} mã hóa AES-256-GCM (docs/mapchat-pairing.md, "Gói tin"). */
object MapchatEnvelope {

    /** Trần thân yêu cầu phía mapchat (MAX_NOTIFY_ENVELOPE). */
    const val MAX_BYTES = 2048

    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** Nối title + text + bigText (những cái có), phân cách bằng dấu cách. */
    fun textOf(n: NotificationRecord): String =
        listOf(n.title, n.text, n.bigText).filter { it.isNotBlank() }.joinToString(" ")

    fun seal(w: Webhook, n: NotificationRecord): String =
        seal(w.pairingId.orEmpty(), w.secret, n.pkg, n.ts, textOf(n))

    /**
     * Mã hóa rồi bọc thành {id, iv, ct}. Vượt 2 KB thì cắt bớt đuôi text (mã đơn và số tiền luôn
     * nằm ở đầu) cho tới khi vừa. IV mới cho mỗi lần gọi, kể cả khi gửi lại cùng một thông báo.
     */
    fun seal(pairingId: String, keyB64: String, pkg: String, at: Long, text: String): String {
        val key = PairingQr.decodeKey(keyB64) ?: error("khóa mapchat không hợp lệ")
        var t = text
        while (true) {
            val plain = JSONObject().apply {
                put("pkg", pkg)
                put("at", at)
                put("text", t)
            }.toString()
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8)) // nhãn 16 byte nằm cuối
            val body = JSONObject().apply {
                put("id", pairingId)
                put("iv", b64.encodeToString(iv))
                put("ct", b64.encodeToString(ct))
            }.toString()
            if (body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES || t.isEmpty()) return body
            t = t.substring(0, t.length * 9 / 10).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        }
    }
}

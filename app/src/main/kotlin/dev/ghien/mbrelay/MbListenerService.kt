package dev.ghien.mbrelay

import android.app.Notification
import android.content.ComponentName
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Trái tim của app: hệ thống Android tự bind service này và gọi
 * onNotificationPosted() ngay khi có thông báo mới — kể cả khi màn hình
 * tắt và app không hề "đang chạy" theo nghĩa thông thường. Đây là lý do
 * cách này đáng tin hơn nhiều so với polling từ Termux.
 *
 * Service KHÔNG tự parse số tiền/nội dung đơn hàng. Nó chỉ trích xuất
 * toàn bộ text thô của thông báo và đẩy nguyên vẹn lên webhook — việc
 * parse (regex số tiền, mã đơn...) nên làm ở backend, vì sửa regex ở đó
 * không cần build lại và cài lại APK.
 */
class MbListenerService : NotificationListenerService() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val targetPkg = Prefs.sourcePackage(applicationContext)
        if (sbn.packageName != targetPkg) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }.orEmpty()

        val payload = JSONObject().apply {
            put("source", "mb-relay")
            put("package", sbn.packageName)
            put("key", sbn.key ?: "")
            put("post_time", sbn.postTime)
            put("title", title)
            put("text", text)
            put("big_text", bigText)
            put("sub_text", subText)
            put("lines", lines)
        }
        val body = payload.toString()
        // Khóa dedupe: nếu hệ thống gửi lại/gộp cùng một thông báo,
        // sự kiện này sẽ không bị đẩy hai lần.
        val hash = Signer.sha1Hex("${sbn.key}|${sbn.postTime}|$text|$bigText")

        dispatch(hash, body)
    }

    private fun dispatch(hash: String, body: String) {
        val store = EventStore(applicationContext)
        if (!store.insertIfNew(hash, body)) return // đã thấy sự kiện này rồi

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "mbrelay:deliver"
        ).apply { acquire(30_000L) }

        io.execute {
            try {
                val url = Prefs.webhookUrl(applicationContext)
                val secret = Prefs.webhookSecret(applicationContext)
                if (url.isBlank() || secret.isBlank()) return@execute

                var ok = false
                var attempt = 0
                while (!ok && attempt < 4) {
                    ok = WebhookClient.post(url, secret, body)
                    if (!ok) {
                        attempt++
                        Thread.sleep(1000L * attempt)
                    }
                }
                if (ok) {
                    store.markSent(hash)
                }
                // Nếu vẫn thất bại: sự kiện đã nằm sẵn trong SQLite với
                // sent=0, OutboxWorker sẽ gửi lại theo chu kỳ.
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        OutboxWorker.schedule(applicationContext)
    }

    override fun onListenerDisconnected() {
        // Hệ thống có thể ngắt kết nối listener trong một số trường hợp;
        // yêu cầu bind lại ngay để không bị "câm" lâu.
        requestRebind(ComponentName(this, MbListenerService::class.java))
    }
}

package dev.ghien.opennotify

import android.app.Notification
import android.content.ComponentName
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/**
 * Chỉ ghi nhận thông báo của những ứng dụng đã được thêm vào danh sách
 * theo dõi (tracked_apps); mọi ứng dụng khác bị bỏ qua hoàn toàn. Với mỗi
 * thông báo được ghi, đối chiếu từng webhook đang bật của ứng dụng đó
 * (URL/secret + bộ pattern riêng) và gửi tới các webhook khớp.
 */
class NotifyListenerService : NotificationListenerService() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Bỏ qua thông báo của chính app này (ví dụ notification keep-alive).
        if (sbn.packageName == applicationContext.packageName) return

        val db = Db.get(applicationContext)
        if (!db.isTracked(sbn.packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }.orEmpty()

        val hash = Signer.sha1Hex("${sbn.packageName}|${sbn.key}|${sbn.postTime}|$text|$bigText")
        val record = NotificationRecord(
            hash = hash, pkg = sbn.packageName, key = sbn.key ?: "", postTime = sbn.postTime,
            title = title, text = text, bigText = bigText, subText = subText, lines = lines,
            ts = System.currentTimeMillis()
        )

        if (!db.insertNotification(record)) return // đã thấy thông báo này rồi

        val searchable = PatternMatcher.searchableText(record)
        val targets = db.webhooksForPackage(sbn.packageName, onlyEnabled = true)
            .filter { PatternMatcher.matches(it, searchable) }
        if (targets.isEmpty()) return

        // Ghi nhận trước khi gửi: nếu app bị kill giữa chừng, OutboxWorker vẫn gửi bù.
        targets.forEach { db.addDelivery(hash, it.id) }
        relay(record, targets)
    }

    private fun relay(record: NotificationRecord, targets: List<Webhook>) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "opennotify:deliver"
        ).apply { acquire(60_000L) }

        io.execute {
            try {
                val db = Db.get(applicationContext)
                val body = Payload.from(record)
                for (w in targets) {
                    var ok = false
                    var attempt = 0
                    while (!ok && attempt < 4) {
                        ok = WebhookClient.post(w.url, w.secret, body)
                        if (!ok) {
                            attempt++
                            Thread.sleep(1000L * attempt)
                        }
                    }
                    if (ok) db.markDelivered(record.hash, w.id)
                    // Thất bại: dòng deliveries sent=0 vẫn còn, OutboxWorker sẽ thử lại theo chu kỳ.
                }
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
        requestRebind(ComponentName(this, NotifyListenerService::class.java))
    }
}

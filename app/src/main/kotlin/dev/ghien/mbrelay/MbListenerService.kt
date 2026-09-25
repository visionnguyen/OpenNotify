package dev.ghien.mbrelay

import android.app.Notification
import android.content.ComponentName
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.Executors

/**
 * Bắt TOÀN BỘ thông báo trên máy (mặc định, không lọc theo nguồn) và lưu
 * lại để xem sau trong app (SourcesActivity / NotificationListActivity).
 * Chỉ thông báo khớp ít nhất một relay_rule đang bật (quản lý trong
 * RulesActivity, theo package + pattern regex) mới thực sự được đẩy ra
 * webhook. Không khớp rule nào thì vẫn được lưu để duyệt, chỉ không gửi đi.
 */
class MbListenerService : NotificationListenerService() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Bỏ qua thông báo của chính app này (ví dụ notification keep-alive)
        // để tránh nhiễu log và mọi khả năng tự tham chiếu không cần thiết.
        if (sbn.packageName == applicationContext.packageName) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }.orEmpty()

        val hash = Signer.sha1Hex("${sbn.packageName}|${sbn.key}|${sbn.postTime}|$text|$bigText")
        val db = Db(applicationContext)

        val combined = "$title $text $bigText $subText $lines"
        val matched = db.rulesForPackage(sbn.packageName).any { rule ->
            rule.pattern.isNullOrBlank() || safeMatches(rule.pattern, combined)
        }

        val record = NotificationRecord(
            hash = hash, pkg = sbn.packageName, key = sbn.key ?: "", postTime = sbn.postTime,
            title = title, text = text, bigText = bigText, subText = subText, lines = lines,
            ts = System.currentTimeMillis(), relayMatched = matched, relaySent = false
        )

        if (db.insertNotification(record) && matched) {
            relay(hash, Payload.from(record))
        }
    }

    private fun safeMatches(pattern: String, input: String): Boolean = try {
        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(input)
    } catch (_: Exception) {
        false // pattern lỗi -> coi như không khớp, không chặn các rule khác
    }

    private fun relay(hash: String, body: String) {
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
                if (ok) Db(applicationContext).markRelaySent(hash)
                // Thất bại: relay_matched=1, relay_sent=0 vẫn nằm trong DB,
                // OutboxWorker sẽ thử lại theo chu kỳ.
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
        requestRebind(ComponentName(this, MbListenerService::class.java))
    }
}

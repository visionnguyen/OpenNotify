package dev.ghien.mbrelay

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Lưới an toàn, KHÔNG phải đường gửi chính. Đường gửi chính là gửi ngay
 * trong MbListenerService lúc có thông báo khớp rule. Worker này chạy
 * định kỳ (tối thiểu 15 phút theo giới hạn WorkManager) để gửi lại các
 * thông báo đã khớp rule nhưng gửi lỗi trước đó, và dọn bớt log cũ.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val db = Db(ctx)
        val url = Prefs.webhookUrl(ctx)
        val secret = Prefs.webhookSecret(ctx)

        if (url.isNotBlank() && secret.isNotBlank()) {
            for (n in db.pendingRelay(limit = 100)) {
                if (WebhookClient.post(url, secret, Payload.from(n))) {
                    db.markRelaySent(n.hash)
                }
            }
        }
        // Giữ log thông báo (mọi nguồn) trong 30 ngày để duyệt lại;
        // chỉnh số này nếu muốn giữ lâu/ngắn hơn.
        db.prune(olderThanMs = 30L * 24 * 60 * 60 * 1000)
        return Result.success()
    }

    companion object {
        private const val NAME = "mb-relay-outbox-flush"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OutboxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}

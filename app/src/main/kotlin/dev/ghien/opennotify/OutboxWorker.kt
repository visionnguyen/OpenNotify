package dev.ghien.opennotify

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
 * trong NotifyListenerService lúc có thông báo khớp webhook. Worker này chạy
 * định kỳ (tối thiểu 15 phút theo giới hạn WorkManager) để gửi lại các
 * lượt gửi tới webhook đã khớp nhưng lỗi trước đó, và dọn bớt log cũ.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val db = Db.get(applicationContext)

        val now = System.currentTimeMillis()
        val unpaired = HashSet<Long>()
        for (d in db.pendingDeliveries(limit = 100)) {
            if (d.webhook.id in unpaired) continue // cặp vừa bị mapchat báo đổi mã trong lượt này
            if (d.webhook.kind == WebhookKind.MAPCHAT && now - d.notification.ts > Sender.MAPCHAT_MAX_AGE_MS) {
                db.markDropped(d.notification.hash, d.webhook.id)
                continue
            }
            val outcome = Sender.send(d.webhook, d.notification)
            Sender.record(db, d.notification.hash, d.webhook, outcome)
            if (outcome == SendOutcome.UNPAIRED) unpaired.add(d.webhook.id)
        }
        // Giữ log thông báo (của các ứng dụng đang theo dõi) trong 30 ngày để duyệt lại;
        // chỉnh số này nếu muốn giữ lâu/ngắn hơn.
        db.prune(olderThanMs = 30L * 24 * 60 * 60 * 1000)
        return Result.success()
    }

    companion object {
        private const val NAME = "opennotify-outbox-flush"

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

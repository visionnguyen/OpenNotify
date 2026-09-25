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
 * Lưới an toàn, KHÔNG phải đường gửi chính. Đường gửi chính là gửi
 * ngay trong MbListenerService lúc có thông báo mới. Worker này chỉ
 * chạy định kỳ (WorkManager, tối thiểu 15 phút theo giới hạn Android)
 * để dọn nốt các sự kiện từng gửi lỗi trước đó — ví dụ lúc mất mạng.
 */
class OutboxWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val url = Prefs.webhookUrl(ctx)
        val secret = Prefs.webhookSecret(ctx)
        if (url.isBlank() || secret.isBlank()) return Result.success()

        val store = EventStore(ctx)
        for (ev in store.unsent(limit = 100)) {
            if (WebhookClient.post(url, secret, ev.payload)) {
                store.markSent(ev.hash)
            }
        }
        store.prune(olderThanMs = 7L * 24 * 60 * 60 * 1000)
        return Result.success()
    }

    companion object {
        private const val NAME = "mb-relay-outbox-flush"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<OutboxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}

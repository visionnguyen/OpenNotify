package dev.ghien.opennotify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Sau khi máy khởi động lại, NotificationListenerService sẽ được hệ
 * thống tự bind lại nếu quyền vẫn còn cấp — không cần code gì thêm cho
 * việc đó. Receiver này chỉ lo phần còn lại: đảm bảo WorkManager job
 * và foreground keep-alive service (nếu đã có ứng dụng được theo dõi) được khởi động lại.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val ctx = context.applicationContext
            OutboxWorker.schedule(ctx)
            if (Db.get(ctx).trackedApps().isNotEmpty()) {
                KeepAliveService.start(ctx)
            }
        }
    }
}

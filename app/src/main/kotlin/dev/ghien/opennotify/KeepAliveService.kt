package dev.ghien.opennotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service "rỗng": không tự làm việc gì, chỉ giữ tiến trình
 * app không bị hệ thống đóng băng (cached app freezer từ Android 14)
 * hoặc bị các ROM tối ưu pin mạnh (MIUI, ColorOS, FuntouchOS...) dọn dẹp.
 * Việc đọc thông báo thật sự vẫn do NotifyListenerService (được hệ thống
 * tự bind) đảm nhiệm — service này chỉ là một lớp đệm an toàn thêm.
 *
 * Notification hiển thị ở mức PRIORITY_MIN nên gần như không làm phiền.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "OpenNotify", NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenNotify đang chạy")
            .setContentText("Đang theo dõi thông báo của các ứng dụng đã thêm")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "opennotify_keepalive"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

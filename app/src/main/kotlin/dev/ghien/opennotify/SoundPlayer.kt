package dev.ghien.opennotify

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri

/**
 * Phát âm thanh hệ thống khi ứng dụng đang theo dõi có thông báo mới. Mặc định là
 * âm thông báo mặc định của máy; người dùng đổi trong màn Cài đặt.
 */
object SoundPlayer {

    // Giữ tham chiếu tới Ringtone đang phát: mất tham chiếu là có thể bị GC cắt tiếng giữa chừng.
    private var current: Ringtone? = null

    fun playIfEnabled(context: Context) {
        if (Prefs.soundEnabled(context)) play(context, Prefs.soundUri(context))
    }

    fun play(context: Context, uri: Uri?) {
        val ctx = context.applicationContext
        try {
            current?.stop()
            val ringtone = RingtoneManager.getRingtone(ctx, uri ?: defaultUri()) ?: return
            ringtone.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            ringtone.play()
            current = ringtone
        } catch (_: Exception) {
            // Âm thanh chỉ là phần phụ: file hỏng / thiếu quyền thì im lặng, không ảnh hưởng việc ghi + gửi thông báo.
        }
    }

    fun defaultUri(): Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    /** Tên hiển thị của âm thanh đang chọn (uri = null: mặc định của hệ thống). */
    fun titleOf(context: Context, uri: Uri?): String {
        val ctx = context.applicationContext
        val title = try {
            RingtoneManager.getRingtone(ctx, uri ?: defaultUri())?.getTitle(ctx)
        } catch (_: Exception) {
            null
        }
        return when {
            uri == null -> "Mặc định của hệ thống" + if (title.isNullOrBlank()) "" else " ($title)"
            title.isNullOrBlank() -> "Âm thanh tùy chọn"
            else -> title
        }
    }
}

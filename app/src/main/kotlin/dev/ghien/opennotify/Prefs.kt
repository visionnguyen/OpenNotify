package dev.ghien.opennotify

import android.content.Context
import android.net.Uri

/** Cài đặt người dùng chỉnh được trong màn Cài đặt (SharedPreferences). */
object Prefs {
    private const val FILE = "opennotify_prefs"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_SOUND_URI = "sound_uri"
    private const val KEY_NOTIF_PERM_ASKED = "notif_perm_asked"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun soundEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_SOUND_ENABLED, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_SOUND_ENABLED, enabled).apply()
    }

    /** null = âm thanh thông báo mặc định của hệ thống. */
    fun soundUri(context: Context): Uri? = sp(context).getString(KEY_SOUND_URI, null)?.let(Uri::parse)

    fun setSoundUri(context: Context, uri: Uri?) {
        sp(context).edit().putString(KEY_SOUND_URI, uri?.toString()).apply()
    }

    /** Đã từng hiện hộp thoại xin quyền hiện thông báo chưa (từ lần 2 chuyển sang mở cài đặt hệ thống). */
    fun notifPermissionAsked(context: Context): Boolean = sp(context).getBoolean(KEY_NOTIF_PERM_ASKED, false)

    fun setNotifPermissionAsked(context: Context) {
        sp(context).edit().putBoolean(KEY_NOTIF_PERM_ASKED, true).apply()
    }
}

package dev.ghien.mbrelay

import android.content.Context

/**
 * Cấu hình lưu bằng SharedPreferences thường (không mã hóa).
 * Đây là công cụ nội bộ chạy trên máy riêng của bạn, nên ưu tiên
 * đơn giản/ổn định hơn là bảo mật-tối-đa. Nếu muốn siết chặt hơn,
 * đổi sang androidx.security EncryptedSharedPreferences.
 */
object Prefs {
    private const val FILE = "mb_relay_prefs"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_WEBHOOK_SECRET = "webhook_secret"
    private const val KEY_PACKAGE = "source_package"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun webhookUrl(context: Context): String =
        sp(context).getString(KEY_WEBHOOK_URL, "") ?: ""

    fun webhookSecret(context: Context): String =
        sp(context).getString(KEY_WEBHOOK_SECRET, "") ?: ""

    fun sourcePackage(context: Context): String =
        sp(context).getString(KEY_PACKAGE, "com.mbmobile") ?: "com.mbmobile"

    fun save(context: Context, url: String, secret: String, pkg: String) {
        sp(context).edit()
            .putString(KEY_WEBHOOK_URL, url.trim())
            .putString(KEY_WEBHOOK_SECRET, secret.trim())
            .putString(KEY_PACKAGE, pkg.trim().ifEmpty { "com.mbmobile" })
            .apply()
    }

    fun isConfigured(context: Context): Boolean =
        webhookUrl(context).startsWith("http") && webhookSecret(context).isNotBlank()
}

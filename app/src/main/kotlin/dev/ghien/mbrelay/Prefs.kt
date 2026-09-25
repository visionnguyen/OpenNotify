package dev.ghien.mbrelay

import android.content.Context

/**
 * Cấu hình lưu bằng SharedPreferences thường (không mã hóa) — công cụ
 * nội bộ chạy trên máy riêng, ưu tiên đơn giản/ổn định hơn bảo mật tối đa.
 * Không còn lưu "source package" — app mặc định bắt mọi nguồn, việc lọc
 * chuyển sang bảng relay_rules (xem Db.kt).
 */
object Prefs {
    private const val FILE = "mb_relay_prefs"
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_WEBHOOK_SECRET = "webhook_secret"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun webhookUrl(context: Context): String = sp(context).getString(KEY_WEBHOOK_URL, "") ?: ""
    fun webhookSecret(context: Context): String = sp(context).getString(KEY_WEBHOOK_SECRET, "") ?: ""

    fun save(context: Context, url: String, secret: String) {
        sp(context).edit()
            .putString(KEY_WEBHOOK_URL, url.trim())
            .putString(KEY_WEBHOOK_SECRET, secret.trim())
            .apply()
    }

    fun isConfigured(context: Context): Boolean =
        webhookUrl(context).startsWith("http") && webhookSecret(context).isNotBlank()
}

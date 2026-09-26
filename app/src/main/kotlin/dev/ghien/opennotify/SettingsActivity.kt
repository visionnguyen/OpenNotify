package dev.ghien.opennotify

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Cài đặt: xem lại trạng thái mọi thứ bật/tắt liên quan tới việc nhận thông báo (quyền đọc
 * thông báo, giới hạn pin, hiện thông báo, âm thanh) và bên dưới là biểu đồ uptime 24h/ngày
 * cho biết Android có thật sự giao thông báo cho app không.
 *
 * Quyền hệ thống (đọc thông báo, pin, hiện thông báo) app không tự bật/tắt được: bấm vào hàng
 * sẽ mở đúng màn hình hệ thống, công tắc chỉ phản ánh trạng thái thật và cập nhật khi quay lại.
 */
class SettingsActivity : AppCompatActivity() {

    private class Row(val view: View) {
        val title: TextView = view.findViewById(R.id.sTitle)
        val sub: TextView = view.findViewById(R.id.sSub)
        val toggle: Switch = view.findViewById(R.id.sSwitch)
    }

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val autoRefresh = object : Runnable {
        override fun run() {
            refreshUptime()
            handler.postDelayed(this, UPTIME_REFRESH_MS)
        }
    }

    private lateinit var listenerRow: Row
    private lateinit var batteryRow: Row
    private lateinit var notifRow: Row
    private lateinit var soundRow: Row
    private lateinit var soundPickRow: Row

    private lateinit var liveStatus: TextView
    private lateinit var liveUptime: TextView
    private lateinit var uptimeContainer: LinearLayout

    private val pickSound = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val picked = result.data?.let {
            IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } ?: return@registerForActivityResult
        // Chọn "Mặc định" trong picker -> lưu null để luôn theo âm mặc định hiện tại của hệ thống.
        Prefs.setSoundUri(this, if (picked == SoundPlayer.defaultUri()) null else picked)
        refreshSettings()
    }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshSettings() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Cài đặt"

        val container = findViewById<LinearLayout>(R.id.settingsContainer)
        listenerRow = addRow(container) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        batteryRow = addRow(container) { openBatterySettings() }
        if (isXiaomi()) {
            // MIUI/HyperOS không cho app đọc trạng thái "Tự khởi chạy" nên hàng này không có công tắc.
            val autoStartRow = addRow(container) { openAutoStartSettings() }
            autoStartRow.toggle.visibility = View.GONE
            autoStartRow.title.text = "Tự khởi chạy (Xiaomi)"
            autoStartRow.sub.text = "Cần bật để Android tự kết nối lại OpenNotify sau khi app bị tắt — bấm để mở"
        }
        notifRow = addRow(container) { openNotificationSettings() }
        soundRow = addRow(container) { Prefs.setSoundEnabled(this, !Prefs.soundEnabled(this)) }
        soundPickRow = addRow(container) { pickSoundTone() }
        soundPickRow.toggle.visibility = View.GONE

        liveStatus = findViewById(R.id.liveStatus)
        liveUptime = findViewById(R.id.liveUptime)
        uptimeContainer = findViewById(R.id.uptimeContainer)
        findViewById<TextView>(R.id.uptimeLegend).text = legend()
    }

    override fun onResume() {
        super.onResume()
        refreshSettings()
        handler.post(autoRefresh)
    }

    override fun onPause() {
        handler.removeCallbacks(autoRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ---- các hàng cài đặt ----

    private fun addRow(container: ViewGroup, onClick: () -> Unit): Row {
        val view = LayoutInflater.from(this).inflate(R.layout.item_setting, container, false)
        container.addView(view)
        val row = Row(view)
        // Công tắc của quyền hệ thống chỉ để hiển thị: sau mỗi lần bấm trả nó về đúng trạng thái thật.
        val click = View.OnClickListener {
            onClick()
            refreshSettings()
        }
        view.setOnClickListener(click)
        row.toggle.setOnClickListener(click)
        return row
    }

    private fun refreshSettings() {
        if (!::soundPickRow.isInitialized) return

        val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        bind(
            listenerRow, "Quyền đọc thông báo", listenerOn,
            if (listenerOn) "Đã cấp — OpenNotify đang được phép đọc thông báo"
            else "Chưa cấp — bấm để mở cài đặt hệ thống và bật OpenNotify"
        )

        val batteryOn = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        bind(
            batteryRow, "Bỏ giới hạn pin", batteryOn,
            if (batteryOn) "Đang được chạy nền không giới hạn — bấm để xem/tắt trong cài đặt hệ thống"
            else "Đang bị hệ thống giới hạn pin — bấm để cho phép chạy nền không giới hạn"
        )

        val notifOn = NotificationManagerCompat.from(this).areNotificationsEnabled()
        bind(
            notifRow, "Hiện thông báo “OpenNotify đang chạy”", notifOn,
            if (notifOn) "Đang bật — bấm để mở cài đặt thông báo của app"
            else "Đang tắt — bấm để bật (không ảnh hưởng việc đọc thông báo, chỉ ẩn dòng “đang chạy”)"
        )

        val soundOn = Prefs.soundEnabled(this)
        bind(
            soundRow, "Phát âm thanh khi có thông báo mới", soundOn,
            if (soundOn) "Bật — kêu khi ứng dụng đang theo dõi có thông báo mới" else "Tắt — im lặng"
        )

        soundPickRow.title.text = "Âm thanh thông báo"
        soundPickRow.sub.text = SoundPlayer.titleOf(this, Prefs.soundUri(this))
        soundPickRow.view.alpha = if (soundOn) 1f else 0.5f
    }

    private fun bind(row: Row, title: String, checked: Boolean, sub: String) {
        row.title.text = title
        row.sub.text = sub
        row.toggle.isChecked = checked
    }

    private fun openBatterySettings() {
        val ignoring = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        try {
            if (ignoring) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } else {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Máy không mở được màn hình này — vào Cài đặt > Pin để chỉnh", Toast.LENGTH_LONG).show()
        }
    }

    private fun isXiaomi(): Boolean =
        Build.MANUFACTURER.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco")

    private fun openAutoStartSettings() {
        val autoStart = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        try {
            startActivity(autoStart)
        } catch (_: Exception) {
            // Bản ROM khác đổi tên màn hình: mở trang thông tin app, mục "Tự khởi chạy" nằm trong đó.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun openNotificationSettings() {
        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Prefs.notifPermissionAsked(this)) {
            // Lần đầu: hiện hộp thoại xin quyền. Bị từ chối rồi thì hệ thống không hiện lại nữa,
            // từ lần sau đưa người dùng vào thẳng cài đặt thông báo của app.
            Prefs.setNotifPermissionAsked(this)
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    private fun pickSoundTone() {
        val current = Prefs.soundUri(this) ?: SoundPlayer.defaultUri()
        pickSound.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Âm thanh thông báo")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, SoundPlayer.defaultUri())
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        )
    }

    // ---- biểu đồ uptime ----

    private fun refreshUptime() {
        io.execute {
            val report = try {
                Liveness.reconnectIfNeeded(applicationContext)
                Liveness.report(applicationContext)
            } catch (_: Exception) {
                return@execute
            }
            runOnUiThread { if (!isDestroyed) showReport(report) }
        }
    }

    private fun showReport(r: UptimeReport) {
        val statusColor = ContextCompat.getColor(this, if (r.connected) R.color.uptime_up else R.color.uptime_down)
        liveStatus.setTextColor(statusColor)
        liveStatus.text = if (r.connected) {
            val since = DateUtils.formatDateTime(
                this, r.connectedSince ?: System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
            )
            "● Đang hoạt động — Android đang giao thông báo cho OpenNotify (kết nối từ $since)"
        } else if (!NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            "● Không kết nối — chưa cấp “Quyền đọc thông báo” ở trên."
        } else {
            "● Không kết nối — quyền đã cấp nhưng Android chưa bind lại OpenNotify (thường do app vừa bị " +
                "tắt và ROM chặn tự khởi động lại). Đang tự yêu cầu kết nối lại…" +
                if (isXiaomi()) " Để không bị lặp lại, bật “Tự khởi chạy” ở trên." else ""
        }

        liveUptime.text = if (r.last24h < 0) "Uptime 24 giờ gần nhất: chưa có dữ liệu"
        else "Uptime 24 giờ gần nhất: ${Liveness.formatPercent(r.last24h)}"

        uptimeContainer.removeAllViews()
        r.days.forEachIndexed { index, day -> uptimeContainer.addView(dayRow(day, index)) }
        uptimeContainer.addView(axisRow())
    }

    private fun dayRow(day: DayUptime, index: Int): View {
        val label = when (index) {
            0 -> "Hôm nay"
            1 -> "Hôm qua"
            else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(day.dayStart))
        }
        val percent = Liveness.formatPercent(day.fraction)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(dp(LABEL_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(UptimeBarView(this).apply {
            setSlots(day.slots)
            contentDescription = "$label: $percent"
            layoutParams = LinearLayout.LayoutParams(0, dp(22), 1f).apply {
                marginStart = dp(BAR_GAP_DP)
                marginEnd = dp(BAR_GAP_DP)
            }
        })
        row.addView(TextView(this).apply {
            text = percent
            textSize = 12f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(dp(LABEL_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        return row
    }

    /** Trục giờ 0h/6h/12h/18h căn thẳng với các thanh phía trên. */
    private fun axisRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(LABEL_DP + BAR_GAP_DP), 0, dp(LABEL_DP + BAR_GAP_DP), 0)
        }
        for (h in listOf("0h", "6h", "12h", "18h")) {
            row.addView(TextView(this).apply {
                text = h
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        return row
    }

    private fun legend(): CharSequence {
        val sb = SpannableStringBuilder()
        val items = listOf(
            R.color.uptime_up to "Hoạt động",
            R.color.uptime_partial to "Gián đoạn một phần",
            R.color.uptime_down to "Mất kết nối",
            R.color.uptime_nodata to "Chưa có dữ liệu"
        )
        items.forEachIndexed { i, (color, text) ->
            if (i > 0) sb.append("   ")
            val start = sb.length
            sb.append("■")
            sb.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, color)),
                start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.append(" ").append(text)
        }
        return sb
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val UPTIME_REFRESH_MS = 5_000L
        private const val LABEL_DP = 58
        private const val BAR_GAP_DP = 6
    }
}

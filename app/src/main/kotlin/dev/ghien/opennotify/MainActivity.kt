package dev.ghien.opennotify

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Trang chủ: danh sách các ứng dụng được phép lấy thông báo (mặc định trống),
 * kèm số thông báo chưa đọc. Dấu + mở màn chọn ứng dụng để thêm; bấm vào một
 * ứng dụng để xem thông báo, nút cấu hình để chỉnh webhook + pattern riêng.
 */
class MainActivity : AppCompatActivity() {

    private class Row(val app: TrackedApp, val icon: Drawable?, val unread: Int)

    private val io = Executors.newSingleThreadExecutor()
    private val adapter = AppAdapter()

    private lateinit var permBanner: View
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permBanner = findViewById(R.id.permBanner)
        emptyView = findViewById(R.id.emptyView)

        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<View>(R.id.addFab).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
        findViewById<View>(R.id.permButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        permBanner.visibility =
            if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) View.GONE
            else View.VISIBLE
        refresh()
    }

    private fun refresh() {
        io.execute {
            val db = Db.get(applicationContext)
            val unread = db.unreadCounts()
            val pm = packageManager
            val rows = db.trackedApps().map { app ->
                val icon = try {
                    pm.getApplicationIcon(app.pkg)
                } catch (_: Exception) {
                    null // ứng dụng đã bị gỡ cài đặt
                }
                Row(app, icon, unread[app.pkg] ?: 0)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                adapter.submit(rows)
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                // Có ứng dụng được theo dõi thì giữ tiến trình sống (không làm gì nếu đã chạy).
                if (rows.isNotEmpty()) {
                    KeepAliveService.start(this)
                    OutboxWorker.schedule(this)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_BATTERY, 0, "Bỏ giới hạn pin cho app này")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != MENU_BATTERY) return super.onOptionsItemSelected(item)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            Toast.makeText(this, "Đã nằm trong danh sách miễn trừ", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {
        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val label: TextView = view.findViewById(R.id.appLabel)
            val sub: TextView = view.findViewById(R.id.appSub)
            val badge: TextView = view.findViewById(R.id.unreadBadge)
            val config: ImageButton = view.findViewById(R.id.configButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.label.text = row.app.label
            holder.sub.text = row.app.pkg
            if (row.icon != null) holder.icon.setImageDrawable(row.icon)
            else holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)

            holder.badge.visibility = if (row.unread > 0) View.VISIBLE else View.GONE
            holder.badge.text = if (row.unread > 99) "99+" else row.unread.toString()

            holder.itemView.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, NotificationListActivity::class.java)
                        .putExtra(EXTRA_PKG, row.app.pkg)
                )
            }
            holder.config.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, AppConfigActivity::class.java)
                        .putExtra(EXTRA_PKG, row.app.pkg)
                )
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        private const val MENU_BATTERY = 1
    }
}

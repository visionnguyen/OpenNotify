package dev.ghien.opennotify

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Chi tiết các thông báo đã ghi nhận của một ứng dụng đang theo dõi. Mở màn
 * này là đánh dấu đã đọc; thông báo mới (chưa đọc lúc mở) được in đậm.
 */
class NotificationListActivity : AppCompatActivity() {

    private lateinit var pkg: String
    private lateinit var listView: ListView
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_list)

        pkg = intent.getStringExtra(MainActivity.EXTRA_PKG).orEmpty()
        title = Db.get(this).trackedApp(pkg)?.label ?: pkg

        listView = findViewById(R.id.notifList)
        emptyView = findViewById(R.id.notifEmpty)
    }

    override fun onResume() {
        super.onResume()
        val db = Db.get(this)
        val items = db.notificationsForPackage(pkg)
        db.markPackageRead(pkg) // `items` giữ trạng thái chưa đọc lúc mở để còn in đậm

        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        listView.adapter = NotifAdapter(items)
        listView.setOnItemClickListener { _, _, position, _ -> showDetail(items[position]) }
    }

    private fun showDetail(n: NotificationRecord) {
        val statuses = Db.get(this).deliveryStatuses(n.hash)
        val body = buildString {
            append("Title: ${n.title}\n\n")
            append("Text: ${n.text}\n\n")
            if (n.bigText.isNotBlank() && n.bigText != n.text) append("Big text: ${n.bigText}\n\n")
            if (n.subText.isNotBlank()) append("Sub text: ${n.subText}\n\n")
            if (n.lines.isNotBlank()) append("Lines: ${n.lines}\n\n")
            if (statuses.isEmpty()) {
                append("Webhook: không khớp webhook nào")
            } else {
                append("Webhook:")
                statuses.forEach { append("\n• ${it.webhookName}: ${if (it.sent) "đã gửi" else "chờ gửi"}") }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Chi tiết thông báo")
            .setMessage(body)
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Cấu hình webhook") { _, _ ->
                startActivity(
                    Intent(this, AppConfigActivity::class.java).putExtra(MainActivity.EXTRA_PKG, pkg)
                )
            }
            .show()
    }

    private inner class NotifAdapter(private val items: List<NotificationRecord>) :
        ArrayAdapter<NotificationRecord>(this@NotificationListActivity, 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_notification, parent, false)
            val n = items[position]
            val style = if (n.read) Typeface.NORMAL else Typeface.BOLD

            view.findViewById<TextView>(R.id.nTitle).apply {
                text = n.title.ifBlank { "(không có tiêu đề)" }
                setTypeface(null, style)
            }
            view.findViewById<TextView>(R.id.nPreview).text = n.text.ifBlank { n.bigText }
            view.findViewById<TextView>(R.id.nMeta).text =
                DateUtils.getRelativeTimeSpanString(n.postTime).toString()
            return view
        }
    }
}

package dev.ghien.mbrelay

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

/** Danh sách thông báo đã ghi nhận của một package cụ thể. Bấm vào một
 *  dòng để xem đầy đủ nội dung, hoặc tạo luật relay ngay từ đó. */
class NotificationListActivity : AppCompatActivity() {

    private lateinit var items: List<NotificationRecord>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_list)

        val pkg = intent.getStringExtra("pkg") ?: ""
        title = pkg

        items = Db(this).notificationsForPackage(pkg)
        val list = findViewById<ListView>(R.id.notifList)

        if (items.isEmpty()) {
            list.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_1, listOf("Chưa có thông báo nào từ nguồn này.")
            )
            return
        }

        val lines = items.map { n ->
            val when_ = DateUtils.getRelativeTimeSpanString(n.postTime)
            val tag = if (n.relayMatched) (if (n.relaySent) "[đã relay] " else "[chờ relay] ") else ""
            val preview = n.text.ifBlank { n.bigText }.take(80)
            "$tag${n.title}\n$preview · $when_"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        list.setOnItemClickListener { _, _, position, _ -> showDetail(items[position]) }
    }

    private fun showDetail(n: NotificationRecord) {
        val body = buildString {
            append("Package: ${n.pkg}\n\n")
            append("Title: ${n.title}\n\n")
            append("Text: ${n.text}\n\n")
            if (n.bigText.isNotBlank() && n.bigText != n.text) append("Big text: ${n.bigText}\n\n")
            if (n.subText.isNotBlank()) append("Sub text: ${n.subText}\n\n")
            if (n.lines.isNotBlank()) append("Lines: ${n.lines}\n\n")
            append(
                "Relay: " + when {
                    !n.relayMatched -> "không khớp luật nào"
                    n.relaySent -> "đã gửi"
                    else -> "khớp luật, đang chờ gửi"
                }
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Chi tiết thông báo")
            .setMessage(body)
            .setPositiveButton("Đóng", null)
            .setNeutralButton("Tạo luật relay cho nguồn này") { _, _ ->
                startActivity(
                    Intent(this, RulesActivity::class.java).putExtra("prefill_pkg", n.pkg)
                )
            }
            .show()
    }
}

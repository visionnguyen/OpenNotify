package dev.ghien.mbrelay

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity

/** Liệt kê các package đã từng gửi thông báo bắt được, kèm số lượng
 *  và lần gần nhất, để chọn nguồn muốn xem chi tiết hoặc tạo luật relay. */
class SourcesActivity : AppCompatActivity() {

    private lateinit var sources: List<SourceSummary>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)
        title = "Nguồn thông báo"
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        sources = Db(this).sourceSummaries()
        val list = findViewById<ListView>(R.id.sourceList)

        if (sources.isEmpty()) {
            list.adapter = ArrayAdapter(
                this, android.R.layout.simple_list_item_1,
                listOf("Chưa có thông báo nào được ghi nhận.")
            )
            return
        }

        val lines = sources.map { s ->
            val when_ = DateUtils.getRelativeTimeSpanString(s.lastTs)
            "${s.pkg}\n${s.count} thông báo · gần nhất $when_"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        list.setOnItemClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, NotificationListActivity::class.java)
                    .putExtra("pkg", sources[position].pkg)
            )
        }
    }
}

package dev.ghien.opennotify

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Cấu hình một ứng dụng: danh sách webhook (mỗi cái có bộ pattern riêng). */
class AppConfigActivity : AppCompatActivity() {

    private lateinit var pkg: String
    private lateinit var db: Db
    private lateinit var emptyView: View
    private val adapter = WebhookAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_config)

        pkg = intent.getStringExtra(MainActivity.EXTRA_PKG).orEmpty()
        db = Db.get(this)
        val app = db.trackedApp(pkg)
        if (app == null) { // ứng dụng đã bị bỏ theo dõi
            finish()
            return
        }
        title = "Cấu hình · ${app.label}"

        emptyView = findViewById(R.id.webhookEmpty)
        findViewById<RecyclerView>(R.id.webhookList).apply {
            layoutManager = LinearLayoutManager(this@AppConfigActivity)
            adapter = this@AppConfigActivity.adapter
        }
        findViewById<View>(R.id.addWebhookButton).setOnClickListener { openEditor(0L) }
    }

    override fun onResume() {
        super.onResume()
        if (!::emptyView.isInitialized) return
        val list = db.webhooksForPackage(pkg)
        adapter.submit(list)
        emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEditor(webhookId: Long) {
        startActivity(
            Intent(this, WebhookEditActivity::class.java)
                .putExtra(MainActivity.EXTRA_PKG, pkg)
                .putExtra(WebhookEditActivity.EXTRA_WEBHOOK_ID, webhookId)
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REMOVE, 0, "Bỏ theo dõi ứng dụng này")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != MENU_REMOVE) return super.onOptionsItemSelected(item)
        AlertDialog.Builder(this)
            .setTitle("Bỏ theo dõi ứng dụng?")
            .setMessage("Toàn bộ thông báo đã lưu, webhook và pattern của ứng dụng này sẽ bị xóa. OpenNotify sẽ không lấy thông báo từ ứng dụng này nữa.")
            .setPositiveButton("Bỏ theo dõi") { _, _ ->
                db.removeTrackedApp(pkg)
                finish()
            }
            .setNegativeButton("Hủy", null)
            .show()
        return true
    }

    private fun confirmDelete(w: Webhook) {
        AlertDialog.Builder(this)
            .setTitle("Xóa webhook?")
            .setMessage(w.name)
            .setPositiveButton("Xóa") { _, _ ->
                db.deleteWebhook(w.id)
                onResume()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private inner class WebhookAdapter : RecyclerView.Adapter<WebhookAdapter.VH>() {
        private var items: List<Webhook> = emptyList()

        fun submit(list: List<Webhook>) {
            items = list
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.whName)
            val url: TextView = view.findViewById(R.id.whUrl)
            val patterns: TextView = view.findViewById(R.id.whPatterns)
            val enabled: Switch = view.findViewById(R.id.whEnabled)
            val delete: ImageButton = view.findViewById(R.id.whDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_webhook, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val w = items[position]
            holder.name.text = w.name
            val security = if (w.security == Security.AES_GCM) "Mã hóa AES-GCM" else "HMAC"
            holder.url.text = "$security · ${w.url}"
            holder.patterns.text = when {
                w.stopped -> "⚠ Bên nhận báo không còn nhận (404/410) — đã ngừng gửi. Bấm vào để sửa hoặc quét lại mã QR."
                w.patterns.isEmpty() -> "Nhận mọi thông báo của ứng dụng"
                w.mode == MatchMode.AND -> "${w.patterns.size} pattern · khớp TẤT CẢ (và)"
                else -> "${w.patterns.size} pattern · khớp MỘT TRONG (hoặc)"
            }
            holder.enabled.setOnCheckedChangeListener(null)
            holder.enabled.isChecked = w.enabled
            holder.enabled.setOnCheckedChangeListener { _, checked -> db.setWebhookEnabled(w.id, checked) }
            holder.delete.setOnClickListener { confirmDelete(w) }
            holder.itemView.setOnClickListener { openEditor(w.id) }
        }
    }

    companion object {
        private const val MENU_REMOVE = 1
    }
}

package dev.ghien.opennotify

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * Chi tiết các thông báo đã ghi nhận của một ứng dụng đang theo dõi. Mở màn
 * này là đánh dấu đã đọc; thông báo mới (chưa đọc lúc mở) được in đậm.
 *
 * Vuốt một dòng sang trái để lộ nút Xóa ở cuối dòng; nút thùng rác trên thanh
 * công cụ xóa hết thông báo của ứng dụng này (có hỏi xác nhận).
 */
class NotificationListActivity : AppCompatActivity() {

    private lateinit var pkg: String
    private lateinit var db: Db
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View

    private val items = mutableListOf<NotificationRecord>()
    private val adapter = NotifAdapter()

    /** hash của dòng đang được vuốt mở để lộ nút Xóa (chỉ một dòng mở tại một thời điểm). */
    private var openHash: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_list)

        pkg = intent.getStringExtra(MainActivity.EXTRA_PKG).orEmpty()
        db = Db.get(this)
        title = db.trackedApp(pkg)?.label ?: pkg

        emptyView = findViewById(R.id.notifEmpty)
        recycler = findViewById(R.id.notifList)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        // Đóng dòng đang mở bằng notifyItemChanged: tắt animation đổi để nó không nhấp nháy.
        (recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        ItemTouchHelper(SwipeRevealCallback()).attachToRecyclerView(recycler)
    }

    override fun onResume() {
        super.onResume()
        val loaded = db.notificationsForPackage(pkg)
        db.markPackageRead(pkg) // `loaded` giữ trạng thái chưa đọc lúc mở để còn in đậm

        items.clear()
        items.addAll(loaded)
        openHash = null
        adapter.notifyDataSetChanged()
        onItemsChanged()
    }

    private fun onItemsChanged() {
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        invalidateOptionsMenu() // ẩn nút xóa hết khi không còn gì để xóa
    }

    // ---- xóa ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_DELETE_ALL, 0, "Xóa hết thông báo")
            .setIcon(R.drawable.ic_delete)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_DELETE_ALL)?.isVisible = items.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != MENU_DELETE_ALL) return super.onOptionsItemSelected(item)
        AlertDialog.Builder(this)
            .setTitle("Xóa hết thông báo?")
            .setMessage("Toàn bộ thông báo của $title sẽ bị xóa khỏi danh sách.")
            .setPositiveButton("Xóa hết") { _, _ ->
                db.deleteAllNotifications(pkg)
                items.clear()
                openHash = null
                adapter.notifyDataSetChanged()
                onItemsChanged()
            }
            .setNegativeButton("Hủy", null)
            .show()
        return true
    }

    private fun deleteAt(position: Int) {
        if (position !in items.indices) return
        db.deleteNotification(items.removeAt(position).hash)
        openHash = null
        adapter.notifyItemRemoved(position)
        onItemsChanged()
    }

    private fun closeOpenRow() {
        val hash = openHash ?: return
        openHash = null
        val index = items.indexOfFirst { it.hash == hash }
        if (index >= 0) adapter.notifyItemChanged(index)
    }

    // ---- chi tiết ----

    private fun showDetail(n: NotificationRecord) {
        val statuses = db.deliveryStatuses(n.hash)
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

    // ---- danh sách ----

    private inner class NotifAdapter : RecyclerView.Adapter<NotifAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val foreground: View = view.findViewById(R.id.nForeground)
            val delete: View = view.findViewById(R.id.nDelete)
            val title: TextView = view.findViewById(R.id.nTitle)
            val preview: TextView = view.findViewById(R.id.nPreview)
            val meta: TextView = view.findViewById(R.id.nMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val n = items[position]
            val style = if (n.read) Typeface.NORMAL else Typeface.BOLD

            holder.title.text = n.title.ifBlank { "(không có tiêu đề)" }
            holder.title.setTypeface(null, style)
            holder.preview.text = n.text.ifBlank { n.bigText }
            holder.meta.text = DateUtils.getRelativeTimeSpanString(n.postTime).toString()

            // ViewHolder được tái sử dụng: dựng lại đúng trạng thái đóng/mở của dòng này.
            holder.foreground.translationX =
                if (n.hash == openHash) -resources.getDimension(R.dimen.swipe_delete_width) else 0f

            holder.foreground.setOnClickListener {
                // Đang có dòng mở nút Xóa thì chạm vào đâu cũng chỉ đóng nó lại, không mở chi tiết.
                if (openHash != null) closeOpenRow() else showDetail(n)
            }
            holder.delete.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) deleteAt(pos)
            }
        }
    }

    /**
     * Vuốt trái/phải chỉ để mở/đóng nút Xóa, không bao giờ "vuốt bay" dòng đi: ngưỡng vuốt > 100%
     * chiều rộng và vận tốc thoát vô hạn nên onSwiped không bao giờ được gọi. Phần nội dung của dòng
     * trượt tối đa bằng bề rộng nút Xóa; thả tay quá nửa thì bám lại ở vị trí mở, chưa tới nửa thì đóng.
     */
    private inner class SwipeRevealCallback :
        ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

        private val reveal = resources.getDimension(R.dimen.swipe_delete_width)
        private var activeHash: String? = null // dòng đang được vuốt
        private var lastX = 0f // vị trí (âm) hiện tại của phần nội dung dòng đang vuốt

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 2f

        override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE || viewHolder == null) return
            val hash = items.getOrNull(viewHolder.bindingAdapterPosition)?.hash
            if (openHash != null && openHash != hash) closeOpenRow() // chỉ một dòng mở
            activeHash = hash
            lastX = if (hash != null && hash == openHash) -reveal else 0f
        }

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                return
            }
            val foreground = (viewHolder as NotifAdapter.VH).foreground
            if (isCurrentlyActive) {
                val hash = items.getOrNull(viewHolder.bindingAdapterPosition)?.hash
                val base = if (hash != null && hash == openHash) -reveal else 0f
                lastX = (base + dX).coerceIn(-reveal, 0f)
            } else {
                // Đã thả tay: chốt mở/đóng một lần rồi giữ nguyên trong lúc ItemTouchHelper chạy animation hoàn về.
                lastX = if (lastX <= -reveal / 2) -reveal else 0f
            }
            foreground.translationX = lastX
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            val hash = items.getOrNull(viewHolder.bindingAdapterPosition)?.hash
            if (hash != null && hash == activeHash) {
                openHash = if (lastX < 0f) hash else null
                activeHash = null
            }
            (viewHolder as NotifAdapter.VH).foreground.translationX =
                if (hash != null && hash == openHash) -reveal else 0f
        }
    }

    companion object {
        private const val MENU_DELETE_ALL = 1
    }
}

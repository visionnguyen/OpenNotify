package dev.ghien.opennotify

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Liệt kê mọi ứng dụng có icon khởi chạy trên máy (icon + tên), cho phép tìm
 * kiếm, chọn nhiều ứng dụng rồi bấm "Thêm" để đưa vào danh sách theo dõi.
 */
class AppPickerActivity : AppCompatActivity() {

    private class PickApp(val pkg: String, val label: String, val icon: Drawable)

    private val io = Executors.newSingleThreadExecutor()
    private val adapter = PickAdapter()
    private val selected = LinkedHashSet<String>()

    private lateinit var addButton: Button
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)
        title = "Chọn ứng dụng"

        savedInstanceState?.getStringArray(STATE_SELECTED)?.let { selected.addAll(it) }

        addButton = findViewById(R.id.addButton)
        emptyView = findViewById(R.id.pickEmpty)

        findViewById<RecyclerView>(R.id.pickList).apply {
            layoutManager = LinearLayoutManager(this@AppPickerActivity)
            adapter = this@AppPickerActivity.adapter
        }
        findViewById<EditText>(R.id.searchField).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = adapter.filter(s?.toString().orEmpty())
        })
        addButton.setOnClickListener { addSelected() }
        updateAddButton()

        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(STATE_SELECTED, selected.toTypedArray())
    }

    private fun load() {
        io.execute {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val tracked = Db.get(applicationContext).trackedPackages()
            val apps = pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in tracked }
                .map { PickApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                adapter.setAll(apps)
                selected.retainAll(apps.mapTo(HashSet()) { it.pkg })
                updateAddButton()
            }
        }
    }

    private fun addSelected() {
        val chosen = adapter.all.filter { it.pkg in selected }.map { it.pkg to it.label }
        if (chosen.isEmpty()) return
        Db.get(this).addTrackedApps(chosen)
        KeepAliveService.start(this)
        OutboxWorker.schedule(this)
        finish()
    }

    private fun updateAddButton() {
        addButton.isEnabled = selected.isNotEmpty()
        addButton.text = if (selected.isEmpty()) "Thêm" else "Thêm (${selected.size})"
    }

    private inner class PickAdapter : RecyclerView.Adapter<PickAdapter.VH>() {
        var all: List<PickApp> = emptyList()
            private set
        private var shown: List<PickApp> = emptyList()
        private var query = ""

        fun setAll(apps: List<PickApp>) {
            all = apps
            applyFilter()
        }

        fun filter(q: String) {
            query = q.trim()
            applyFilter()
        }

        private fun applyFilter() {
            shown = if (query.isEmpty()) all
            else all.filter {
                it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
            }
            notifyDataSetChanged()
            emptyView.text = "Không có ứng dụng nào phù hợp."
            emptyView.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.pickIcon)
            val label: TextView = view.findViewById(R.id.pickLabel)
            val pkg: TextView = view.findViewById(R.id.pickPkg)
            val check: CheckBox = view.findViewById(R.id.pickCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pick_app, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val app = shown[position]
            holder.icon.setImageDrawable(app.icon)
            holder.label.text = app.label
            holder.pkg.text = app.pkg
            holder.check.isChecked = app.pkg in selected
            holder.itemView.setOnClickListener {
                if (!selected.add(app.pkg)) selected.remove(app.pkg)
                holder.check.isChecked = app.pkg in selected
                updateAddButton()
            }
        }
    }

    companion object {
        private const val STATE_SELECTED = "selected"
    }
}

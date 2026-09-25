package dev.ghien.mbrelay

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Quản lý bảng relay_rules: thêm/xóa/bật-tắt. Một rule gồm package +
 *  pattern regex tùy chọn (để trống = khớp mọi thông báo từ package đó). */
class RulesActivity : AppCompatActivity() {

    private lateinit var db: Db
    private lateinit var listView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)
        title = "Luật relay"

        db = Db(this)
        listView = findViewById(R.id.ruleList)
        findViewById<Button>(R.id.addRuleButton).setOnClickListener { showAddDialog(null) }

        refresh()
        // Mở từ màn chi tiết thông báo với package gợi ý sẵn -> mở luôn dialog thêm luật.
        intent.getStringExtra("prefill_pkg")?.let { showAddDialog(it) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        listView.adapter = RuleAdapter(this, db.allRules())
    }

    private fun showAddDialog(prefillPkg: String?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_rule, null)
        val pkgField = view.findViewById<EditText>(R.id.dialogPkg)
        val patternField = view.findViewById<EditText>(R.id.dialogPattern)
        val enabledField = view.findViewById<CheckBox>(R.id.dialogEnabled)
        prefillPkg?.let { pkgField.setText(it) }
        enabledField.isChecked = true

        val dialog = AlertDialog.Builder(this)
            .setTitle("Thêm luật relay")
            .setView(view)
            .setPositiveButton("Lưu", null)
            .setNegativeButton("Hủy", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pkg = pkgField.text.toString().trim()
                val pattern = patternField.text.toString().trim()

                if (pkg.isBlank()) {
                    Toast.makeText(this, "Cần nhập package", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pattern.isNotBlank()) {
                    try {
                        Regex(pattern)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Pattern regex không hợp lệ: ${e.message}", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                }
                db.addRule(pkg, pattern.ifBlank { null }, enabledField.isChecked)
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private inner class RuleAdapter(
        private val ctx: RulesActivity,
        private val rules: List<RelayRule>
    ) : ArrayAdapter<RelayRule>(ctx, 0, rules) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_rule, parent, false)
            val rule = rules[position]

            view.findViewById<TextView>(R.id.rulePkg).text = rule.pkg
            view.findViewById<TextView>(R.id.rulePattern).text =
                if (rule.pattern.isNullOrBlank()) "Khớp mọi thông báo từ nguồn này"
                else "Pattern: ${rule.pattern}"

            val sw = view.findViewById<Switch>(R.id.ruleEnabled)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = rule.enabled
            sw.setOnCheckedChangeListener { _, checked -> db.setRuleEnabled(rule.id, checked) }

            view.findViewById<Button>(R.id.ruleDelete).setOnClickListener {
                db.deleteRule(rule.id)
                refresh()
            }
            return view
        }
    }
}

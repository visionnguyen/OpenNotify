package dev.ghien.opennotify

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Thêm/sửa một webhook của một ứng dụng: URL, secret, danh sách pattern
 * (regex) và cách kết hợp chúng (VÀ / HOẶC).
 */
class WebhookEditActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var pkg: String
    private var webhookId = 0L

    private lateinit var nameField: EditText
    private lateinit var urlField: EditText
    private lateinit var secretField: EditText
    private lateinit var modeAnd: RadioButton
    private lateinit var modeOr: RadioButton
    private lateinit var enabledField: CheckBox
    private lateinit var patternContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webhook_edit)

        pkg = intent.getStringExtra(MainActivity.EXTRA_PKG).orEmpty()
        webhookId = intent.getLongExtra(EXTRA_WEBHOOK_ID, 0L)

        nameField = findViewById(R.id.whNameField)
        urlField = findViewById(R.id.whUrlField)
        secretField = findViewById(R.id.whSecretField)
        modeAnd = findViewById(R.id.modeAnd)
        modeOr = findViewById(R.id.modeOr)
        enabledField = findViewById(R.id.whEnabledField)
        patternContainer = findViewById(R.id.patternContainer)

        findViewById<Button>(R.id.addPatternButton).setOnClickListener { addPatternRow("") }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testButton).setOnClickListener { sendTest() }

        val existing = if (webhookId != 0L) Db.get(this).webhook(webhookId) else null
        if (webhookId != 0L && existing == null) { // webhook đã bị xóa
            finish()
            return
        }
        title = if (existing == null) "Thêm webhook" else "Sửa webhook"

        if (existing != null) {
            nameField.setText(existing.name)
            urlField.setText(existing.url)
            secretField.setText(existing.secret)
            enabledField.isChecked = existing.enabled
            existing.patterns.forEach { addPatternRow(it) }
        }
        (if (existing?.mode == MatchMode.AND) modeAnd else modeOr).isChecked = true
    }

    private fun addPatternRow(value: String) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_pattern_row, patternContainer, false)
        row.findViewById<EditText>(R.id.patternField).setText(value)
        row.findViewById<View>(R.id.patternRemove).setOnClickListener { patternContainer.removeView(row) }
        patternContainer.addView(row)
    }

    private fun currentPatterns(): List<String> =
        (0 until patternContainer.childCount)
            .map { patternContainer.getChildAt(it).findViewById<EditText>(R.id.patternField).text.toString().trim() }
            .filter { it.isNotEmpty() }

    /** Trả về thông báo lỗi nếu url/secret không hợp lệ, null nếu ổn. */
    private fun validateEndpoint(url: String, secret: String): String? = when {
        !(url.startsWith("http://") || url.startsWith("https://")) || Uri.parse(url).host.isNullOrBlank() ->
            "URL phải bắt đầu bằng http:// hoặc https://"
        secret.isBlank() -> "Cần nhập secret để ký HMAC"
        else -> null
    }

    private fun save() {
        val url = urlField.text.toString().trim()
        val secret = secretField.text.toString().trim()
        validateEndpoint(url, secret)?.let { toast(it); return }

        val patterns = currentPatterns()
        patterns.firstOrNull { !PatternMatcher.isValidRegex(it) }?.let {
            toast("Pattern regex không hợp lệ: $it")
            return
        }

        val name = nameField.text.toString().trim().ifEmpty { Uri.parse(url).host ?: url }
        Db.get(this).saveWebhook(
            Webhook(
                id = webhookId, pkg = pkg, name = name, url = url, secret = secret,
                mode = if (modeAnd.isChecked) MatchMode.AND else MatchMode.OR,
                enabled = enabledField.isChecked, patterns = patterns
            )
        )
        finish()
    }

    private fun sendTest() {
        val url = urlField.text.toString().trim()
        val secret = secretField.text.toString().trim()
        validateEndpoint(url, secret)?.let { toast(it); return }

        io.execute {
            val body = JSONObject().apply {
                put("source", "opennotify")
                put("package", "test")
                put("key", "test-${System.currentTimeMillis()}")
                put("post_time", System.currentTimeMillis())
                put("title", "Test")
                put("text", "+50,000 VND test DH0001")
                put("big_text", "")
                put("sub_text", "")
                put("lines", "")
            }.toString()
            val ok = WebhookClient.post(url, secret, body)
            runOnUiThread {
                toast(if (ok) "Gửi test thành công" else "Gửi thất bại, xem log backend")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_WEBHOOK_ID = "webhook_id"
    }
}

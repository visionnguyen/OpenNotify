package dev.ghien.opennotify

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.util.concurrent.Executors

/**
 * Thêm/sửa một webhook theo chuẩn OpenNotify Webhook v1 (docs/webhook-standard.md). Một form duy
 * nhất cho mọi chế độ bảo mật; nút quét QR chỉ điền sẵn các ô của form — nhập tay và quét QR cho
 * ra đúng cùng một cấu hình (WebhookConfig) và qua cùng một bước kiểm tra.
 *
 * Secret/khóa không bao giờ được điền ra ô dạng rõ: ô để trống nghĩa là giữ giá trị đang lưu
 * (hoặc vừa quét), gõ vào thì thay.
 */
class WebhookEditActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var pkg: String
    private var webhookId = 0L
    private var stopped = false

    /** Secret/khóa đang giữ (đã lưu hoặc vừa quét), dùng khi ô secret để trống. */
    private var keptSecret = ""

    private lateinit var nameField: EditText
    private lateinit var urlField: EditText
    private lateinit var securityHmac: RadioButton
    private lateinit var securityAes: RadioButton
    private lateinit var secretLabel: TextView
    private lateinit var secretField: EditText
    private lateinit var endpointIdLabel: TextView
    private lateinit var endpointIdField: EditText
    private lateinit var modeAnd: RadioButton
    private lateinit var modeOr: RadioButton
    private lateinit var normalizeField: CheckBox
    private lateinit var enabledField: CheckBox
    private lateinit var patternContainer: LinearLayout
    private lateinit var stoppedWarning: View

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onScanned) // null = người dùng bấm quay lại
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webhook_edit)

        pkg = intent.getStringExtra(MainActivity.EXTRA_PKG).orEmpty()
        webhookId = intent.getLongExtra(EXTRA_WEBHOOK_ID, 0L)

        nameField = findViewById(R.id.whNameField)
        urlField = findViewById(R.id.whUrlField)
        securityHmac = findViewById(R.id.securityHmac)
        securityAes = findViewById(R.id.securityAes)
        secretLabel = findViewById(R.id.secretLabel)
        secretField = findViewById(R.id.whSecretField)
        endpointIdLabel = findViewById(R.id.endpointIdLabel)
        endpointIdField = findViewById(R.id.whEndpointIdField)
        modeAnd = findViewById(R.id.modeAnd)
        modeOr = findViewById(R.id.modeOr)
        normalizeField = findViewById(R.id.normalizeField)
        enabledField = findViewById(R.id.whEnabledField)
        patternContainer = findViewById(R.id.patternContainer)
        stoppedWarning = findViewById(R.id.stoppedWarning)

        findViewById<Button>(R.id.addPatternButton).setOnClickListener { addPatternRow("") }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testButton).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.scanQrButton).setOnClickListener { scanQr() }
        securityHmac.setOnCheckedChangeListener { _, _ -> refreshSecurityLabels() }
        securityAes.setOnCheckedChangeListener { _, _ -> refreshSecurityLabels() }

        val existing = if (webhookId != 0L) Db.get(this).webhook(webhookId) else null
        if (webhookId != 0L && existing == null) { // webhook đã bị xóa
            finish()
            return
        }
        title = if (existing == null) "Thêm webhook" else "Sửa webhook"

        if (existing != null) {
            fill(WebhookConfig.of(existing))
            enabledField.isChecked = existing.enabled
            stopped = existing.stopped
        } else {
            securityHmac.isChecked = true
            modeOr.isChecked = true
        }
        stoppedWarning.visibility = if (stopped) View.VISIBLE else View.GONE
        refreshSecurityLabels()
    }

    /** Điền cấu hình vào form — dùng chung cho mở webhook đã lưu và kết quả quét QR. */
    private fun fill(c: WebhookConfig) {
        nameField.setText(c.name)
        urlField.setText(c.url)
        (if (c.security == Security.AES_GCM) securityAes else securityHmac).isChecked = true
        keptSecret = c.secret
        secretField.setText("")
        endpointIdField.setText(c.endpointId.orEmpty())
        patternContainer.removeAllViews()
        c.patterns.forEach { addPatternRow(it) }
        (if (c.mode == MatchMode.AND) modeAnd else modeOr).isChecked = true
        normalizeField.isChecked = c.normalize
        refreshSecurityLabels()
    }

    private fun currentSecurity() = if (securityAes.isChecked) Security.AES_GCM else Security.HMAC

    private fun refreshSecurityLabels() {
        val aes = currentSecurity() == Security.AES_GCM
        secretLabel.text = if (aes) "Khóa AES-GCM (32 byte base64url, 43 ký tự)" else "Secret HMAC"
        endpointIdLabel.text = if (aes) "ID endpoint (bắt buộc)" else "ID endpoint (tùy chọn, gửi trong header X-OpenNotify-Id)"
        secretField.hint = if (keptSecret.isNotEmpty()) "Đã lưu — để trống để giữ nguyên" else ""
    }

    // ---- quét mã QR ----

    private fun scanQr() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Quét mã QR cấu hình webhook")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                // Mã QR không mang ECI: phải ép UTF-8, nếu không tên có dấu sẽ bị vỡ.
                .addExtra(Intents.Scan.CHARACTER_SET, "UTF-8")
        )
    }

    private fun onScanned(contents: String) {
        val config = when (val r = WebhookQr.parse(contents)) {
            is QrParse.Error -> {
                AlertDialog.Builder(this).setTitle("Không dùng được mã QR này").setMessage(r.message)
                    .setPositiveButton("Đóng", null).show()
                return
            }
            is QrParse.Ok -> r.config
        }

        // Quét lại đúng endpoint đã có trong app này = cập nhật webhook đó, không tạo bản thứ hai.
        val same = config.endpointId?.let { Db.get(this).findByEndpoint(pkg, config.url, it) }
        if (same != null && same.id != webhookId) {
            webhookId = same.id
            title = "Sửa webhook"
            enabledField.isChecked = same.enabled
            toast("Webhook tới endpoint này đã có — lưu sẽ cập nhật webhook đó")
            apply(config)
            return
        }

        // Đang sửa một webhook mà mã QR trỏ sang nơi khác: hỏi trước khi ghi đè cấu hình.
        val current = urlField.text.toString().trim()
        if (webhookId != 0L && (config.url != current || config.security != currentSecurity())) {
            AlertDialog.Builder(this)
                .setTitle("Thay cấu hình webhook này?")
                .setMessage("Mã QR trỏ tới ${config.url}. Cấu hình hiện tại ($current) sẽ bị thay khi bấm Lưu.")
                .setPositiveButton("Thay") { _, _ -> apply(config) }
                .setNegativeButton("Hủy", null)
                .show()
            return
        }
        apply(config)
    }

    private fun apply(config: WebhookConfig) {
        fill(config)
        stopped = false
        stoppedWarning.visibility = View.GONE
        toast("Đã điền từ mã QR — kiểm tra rồi bấm Lưu")
    }

    // ---- form ----

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

    /** Cấu hình đang có trên form; null (kèm thông báo) nếu không hợp lệ theo chuẩn. */
    private fun currentConfig(): WebhookConfig? {
        val config = WebhookConfig(
            name = nameField.text.toString().trim(),
            url = urlField.text.toString().trim(),
            security = currentSecurity(),
            secret = secretField.text.toString().trim().ifEmpty { keptSecret },
            endpointId = endpointIdField.text.toString().trim().ifEmpty { null },
            patterns = currentPatterns(),
            mode = if (modeAnd.isChecked) MatchMode.AND else MatchMode.OR,
            normalize = normalizeField.isChecked
        )
        config.validate()?.let { toast(it); return null }
        return config
    }

    private fun save() {
        val config = currentConfig() ?: return
        // Lưu lại sau khi sửa = người dùng đã xử lý cảnh báo "ngừng nhận" -> gửi lại bình thường.
        Db.get(this).saveWebhook(config.toWebhook(webhookId, pkg, enabledField.isChecked, stopped = false))
        finish()
    }

    private fun sendTest() {
        val config = currentConfig() ?: return
        val w = config.toWebhook(webhookId, pkg, enabled = true)
        io.execute {
            val code = Sender.post(w, Payload.test())
            runOnUiThread { toast(Sender.describeTest(code, w.url)) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_WEBHOOK_ID = "webhook_id"
    }
}

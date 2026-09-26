package dev.ghien.opennotify

import android.app.AlertDialog
import android.net.Uri
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
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Thêm/sửa một webhook của một ứng dụng. Hai loại:
 *  - HMAC: tự nhập URL, secret, danh sách pattern (regex) và cách kết hợp (VÀ / HOẶC).
 *  - mapchat: quét mã QR ở máy quầy là xong; URL, mã ghép, khóa và pattern đều lấy từ mã QR
 *    và bị khóa không cho sửa tay (docs/mapchat-pairing.md). Khóa không bao giờ hiện ra màn hình.
 */
class WebhookEditActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    private lateinit var pkg: String
    private var webhookId = 0L

    /** Cặp mapchat đang sửa: từ lần quét vừa rồi, hoặc từ webhook mapchat đã lưu. null = webhook HMAC. */
    private var pairing: MapchatPairing? = null
    private var unpaired = false

    private lateinit var nameField: EditText
    private lateinit var urlField: EditText
    private lateinit var secretField: EditText
    private lateinit var secretGroup: View
    private lateinit var modeGroup: View
    private lateinit var modeAnd: RadioButton
    private lateinit var modeOr: RadioButton
    private lateinit var enabledField: CheckBox
    private lateinit var patternContainer: LinearLayout
    private lateinit var addPatternButton: Button
    private lateinit var patternHelp: TextView
    private lateinit var pairingInfo: TextView

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
        secretField = findViewById(R.id.whSecretField)
        secretGroup = findViewById(R.id.secretGroup)
        modeGroup = findViewById(R.id.modeGroup)
        modeAnd = findViewById(R.id.modeAnd)
        modeOr = findViewById(R.id.modeOr)
        enabledField = findViewById(R.id.whEnabledField)
        patternContainer = findViewById(R.id.patternContainer)
        addPatternButton = findViewById(R.id.addPatternButton)
        patternHelp = findViewById(R.id.patternHelp)
        pairingInfo = findViewById(R.id.pairingInfo)

        addPatternButton.setOnClickListener { addPatternRow("") }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }
        findViewById<Button>(R.id.testButton).setOnClickListener { sendTest() }
        findViewById<Button>(R.id.scanPairingButton).setOnClickListener { scanPairing() }

        val existing = if (webhookId != 0L) Db.get(this).webhook(webhookId) else null
        if (webhookId != 0L && existing == null) { // webhook đã bị xóa
            finish()
            return
        }
        title = if (existing == null) "Thêm webhook" else "Sửa webhook"

        if (existing != null) {
            nameField.setText(existing.name)
            urlField.setText(existing.url)
            enabledField.isChecked = existing.enabled
            existing.patterns.forEach { addPatternRow(it) }
            if (existing.kind == WebhookKind.MAPCHAT) {
                pairing = MapchatPairing(
                    name = existing.name, url = existing.url, id = existing.pairingId.orEmpty(),
                    key = existing.secret, pattern = existing.patterns.firstOrNull().orEmpty()
                )
                unpaired = existing.unpaired
            } else {
                secretField.setText(existing.secret)
            }
        }
        (if (existing?.mode == MatchMode.AND) modeAnd else modeOr).isChecked = true
        applyKind()
    }

    // ---- quét mã QR ghép đôi ----

    private fun scanPairing() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Quét mã QR ở máy quầy: Cài đặt → Xác nhận thanh toán tự động")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                // Mã QR không mang ECI: phải ép UTF-8, nếu không tên tiệm có dấu sẽ bị vỡ.
                .addExtra(Intents.Scan.CHARACTER_SET, "UTF-8")
        )
    }

    private fun onScanned(contents: String) {
        val p = when (val r = PairingQr.parse(contents)) {
            is PairingParse.Error -> {
                AlertDialog.Builder(this).setTitle("Không dùng được mã QR này").setMessage(r.message)
                    .setPositiveButton("Đóng", null).show()
                return
            }
            is PairingParse.Ok -> r.pairing
        }

        // Luật 7: quét lại cùng mã ghép trong cùng app = cập nhật cặp cũ, không tạo cặp thứ hai.
        val same = Db.get(this).findPairing(pkg, p.id)
        if (same != null && same.id != webhookId) {
            webhookId = same.id
            title = "Sửa webhook"
            enabledField.isChecked = same.enabled
            toast("Đã ghép với tiệm này từ trước — lưu sẽ cập nhật cặp cũ")
        }
        pairing = p
        unpaired = false
        nameField.setText(p.name)
        urlField.setText(p.url)
        secretField.setText("")
        patternContainer.removeAllViews()
        addPatternRow(p.pattern)
        modeOr.isChecked = true
        applyKind()
    }

    /** Hiện/khóa các ô theo loại webhook đang sửa. */
    private fun applyKind() {
        val p = pairing
        val isPairing = p != null
        urlField.isEnabled = !isPairing
        secretGroup.visibility = if (isPairing) View.GONE else View.VISIBLE
        modeGroup.visibility = if (isPairing) View.GONE else View.VISIBLE
        addPatternButton.visibility = if (isPairing) View.GONE else View.VISIBLE
        for (i in 0 until patternContainer.childCount) {
            val row = patternContainer.getChildAt(i)
            row.findViewById<EditText>(R.id.patternField).isEnabled = !isPairing
            row.findViewById<View>(R.id.patternRemove).visibility = if (isPairing) View.GONE else View.VISIBLE
        }
        patternHelp.text = if (isPairing) {
            "Mẫu lọc lấy từ mã QR (mã đơn mapchat), đối chiếu trên nội dung đã viết hoa và bỏ ký tự " +
                "không phải chữ/số. Không sửa tay được — máy quầy đổi mẫu thì quét lại mã QR."
        } else {
            "Regex, không phân biệt hoa/thường, đối chiếu với title + text + big text + sub text + lines. " +
                "Không có pattern nào = nhận mọi thông báo của ứng dụng."
        }

        pairingInfo.visibility = if (isPairing) View.VISIBLE else View.GONE
        if (p != null) {
            pairingInfo.text = buildString {
                if (unpaired) {
                    append("⚠ Máy quầy đã đổi mã ghép — cặp này đã ngừng gửi. Quét mã QR mới để ghép lại.\n\n")
                }
                append("Ghép với máy quầy mapchat · mã ghép ${p.id}\n")
                append("Khóa mã hóa: đã lưu trên máy, không hiển thị.\n")
                append("Thông báo khớp được mã hóa AES-256-GCM trước khi gửi; mapchat chỉ chuyển tiếp, không đọc được.\n")
                append("Chỉ đặt cặp này cho app ngân hàng nhận tiền của tiệm: app khác có thể dựng thông báo giả có mã đơn.\n")
                append("Mã QR chứa khóa — đừng chụp màn hình gửi đi.")
            }
        }
    }

    // ---- sửa tay (HMAC) ----

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
        val p = pairing
        val webhook = if (p != null) {
            Webhook(
                id = webhookId, pkg = pkg, name = nameField.text.toString().trim().ifEmpty { p.name },
                url = p.url, secret = p.key, mode = MatchMode.OR, enabled = enabledField.isChecked,
                patterns = listOf(p.pattern), kind = WebhookKind.MAPCHAT, pairingId = p.id, unpaired = unpaired
            )
        } else {
            val url = urlField.text.toString().trim()
            val secret = secretField.text.toString().trim()
            validateEndpoint(url, secret)?.let { toast(it); return }

            val patterns = currentPatterns()
            patterns.firstOrNull { !PatternMatcher.isValidRegex(it) }?.let {
                toast("Pattern regex không hợp lệ: $it")
                return
            }
            Webhook(
                id = webhookId, pkg = pkg, name = nameField.text.toString().trim().ifEmpty { Uri.parse(url).host ?: url },
                url = url, secret = secret, mode = if (modeAnd.isChecked) MatchMode.AND else MatchMode.OR,
                enabled = enabledField.isChecked, patterns = patterns
            )
        }
        Db.get(this).saveWebhook(webhook)
        finish()
    }

    // ---- gửi thử ----

    private fun sendTest() {
        val p = pairing
        if (p != null) {
            io.execute {
                // Nội dung không chứa mã đơn: máy quầy giải mã được rồi bỏ qua, không đơn nào bị đụng tới.
                val body = MapchatEnvelope.seal(
                    p.id, p.key, "opennotify.test", System.currentTimeMillis(),
                    "OpenNotify thử kết nối — không phải giao dịch"
                )
                val code = WebhookClient.postJson(p.url, body)
                val msg = when (Sender.classify(code)) {
                    SendOutcome.OK -> "Thông suốt — máy quầy đã nhận gói thử"
                    SendOutcome.UNPAIRED -> "Mã ghép không còn hiệu lực (máy quầy đã đổi mã) — quét lại mã QR"
                    SendOutcome.RETRY -> when (code) {
                        503 -> "mapchat nhận được nhưng máy quầy đang tắt"
                        429 -> "Gửi quá nhanh, thử lại sau ít phút"
                        else -> "Không tới được ${Uri.parse(p.url).host} — kiểm tra mạng"
                    }
                    SendOutcome.DROP -> "mapchat từ chối gói tin (HTTP $code)"
                }
                runOnUiThread { toast(msg) }
            }
            return
        }

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

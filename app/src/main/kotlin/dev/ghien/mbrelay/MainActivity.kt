package dev.ghien.mbrelay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val urlField = findViewById<EditText>(R.id.webhookUrl)
        val secretField = findViewById<EditText>(R.id.webhookSecret)
        val pkgField = findViewById<EditText>(R.id.sourcePackage)
        val log = findViewById<TextView>(R.id.eventLog)

        urlField.setText(Prefs.webhookUrl(this))
        secretField.setText(Prefs.webhookSecret(this))
        pkgField.setText(Prefs.sourcePackage(this))

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            Prefs.save(
                this,
                urlField.text.toString(),
                secretField.text.toString(),
                pkgField.text.toString()
            )
            KeepAliveService.start(this)
            OutboxWorker.schedule(this)
            Toast.makeText(this, "Đã lưu cấu hình", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.notifAccessButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } else {
                    Toast.makeText(this, "Đã nằm trong danh sách miễn trừ", Toast.LENGTH_SHORT).show()
                }
            }
        }

        findViewById<Button>(R.id.testButton).setOnClickListener {
            val url = urlField.text.toString().trim()
            val secret = secretField.text.toString().trim()
            if (url.isBlank() || secret.isBlank()) {
                Toast.makeText(this, "Điền webhook URL và secret trước", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            io.execute {
                val body = JSONObject().apply {
                    put("source", "mb-relay")
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
                    Toast.makeText(
                        this,
                        if (ok) "Gửi test thành công" else "Gửi thất bại, xem log backend",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        findViewById<Button>(R.id.refreshLogButton).setOnClickListener {
            val recent = EventStore(this).recent(30)
            log.text = if (recent.isEmpty()) {
                "Chưa có sự kiện nào."
            } else {
                recent.joinToString("\n\n") { it.payload }
            }
        }
    }
}

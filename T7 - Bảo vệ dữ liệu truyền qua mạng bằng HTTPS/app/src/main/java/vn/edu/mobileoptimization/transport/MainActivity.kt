package vn.edu.mobileoptimization.transport

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private object NetworkWorker { val executor = Executors.newSingleThreadExecutor() }
private data class Entry(val time: Long, val result: DemoResult)
private data class ModeStats(var total: Int = 0, val entries: MutableList<Entry> = mutableListOf())
private data class ScreenState(
    val enabled: Boolean, val untrusted: Boolean,
    val http: ModeStats, val https: ModeStats,
)

class MainActivity : Activity() {
    private lateinit var toggle: Switch
    private lateinit var certificateToggle: Switch
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var send: Button
    private lateinit var reset: ImageButton
    private var statusToast: Toast? = null
    private lateinit var httpCard: LinearLayout
    private lateinit var httpsCard: LinearLayout
    private lateinit var httpMetrics: TextView
    private lateinit var httpsMetrics: TextView
    private var httpStats = ModeStats()
    private var httpsStats = ModeStats()
    private var client = TransportClient()
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets()
        toggle = findViewById(R.id.httpsSwitch)
        certificateToggle = findViewById(R.id.certificateSwitch)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        send = findViewById(R.id.sendButton)
        reset = findViewById(R.id.resetButton)
        httpCard = findViewById(R.id.httpCard)
        httpsCard = findViewById(R.id.httpsCard)
        httpMetrics = findViewById(R.id.httpMetrics)
        httpsMetrics = findViewById(R.id.httpsMetrics)
        val retained = lastNonConfigurationInstance as? ScreenState
        if (retained != null) {
            httpStats = retained.http
            httpsStats = retained.https
        }
        toggle.isChecked = retained?.enabled ?: savedInstanceState?.getBoolean("https") ?: false
        certificateToggle.isChecked = retained?.untrusted ?: savedInstanceState?.getBoolean("untrusted") ?: false
        toggle.setOnCheckedChangeListener { _, _ ->
            clearStatus()
            render()
        }
        certificateToggle.setOnCheckedChangeListener { _, _ ->
            clearStatus()
            render()
        }
        send.setOnClickListener {
            val username = usernameInput.text.toString()
            val password = passwordInput.text.toString()
            when {
                username.isBlank() -> {
                    usernameInput.requestFocus()
                    showStatus("Vui lòng nhập Username giả lập.")
                }
                password.isEmpty() -> {
                    passwordInput.requestFocus()
                    showStatus("Vui lòng nhập Password giả lập.")
                }
                else -> execute(selectedMode(), username, password)
            }
        }
        reset.setOnClickListener { resetMeasurement() }
        httpCard.setOnClickListener { details(DemoMode.HTTP, httpStats) }
        httpsCard.setOnClickListener { details(DemoMode.HTTPS, httpsStats) }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("https", toggle.isChecked)
        outState.putBoolean("untrusted", certificateToggle.isChecked)
        super.onSaveInstanceState(outState)
    }

    override fun onRetainNonConfigurationInstance(): Any = ScreenState(
        toggle.isChecked, certificateToggle.isChecked, httpStats, httpsStats,
    )

    override fun onDestroy() {
        destroyed = true
        clearStatus()
        super.onDestroy()
    }

    private fun selectedMode(): DemoMode = when {
        !toggle.isChecked -> DemoMode.HTTP
        certificateToggle.isChecked -> DemoMode.UNTRUSTED
        else -> DemoMode.HTTPS
    }

    private fun execute(mode: DemoMode, username: String, password: String) {
        if (busy) return
        setBusy(true)
        showStatus("Đang gửi yêu cầu...")
        NetworkWorker.executor.execute {
            val result = client.execute(mode, username, password)
            handler.post {
                if (!destroyed) {
                    setBusy(false)
                    val stats = when (mode) {
                        DemoMode.HTTP -> httpStats
                        DemoMode.HTTPS, DemoMode.UNTRUSTED -> httpsStats
                    }
                    stats.total++
                    stats.entries.add(Entry(System.currentTimeMillis(), result))
                    if (stats.entries.size > 30) stats.entries.removeAt(0)
                    showStatus(when {
                        result.certificateRejected -> "Đã từ chối chứng chỉ hoặc tên máy chủ. Chưa gửi nội dung."
                        result.unexpectedTrust -> result.outcome
                        result.successful -> result.outcome
                        mode == DemoMode.UNTRUSTED -> "Chưa thể kết luận về chứng chỉ: ${result.outcome}"
                        else -> "${result.outcome} Chạy Start.cmd và thử lại."
                    })
                    render()
                }
            }
        }
    }

    private fun showStatus(message: String) {
        clearStatus()
        statusToast = Toast.makeText(this, message, Toast.LENGTH_LONG).also { it.show() }
    }

    private fun clearStatus() {
        statusToast?.cancel()
        statusToast = null
    }

    private fun resetMeasurement() {
        if (busy) return
        httpStats = ModeStats()
        httpsStats = ModeStats()
        usernameInput.text.clear()
        passwordInput.text.clear()
        clearStatus()
        render()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        toggle.isEnabled = !value
        certificateToggle.isEnabled = !value && toggle.isChecked
        usernameInput.isEnabled = !value
        passwordInput.isEnabled = !value
        send.isEnabled = !value
        reset.isEnabled = !value
        reset.alpha = if (value) 0.5f else 1f
        httpCard.isEnabled = !value
        httpsCard.isEnabled = !value
    }

    private fun render() {
        val mode = selectedMode()
        certificateToggle.isEnabled = !busy && toggle.isChecked
        httpCard.background = background(mode == DemoMode.HTTP, "#A34C12")
        httpsCard.background = background(mode != DemoMode.HTTP, "#087A62")
        httpMetrics.text = metrics(httpStats, DemoMode.HTTP)
        httpsMetrics.text = metrics(httpsStats, DemoMode.HTTPS)
        httpCard.contentDescription = "HTTP. ${httpMetrics.text}. Nhấn xem chi tiết."
        httpsCard.contentDescription = "HTTPS. ${httpsMetrics.text}. Nhấn xem chi tiết."
    }

    private fun metrics(stats: ModeStats, mode: DemoMode): String {
        val last = stats.entries.lastOrNull()?.result
        val state = when {
            last == null -> "Chưa gửi"
            last.unexpectedTrust && last.successful -> "Thành công · Cảnh báo"
            last.unexpectedTrust -> "Không thành công · Cảnh báo"
            last.certificateRejected -> "Bị từ chối"
            last.successful -> "Thành công"
            else -> "Không thành công"
        }
        val protection = when {
            mode == DemoMode.HTTP -> "Body: không mã hóa"
            last?.certificateRejected == true -> "TLS: từ chối xác thực"
            last?.unexpectedTrust == true -> "TLS: chấp nhận ngoài dự kiến"
            last?.successful == true -> "TLS: đã xác thực"
            else -> "TLS: chưa xác nhận"
        }
        val certificate = if (mode == DemoMode.HTTPS) when (last?.mode) {
            DemoMode.HTTPS -> "Chứng chỉ: Tin cậy\n"
            DemoMode.UNTRUSTED -> "Chứng chỉ: Không tin cậy\n"
            else -> "Chứng chỉ: —\n"
        } else ""
        val payload = when { last == null -> "—"; last.payloadStarted -> "Đã bắt đầu ghi"; else -> "Chưa ghi" }
        val httpCode = last?.httpStatus?.toString() ?: "—"
        val recordId = last?.recordId ?: "—"
        val time = last?.elapsedMillis?.let(::format) ?: "—"
        return "Lần gửi: ${stats.total}\n$certificate$state\n$protection\nNội dung: $payload\nHTTP: $httpCode\nBản ghi: $recordId\nThời gian: $time\n\nXem chi tiết ›"
    }

    private fun details(mode: DemoMode, stats: ModeStats) {
        val title = when (mode) {
            DemoMode.HTTP -> "Chi tiết · HTTP"
            DemoMode.HTTPS, DemoMode.UNTRUSTED -> "Chi tiết · HTTPS"
        }
        val entries = stats.entries
        val message = if (entries.isEmpty()) "Chưa có yêu cầu trong phiên này." else entries.asReversed()
            .mapIndexed { index, entry ->
                val r = entry.result
                val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.time))
                val sent = if (r.payloadStarted) "Đã bắt đầu ghi nội dung" else "Chưa ghi nội dung"
                val certificate = when (r.mode) {
                    DemoMode.HTTP -> ""
                    DemoMode.HTTPS -> "\nChứng chỉ: Tin cậy"
                    DemoMode.UNTRUSTED -> "\nChứng chỉ: Không tin cậy"
                }
                "#${stats.total - index} · $time$certificate\n${r.outcome}\nHTTP: ${r.httpStatus ?: "—"} · ${format(r.elapsedMillis)}\n$sent" +
                    (r.recordId?.let { "\nMã bản ghi: $it" } ?: "") +
                    (r.cipher?.let { "\nBộ mã TLS: $it" } ?: "") +
                    (r.certificateSubject?.let { "\nChứng chỉ: $it" } ?: "")
            }.joinToString("\n\n") + if (stats.total > entries.size) "\n\nChỉ giữ chi tiết 30 lần gần nhất." else ""
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("ĐÓNG", null).show()
    }

    private fun format(ms: Double) = String.format(Locale.US, "%.2f ms", ms)

    private fun background(selected: Boolean, color: String) = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = 8 * resources.displayMetrics.density
        setStroke(((if (selected) 2 else 1) * resources.displayMetrics.density).toInt().coerceAtLeast(1),
            Color.parseColor(if (selected) color else "#E2E8F1"))
    }

    private fun applyInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val root = findViewById<View>(R.id.rootContainer)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            root.requestApplyInsets()
        }
    }
}

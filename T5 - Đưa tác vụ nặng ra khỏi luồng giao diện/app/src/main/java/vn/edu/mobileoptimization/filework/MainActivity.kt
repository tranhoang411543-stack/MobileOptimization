package vn.edu.mobileoptimization.filework

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.math.max

private const val SAMPLE_URL = "http://10.0.2.2:3000/api/file-demo/products.csv"
private const val TICK_MS = 100L
private val INK = Color.rgb(21, 34, 56)
private val BORDER = Color.rgb(226, 232, 241)
private val BG = Color.rgb(243, 245, 250)
private val GREEN = Color.rgb(8, 122, 98)
private val ORANGE = Color.rgb(163, 76, 18)
private val BLUE = Color.rgb(49, 87, 212)
private val DISABLED_BUTTON = Color.rgb(224, 224, 224)
private val DISABLED_TEXT = Color.rgb(128, 128, 128)

private data class Measurement(
    val optimized: Boolean,
    val fileName: String,
    val elapsedMs: Long,
    val readMs: Long,
    val processMs: Long,
    val maxDelayMs: Long,
    val interactions: Int,
    val result: FileResult,
)

/** An approximate heartbeat delay, not an Android frame/jank measurement. */
private class UiMeter(start: Long) {
    private var previous = start
    var maxDelay = 0L
        private set
    var interactions = 0
    fun sample(now: Long) {
        maxDelay = max(maxDelay, max(0L, now - previous - TICK_MS))
        previous = now
    }
}

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val screenStarted = SystemClock.uptimeMillis()
    private var job: Job? = null
    private var meter: UiMeter? = null
    private val sampleFile get() = File(filesDir, "products-demo.csv")
    private var off: Measurement? = null
    private var on: Measurement? = null
    private var attemptsOff = 0
    private var attemptsOn = 0
    private var downloading = false
    private var running = false
    private var runOptimized = false
    private var destroyed = false

    private lateinit var toggle: Switch
    private lateinit var clock: TextView
    private lateinit var process: Button
    private lateinit var probe: Button
    private lateinit var cancel: Button
    private lateinit var reset: ImageButton
    private lateinit var offCard: LinearLayout
    private lateinit var onCard: LinearLayout
    private lateinit var offMetrics: List<TextView>
    private lateinit var onMetrics: List<TextView>

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            meter?.sample(now)
            clock.text = String.format(Locale.US, "Đồng hồ giao diện: %.1f s", (now - screenStarted) / 1000.0)
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (savedInstanceState != null) toggle.isChecked = savedInstanceState.getBoolean("optimized")
        refreshCards()
        handler.post(heartbeat)
        if (sampleFile.isFile && sampleFile.length() > 0L) {
            updateButtons()
        } else {
            downloadSample()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("optimized", toggle.isChecked)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacks(heartbeat)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(BG) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
                insets
            }
        }

        val header = row().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Background Work", 24f, true), weighted())
        reset = ImageButton(this).apply {
            contentDescription = "Làm lại phép đo"
            setImageResource(R.drawable.ic_refresh)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(Color.WHITE, Color.rgb(200, 212, 232), 1)
            setOnClickListener {
                off = null; on = null; attemptsOff = 0; attemptsOn = 0
                updateProbeCount(0)
                showMessage("Đã xóa kết quả đo.")
                refreshCards()
            }
        }
        header.addView(reset, LinearLayout.LayoutParams(dp(40), dp(40)))
        content.addView(header)

        offMetrics = metricLines()
        onMetrics = metricLines()
        offCard = surface().apply {
            addView(text("TẮT TỐI ƯU", 16f, true).apply { setTextColor(ORANGE) })
            offMetrics.forEachIndexed { index, line -> addView(line, if (index == 0) top(8) else lp()) }
            addView(text("Xem chi tiết  ›", 12f), top(8))
            isFocusable = true
            setOnClickListener { showDetails(false) }
        }
        onCard = surface().apply {
            addView(text("BẬT TỐI ƯU", 16f, true).apply { setTextColor(GREEN) })
            onMetrics.forEachIndexed { index, line -> addView(line, if (index == 0) top(8) else lp()) }
            addView(text("Xem chi tiết  ›", 12f), top(8))
            isFocusable = true
            setOnClickListener { showDetails(true) }
        }
        val cards = row()
        cards.addView(offCard, weighted())
        cards.addView(onCard, weighted(16))
        content.addView(cards, top(16))

        val monitor = surface()
        toggle = Switch(this).apply {
            text = "Bật/Tắt tối ưu luồng nền"
            textSize = 16f
            setTextColor(INK)
            typeface = Typeface.DEFAULT_BOLD
            setOnCheckedChangeListener { _, _ -> refreshCards() }
        }
        monitor.addView(toggle, lp())
        val clockRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val spinner = ProgressBar(this).apply { isIndeterminate = true; contentDescription = "Vòng xoay theo dõi giao diện" }
        clockRow.addView(spinner, LinearLayout.LayoutParams(dp(32), dp(32)))
        clock = text("Đồng hồ giao diện: 0.0 s", 16f).apply { setTextColor(INK) }
        clockRow.addView(clock, weighted(8))
        monitor.addView(clockRow, top(16))
        process = button("Xử lý file") { startProcessing() }
        probe = button("Kiểm tra phản hồi (0)") {
            val current = meter
            if (running && runOptimized && current != null) {
                current.interactions++
                updateProbeCount(current.interactions)
            }
        }
        monitor.addView(process, top(16))
        monitor.addView(probe, top(8))
        cancel = button("Hủy tác vụ nền") { if (running && runOptimized) job?.cancel() }
        monitor.addView(cancel, top(8))
        content.addView(monitor, top(16))
    }

    private fun downloadSample() {
        if (downloading || running) return
        downloading = true
        showMessage("Đang tải file mẫu 500.000 sản phẩm…")
        updateButtons()
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val ctx = currentCoroutineContext()
                    downloadFile(ctx)
                }
                showMessage("Đã tải xong file mẫu.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage("Không tải được file. Kiểm tra backend rồi nhấn Xử lý file để thử lại.")
            } finally {
                downloading = false
                job = null
                updateButtons()
            }
        }
    }

    private fun downloadFile(context: CoroutineContext): File {
        val target = sampleFile
        val temp = File.createTempFile("products-demo-", ".part", filesDir)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(SAMPLE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) throw IOException("Backend trả HTTP ${connection.responseCode}.")
            var total = 0L
            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > FileProcessor.MAX_BYTES) throw IOException("File mẫu vượt 64 MiB.")
                        output.write(buffer, 0, count)
                    }
                }
            }
            context.ensureActive()
            if (total == 0L) throw IOException("Backend trả file rỗng.")
            if (!temp.renameTo(target)) throw IOException("Không thể lưu file mẫu.")
            return target
        } finally {
            connection?.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    // Draw the enabled controls before OFF deliberately blocks the main thread.
    private suspend fun awaitUiFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback {
            handler.post { if (continuation.isActive) continuation.resume(Unit) }
        }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { handler.post { choreographer.removeFrameCallback(callback) } }
    }

    private fun startProcessing() {
        if (running) return
        if (downloading) {
            showMessage("File mẫu đang tải, vui lòng chờ.")
            return
        }
        val file = sampleFile
        if (!file.isFile || file.length() == 0L) {
            downloadSample()
            return
        }
        val name = file.name
        val optimized = toggle.isChecked
        runOptimized = optimized
        running = true
        if (optimized) attemptsOn++ else attemptsOff++
        val started = SystemClock.uptimeMillis()
        val currentMeter = UiMeter(started)
        meter = currentMeter
        updateProbeCount(0)
        updateButtons()
        refreshCards()
        job = scope.launch {
            try {
                awaitUiFrame()
                var readMs = 0L
                var processMs = 0L
                val read: suspend () -> ByteArray = {
                    val ctx = currentCoroutineContext()
                    val begin = SystemClock.uptimeMillis()
                    val input = file.inputStream()
                    val bytes = input.use { FileProcessor.read(it) { ctx.ensureActive() } }
                    readMs = SystemClock.uptimeMillis() - begin
                    bytes
                }
                val bytes = if (optimized) withContext(Dispatchers.IO) { read() } else read()
                val calculate: suspend () -> FileResult = {
                    val ctx = currentCoroutineContext()
                    val begin = SystemClock.uptimeMillis()
                    val result = FileProcessor.process(bytes) { ctx.ensureActive() }
                    processMs = SystemClock.uptimeMillis() - begin
                    result
                }
                val result = if (optimized) withContext(Dispatchers.Default) { calculate() } else calculate()
                val ended = SystemClock.uptimeMillis()
                currentMeter.sample(ended)
                val measurement = Measurement(optimized, name, ended - started, readMs, processMs, currentMeter.maxDelay, currentMeter.interactions, result)
                if (optimized) on = measurement else off = measurement
                showMessage("Đã xử lý: ${formatNumber(result.valid)} hợp lệ, ${formatNumber(result.invalid)} dòng lỗi.")
            } catch (cancelled: CancellationException) {
                showMessage("Đã hủy tác vụ nền.")
                throw cancelled
            } catch (error: Exception) {
                showMessage("Xử lý thất bại: ${explain(error)}")
            } finally {
                meter = null
                running = false
                job = null
                updateButtons()
                refreshCards()
            }
        }
    }

    private fun updateButtons() {
        val busy = running || downloading
        toggle.isEnabled = !busy
        toggle.alpha = if (busy) 0.45f else 1f
        reset.isEnabled = !busy
        reset.alpha = if (busy) 0.45f else 1f
        fun setButtonState(button: Button, enabled: Boolean) {
            val backgroundColor = if (enabled) BLUE else DISABLED_BUTTON
            button.isEnabled = enabled
            button.setTextColor(if (enabled) Color.WHITE else DISABLED_TEXT)
            button.background = rounded(backgroundColor, backgroundColor, 1)
        }
        setButtonState(process, !running)
        setButtonState(probe, running)
        setButtonState(cancel, running)
    }

    private fun refreshCards() {
        if (!::offCard.isInitialized) return
        fun bind(view: LinearLayout, metrics: List<TextView>, optimized: Boolean, measurement: Measurement?, attempts: Int) {
            val color = if (optimized) GREEN else ORANGE
            val selected = toggle.isChecked == optimized
            view.background = rounded(Color.WHITE, if (selected) color else BORDER, if (selected) 2 else 1)
            val title = if (optimized) "BẬT TỐI ƯU" else "TẮT TỐI ƯU"
            val lines = listOf(
                "Lượt chạy: $attempts",
                "Hoàn thành: ${measurement?.let { "${it.elapsedMs} ms" } ?: "—"}",
                "UI khựng: ${measurement?.let { "~${it.maxDelayMs} ms" } ?: "—"}",
                "Phản hồi: ${measurement?.let { "${it.interactions} lần" } ?: "—"}",
                "Hợp lệ: ${measurement?.let { formatNumber(it.result.valid) } ?: "—"}",
            )
            metrics.zip(lines).forEach { (metric, line) -> metric.text = line }
            view.contentDescription = "$title. ${lines.joinToString(". ")}. Nhấn để xem chi tiết."
        }
        bind(offCard, offMetrics, false, off, attemptsOff)
        bind(onCard, onMetrics, true, on, attemptsOn)
    }

    private fun showDetails(optimized: Boolean) {
        val value = if (optimized) on else off
        val message = if (value == null) "Chưa có lượt xử lý hoàn thành ở chế độ này." else {
            val r = value.result
            "File: ${value.fileName}\nKích thước: ${formatSize(r.size.toLong())}\n" +
                "Thời gian hoàn thành: ${value.elapsedMs} ms\nĐọc file: ${value.readMs} ms\n" +
                "Kiểm tra và tính toán: ${value.processMs} ms\nUI khựng ước lượng: ~${value.maxDelayMs} ms\n" +
                "Lượt nút phản hồi trong khi chạy: ${value.interactions}\n\n" +
                "Dòng hợp lệ: ${formatNumber(r.valid)}\nDòng lỗi: ${formatNumber(r.invalid)}\n" +
                "Danh mục: ${formatNumber(r.categories.toLong())}\nGiá trị tồn kho: ${formatNumber(r.inventoryValue)} đ\n\n" +
                "SHA-256: ${r.sha256}\n\n" +
                "UI khựng là khoảng trễ lớn nhất vượt nhịp 100 ms của đồng hồ; đây là ước lượng, không phải số khung hình bị rớt. Thời gian hoàn thành có thể khác thời gian đọc cộng xử lý vì còn chi phí điều phối."
        }
        AlertDialog.Builder(this).setTitle(if (optimized) "Chi tiết · BẬT TỐI ƯU" else "Chi tiết · TẮT TỐI ƯU")
            .setMessage(message).setPositiveButton("Đóng", null).show()
    }

    private fun explain(error: Exception): String = when (error) {
        is java.nio.charset.CharacterCodingException -> "File phải được mã hóa UTF-8."
        is SecurityException -> "Không thể truy cập file mẫu."
        else -> error.message?.take(180) ?: "Không thể đọc hoặc xử lý file."
    }
    private fun formatNumber(value: Long) = String.format(Locale.US, "%,d", value)
    private fun formatSize(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1048576 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MiB", bytes / 1048576.0)
    }
    private fun showMessage(message: String) { if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    private fun updateProbeCount(count: Int) { probe.text = "Kiểm tra phản hồi ($count)" }
    private fun metricLines() = List(5) {
        text("", 12f).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun lp() = LinearLayout.LayoutParams(-1, -2)
    private fun top(value: Int) = lp().apply { topMargin = dp(value) }
    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(start) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(INK)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
        minHeight = dp(48); minimumHeight = dp(48); setPadding(dp(4), 0, dp(4), 0)
        background = rounded(BLUE, BLUE, 1)
        setOnClickListener { action() }
    }
    private fun surface() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.WHITE, BORDER, 1)
    }
    private fun rounded(fill: Int, border: Int, width: Int) = GradientDrawable().apply {
        setColor(fill); setStroke(dp(width), border); cornerRadius = dp(8).toFloat()
    }
}

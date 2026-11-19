package vn.edu.mobileoptimization.debounce

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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {
    private data class Book(val title: String, val author: String, val priceVnd: Int)
    private data class SearchResult(
        val books: List<Book>?,
        val databaseMs: Double,
        val httpMs: Long,
        val connectionId: String?,
        val connectionRequest: Int,
        val statusCode: Int,
        val error: String?,
    )
    private data class RetainedMeasurements(val off: ModeMetrics, val on: ModeMetrics)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)
    private val connectionKeeper = ConnectionKeeper()
    private var offMetrics = ModeMetrics()
    private var onMetrics = ModeMetrics()
    private var pendingSearch: Runnable? = null
    private var queryVersion = 0L
    @Volatile private var measurementSession = 0L
    @Volatile private var stopped = false
    private var ignoreTextChanges = false
    private var detailDialog: AlertDialog? = null
    private var detailAdapter: RequestHistoryAdapter? = null

    private lateinit var optimizationSwitch: Switch
    private lateinit var searchInput: EditText
    private lateinit var resultsHeading: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var offCard: LinearLayout
    private lateinit var onCard: LinearLayout
    private lateinit var offMetricsText: TextView
    private lateinit var onMetricsText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()

        optimizationSwitch = findViewById(R.id.optimizationSwitch)
        searchInput = findViewById(R.id.searchInput)
        resultsHeading = findViewById(R.id.resultsHeading)
        resultsContainer = findViewById(R.id.resultsContainer)
        offCard = findViewById(R.id.offCard)
        onCard = findViewById(R.id.onCard)
        offMetricsText = findViewById(R.id.offMetrics)
        onMetricsText = findViewById(R.id.onMetrics)

        offCard.setOnClickListener { showRequestDetails(false) }
        onCard.setOnClickListener { showRequestDetails(true) }

        if (savedInstanceState != null) {
            val retained = lastNonConfigurationInstance as? RetainedMeasurements
            if (retained != null) {
                offMetrics = retained.off
                onMetrics = retained.on
                (offMetrics.records + onMetrics.records)
                    .filter { it.state == RequestState.PENDING }
                    .forEach { it.state = RequestState.INTERRUPTED }
            }
            optimizationSwitch.isChecked = savedInstanceState.getBoolean("optimized", false)
        }

        optimizationSwitch.setOnCheckedChangeListener { _, _ ->
            invalidateCurrentSearch()
            clearInputWithoutSearching()
            showIdle()
            renderMode()
        }
        findViewById<View>(R.id.resetButton).setOnClickListener { resetMeasurement() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!ignoreTextChanges) onQueryChanged(s?.toString().orEmpty())
            }
        })

        // The text is deliberately cleared after recreation: an in-flight result belongs to the old Activity.
        clearInputWithoutSearching()
        renderMode()
        renderMetrics()
        showIdle()
    }

    private fun onQueryChanged(raw: String) {
        invalidateCurrentSearch()
        val query = raw.trim()
        if (query.isEmpty()) {
            showIdle()
            return
        }

        val optimized = optimizationSwitch.isChecked
        val version = queryVersion
        val session = measurementSession
        resultsHeading.text = "Kết quả tìm kiếm"
        showPlaceholder(if (optimized) "Đang chờ bạn ngừng gõ…" else "Đang tìm sách…")

        val task = Runnable {
            pendingSearch = null
            executeSearch(query, optimized, version, session)
        }
        if (optimized) {
            pendingSearch = task
            mainHandler.postDelayed(task, DEBOUNCE_MS)
        } else {
            task.run()
        }
    }

    private fun executeSearch(query: String, optimized: Boolean, version: Long, session: Long) {
        executor.execute {
            if (stopped || measurementSession != session) return@execute

            // Count an HTTP attempt on the client, not the number received by the server.
            val record = RequestRecord(number = 0, query = query, startedAtMillis = System.currentTimeMillis())
            mainHandler.post {
                if (!stopped && measurementSession == session) {
                    metricsFor(optimized).add(record)
                    renderMetrics()
                }
            }

            val result = requestBooks(query)
            mainHandler.post {
                if (stopped || measurementSession != session || record.number == 0) return@post
                record.httpMs = result.httpMs
                record.databaseMs = result.databaseMs
                record.connectionId = result.connectionId
                record.connectionRequest = result.connectionRequest
                record.statusCode = result.statusCode
                record.error = result.error
                record.state = if (result.error == null) RequestState.SUCCESS else RequestState.ERROR

                if (isCurrent(version, session, optimized) && result.error == null && result.books != null) {
                    val metrics = metricsFor(optimized)
                    metrics.httpMs = result.httpMs
                    metrics.databaseMs = result.databaseMs
                    renderResults(result.books)
                    record.displayed = true
                } else if (isCurrent(version, session, optimized) && result.error != null) {
                    showPlaceholder("Không tải được kết quả. Kiểm tra Backend\\Start.cmd rồi nhập lại.", verticalPaddingDp = 5)
                }
                renderMetrics()
            }
        }
    }

    private fun requestBooks(query: String): SearchResult {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val start = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        var responseMs = -1L
        var connectionId: String? = null
        var connectionRequest = 0
        var statusCode = 0
        try {
            val url = URL("$BACKEND_BASE_URL/api/books/search?q=$encoded&limit=20")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 8000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            statusCode = connection.responseCode
            connectionId = connection.getHeaderField("X-Demo-Connection-Id")?.takeIf { it.isNotBlank() }
            connectionRequest = connection.getHeaderField("X-Demo-Connection-Request")?.toIntOrNull() ?: 0
            val input = if (statusCode == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
            val body = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            responseMs = SystemClock.elapsedRealtime() - start
            if (statusCode != HttpURLConnection.HTTP_OK) {
                return SearchResult(null, -1.0, responseMs, connectionId, connectionRequest, statusCode,
                    "Backend trả lỗi HTTP $statusCode")
            }
            val json = JSONObject(body)
            val rows = json.getJSONArray("items")
            val books = buildList {
                for (i in 0 until rows.length()) {
                    val item = rows.getJSONObject(i)
                    add(Book(item.getString("title"), item.getString("author"), item.getInt("priceVnd")))
                }
            }
            return SearchResult(books, json.getJSONObject("timing").getDouble("databaseMs"), responseMs,
                connectionId, connectionRequest, statusCode, null)
        } catch (_: Exception) {
            val elapsed = if (responseMs >= 0) responseMs else SystemClock.elapsedRealtime() - start
            val message = if (responseMs >= 0) "Phản hồi không hợp lệ" else "Không nhận được phản hồi"
            return SearchResult(null, -1.0, elapsed, connectionId, connectionRequest, statusCode, message)
        } finally {
            connection?.disconnect()
        }
    }

    private fun isCurrent(version: Long, session: Long, optimized: Boolean): Boolean =
        !stopped && measurementSession == session && queryVersion == version &&
            optimizationSwitch.isChecked == optimized

    private fun invalidateCurrentSearch() {
        queryVersion++
        pendingSearch?.let(mainHandler::removeCallbacks)
        pendingSearch = null
    }

    private fun resetMeasurement() {
        measurementSession++
        invalidateCurrentSearch()
        listOf(offMetrics, onMetrics).forEach { it.clear() }
        clearInputWithoutSearching()
        renderMetrics()
        showIdle()
    }

    private fun clearInputWithoutSearching() {
        ignoreTextChanges = true
        searchInput.setText("")
        ignoreTextChanges = false
    }

    private fun metricsFor(optimized: Boolean) = if (optimized) onMetrics else offMetrics

    private fun renderMode() {
        val optimized = optimizationSwitch.isChecked
        offCard.background = metricBackground(!optimized, Color.rgb(163, 76, 18))
        onCard.background = metricBackground(optimized, Color.rgb(8, 122, 98))
    }

    private fun renderMetrics() {
        offMetricsText.text = formatMetrics(offMetrics)
        onMetricsText.text = formatMetrics(onMetrics)
        offCard.contentDescription = "Tắt tối ưu. ${offMetricsText.text.toString().replace('\n', ' ')}"
        onCard.contentDescription = "Bật tối ưu. ${onMetricsText.text.toString().replace('\n', ' ')}"
        detailAdapter?.notifyDataSetChanged()
    }

    private fun formatMetrics(metrics: ModeMetrics): String {
        fun duration(value: Long) = if (value < 0) "—" else "$value ms"
        val database = if (metrics.databaseMs < 0) "—" else String.format(Locale.US, "%.2f ms", metrics.databaseMs)
        val totals = metrics.totals()
        val totalHttp = if (totals.httpCount == 0) "—" else "${totals.httpMs} ms"
        val totalDatabase = if (totals.databaseCount == 0) "—" else String.format(Locale.US, "%.2f ms", totals.databaseMs)
        return "Yêu cầu: ${metrics.requests}\n\nKết quả: ${duration(metrics.httpMs)}" +
            "\nCSDL: $database\n\nTổng HTTP (${totals.httpCount}): $totalHttp" +
            "\nTổng CSDL (${totals.databaseCount}): $totalDatabase\n\nXem chi tiết  ›"
    }

    private fun showRequestDetails(optimized: Boolean) {
        if (detailDialog?.isShowing == true) return
        val metrics = metricsFor(optimized)
        val adapter = RequestHistoryAdapter(metrics)
        val list = ListView(this).apply {
            this.adapter = adapter
            dividerHeight = dp(1)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(520), (resources.displayMetrics.heightPixels * 0.58f).toInt()),
            )
        }
        detailAdapter = adapter
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (optimized) "Chi tiết · BẬT TỐI ƯU" else "Chi tiết · TẮT TỐI ƯU")
            .setView(list)
            .setPositiveButton("Đóng", null)
            .create()
        detailDialog = dialog
        dialog.setOnDismissListener {
            detailDialog = null
            detailAdapter = null
        }
        dialog.show()
    }

    private inner class RequestHistoryAdapter(private val metrics: ModeMetrics) : BaseAdapter() {
        private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.forLanguageTag("vi-VN"))

        override fun getCount(): Int = max(1, metrics.records.size)
        override fun getItem(position: Int): RequestRecord? =
            metrics.records.getOrNull(metrics.records.lastIndex - position)
        override fun getItemId(position: Int): Long = getItem(position)?.number?.toLong() ?: -1

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(getColor(R.color.ink))
                textSize = 14f
            }
            val record = getItem(position)
            if (record == null) {
                view.text = "Chưa có request nào. Hãy nhập từ khóa để bắt đầu đo."
                return view
            }

            val whenStarted = clock.format(Date(record.startedAtMillis))
            val outcome = when (record.state) {
                RequestState.PENDING -> "Đang thực hiện…"
                RequestState.INTERRUPTED -> "Gián đoạn khi ứng dụng được tạo lại."
                RequestState.ERROR -> "${record.error ?: "Không hoàn tất"} · ${record.httpMs} ms"
                RequestState.SUCCESS -> {
                    val database = String.format(Locale.US, "%.2f ms", record.databaseMs)
                    val shown = if (record.displayed) "Đã hiển thị" else "Kết quả cũ, không ghi đè giao diện"
                    "HTTP: ${record.httpMs} ms · CSDL: $database\nThành công (HTTP ${record.statusCode}) · $shown"
                }
            }
            view.text = "#${record.number} · “${record.query}”\n$whenStarted\n$outcome"
            return view
        }
    }

    private fun renderResults(books: List<Book>) {
        resultsHeading.text = "Kết quả tìm kiếm (${books.size})"
        resultsContainer.removeAllViews()
        if (books.isEmpty()) {
            showPlaceholder("Không tìm thấy kết quả phù hợp.")
            return
        }

        val currency = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
        books.forEachIndexed { index, book ->
            if (index > 0) {
                resultsContainer.addView(View(this).apply {
                    setBackgroundColor(Color.rgb(232, 237, 245))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(8)
                        bottomMargin = dp(8)
                    }
                })
            }
            resultsContainer.addView(TextView(this).apply {
                text = book.title
                setTextColor(getColor(R.color.ink))
                setTypeface(null, Typeface.BOLD)
                textSize = 16f
            })
            resultsContainer.addView(TextView(this).apply {
                text = "${book.author}  ·  ${currency.format(book.priceVnd)} đ"
                setTextColor(getColor(R.color.muted))
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun showIdle() {
        resultsHeading.text = "Kết quả tìm kiếm"
        showPlaceholder("Thử tìm “Android” hoặc “Kotlin”.")
    }

    private fun showPlaceholder(message: String, verticalPaddingDp: Int = 0) {
        resultsContainer.removeAllViews()
        resultsContainer.addView(TextView(this).apply {
            text = message
            setTextColor(getColor(R.color.muted))
            textSize = 16f
            setPadding(0, dp(verticalPaddingDp), 0, dp(verticalPaddingDp))
        })
    }

    private fun metricBackground(selected: Boolean, accent: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(if (selected) 2 else 1), if (selected) accent else Color.rgb(226, 232, 241))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun applySystemInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            findViewById<View>(R.id.rootContainer).setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, keyboard.bottom))
                insets
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("optimized", optimizationSwitch.isChecked)
    }

    override fun onRetainNonConfigurationInstance(): Any = RetainedMeasurements(offMetrics, onMetrics)

    override fun onStart() {
        super.onStart()
        connectionKeeper.start()
    }

    override fun onStop() {
        connectionKeeper.stop()
        super.onStop()
    }

    override fun onDestroy() {
        stopped = true
        connectionKeeper.close()
        detailDialog?.dismiss()
        pendingSearch?.let(mainHandler::removeCallbacks)
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}

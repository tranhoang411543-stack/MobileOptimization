package vn.edu.mobileoptimization.paging

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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {
    private data class Book(val id: Int, val title: String, val author: String, val priceVnd: Int)

    private data class RequestRecord(
        var number: Int = 0,
        var page: Int,
        var status: String = "Đang thực hiện…",
        var httpMs: Long = -1,
        var bytes: Long = 0,
        var returned: Int = 0,
        var databaseMs: Double = -1.0,
        var connectionId: String? = null,
        var connectionNumber: Int = 0,
    )

    private class Measurement {
        val records = mutableListOf<RequestRecord>()
        var receivedBooks = 0
        var receivedBytes = 0L
        var initialBooks: Int? = null
        var initialBytes: Long? = null
        var firstFrameMs: Long? = null
        var heapBefore: Long? = null
        var heapAfter: Long? = null
    }

    private data class FetchResult(
        val books: List<Book>?,
        val page: Int,
        val total: Int,
        val totalPages: Int,
        val httpMs: Long,
        val bytes: Long,
        val statusCode: Int,
        val databaseMs: Double,
        val connectionId: String?,
        val connectionNumber: Int,
        val error: String?,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)
    private val books = mutableListOf<Book>()
    private var offMeasurement = Measurement()
    private var onMeasurement = Measurement()
    @Volatile private var generation = 0L
    @Volatile private var stopped = false
    private var actionStartedAt = 0L
    private var isLoading = false
    private var currentPage = 1
    private var totalBooks = 0
    private var totalPages = 0
    private var requestedPage = 1
    private var failedPage: Int? = null
    private var hasInitialPage = false
    private val pageWindow = PageWindow()
    private var loadError: String? = null
    private var detailDialog: AlertDialog? = null
    private var detailAdapter: RequestHistoryAdapter? = null

    private lateinit var list: ListView
    private lateinit var catalogAdapter: CatalogAdapter
    private lateinit var catalogStatus: TextView
    private lateinit var paginationScroll: HorizontalScrollView
    private lateinit var paginationControls: LinearLayout
    private lateinit var optimizationSwitch: Switch
    private lateinit var loadButton: Button
    private lateinit var catalogHeading: TextView
    private lateinit var offCard: LinearLayout
    private lateinit var onCard: LinearLayout
    private lateinit var offMetrics: TextView
    private lateinit var onMetrics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()

        list = findViewById(R.id.catalogList)
        val header = layoutInflater.inflate(R.layout.catalog_header, list, false)
        val footer = layoutInflater.inflate(R.layout.catalog_footer, list, false)
        optimizationSwitch = header.findViewById(R.id.optimizationSwitch)
        loadButton = header.findViewById(R.id.loadButton)
        catalogHeading = header.findViewById(R.id.catalogHeading)
        offCard = header.findViewById(R.id.offCard)
        onCard = header.findViewById(R.id.onCard)
        offMetrics = header.findViewById(R.id.offMetrics)
        onMetrics = header.findViewById(R.id.onMetrics)
        catalogStatus = footer.findViewById(R.id.catalogStatus)
        paginationScroll = header.findViewById(R.id.paginationScroll)
        paginationControls = header.findViewById(R.id.paginationControls)
        catalogStatus.setOnClickListener {
            val page = failedPage
            if (page != null && !isLoading) loadPage(page)
        }
        list.addHeaderView(header, null, false)
        list.addFooterView(footer, null, false)
        catalogAdapter = CatalogAdapter()
        list.adapter = catalogAdapter
        list.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && hasInitialPage) renderPagination()
        }

        optimizationSwitch.isChecked = savedInstanceState?.getBoolean("optimized", false) ?: false
        optimizationSwitch.setOnCheckedChangeListener { _, checked -> beginSession(checked) }
        loadButton.setOnClickListener {
            if (!isLoading) {
                val optimized = optimizationSwitch.isChecked
                beginSession(optimized)
                actionStartedAt = SystemClock.elapsedRealtime()
                measurementFor(optimized).heapBefore = usedJavaHeap()
                loadPage(1)
            }
        }
        header.findViewById<View>(R.id.resetButton).setOnClickListener {
            offMeasurement = Measurement()
            onMeasurement = Measurement()
            beginSession(optimizationSwitch.isChecked)
        }
        offCard.setOnClickListener { showDetails(false) }
        onCard.setOnClickListener { showDetails(true) }
        beginSession(optimizationSwitch.isChecked)
    }

    private fun beginSession(optimized: Boolean) {
        generation++
        actionStartedAt = 0L
        books.clear()
        isLoading = false
        currentPage = 1
        totalBooks = 0
        totalPages = 0
        requestedPage = 1
        failedPage = null
        hasInitialPage = false
        pageWindow.reset()
        loadError = null
        val measurement = Measurement()
        if (optimized) onMeasurement = measurement else offMeasurement = measurement
        offCard.background = metricBackground(!optimized, getColor(R.color.warning))
        onCard.background = metricBackground(optimized, getColor(R.color.success))
        catalogHeading.text = "Danh sách sách"
        catalogAdapter.notifyDataSetChanged()
        list.setSelection(0)
        renderMetrics()
        updateFooter()
    }

    private fun loadPage(page: Int) {
        if (isLoading || page < 1) return
        val optimized = optimizationSwitch.isChecked
        if (!optimized && page != 1) return
        if (optimized && hasInitialPage && page > totalPages) return
        val session = generation
        val measurement = measurementFor(optimized)
        requestedPage = page
        isLoading = true
        loadError = null
        failedPage = null
        updateFooter()

        executor.execute {
            if (stopped || generation != session) return@execute
            val record = RequestRecord(page = page)
            handler.post {
                if (!isCurrent(session, optimized)) return@post
                record.number = measurement.records.size + 1
                measurement.records.add(record)
                renderMetrics()
            }
            val result = requestCatalog(optimized, page)
            handler.post {
                if (!isCurrent(session, optimized) || record.number == 0) return@post
                isLoading = false
                record.httpMs = result.httpMs
                record.bytes = result.bytes
                record.databaseMs = result.databaseMs
                record.connectionId = result.connectionId
                record.connectionNumber = result.connectionNumber
                measurement.receivedBytes += result.bytes
                if (result.error != null || result.books == null) {
                    record.status = result.error ?: "Không nhận được dữ liệu"
                    loadError = "Không tải được danh sách (${record.status}). Nhấn để thử lại."
                    failedPage = page
                } else {
                    record.page = result.page
                    record.returned = result.books.size
                    record.status = "Thành công (HTTP ${result.statusCode})"
                    measurement.receivedBooks += result.books.size
                    val firstPage = !hasInitialPage
                    books.clear()
                    books.addAll(result.books)
                    hasInitialPage = true
                    currentPage = result.page
                    totalBooks = result.total
                    totalPages = result.totalPages
                    catalogHeading.text = "Danh sách sách (${formatNumber(books.size)})"
                    if (firstPage) {
                        measurement.initialBooks = result.books.size
                        measurement.initialBytes = result.bytes
                        measureFirstFrame(session, optimized, measurement)
                    }
                    catalogAdapter.notifyDataSetChanged()
                }
                updateFooter()
                renderMetrics()
            }
        }
    }

    private fun requestCatalog(optimized: Boolean, page: Int): FetchResult {
        val start = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        var httpMs = -1L
        var bytes = 0L
        var status = 0
        var connectionId: String? = null
        var connectionNumber = 0
        try {
            val query = if (optimized) "mode=paged&limit=$PAGE_SIZE&page=$page" else "mode=all"
            connection = URL("http://10.0.2.2:3000/api/books/catalog?$query").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            status = connection.responseCode
            connectionId = connection.getHeaderField("X-Demo-Connection-Id")?.takeIf { it.isNotBlank() }
            connectionNumber = connection.getHeaderField("X-Demo-Connection-Request")?.toIntOrNull() ?: 0
            val stream = if (status == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
            val payload = stream?.use { it.readBytes() } ?: ByteArray(0)
            bytes = payload.size.toLong()
            httpMs = SystemClock.elapsedRealtime() - start
            if (status != HttpURLConnection.HTTP_OK) {
                return FetchResult(null, page, 0, 0, httpMs, bytes, status, -1.0, connectionId, connectionNumber, "HTTP $status")
            }
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            check(json.getString("mode") == if (optimized) "paged" else "all")
            val items = json.getJSONArray("items")
            val result = buildList {
                var previousId: Int? = null
                for (i in 0 until items.length()) {
                    val row = items.getJSONObject(i)
                    val id = row.getInt("id")
                    previousId?.let { check(id > it) }
                    previousId = id
                    add(Book(id, row.getString("title"), row.getString("author"), row.getInt("priceVnd")))
                }
            }
            check(json.getInt("returned") == result.size)
            val total = if (optimized) json.getInt("total") else result.size
            val pages = if (optimized) json.getInt("totalPages") else if (total == 0) 0 else 1
            val actualPage = if (optimized) json.getInt("page") else 1
            check(total >= 0 && pages >= 0)
            if (optimized) {
                check(json.getInt("pageSize") == PAGE_SIZE && pages == pageCount(total, PAGE_SIZE))
                check(actualPage == if (pages == 0) 1 else minOf(page, pages))
                val expected = if (total == 0) 0 else minOf(PAGE_SIZE, total - (actualPage - 1) * PAGE_SIZE)
                check(result.size == expected && json.getBoolean("hasMore") == (actualPage < pages))
            } else {
                check(!json.getBoolean("hasMore") && json.isNull("nextCursor"))
            }
            return FetchResult(result, actualPage, total, pages, httpMs, bytes, status,
                json.getJSONObject("timing").getDouble("databaseMs"), connectionId, connectionNumber, null)
        } catch (_: Exception) {
            val elapsed = if (httpMs >= 0) httpMs else SystemClock.elapsedRealtime() - start
            val error = if (httpMs >= 0) "Phản hồi không hợp lệ" else "Không nhận được phản hồi"
            return FetchResult(null, page, 0, 0, elapsed, bytes, status, -1.0, connectionId, connectionNumber, error)
        } finally {
            connection?.disconnect()
        }
    }

    private fun measureFirstFrame(session: Long, optimized: Boolean, measurement: Measurement) {
        val observer = list.viewTreeObserver
        var captured = false
        observer.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (captured || (books.isNotEmpty() && list.lastVisiblePosition < list.headerViewsCount)) return
                captured = true
                list.post {
                    if (observer.isAlive) observer.removeOnDrawListener(this)
                    if (isCurrent(session, optimized) && measurement.firstFrameMs == null) {
                        measurement.firstFrameMs = SystemClock.elapsedRealtime() - actionStartedAt
                        measurement.heapAfter = usedJavaHeap()
                        renderMetrics()
                    }
                }
            }
        })
        list.invalidate()
    }

    private fun updateFooter() {
        val message = when {
            isLoading && !hasInitialPage -> "Đang tải danh sách…"
            isLoading -> "Đang tải trang $requestedPage…"
            loadError != null -> loadError
            !hasInitialPage -> "Nhấn “Tải danh sách” để bắt đầu."
            books.isEmpty() -> "Chưa có sách trong cơ sở dữ liệu."
            else -> null
        }
        catalogStatus.visibility = if (message == null) View.GONE else View.VISIBLE
        catalogStatus.text = message.orEmpty()
        catalogStatus.setTextColor(getColor(if (loadError != null) R.color.error else R.color.muted))
        catalogStatus.isClickable = loadError != null
        catalogStatus.isFocusable = loadError != null
        loadButton.isEnabled = !isLoading
        loadButton.alpha = if (isLoading) 0.6f else 1f
        loadButton.visibility = if (optimizationSwitch.isChecked && hasInitialPage && totalBooks > 0) View.GONE else View.VISIBLE
        renderPagination()
    }

    private fun renderPagination() {
        paginationControls.removeAllViews()
        val showPagination = optimizationSwitch.isChecked && hasInitialPage && totalPages > 0
        paginationScroll.visibility = if (showPagination) View.VISIBLE else View.GONE
        if (!showPagination) return
        val pages = pageWindow.pages(currentPage, totalPages)
        val buttonSize = dp(40)
        val controls = mutableListOf<View>()
        controls.add(pageButton("<", "Trang trước", currentPage - 1, false, currentPage > 1, buttonSize))
        pages.forEach { page ->
            if (page == null) {
                controls.add(TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
                    gravity = Gravity.CENTER
                    text = "..."
                    textSize = 16f
                    setTextColor(getColor(R.color.pagination_outline))
                    background = pageBackground(selected = false, enabled = false)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
            } else {
                val selected = page == currentPage
                val label = if (selected) "Trang $page, đang chọn" else "Trang $page"
                controls.add(pageButton(page.toString(), label, page, selected, true, buttonSize))
            }
        }
        controls.add(pageButton(">", "Trang sau", currentPage + 1, false, currentPage < totalPages, buttonSize))
        controls.forEachIndexed { index, control ->
            if (index > 0) {
                paginationControls.addView(Space(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            paginationControls.addView(control)
        }
    }

    private fun pageButton(
        label: String,
        description: String,
        page: Int,
        selected: Boolean,
        enabled: Boolean,
        size: Int,
    ): Button = Button(this).apply {
        layoutParams = LinearLayout.LayoutParams(size, size)
        minWidth = 0
        minHeight = 0
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER
        text = label
        textSize = 16f
        setAllCaps(false)
        setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(getColor(if (selected) R.color.white else R.color.pagination_outline))
        val canSelect = enabled && !isLoading
        backgroundTintList = null
        background = pageBackground(selected, canSelect)
        stateListAnimator = null
        contentDescription = description
        isSelected = selected
        isEnabled = canSelect
        setOnClickListener { if (page != currentPage) loadPage(page) }
    }

    private fun pageBackground(selected: Boolean, enabled: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        when {
            selected -> setColor(getColor(R.color.indigo))
            enabled -> {
                setColor(getColor(R.color.white))
                setStroke(dp(1), getColor(R.color.pagination_outline))
            }
            else -> setColor(getColor(R.color.pagination_disabled))
        }
    }

    private fun renderMetrics() {
        offMetrics.text = formatMeasurement(offMeasurement)
        onMetrics.text = formatMeasurement(onMeasurement)
        offCard.contentDescription = "Tắt tối ưu. ${offMetrics.text.toString().replace('\n', ' ')}"
        onCard.contentDescription = "Bật tối ưu. ${onMetrics.text.toString().replace('\n', ' ')}"
        detailAdapter?.notifyDataSetChanged()
    }

    private fun formatMeasurement(value: Measurement): String {
        val initial = value.initialBooks?.let(::formatNumber) ?: "—"
        val firstBytes = value.initialBytes?.let(::formatBytes) ?: "—"
        val firstFrame = value.firstFrameMs?.let { "$it ms" } ?: "—"
        val before = value.heapBefore?.let(::formatBytes) ?: "—"
        val after = value.heapAfter?.let(::formatBytes) ?: "—"
        return "Sách đầu: $initial\nDữ liệu đầu: $firstBytes\nHiển thị đầu: $firstFrame" +
            "\nHeap Java:\n$before → $after\n\nTổng: ${value.records.size} req" +
            "\n${formatNumber(value.receivedBooks)} sách · ${formatBytes(value.receivedBytes)}\n\nXem chi tiết  ›"
    }

    private fun showDetails(optimized: Boolean) {
        if (detailDialog?.isShowing == true) return
        val adapter = RequestHistoryAdapter(measurementFor(optimized), optimized)
        val details = ListView(this).apply {
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
            .setView(details)
            .setPositiveButton("Đóng", null)
            .create()
        detailDialog = dialog
        dialog.setOnDismissListener { detailDialog = null; detailAdapter = null }
        dialog.show()
    }

    private inner class CatalogAdapter : BaseAdapter() {
        private val currency = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
        override fun getCount() = books.size
        override fun getItem(position: Int) = books[position]
        override fun getItemId(position: Int) = books[position].id.toLong()
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.catalog_item, parent, false)
            val book = getItem(position)
            val first = position == 0
            val last = position == books.lastIndex
            view.setPadding(0, if (first) dp(16) else 0, 0, 0)
            view.findViewById<View>(R.id.groupTop).visibility = if (first) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.groupBottom).visibility = if (last) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.bookDivider).visibility = if (last) View.GONE else View.VISIBLE
            view.findViewById<TextView>(R.id.bookTitle).text = book.title
            view.findViewById<TextView>(R.id.bookMeta).text = "${book.author}  ·  ${currency.format(book.priceVnd)} đ"
            return view
        }
    }

    private inner class RequestHistoryAdapter(
        private val measurement: Measurement,
        private val optimized: Boolean,
    ) : BaseAdapter() {
        override fun getCount() = max(1, measurement.records.size)
        override fun getItem(position: Int): RequestRecord? = measurement.records.getOrNull(position)
        override fun getItemId(position: Int) = getItem(position)?.number?.toLong() ?: -1L
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(getColor(R.color.ink))
                textSize = 14f
            }
            val record = getItem(position)
            if (record == null) {
                view.text = "Chưa có request nào trong phiên đo này."
                return view
            }
            val page = if (optimized) "Trang ${record.page}" else "Toàn bộ danh sách"
            val http = if (record.httpMs < 0) "—" else "${record.httpMs} ms"
            val database = if (record.databaseMs < 0) "—" else String.format(Locale.US, "%.2f ms", record.databaseMs)
            view.text = "#${record.number} · $page\n${record.status}" +
                "\n${formatNumber(record.returned)} sách · ${formatBytes(record.bytes)}" +
                "\nHTTP: $http · CSDL: $database"
            return view
        }
    }

    private fun measurementFor(optimized: Boolean) = if (optimized) onMeasurement else offMeasurement
    private fun isCurrent(session: Long, optimized: Boolean) =
        !stopped && generation == session && optimizationSwitch.isChecked == optimized

    private fun usedJavaHeap(): Long = Runtime.getRuntime().run { totalMemory() - freeMemory() }
    private fun formatNumber(value: Int) = formatNumber(value.toLong())
    private fun formatNumber(value: Long) = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value)
    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
        else -> String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun metricBackground(selected: Boolean, accent: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(if (selected) 2 else 1), if (selected) accent else Color.rgb(226, 232, 241))
    }

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

    override fun onDestroy() {
        stopped = true
        generation++
        detailDialog?.dismiss()
        executor.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

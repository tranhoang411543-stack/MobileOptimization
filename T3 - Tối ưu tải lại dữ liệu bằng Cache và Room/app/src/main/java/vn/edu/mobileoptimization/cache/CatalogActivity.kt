package vn.edu.mobileoptimization.cache

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import java.text.NumberFormat
import java.util.Locale

class CatalogActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val app by lazy { application as CacheLabApp }
    private val books = mutableListOf<CachedBook>()
    private lateinit var adapter: BookAdapter
    private lateinit var list: ListView
    private lateinit var emptyText: TextView
    private lateinit var sourceText: TextView
    private lateinit var updatedText: TextView
    private lateinit var statusText: TextView
    private lateinit var syncText: TextView
    private lateinit var heading: TextView
    private lateinit var refresh: ImageButton
    private lateinit var visit: VisitMeasurement
    private var optimized = false
    @Volatile private var destroyed = false
    private var busy = false
    private var hasContent = false
    private var currentSource = ""
    private var waitingForFirstDraw = false
    private var seenChange = -1L
    private val syncObserver: (SyncView) -> Unit = { state ->
        syncText.text = if (optimized) "WebSocket: ${state.socket} · ${state.message}" else "Đồng bộ tự động tắt."
        if (state.contentChanged != seenChange) {
            seenChange = state.contentChanged
            if (optimized && hasContent) showSynchronizedRoom()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val startedAt = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_catalog)
        applySafeInsets()
        optimized = intent.getBooleanExtra(EXTRA_OPTIMIZED, false)
        visit = VisitMeasurement(optimized, startedAt)
        Measurements.forMode(optimized).add(visit)

        list = findViewById(R.id.catalogList)
        emptyText = findViewById(R.id.emptyText)
        sourceText = findViewById(R.id.sourceText)
        updatedText = findViewById(R.id.updatedText)
        statusText = findViewById(R.id.statusText)
        syncText = findViewById(R.id.syncText)
        heading = findViewById(R.id.booksHeading)
        refresh = findViewById(R.id.refreshCatalog)
        adapter = BookAdapter()
        list.adapter = adapter
        list.emptyView = emptyText
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        refresh.setOnClickListener {
            if (optimized && hasContent) app.sync.requestCheck() else requestFromServer()
        }
        if (optimized) readRoomFirst() else requestFromServer()
    }

    private fun readRoomFirst() {
        val changeAtStart = seenChange
        setBusy(true)
        statusText.text = "Đang đọc dữ liệu trong Room…"
        app.diskExecutor.execute {
            val result = runCatching { app.database.catalogDao().readSnapshot() }
            handler.post {
                if (destroyed) return@post
                setBusy(false)
                val snapshot = result.getOrNull()
                if (snapshot != null) {
                    displayBooks(snapshot.books, "Room", snapshot.savedAt, stored = true)
                    if (seenChange != changeAtStart) showSynchronizedRoom()
                    val reason = CatalogApi.offlineReason(this)
                    val freshness = if (CachePolicy.isFresh(snapshot.savedAt)) "Cache còn mới" else "Cache đã cũ"
                    statusText.text = if (reason == null) "$freshness · Kiểm tra phiên bản ở nền."
                    else "$reason · Đang hiển thị cache."
                    visit.outcome = statusText.text.toString()
                    app.sync.requestCheck()
                } else {
                    statusText.text = if (result.isFailure) "Không đọc được Room · Đang thử server."
                    else "Chưa có cache · Đang tải từ server."
                    requestFromServer()
                }
            }
        }
    }

    private fun requestFromServer() {
        if (busy) return
        val offline = CatalogApi.offlineReason(this)
        if (offline != null) {
            showFailure("$offline · Không gửi request.")
            return
        }
        setBusy(true)
        statusText.text = if (hasContent) "Đang làm mới từ server…" else "Đang tải danh sách từ server…"
        emptyText.text = "Đang tải danh sách từ server…"
        app.networkExecutor.execute {
            if (destroyed) return@execute
            val attempt = NetworkAttempt()
            handler.post {
                if (!destroyed) visit.attempts.add(attempt)
            }
            val result = CatalogApi.fetch()
            handler.post {
                if (destroyed) return@post
                attempt.httpMs = result.httpMs
                attempt.bytes = result.bytes
                attempt.databaseMs = result.databaseMs
                attempt.connectionId = result.connectionId
                attempt.connectionNumber = result.connectionNumber
                if (result.error != null || result.books == null) {
                    attempt.status = result.error ?: "Không nhận được dữ liệu"
                    setBusy(false)
                    showFailure(attempt.status)
                } else {
                    attempt.status = "Thành công (HTTP ${result.statusCode})"
                    attempt.books = result.books.size
                    if (optimized) saveAndDisplay(result.books) else {
                        setBusy(false)
                        displayBooks(result.books, "Máy chủ", System.currentTimeMillis(), stored = false)
                        statusText.text = "Đã tải từ server. Chế độ tắt không dùng Room."
                        visit.outcome = statusText.text.toString()
                    }
                }
            }
        }
    }

    private fun saveAndDisplay(resultBooks: List<CachedBook>) {
        statusText.text = "Đang lưu dữ liệu vào Room…"
        app.diskExecutor.execute {
            val savedAt = System.currentTimeMillis()
            val result = runCatching {
                app.database.catalogDao().mergeBaseSnapshot(resultBooks, savedAt)
                app.database.catalogDao().readSnapshot()
            }
            handler.post {
                if (destroyed) return@post
                setBusy(false)
                displayBooks(result.getOrNull()?.books ?: resultBooks, "Máy chủ", savedAt, stored = result.isSuccess)
                statusText.text = if (result.isSuccess) "Đã tải từ server và lưu vào Room."
                else "Đã tải từ server nhưng không lưu được vào Room."
                visit.outcome = statusText.text.toString()
                if (result.isSuccess) app.sync.requestCheck()
            }
        }
    }

    private fun showFailure(reason: String) {
        if (hasContent) {
            val description = if (currentSource == "Room") "Đang hiển thị dữ liệu Room đã lưu."
            else "Đang giữ dữ liệu của lần tải trước trên màn hình này."
            statusText.text = "$reason $description"
        } else {
            sourceText.text = "Nguồn: Chưa có dữ liệu"
            updatedText.text = "Cập nhật lần cuối: —"
            emptyText.text = if (optimized) "Chưa có dữ liệu Room để xem. Hãy kết nối mạng và làm mới."
            else "Chế độ tắt cần kết nối server để tải danh sách."
            statusText.text = reason
        }
        visit.outcome = statusText.text.toString()
    }

    private fun displayBooks(items: List<CachedBook>, source: String, updatedAt: Long, stored: Boolean) {
        books.clear()
        books.addAll(items)
        hasContent = true
        currentSource = source
        sourceText.text = when {
            source == "Room" -> "Nguồn: Room"
            stored -> "Nguồn: Máy chủ → Room"
            else -> "Nguồn: Máy chủ"
        }
        updatedText.text = if (stored) "Cập nhật Room: ${formatTime(updatedAt)}"
        else "Nhận từ server: ${formatTime(updatedAt)}"
        heading.text = "Danh sách sách (${formatCount(books.size)})"
        emptyText.text = "Danh mục hiện không có sách."
        adapter.notifyDataSetChanged()
        if (visit.firstContentMs == null && !waitingForFirstDraw) measureFirstContent()
    }

    private fun showSynchronizedRoom() {
        app.diskExecutor.execute {
            val snapshot = runCatching { app.database.catalogDao().readSnapshot() }.getOrNull()
            handler.post {
                if (!destroyed && optimized && snapshot != null) {
                    displayBooks(snapshot.books, "Room", snapshot.savedAt, stored = true)
                    statusText.text = "Đã cập nhật dữ liệu Room."
                    visit.outcome = statusText.text.toString()
                }
            }
        }
    }

    private fun measureFirstContent() {
        waitingForFirstDraw = true
        val root = findViewById<View>(R.id.rootContainer)
        val observer = root.viewTreeObserver
        var captured = false
        observer.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (captured || !hasContent || (books.isNotEmpty() && list.lastVisiblePosition < 0)) return
                captured = true
                val drawnSource = currentSource
                val drawnCount = books.size
                root.post {
                    if (observer.isAlive) observer.removeOnDrawListener(this)
                    waitingForFirstDraw = false
                    if (!destroyed && visit.firstContentMs == null) {
                        visit.firstSource = drawnSource
                        visit.firstCount = drawnCount
                        visit.firstContentMs = SystemClock.elapsedRealtime() - visit.startedAt
                    }
                }
            }
        })
        root.invalidate()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        refresh.isEnabled = !value
        refresh.alpha = if (value) 0.5f else 1f
    }

    private inner class BookAdapter : BaseAdapter() {
        private val currency = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
        override fun getCount() = books.size
        override fun getItem(position: Int) = books[position]
        override fun getItemId(position: Int) = books[position].id.toLong()
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.catalog_item, parent, false)
            val book = getItem(position)
            view.findViewById<TextView>(R.id.bookTitle).text = book.title
            view.findViewById<TextView>(R.id.bookMeta).text = "${book.author}  ·  ${currency.format(book.priceVnd)} đ"
            return view
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (busy) {
            visit.attempts.lastOrNull()?.let { if (it.httpMs < 0) it.status = "Đã rời màn hình" }
            visit.outcome = "Đã rời màn hình khi đang tải."
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        app.sync.observe(syncObserver)
        if (optimized) app.sync.requestCheck()
    }

    override fun onStop() {
        app.sync.removeObserver(syncObserver)
        super.onStop()
    }

    companion object {
        const val EXTRA_OPTIMIZED = "optimized"
    }
}

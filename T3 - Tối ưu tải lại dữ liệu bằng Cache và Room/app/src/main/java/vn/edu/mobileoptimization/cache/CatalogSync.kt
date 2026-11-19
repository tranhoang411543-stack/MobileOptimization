package vn.edu.mobileoptimization.cache

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val APP3_BASE_URL = "http://10.0.2.2:3000"

data class SyncView(
    val socket: String,
    val message: String,
    val checks: Int,
    val changeRequests: Int,
    val baseRequests: Int,
    val receivedBooks: Int,
    val bytes: Long,
    val lastHttpMs: Long?,
    val events: Int,
    val revision: Long?,
    val contentChanged: Long,
)

private data class SyncHead(val epoch: String, val version: Long)
private data class SyncPage(val epoch: String, val books: List<CachedBook>, val cursor: Long, val hasMore: Boolean)

/** One foreground, best-effort socket; durable HTTP cursors repair missed events. */
class CatalogSync(private val context: Context, private val database: CatalogDatabase) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val listeners = mutableSetOf<(SyncView) -> Unit>()
    private val gate = Any()
    private var foreground = false
    private var enabled = false
    @Volatile private var active = false
    @Volatile private var generation = 0L
    @Volatile private var metricsGeneration = 0L
    private var socketGeneration = 0L
    private var socket: WebSocket? = null
    private var running = false
    private var pending = false
    private var socketText = "Chưa kết nối"
    private var message = "Bật tối ưu để đồng bộ."
    private var checks = 0
    private var changeRequests = 0
    private var baseRequests = 0
    private var receivedBooks = 0
    private var bytes = 0L
    private var lastHttpMs: Long? = null
    private var events = 0
    private var revision: Long? = null
    private var contentChanged = 0L

    private val reconnect = Runnable { if (active) connectSocket() }
    private val retry = Runnable { if (active) requestCheck() }

    fun observe(listener: (SyncView) -> Unit) { listeners.add(listener); listener(view()) }
    fun removeObserver(listener: (SyncView) -> Unit) { listeners.remove(listener) }

    fun configure(optimized: Boolean) {
        enabled = optimized
        updateEligibility()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        updateEligibility()
    }

    private fun updateEligibility() {
        val shouldRun = foreground && enabled
        if (shouldRun == active) {
            if (!shouldRun) {
                socketText = "Đã dừng"
                message = if (!enabled) "Đang tắt tối ưu." else "App không ở màn hình trước."
                emit()
            }
            return
        }
        synchronized(gate) { active = shouldRun; generation++ }
        if (shouldRun) {
            message = "Đang kiểm tra dữ liệu Room…"
            connectSocket()
            requestCheck()
        } else {
            pending = false
            stopSocket()
            main.removeCallbacks(retry)
            socketText = "Đã dừng"
            message = if (!enabled) "Đang tắt tối ưu." else "App không ở màn hình trước."
            emit()
        }
    }

    /** Invalidate in-flight writes before clearing Room. */
    fun invalidateCache() {
        synchronized(gate) { generation++ }
        pending = false
        revision = null
        message = "Đang xóa cache Room…"
        emit()
    }

    fun resetMetrics() {
        metricsGeneration++
        checks = 0; changeRequests = 0; baseRequests = 0; receivedBooks = 0; bytes = 0; events = 0; lastHttpMs = null
        emit()
    }

    fun requestCheck() {
        if (!active) return
        if (running) { pending = true; return }
        running = true
        pending = false
        val token = generation
        message = "Đang kiểm tra phiên bản…"
        emit()
        worker.execute {
            val result = runCatching { synchronize(token) }
            main.post {
                running = false
                if (active && token == generation) {
                    result.onSuccess { message = it; main.removeCallbacks(retry) }
                        .onFailure { message = "Chưa đồng bộ: ${it.message ?: "lỗi kết nối"}"; main.removeCallbacks(retry); main.postDelayed(retry, 5000) }
                    emit()
                }
                if (active && (pending || token != generation)) { pending = false; requestCheck() }
            }
        }
    }

    private fun synchronize(token: Long): String {
        val dao = database.catalogDao()
        var local = dao.ensureSyncState() ?: return "Chưa có cache. Mở danh mục để tải lần đầu."
        if (!dao.hasExpandedBase()) {
            // Keep existing cached and synchronized books until the expanded base is ready.
            val metric = metricsGeneration
            val base = CatalogApi.fetch()
            record(metric, "base", base.httpMs, base.bytes)
            val baseBooks = base.books ?: throw IOException(base.error ?: "Không tải được danh mục gốc")
            synchronized(gate) {
                if (!valid(token)) return "Đã dừng đồng bộ."
                if (!dao.upgradeBaseSnapshot(local, baseBooks, System.currentTimeMillis())) {
                    return "Cache đã thay đổi; sẽ kiểm tra lại."
                }
            }
            changed(baseBooks.size, local.cursor)
        }
        val remote = readHead()
        if (!valid(token)) return "Đã dừng đồng bộ."
        if (local.epoch.isNotEmpty() && (local.epoch != remote.epoch || local.cursor > remote.version)) {
            // Database was recreated/restored: preserve old cache until a complete replacement is ready.
            val metric = metricsGeneration
            val base = CatalogApi.fetch()
            record(metric, "base", base.httpMs, base.bytes)
            val baseBooks = base.books ?: throw IOException(base.error ?: "Không tải được danh mục gốc")
            val all = mutableListOf<CachedBook>()
            var cursor = 0L
            var more: Boolean
            do {
                val page = readChanges(remote.epoch, cursor)
                all.addAll(page.books)
                if (all.size > 50_000) throw IOException("Danh sách đồng bộ vượt giới hạn demo")
                cursor = page.cursor
                more = page.hasMore
            } while (more && valid(token))
            if (cursor < remote.version) throw IOException("Dữ liệu thay đổi chưa đầy đủ")
            synchronized(gate) {
                if (!valid(token)) return "Đã dừng đồng bộ."
                if (!dao.resetSyncSnapshot(local, remote.epoch, cursor, baseBooks + all, System.currentTimeMillis())) {
                    return "Cache đã thay đổi; sẽ kiểm tra lại."
                }
            }
            changed(all.size, cursor)
            return "Đã khôi phục cache và đồng bộ ${all.size} sách."
        }

        var updated = 0
        var cursor = if (local.epoch.isEmpty()) 0L else local.cursor
        if (remote.version > cursor) {
            var more: Boolean
            do {
                val page = readChanges(remote.epoch, cursor)
                synchronized(gate) {
                    if (!valid(token)) return "Đã dừng đồng bộ."
                    if (!dao.applySyncPage(local, remote.epoch, page.cursor, page.books, System.currentTimeMillis())) {
                        return "Cache đã thay đổi; sẽ kiểm tra lại."
                    }
                }
                local = local.copy(epoch = remote.epoch, cursor = page.cursor)
                cursor = page.cursor
                updated += page.books.size
                if (page.books.isNotEmpty()) changed(page.books.size, cursor)
                more = page.hasMore
            } while (more && valid(token))
            if (cursor < remote.version) throw IOException("Dữ liệu thay đổi chưa đầy đủ")
        } else if (local.epoch.isEmpty()) {
            synchronized(gate) {
                if (!valid(token)) return "Đã dừng đồng bộ."
                if (!dao.applySyncPage(local, remote.epoch, 0, emptyList(), System.currentTimeMillis())) {
                    return "Cache đã thay đổi; sẽ kiểm tra lại."
                }
            }
        }
        val finalCursor = cursor
        main.post { if (valid(token)) { revision = finalCursor; emit() } }
        return if (updated > 0) "Đã đồng bộ $updated sách mới vào Room." else "Không có sách mới từ script."
    }

    private fun valid(token: Long) = active && generation == token

    private fun readHead(): SyncHead {
        val json = getJson("/api/app3-sync/state", "check")
        return SyncHead(json.getString("epoch"), parseCursor(json.getString("version")))
    }

    private fun readChanges(epoch: String, after: Long): SyncPage {
        val json = getJson("/api/app3-sync/changes?epoch=$epoch&after=$after&limit=50", "changes")
        if (json.getString("epoch") != epoch) throw IOException("Phiên bản backend đã thay đổi")
        val rows = json.getJSONArray("changes")
        val books = mutableListOf<CachedBook>()
        var previous = after
        for (i in 0 until rows.length()) {
            val item = rows.getJSONObject(i)
            val version = parseCursor(item.getString("version"))
            if (version <= previous) throw IOException("Thứ tự thay đổi không hợp lệ")
            previous = version
            val book = item.getJSONObject("book")
            val id = book.getInt("id")
            val price = book.getInt("priceVnd")
            val title = book.getString("title")
            val author = book.getString("author")
            if (id < 1 || price < 1 || title.isBlank() || author.isBlank()) throw IOException("Dữ liệu sách không hợp lệ")
            books.add(CachedBook(id, title, author, price))
        }
        val cursor = parseCursor(json.getString("nextCursor"))
        val hasMore = json.getBoolean("hasMore")
        if (cursor != previous || (hasMore && books.isEmpty())) throw IOException("Mốc đồng bộ không hợp lệ")
        return SyncPage(epoch, books, cursor, hasMore)
    }

    private fun parseCursor(text: String): Long = text.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw IOException("Mốc đồng bộ không hợp lệ")

    private fun getJson(path: String, kind: String): JSONObject {
        val metric = metricsGeneration
        val started = SystemClock.elapsedRealtime()
        var size = 0L
        var connection: HttpURLConnection? = null
        try {
            connection = URL(APP3_BASE_URL + path).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            size = data.size.toLong()
            if (code != 200) throw IOException("HTTP $code")
            return JSONObject(data.toString(Charsets.UTF_8))
        } finally {
            connection?.disconnect()
            record(metric, kind, SystemClock.elapsedRealtime() - started, size)
        }
    }

    private fun record(metric: Long, kind: String, elapsed: Long, size: Long) {
        main.post {
            if (metric != metricsGeneration) return@post
            when (kind) { "check" -> checks++; "changes" -> changeRequests++; "base" -> baseRequests++ }
            bytes += size
            lastHttpMs = elapsed.takeIf { it >= 0 }
            emit()
        }
    }

    private fun changed(count: Int, cursor: Long) {
        val metric = metricsGeneration
        main.post {
            if (metric == metricsGeneration) receivedBooks += count
            revision = cursor
            contentChanged++
            emit()
        }
    }

    private fun connectSocket() {
        if (!active || socket != null) return
        socketText = "Đang kết nối"
        emit()
        val token = ++socketGeneration
        val url = APP3_BASE_URL.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + "/api/app3-sync/events"
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { main.post {
                if (!active || token != socketGeneration) { webSocket.cancel(); return@post }
                socketText = "Đã kết nối"; emit(); requestCheck()
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { main.post {
                if (!active || token != socketGeneration) return@post
                val type = runCatching { JSONObject(text).optString("type") }.getOrDefault("")
                if (type == "catalog_changed") { events++; message = "Nhận tín hiệu thay đổi từ backend."; emit(); requestCheck() }
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected(token) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected(token) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private fun disconnected(token: Long) { main.post {
        if (token != socketGeneration) return@post
        socket = null
        if (active) {
            socketText = "Mất kết nối · Đang thử lại"
            emit()
            main.removeCallbacks(reconnect)
            main.postDelayed(reconnect, 3000)
        }
    } }

    private fun stopSocket() {
        socketGeneration++
        main.removeCallbacks(reconnect)
        socket?.cancel()
        socket = null
    }

    private fun view() = SyncView(socketText, message, checks, changeRequests, baseRequests, receivedBooks, bytes, lastHttpMs, events, revision, contentChanged)
    private fun emit() { val state = view(); listeners.toList().forEach { it(state) } }
}

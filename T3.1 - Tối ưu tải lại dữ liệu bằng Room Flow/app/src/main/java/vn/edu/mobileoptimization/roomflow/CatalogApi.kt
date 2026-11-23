package vn.edu.mobileoptimization.roomflow

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val BASE_URL = "http://10.0.2.2:3000/"

@Serializable data class BookDto(val id: Int, val title: String, val author: String, val priceVnd: Int)
@Serializable data class TimingDto(val databaseMs: Double)
@Serializable data class CatalogDto(val mode: String, val items: List<BookDto>, val returned: Int, val timing: TimingDto)
@Serializable data class SyncHeadDto(val epoch: String, val version: String)
@Serializable data class ChangeDto(val version: String, val book: BookDto)
@Serializable data class ChangesDto(val epoch: String, val changes: List<ChangeDto>, val nextCursor: String, val hasMore: Boolean)

interface CatalogService {
    /** Trả body thô để đếm đúng số byte nhận được, giống T3. */
    @GET("api/app3-sync/catalog")
    suspend fun catalog(@Header("Accept-Encoding") encoding: String = "identity"): retrofit2.Response<ResponseBody>

    @GET("api/app3-sync/state")
    suspend fun state(@Header("Accept-Encoding") encoding: String = "identity"): SyncHeadDto

    @GET("api/app3-sync/changes")
    suspend fun changes(
        @Query("epoch") epoch: String,
        @Query("after") after: Long,
        @Query("limit") limit: Int = 50,
        @Header("Accept-Encoding") encoding: String = "identity",
    ): ChangesDto
}

internal val json = Json { ignoreUnknownKeys = true }

object Network {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val service: CatalogService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CatalogService::class.java)
}

data class NetworkResult(
    val books: List<CachedBook>?,
    val httpMs: Long,
    val bytes: Long,
    val statusCode: Int,
    val databaseMs: Double,
    val connectionId: String?,
    val connectionNumber: Int,
    val error: String?,
)

data class SyncHead(val epoch: String, val version: Long)
data class SyncPage(val epoch: String, val books: List<CachedBook>, val cursor: Long, val hasMore: Boolean)

/** Ranh giới giữa logic đồng bộ và mạng; bộ test thay bằng bản giả. */
interface CatalogRemote {
    suspend fun fetchCatalog(): NetworkResult
    suspend fun state(): SyncHead
    suspend fun changes(epoch: String, after: Long): SyncPage
}

class RetrofitCatalogRemote(
    private val service: CatalogService,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : CatalogRemote {
    override suspend fun fetchCatalog(): NetworkResult {
        val start = clock()
        var httpMs = -1L
        var bytes = 0L
        var status = 0
        var connectionId: String? = null
        var connectionNumber = 0
        try {
            val response = service.catalog()
            status = response.code()
            connectionId = response.headers()["X-Demo-Connection-Id"]?.takeIf { it.isNotBlank() }
            connectionNumber = response.headers()["X-Demo-Connection-Request"]?.toIntOrNull() ?: 0
            val payload = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.bytes() } ?: ByteArray(0)
            bytes = payload.size.toLong()
            httpMs = clock() - start
            if (!response.isSuccessful) {
                return NetworkResult(null, httpMs, bytes, status, -1.0, connectionId, connectionNumber, "HTTP $status")
            }
            val dto = json.decodeFromString<CatalogDto>(payload.toString(Charsets.UTF_8))
            check(dto.mode == "paged" && dto.returned == dto.items.size && dto.items.size <= 1000)
            check(dto.items.zipWithNext().all { (a, b) -> a.id < b.id })
            return NetworkResult(dto.items.map { it.toEntity() }, httpMs, bytes, status, dto.timing.databaseMs,
                connectionId, connectionNumber, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val elapsed = if (httpMs >= 0) httpMs else clock() - start
            val error = if (httpMs >= 0) "Phản hồi không hợp lệ" else "Không kết nối được backend"
            return NetworkResult(null, elapsed, bytes, status, -1.0, connectionId, connectionNumber, error)
        }
    }

    override suspend fun state(): SyncHead = service.state().let { SyncHead(it.epoch, parseCursor(it.version)) }

    override suspend fun changes(epoch: String, after: Long): SyncPage {
        val dto = service.changes(epoch, after)
        if (dto.epoch != epoch) throw IOException("Phiên bản backend đã thay đổi")
        var previous = after
        val books = dto.changes.map { change ->
            val version = parseCursor(change.version)
            if (version <= previous) throw IOException("Thứ tự thay đổi không hợp lệ")
            previous = version
            val book = change.book
            if (book.id < 1 || book.priceVnd < 1 || book.title.isBlank() || book.author.isBlank()) {
                throw IOException("Dữ liệu sách không hợp lệ")
            }
            book.toEntity()
        }
        val cursor = parseCursor(dto.nextCursor)
        if (cursor != previous || (dto.hasMore && books.isEmpty())) throw IOException("Mốc đồng bộ không hợp lệ")
        return SyncPage(epoch, books, cursor, dto.hasMore)
    }

    private fun parseCursor(text: String): Long = text.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw IOException("Mốc đồng bộ không hợp lệ")
}

private fun BookDto.toEntity() = CachedBook(id, title, author, priceVnd)

sealed interface SocketEvent {
    data object Connecting : SocketEvent
    data object Connected : SocketEvent
    data object CatalogChanged : SocketEvent
    data object Lost : SocketEvent
}

/**
 * WebSocket chỉ báo "có thay đổi"; dữ liệu thật lấy qua HTTP theo mốc đã lưu.
 * `callbackFlow` đóng socket khi coroutine thu thập bị hủy; `retryWhen` thay cho Handler.postDelayed.
 */
fun syncSocketEvents(client: OkHttpClient, url: String = BASE_URL.replaceFirst("http", "ws") + "api/app3-sync/events"): Flow<SocketEvent> =
    callbackFlow {
        trySend(SocketEvent.Connecting)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { trySend(SocketEvent.Connected) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val type = runCatching { json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
                if (type == "catalog_changed") trySend(SocketEvent.CatalogChanged)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close(IOException("Socket đã đóng")) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(t) }
        })
        awaitClose { socket.cancel() }
    }.retryWhen { cause, _ ->
        if (cause is CancellationException) return@retryWhen false
        emit(SocketEvent.Lost)
        delay(3000)
        true
    }

fun Context.offlineReason(): String? {
    val manager = getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: return "Thiết bị không có kết nối mạng"
    val capabilities = manager.getNetworkCapabilities(network)
    return if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) null
    else "Thiết bị không có kết nối mạng"
}

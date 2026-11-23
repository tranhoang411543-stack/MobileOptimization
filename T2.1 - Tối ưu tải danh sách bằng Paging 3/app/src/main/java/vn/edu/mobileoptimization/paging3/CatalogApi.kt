package vn.edu.mobileoptimization.paging3

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

internal const val BACKEND_BASE_URL = "http://10.0.2.2:3000/"
internal const val PAGE_SIZE = 20

@Serializable
internal data class BookDto(val id: Int, val title: String, val author: String, val priceVnd: Int)

@Serializable
internal data class TimingDto(val databaseMs: Double)

@Serializable
internal data class CatalogResponse(
    val mode: String,
    val items: List<BookDto>,
    val returned: Int,
    val hasMore: Boolean,
    val nextCursor: Int? = null,
    val timing: TimingDto,
)

internal interface CatalogApi {
    /** Trả body thô để đếm đúng số byte nhận được như T2, sau đó mới đọc JSON. */
    @GET("api/books/catalog")
    suspend fun catalog(
        @Query("mode") mode: String,
        @Query("limit") limit: Int? = null,
        @Query("afterId") afterId: Int? = null,
        // Tắt nén để số byte giữa T2 và T2.1 so sánh được; OkHttp khi đó không tự giải nén gzip.
        @Header("Accept-Encoding") encoding: String = "identity",
    ): Response<ResponseBody>
}

internal object Network {
    val api: CatalogApi = Retrofit.Builder()
        .baseUrl(BACKEND_BASE_URL)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build(),
        )
        .build()
        .create(CatalogApi::class.java)
}

internal data class FetchResult(
    val books: List<BookDto>?,
    val hasMore: Boolean,
    val nextCursor: Int?,
    val httpMs: Long,
    val bytes: Long,
    val statusCode: Int,
    val databaseMs: Double,
    val connectionId: String?,
    val connectionNumber: Int,
    val error: String?,
)

internal fun interface CatalogRepository {
    /** `afterId == null` nghĩa là tải toàn bộ (chế độ tắt tối ưu). */
    suspend fun fetch(afterId: Int?, limit: Int): FetchResult
}

internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

internal class RetrofitCatalogRepository(
    private val api: CatalogApi,
    private val clock: () -> Long = ::monotonicMillis,
) : CatalogRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(afterId: Int?, limit: Int): FetchResult {
        val start = clock()
        var httpMs = -1L
        var bytes = 0L
        var status = 0
        var connectionId: String? = null
        var connectionNumber = 0
        return try {
            val response = if (afterId == null) api.catalog("all")
            else api.catalog("paged", limit = limit, afterId = afterId)
            status = response.code()
            connectionId = response.headers()["X-Demo-Connection-Id"]?.takeIf { it.isNotBlank() }
            connectionNumber = response.headers()["X-Demo-Connection-Request"]?.toIntOrNull() ?: 0
            val payload = (response.body() ?: response.errorBody())?.use { it.bytes() } ?: ByteArray(0)
            bytes = payload.size.toLong()
            httpMs = clock() - start // Giống T2: tính đến khi đọc xong body, chưa gồm đọc JSON.
            if (!response.isSuccessful) {
                return FetchResult(null, false, null, httpMs, bytes, status, -1.0, connectionId, connectionNumber, "HTTP $status")
            }
            val body = json.decodeFromString<CatalogResponse>(payload.toString(Charsets.UTF_8))
            check(body.mode == if (afterId == null) "all" else "paged")
            check(body.returned == body.items.size)
            body.items.zipWithNext().forEach { (a, b) -> check(b.id > a.id) }
            FetchResult(body.items, body.hasMore, body.nextCursor, httpMs, bytes, status, body.timing.databaseMs,
                connectionId, connectionNumber, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val elapsed = if (httpMs >= 0) httpMs else clock() - start
            val error = if (httpMs >= 0) "Phản hồi không hợp lệ" else "Không nhận được phản hồi"
            FetchResult(null, false, null, elapsed, bytes, status, -1.0, connectionId, connectionNumber, error)
        }
    }
}

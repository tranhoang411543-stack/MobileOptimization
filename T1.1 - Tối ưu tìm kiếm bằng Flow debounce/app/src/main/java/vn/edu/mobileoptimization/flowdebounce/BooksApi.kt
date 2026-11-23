package vn.edu.mobileoptimization.flowdebounce

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

internal const val BACKEND_BASE_URL = "http://10.0.2.2:3000/"

@Serializable
internal data class BookDto(val title: String, val author: String, val priceVnd: Int)

@Serializable
internal data class TimingDto(val databaseMs: Double)

@Serializable
internal data class SearchResponse(val items: List<BookDto>, val timing: TimingDto)

/** Retrofit tự sinh mã gọi HTTP từ interface; hàm suspend bị hủy thì OkHttp cũng hủy request. */
internal interface BooksApi {
    @GET("api/books/search")
    suspend fun search(@Query("q") query: String, @Query("limit") limit: Int = 20): Response<SearchResponse>

    @GET("api/health/keep-alive")
    suspend fun keepAlive(): Response<Unit>
}

internal object Network {
    private val json = Json { ignoreUnknownKeys = true }

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    val api: BooksApi = Retrofit.Builder()
        .baseUrl(BACKEND_BASE_URL)
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BooksApi::class.java)
}

internal data class SearchResult(
    val books: List<BookDto>?,
    val databaseMs: Double,
    val httpMs: Long,
    val connectionId: String?,
    val connectionRequest: Int,
    val statusCode: Int,
    val error: String?,
)

internal fun interface SearchRepository {
    suspend fun search(query: String): SearchResult
}

internal fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

internal class RetrofitSearchRepository(
    private val api: BooksApi,
    private val clock: () -> Long = ::monotonicMillis,
) : SearchRepository {
    override suspend fun search(query: String): SearchResult {
        val start = clock()
        return try {
            val response = api.search(query)
            // Retrofit đọc và chuyển JSON thành đối tượng trước khi trả về, nên số đo gồm cả bước này.
            val httpMs = clock() - start
            val connectionId = response.headers()["X-Demo-Connection-Id"]?.takeIf { it.isNotBlank() }
            val connectionRequest = response.headers()["X-Demo-Connection-Request"]?.toIntOrNull() ?: 0
            val body = response.body()
            if (!response.isSuccessful || body == null) {
                SearchResult(null, -1.0, httpMs, connectionId, connectionRequest, response.code(),
                    "Backend trả lỗi HTTP ${response.code()}")
            } else {
                SearchResult(body.items, body.timing.databaseMs, httpMs, connectionId, connectionRequest,
                    response.code(), null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SerializationException) {
            SearchResult(null, -1.0, clock() - start, null, 0, 0, "Phản hồi không hợp lệ")
        } catch (_: Exception) {
            SearchResult(null, -1.0, clock() - start, null, 0, 0, "Không nhận được phản hồi")
        }
    }
}

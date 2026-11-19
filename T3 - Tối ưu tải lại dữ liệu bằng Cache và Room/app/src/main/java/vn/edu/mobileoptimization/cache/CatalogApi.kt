package vn.edu.mobileoptimization.cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

object CatalogApi {
    fun offlineReason(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return "Thiết bị không có kết nối mạng"
        val capabilities = manager.getNetworkCapabilities(network)
        return if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) null
        else "Thiết bị không có kết nối mạng"
    }

    fun fetch(): NetworkResult {
        val start = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        var httpMs = -1L
        var bytes = 0L
        var status = 0
        var connectionId: String? = null
        var connectionNumber = 0
        try {
            connection = URL("$APP3_BASE_URL/api/app3-sync/catalog")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
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
                return NetworkResult(null, httpMs, bytes, status, -1.0, connectionId, connectionNumber, "HTTP $status")
            }
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            check(json.getString("mode") == "paged")
            val rows = json.getJSONArray("items")
            val books = buildList {
                var previousId = 0
                for (index in 0 until rows.length()) {
                    val item = rows.getJSONObject(index)
                    val id = item.getInt("id")
                    check(id > previousId)
                    previousId = id
                    add(CachedBook(id, item.getString("title"), item.getString("author"), item.getInt("priceVnd")))
                }
            }
            check(books.size <= 1000 && json.getInt("returned") == books.size)
            return NetworkResult(books, httpMs, bytes, status,
                json.getJSONObject("timing").getDouble("databaseMs"), connectionId, connectionNumber, null)
        } catch (_: Exception) {
            val elapsed = if (httpMs >= 0) httpMs else SystemClock.elapsedRealtime() - start
            val error = if (httpMs >= 0) "Phản hồi không hợp lệ" else "Không kết nối được backend"
            return NetworkResult(null, elapsed, bytes, status, -1.0, connectionId, connectionNumber, error)
        } finally {
            connection?.disconnect()
        }
    }
}

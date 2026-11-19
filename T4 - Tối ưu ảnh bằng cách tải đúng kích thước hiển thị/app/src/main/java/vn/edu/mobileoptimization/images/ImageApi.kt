package vn.edu.mobileoptimization.images

import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Product(val id: Int, val name: String, val priceVnd: Int, val imagePath: String)
data class ImageResponse(
    val payload: ByteArray?,
    val receivedBytes: Long,
    val elapsedMs: Long,
    val code: Int,
    val connectionId: String?,
    val connectionNumber: Int,
    val error: String?,
)

object ImageApi {
    private const val BASE = "http://10.0.2.2:3000"
    private val imagePath = Regex("^/api/image-demo/images/[0-9]{1,12}$")

    fun products(): List<Product> {
        val response = get("/api/image-demo/products")
        require(response.error == null && response.payload != null) { response.error ?: "Không có dữ liệu" }
        val json = JSONObject(response.payload.toString(Charsets.UTF_8))
        val rows = json.getJSONArray("items")
        val result = buildList {
            for (index in 0 until rows.length()) {
                val item = rows.getJSONObject(index)
                val path = item.getString("imagePath")
                require(imagePath.matches(path))
                add(Product(item.getInt("id"), item.getString("name"), item.getInt("priceVnd"), path))
            }
        }
        require(json.getInt("returned") == result.size && result.map { it.id }.distinct().size == result.size)
        return result
    }

    fun image(path: String): ImageResponse {
        require(imagePath.matches(path))
        return get(path)
    }

    private fun get(path: String): ImageResponse {
        val started = SystemClock.elapsedRealtime()
        var connection: HttpURLConnection? = null
        var code = 0
        var connectionId: String? = null
        var connectionNumber = 0
        try {
            connection = URL(BASE + path).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            code = connection.responseCode
            connectionId = connection.getHeaderField("X-Demo-Connection-Id")?.takeIf { it.isNotBlank() }
            connectionNumber = connection.getHeaderField("X-Demo-Connection-Request")?.toIntOrNull() ?: 0
            val stream = if (code == HttpURLConnection.HTTP_OK) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val elapsed = SystemClock.elapsedRealtime() - started
            return if (code == HttpURLConnection.HTTP_OK) {
                ImageResponse(bytes, bytes.size.toLong(), elapsed, code, connectionId, connectionNumber, null)
            } else {
                ImageResponse(null, bytes.size.toLong(), elapsed, code, connectionId, connectionNumber, "HTTP $code")
            }
        } catch (_: Exception) {
            val message = if (Thread.currentThread().isInterrupted) "Đã hủy" else "Không kết nối được backend"
            return ImageResponse(null, 0, SystemClock.elapsedRealtime() - started, code, connectionId, connectionNumber, message)
        } finally {
            connection?.disconnect()
        }
    }
}

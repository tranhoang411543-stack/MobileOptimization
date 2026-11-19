package vn.edu.mobileoptimization.securestore

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeParseException

internal data class NoteSummary(
    val id: Int, val title: String, val encrypted: Boolean,
    val createdAt: Instant? = null, val updatedAt: Instant? = null,
)
internal data class NotePage(val total: Int, val items: List<NoteSummary>, val nextCursor: Int?)
internal data class RemoteNote(
    val id: Int, val title: String, val content: String, val encrypted: Boolean, val revision: String = "",
)
internal data class CreateNoteRequest(
    val requestId: String, val title: String, val content: String, val encrypted: Boolean,
)
internal data class UpdateNoteRequest(
    val expectedRevision: String, val title: String, val content: String, val encrypted: Boolean,
)
internal class ApiException(val statusCode: Int) : IOException("Backend trả mã $statusCode")
internal class BackendOutdatedException : IOException("Backend chưa hỗ trợ sửa ghi chú.")

internal interface NotesGateway {
    fun list(afterId: Int? = null): NotePage
    fun get(id: Int): RemoteNote
    fun create(note: CreateNoteRequest): NoteSummary
    fun update(id: Int, note: UpdateNoteRequest): NoteSummary
}

internal class NotesApi(private val baseUrl: String = "http://10.0.2.2:3000") : NotesGateway {
    override fun list(afterId: Int?): NotePage {
        val query = if (afterId == null) "?limit=50" else "?limit=50&afterId=$afterId"
        val json = request("GET", "/api/app6-notes$query")
        val rows = json.getJSONArray("items")
        val items = (0 until rows.length()).map { summary(rows.getJSONObject(it)) }
        val nextCursor = if (json.isNull("nextCursor")) null else json.getInt("nextCursor")
        return NotePage(json.getInt("total"), items, nextCursor)
    }

    override fun get(id: Int): RemoteNote {
        val json = request("GET", "/api/app6-notes/$id")
        val revision = json.optString("revision", "")
        if (!revision.matches(Regex("^[0-9a-f]{64}$"))) throw BackendOutdatedException()
        return RemoteNote(json.getInt("id"), json.getString("title"), json.getString("content"),
            json.getBoolean("encrypted"), revision)
    }

    override fun create(note: CreateNoteRequest): NoteSummary {
        val body = JSONObject().put("requestId", note.requestId).put("title", note.title)
            .put("content", note.content).put("encrypted", note.encrypted)
        return summary(request("POST", "/api/app6-notes", body))
    }

    override fun update(id: Int, note: UpdateNoteRequest): NoteSummary {
        val body = JSONObject().put("expectedRevision", note.expectedRevision).put("title", note.title)
            .put("content", note.content).put("encrypted", note.encrypted)
        return summary(request("PUT", "/api/app6-notes/$id", body))
    }

    private fun summary(json: JSONObject) = NoteSummary(
        json.getInt("id"), json.getString("title"), json.getBoolean("encrypted"),
        readTimestamp(json, "createdAt"), readTimestamp(json, "updatedAt"),
    )

    private fun readTimestamp(json: JSONObject, name: String): Instant? {
        if (!json.has(name) || json.isNull(name)) return null
        return try {
            Instant.parse(json.getString(name))
        } catch (error: DateTimeParseException) {
            throw IOException("Thời gian ghi chú không hợp lệ.", error)
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw ApiException(status)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 256 * 1024) throw IOException("Phản hồi backend quá lớn.")
                    output.write(buffer, 0, count)
                }
            }
            return JSONObject(output.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }
}

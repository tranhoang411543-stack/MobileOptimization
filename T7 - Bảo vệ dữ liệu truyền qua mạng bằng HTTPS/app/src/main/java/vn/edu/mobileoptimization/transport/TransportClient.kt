package vn.edu.mobileoptimization.transport

import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal enum class DemoMode(val address: String) {
    HTTP("http://10.0.2.2:3000/api/transport-demo/submit"),
    HTTPS("https://10.0.2.2:3443/api/transport-demo/submit"),
    UNTRUSTED("https://10.0.2.2:3444/api/transport-demo/submit"),
}

internal data class DemoResult(
    val mode: DemoMode,
    val elapsedMillis: Double,
    val httpStatus: Int?,
    val outcome: String,
    val successful: Boolean,
    val payloadStarted: Boolean,
    val cipher: String? = null,
    val certificateSubject: String? = null,
    val certificateRejected: Boolean = false,
    val unexpectedTrust: Boolean = false,
    val recordId: String? = null,
)

internal class TransportClient(private val addressFor: (DemoMode) -> String = { it.address }) {
    fun execute(mode: DemoMode, username: String, password: String): DemoResult {
        val start = SystemClock.elapsedRealtimeNanos()
        var connection: HttpURLConnection? = null
        var payloadStarted = false
        var unexpectedTrust = false
        fun elapsed() = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        try {
            connection = URL(addressFor(mode)).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Connection", "close")
            val payload = JSONObject().put("username", username).put("password", password)
                .toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            // For HTTPS, establish the standard trust and hostname checks before writing a body.
            connection.connect()
            unexpectedTrust = mode == DemoMode.UNTRUSTED
            val tls = connection as? HttpsURLConnection
            val cipher = tls?.cipherSuite
            val subject = (tls?.serverCertificates?.firstOrNull() as? X509Certificate)?.subjectX500Principal?.name
            connection.outputStream.use { output ->
                payloadStarted = true
                output.write(payload)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytesLimited(4096) } ?: ByteArray(0)
            val response = JSONObject(bytes.toString(Charsets.UTF_8))
            val responseId = response.optString("recordId", "")
            val ok = code == 200 && response.optBoolean("ok", false) && responseId.matches(Regex("[1-9][0-9]*"))
            val message = if (ok) "Backend đã lưu dữ liệu vào CSDL (mã #$responseId)."
                else "Backend từ chối yêu cầu hoặc chưa xác nhận đã lưu vào CSDL."
            return DemoResult(mode, elapsed(), code, withTrustWarning(message, unexpectedTrust),
                ok, payloadStarted, cipher, subject, unexpectedTrust = unexpectedTrust,
                recordId = responseId.takeIf { ok })
        } catch (error: Exception) {
            val certificateFailure = causes(error).any {
                it is CertPathValidatorException || it is CertificateException || it is SSLPeerUnverifiedException
            }
            val handshakeFailure = causes(error).any { it is SSLHandshakeException }
            val message = when {
                certificateFailure && !payloadStarted -> "Xác thực chứng chỉ hoặc tên máy chủ thất bại."
                payloadStarted -> "Chưa nhận được xác nhận; dữ liệu có thể đã được lưu. Hãy kiểm tra CSDL."
                handshakeFailure -> "Bắt tay TLS thất bại; không xác định được nguyên nhân chứng chỉ."
                error is java.net.SocketTimeoutException -> "Hết thời gian chờ. Kiểm tra backend và kết nối emulator."
                error is IOException -> "Không kết nối được. Kiểm tra backend, cổng và chứng chỉ demo."
                else -> "Không xử lý được phản hồi từ backend."
            }
            return DemoResult(mode, elapsed(), null, withTrustWarning(message, unexpectedTrust), false, payloadStarted,
                certificateRejected = certificateFailure && !payloadStarted && !unexpectedTrust,
                unexpectedTrust = unexpectedTrust)
        } finally {
            connection?.disconnect()
        }
    }

    private fun withTrustWarning(message: String, unexpectedTrust: Boolean): String =
        if (unexpectedTrust) "CẢNH BÁO: chứng chỉ ở cổng thử nghiệm đã được chấp nhận. $message" else message

    private fun causes(error: Throwable): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && current !in result && result.size < 12) {
            result.add(current)
            current = current.cause
        }
        return result
    }

    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw IOException("Response too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}

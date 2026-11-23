package vn.edu.mobileoptimization.pinning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.net.URI
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

internal enum class DemoMode(val baseUrl: String, val port: Int) {
    HTTP("http://10.0.2.2:3000", 3000),
    HTTPS("https://10.0.2.2:3443", 3443),
    UNTRUSTED("https://10.0.2.2:3444", 3444),
}

@Serializable
internal data class SubmitRequest(val username: String, val password: String)

@Serializable
internal data class SubmitResponse(val ok: Boolean, val recordId: String? = null)

internal data class DemoResult(
    val mode: DemoMode,
    val elapsedMillis: Long,
    val httpStatus: Int?,
    val outcome: String,
    val successful: Boolean,
    val payloadStarted: Boolean,
    val cipher: String? = null,
    val certificateSubject: String? = null,
    val certificateRejected: Boolean = false,
    val unexpectedTrust: Boolean = false,
    val recordId: String? = null,
    val pinned: Boolean = false,
)

internal interface TransportApi {
    @POST("/api/transport-demo/submit")
    suspend fun submit(@Body request: SubmitRequest): SubmitResponse
}

internal class TransportClient(
    private val baseUrlFor: (DemoMode) -> String = { it.baseUrl },
    // SHA-256 public key pin của CA demo (Backend/.local-tls/app7/root-ca.pem).
    // Pin này sinh bằng: openssl x509 -in root-ca.pem -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl enc -base64
    // Người dùng khác cần chạy lại backend setup script để lấy pin mới nếu CA thay đổi.
    private val caDemoPin: String = "sha256/nPLQJKxUyZJRtpUbRaivYxOeLs43hKREoBtIky/F2w4=",
    private val customSslSocketFactory: SSLSocketFactory? = null,
    private val customTrustManager: X509TrustManager? = null,
    private val customHostnameVerifier: HostnameVerifier? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun createClient(mode: DemoMode, enablePinning: Boolean, listener: EventListener): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .eventListener(listener)

        if (customSslSocketFactory != null && customTrustManager != null) {
            builder.sslSocketFactory(customSslSocketFactory, customTrustManager)
        }
        if (customHostnameVerifier != null) {
            builder.hostnameVerifier(customHostnameVerifier)
        }

        if (enablePinning && mode != DemoMode.HTTP) {
            val host = try {
                URI(baseUrlFor(mode)).host ?: "10.0.2.2"
            } catch (_: Exception) {
                "10.0.2.2"
            }
            // Pin SHA-256 của public key CA demo cho cổng HTTPS tin cậy.
            // Với cổng UNTRUSTED, pin vẫn được áp dụng và sẽ từ chối vì chứng chỉ không khớp.
            builder.certificatePinner(
                CertificatePinner.Builder()
                    .add(host, caDemoPin)
                    .build()
            )
        }

        return builder.build()
    }

    suspend fun execute(mode: DemoMode, username: String, password: String, enablePinning: Boolean = false): DemoResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var payloadStarted = false
        var unexpectedTrust = false
        var cipher: String? = null
        var subject: String? = null

        val listener = object : EventListener() {
            override fun connectionAcquired(call: Call, connection: Connection) {
                val handshake = connection.handshake()
                cipher = handshake?.cipherSuite?.javaName
                val cert = handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                subject = cert?.subjectX500Principal?.name
            }

            override fun requestBodyStart(call: Call) {
                payloadStarted = true
            }
        }

        try {
            val client = createClient(mode, enablePinning, listener)
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrlFor(mode))
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()

            val api = retrofit.create(TransportApi::class.java)

            // OkHttpClient tự động xác thực chứng chỉ và hostname trước khi gửi body.
            // Nếu pin sai hoặc chứng chỉ không tin cậy, exception được ném trước khi gửi.
            val response = api.submit(SubmitRequest(username, password))
            unexpectedTrust = mode == DemoMode.UNTRUSTED

            val elapsed = System.currentTimeMillis() - start
            val ok = response.ok && !response.recordId.isNullOrBlank()
            val message = if (ok) "Backend đã lưu dữ liệu vào CSDL (mã #${response.recordId})."
            else "Backend từ chối yêu cầu hoặc chưa xác nhận đã lưu vào CSDL."

            DemoResult(
                mode = mode,
                elapsedMillis = elapsed,
                httpStatus = 200,
                outcome = withTrustWarning(message, unexpectedTrust),
                successful = ok,
                payloadStarted = payloadStarted,
                cipher = cipher,
                certificateSubject = subject,
                unexpectedTrust = unexpectedTrust,
                recordId = response.recordId,
                pinned = enablePinning && mode != DemoMode.HTTP,
            )
        } catch (error: Exception) {
            val elapsed = System.currentTimeMillis() - start
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

            DemoResult(
                mode = mode,
                elapsedMillis = elapsed,
                httpStatus = null,
                outcome = withTrustWarning(message, unexpectedTrust),
                successful = false,
                payloadStarted = payloadStarted,
                certificateRejected = certificateFailure && !payloadStarted && !unexpectedTrust,
                unexpectedTrust = unexpectedTrust,
                pinned = enablePinning && mode != DemoMode.HTTP,
            )
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
}

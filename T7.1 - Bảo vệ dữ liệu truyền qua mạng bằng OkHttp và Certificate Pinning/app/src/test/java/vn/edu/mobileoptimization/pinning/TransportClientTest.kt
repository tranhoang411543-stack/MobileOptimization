package vn.edu.mobileoptimization.pinning

import kotlinx.coroutines.test.runTest
import okhttp3.CertificatePinner
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.HostnameVerifier

class TransportClientTest {
    private lateinit var server: MockWebServer
    private lateinit var serverCertificate: HeldCertificate
    private lateinit var serverCertificates: HandshakeCertificates
    private lateinit var clientCertificates: HandshakeCertificates
    private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

    @Before
    fun setup() {
        // Tạo chứng chỉ tự ký cho localhost.
        serverCertificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()

        serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()

        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `HTTP request succeeds without pinning`() = runTest {
        val plainServer = MockWebServer()
        plainServer.enqueue(MockResponse().setBody("""{"ok":true,"recordId":"123"}"""))
        plainServer.start()

        val client = TransportClient(baseUrlFor = { plainServer.url("/").toString() })
        val result = client.execute(DemoMode.HTTP, "user", "pass", enablePinning = false)

        assertTrue(result.successful)
        assertEquals("123", result.recordId)
        assertFalse(result.pinned)
        plainServer.shutdown()
    }

    @Test
    fun `HTTPS request succeeds with correct pin`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"recordId":"456"}"""))
        server.start()

        // Pin SHA-256 của public key chứng chỉ server.
        val pin = CertificatePinner.pin(serverCertificate.certificate)
        val client = TransportClient(
            baseUrlFor = { server.url("/").toString() },
            caDemoPin = pin,
            customSslSocketFactory = clientCertificates.sslSocketFactory(),
            customTrustManager = clientCertificates.trustManager,
            customHostnameVerifier = trustAllHostnameVerifier,
        )

        val result = client.execute(DemoMode.HTTPS, "user", "pass", enablePinning = true)

        assertTrue("Expected successful result but got outcome: ${result.outcome}", result.successful)
        assertEquals("456", result.recordId)
        assertTrue(result.pinned)
    }

    @Test
    fun `HTTPS request fails with wrong pin`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"recordId":"789"}"""))
        server.start()

        // Pin sai (không khớp với chứng chỉ server).
        val wrongPin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val client = TransportClient(
            baseUrlFor = { server.url("/").toString() },
            caDemoPin = wrongPin,
            customSslSocketFactory = clientCertificates.sslSocketFactory(),
            customTrustManager = clientCertificates.trustManager,
            customHostnameVerifier = trustAllHostnameVerifier,
        )

        val result = client.execute(DemoMode.HTTPS, "user", "pass", enablePinning = true)

        assertFalse(result.successful)
        assertTrue(result.certificateRejected)
        assertNull(result.recordId)
        assertTrue(result.pinned)
    }

    @Test
    fun `Backend returns invalid response`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":false}"""))
        server.start()

        val pin = CertificatePinner.pin(serverCertificate.certificate)
        val client = TransportClient(
            baseUrlFor = { server.url("/").toString() },
            caDemoPin = pin,
            customSslSocketFactory = clientCertificates.sslSocketFactory(),
            customTrustManager = clientCertificates.trustManager,
            customHostnameVerifier = trustAllHostnameVerifier,
        )

        val result = client.execute(DemoMode.HTTPS, "user", "pass", enablePinning = true)

        assertFalse(result.successful)
        assertNull(result.recordId)
        assertTrue("Expected 'chưa xác nhận' in outcome but got: ${result.outcome}", result.outcome.contains("chưa xác nhận"))
    }
}

package vn.edu.mobileoptimization.debounce

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal const val BACKEND_BASE_URL = "http://10.0.2.2:3000"

/** Keeps one idle socket warm on a best-effort basis while the Activity is visible. */
internal class ConnectionKeeper(
    private val intervalMs: Long = 2_000L,
    private val reportState: (Boolean, String?) -> Unit = ::logHeartbeatState,
    private val sendHeartbeat: () -> Unit = ::sendConnectionHeartbeat,
) : Closeable {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "connection-heartbeat").apply { isDaemon = true }
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var reportedFailure = false // Accessed only by the scheduler thread.

    @Synchronized
    fun start() {
        if (scheduled != null || scheduler.isShutdown) return
        scheduled = scheduler.scheduleWithFixedDelay({
            try {
                sendHeartbeat()
                if (reportedFailure) reportState(true, null)
                reportedFailure = false
            } catch (error: Exception) {
                if (!reportedFailure) {
                    reportState(false, error.javaClass.simpleName)
                    reportedFailure = true
                }
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        // A request already in flight can finish; no new periodic requests are scheduled.
        scheduled?.cancel(false)
        scheduled = null
    }

    @Synchronized
    override fun close() {
        stop()
        scheduler.shutdownNow()
    }

}

private fun logHeartbeatState(connected: Boolean, reason: String?) {
    if (connected) Log.i("ConnectionKeeper", "Đã kết nối lại heartbeat.")
    else Log.w("ConnectionKeeper", "Heartbeat thất bại ($reason); sẽ thử lại.")
}

private fun sendConnectionHeartbeat() {
    val connection = URL("$BACKEND_BASE_URL/api/health/keep-alive").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 4_000
        connection.readTimeout = 4_000
        connection.useCaches = false
        val status = connection.responseCode
        val body = if (status in 200..299) connection.inputStream else connection.errorStream
        body?.use { stream ->
            val buffer = ByteArray(1024)
            while (stream.read(buffer) != -1) Unit
        }
        if (status != HttpURLConnection.HTTP_NO_CONTENT) throw IOException("HTTP $status")
    } finally {
        connection.disconnect()
    }
}

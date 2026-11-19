package vn.edu.mobileoptimization.transport

import android.accessibilityservice.AccessibilityService
import android.security.NetworkSecurityPolicy
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransportClientTest {
    private val username = "android_client_demo"
    private val password = "client_demo_pass_123"
    private val client = TransportClient { mode ->
        when (mode) {
            DemoMode.HTTP -> "http://10.0.2.2:13070/api/transport-demo/submit"
            DemoMode.HTTPS -> "https://10.0.2.2:13473/api/transport-demo/submit"
            DemoMode.UNTRUSTED -> "https://10.0.2.2:13474/api/transport-demo/submit"
        }
    }

    @Test
    fun debugPolicyOnlyPermitsCleartextForEmulatorHost() {
        val policy = NetworkSecurityPolicy.getInstance()
        assertTrue(policy.isCleartextTrafficPermitted("10.0.2.2"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }

    @Test
    fun androidActuallySendsHttpAndTrustedHttpsAndRejectsUntrustedCertificate() {
        val plain = client.execute(DemoMode.HTTP, username, password)
        assertTrue("HTTP failed: $plain", plain.successful && plain.httpStatus == 200 && plain.payloadStarted)
        val protected = client.execute(DemoMode.HTTPS, username, password)
        assertTrue("HTTPS failed: $protected", protected.successful && protected.httpStatus == 200 && protected.payloadStarted)
        assertNotNull(protected.cipher)
        assertNotNull(protected.certificateSubject)
        val invalid = client.execute(DemoMode.UNTRUSTED, username, password)
        assertTrue("Expected certificate rejection: $invalid", invalid.certificateRejected)
        assertFalse(invalid.payloadStarted)
        assertFalse(invalid.successful)
    }

    @Test
    fun unexpectedlyTrustedServerReceivesTheEnteredDemoPayloadAndShowsWarning() {
        val unexpectedlyTrusted = TransportClient {
            "https://10.0.2.2:13473/api/transport-demo/submit"
        }.execute(DemoMode.UNTRUSTED, "android_unexpected_demo", "unexpected_demo_pass")
        assertTrue("Expected a successful demo submission: $unexpectedlyTrusted",
            unexpectedlyTrusted.successful && unexpectedlyTrusted.httpStatus == 200 && unexpectedlyTrusted.payloadStarted)
        assertTrue(unexpectedlyTrusted.unexpectedTrust)
        assertTrue(unexpectedlyTrusted.outcome.contains("CẢNH BÁO"))
    }

    @Test
    fun emptyFieldsPreventSending() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val usernameInput = activity.findViewById<EditText>(R.id.usernameInput)
                val passwordInput = activity.findViewById<EditText>(R.id.passwordInput)
                val send = activity.findViewById<Button>(R.id.sendButton)
                send.performClick()
                assertTrue(usernameInput.hasFocus())
                assertTrue(usernameInput.error == null)
                usernameInput.setText("android_ui_demo")
                send.performClick()
                assertTrue(passwordInput.hasFocus())
                assertTrue(passwordInput.error == null)
                assertTrue(activity.findViewById<TextView>(R.id.httpMetrics).text.contains("Lần gửi: 0"))
            }
        }
    }

    @Test
    fun certificateSwitchIsEnabledOnlyForHttpsAndKeepsItsSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<Switch>(R.id.httpsSwitch)
                val certificate = activity.findViewById<Switch>(R.id.certificateSwitch)
                toggle.isChecked = false
                assertFalse(certificate.isEnabled)
                toggle.isChecked = true
                assertTrue(certificate.isEnabled)
                certificate.isChecked = true
                toggle.isChecked = false
                assertFalse(certificate.isEnabled)
                toggle.isChecked = true
                assertTrue(certificate.isChecked)
            }
        }
    }

    @Test
    fun screenShowsBothResponsesAndCertificateRejection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, client)
                activity.findViewById<EditText>(R.id.usernameInput).setText("android_ui_demo")
                activity.findViewById<EditText>(R.id.passwordInput).setText("ui_demo_pass_123")
                activity.findViewById<Switch>(R.id.httpsSwitch).isChecked = false
                activity.findViewById<Button>(R.id.sendButton).performClick()
                assertFalse(activity.findViewById<ImageButton>(R.id.resetButton).isEnabled)
            }
            await { textContains(scenario, R.id.httpMetrics, "Lần gửi: 1") }
            scenario.onActivity { activity ->
                val metrics = activity.findViewById<TextView>(R.id.httpMetrics).text
                assertTrue(metrics.contains("Lần gửi: 1") && metrics.contains("HTTP: 200"))
                activity.findViewById<Switch>(R.id.httpsSwitch).isChecked = true
                activity.findViewById<Button>(R.id.sendButton).performClick()
            }
            await { textContains(scenario, R.id.httpsMetrics, "Lần gửi: 1") }
            scenario.onActivity { activity ->
                val metrics = activity.findViewById<TextView>(R.id.httpsMetrics).text
                assertTrue(metrics.contains("Lần gửi: 1") && metrics.contains("HTTP: 200"))
                activity.findViewById<Switch>(R.id.certificateSwitch).isChecked = true
                activity.findViewById<Button>(R.id.sendButton).performClick()
            }
            await { textContains(scenario, R.id.httpsMetrics, "Lần gửi: 2") }
            scenario.onActivity { activity ->
                val metrics = activity.findViewById<TextView>(R.id.httpsMetrics).text
                assertTrue(metrics.contains("Lần gửi: 2") && metrics.contains("HTTP: —"))
                assertTrue(metrics.contains("Chứng chỉ: Không tin cậy"))
                assertTrue(metrics.contains("Nội dung: Chưa ghi"))
                activity.findViewById<LinearLayout>(R.id.httpsCard).performClick()
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            await {
                val dialog = instrumentation.uiAutomation.rootInActiveWindow
                dialog != null && dialog.findAccessibilityNodeInfosByText("Chứng chỉ: Tin cậy").isNotEmpty() &&
                    dialog.findAccessibilityNodeInfosByText("Chứng chỉ: Không tin cậy").isNotEmpty()
            }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val reset = activity.findViewById<ImageButton>(R.id.resetButton)
                assertTrue(reset.isEnabled)
                reset.performClick()
                assertTrue(activity.findViewById<EditText>(R.id.usernameInput).text.isEmpty())
                assertTrue(activity.findViewById<EditText>(R.id.passwordInput).text.isEmpty())
                assertTrue(activity.findViewById<TextView>(R.id.httpMetrics).text.contains("Lần gửi: 0"))
                assertTrue(activity.findViewById<TextView>(R.id.httpsMetrics).text.contains("Lần gửi: 0"))
                assertTrue(activity.findViewById<Switch>(R.id.httpsSwitch).isChecked)
                assertTrue(activity.findViewById<Switch>(R.id.certificateSwitch).isChecked)
            }
        }
    }

    private fun textContains(scenario: ActivityScenario<MainActivity>, id: Int, part: String): Boolean {
        var text = ""
        scenario.onActivity { text = it.findViewById<TextView>(id).text.toString() }
        return text.contains(part)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for App 7 UI")
    }
}

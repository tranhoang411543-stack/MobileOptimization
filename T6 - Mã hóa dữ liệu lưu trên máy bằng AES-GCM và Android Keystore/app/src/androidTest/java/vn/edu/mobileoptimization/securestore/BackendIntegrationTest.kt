package vn.edu.mobileoptimization.securestore

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in: URL must point to the isolated backend/test/app6-emulator-host.cjs fixture. */
@RunWith(AndroidJUnit4::class)
class BackendIntegrationTest {
    private val alias = "app6-e2e-${UUID.randomUUID()}"

    @After
    fun tearDown() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
    }

    @Test
    fun createOpenEditBothModesAndRejectTamperedContent() {
        val base = InstrumentationRegistry.getArguments().getString("app6IntegrationBaseUrl")
        assumeTrue("Chỉ chạy khi truyền URL backend kiểm thử cô lập", !base.isNullOrBlank())
        val baseUrl = checkNotNull(base)
        val api = NotesApi(baseUrl)
        val cipher = NoteCipher(alias)
        val state = NotesState(api, cipher)
        val messages = CopyOnWriteArrayList<String>()
        val suffix = UUID.randomUUID().toString().take(8)
        val plainTitle = "Ban ro $suffix"
        val encryptedTitle = "Ten ma hoa $suffix"
        val restoredTitle = "Ten sau sua $suffix"
        val secretTitle = "Ten bao mat $suffix"
        val clearText = "Noi dung ro $suffix"
        val encryptedText = "Noi dung moi ma hoa $suffix"
        val restoredText = "Noi dung da sua $suffix"
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy", Locale.ROOT)
            .withZone(ZoneId.systemDefault())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                (field.get(activity) as NotesState).listener = null
                field.set(activity, state)
                installTestBiometric(activity, TestBiometricUnlocker())
                val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                state.listener = {
                    state.pendingToast?.let { messages.add(it) }
                    render.invoke(activity)
                }
                state.load(reset = true)
            }
            await { !state.busy && state.total != null }
            create(scenario, state, plainTitle, clearText, false)
            val created = state.items.first { it.title == plainTitle }
            val id = created.id
            assertNotNull(created.createdAt)
            assertNull(created.updatedAt)
            assertEquals(timeFormatter.format(checkNotNull(created.createdAt)), rowTime(scenario, state, id))
            val plain = api.get(id)
            assertFalse(plain.encrypted)
            assertEquals(plainTitle, plain.title)
            assertEquals(clearText, plain.content)

            open(scenario, state, id)
            scenario.onActivity { activity ->
                assertEquals("Xem ghi chú", activity.findViewById<TextView>(R.id.editorTitle).text.toString())
                assertEquals(plainTitle, activity.findViewById<EditText>(R.id.noteTitle).text.toString())
                assertEquals(clearText, activity.findViewById<EditText>(R.id.noteInput).text.toString())
                assertEquals("Lưu ghi chú", activity.findViewById<Button>(R.id.saveButton).text.toString())
                assertFalse(activity.findViewById<Switch>(R.id.encryptionSwitch).isChecked)
            }
            save(scenario, state, id, encryptedTitle, encryptedText, true)
            val encrypted = api.get(id)
            assertTrue(encrypted.encrypted)
            assertEquals(encryptedTitle, encrypted.title)
            assertFalse(encrypted.content.contains(encryptedText))
            assertEquals(encryptedText, cipher.decrypt(encrypted.content, testNoteKey()))
            assertEquals(encryptedTitle, state.items.first { it.id == id }.title)
            val afterEdit = state.items.first { it.id == id }
            assertEquals(created.createdAt, afterEdit.createdAt)
            assertNotNull(afterEdit.updatedAt)
            assertEquals(timeFormatter.format(checkNotNull(afterEdit.updatedAt)), rowTime(scenario, state, id))

            open(scenario, state, id)
            scenario.onActivity {
                assertTrue(it.findViewById<Switch>(R.id.encryptionSwitch).isChecked)
                assertEquals(encryptedTitle, it.findViewById<EditText>(R.id.noteTitle).text.toString())
                assertEquals(encryptedText, it.findViewById<EditText>(R.id.noteInput).text.toString())
            }
            save(scenario, state, id, restoredTitle, restoredText, false)
            val restored = api.get(id)
            assertEquals(id, restored.id)
            assertFalse(restored.encrypted)
            assertEquals(restoredTitle, restored.title)
            assertEquals(restoredText, restored.content)

            create(scenario, state, secretTitle, encryptedText, true)
            val secureId = state.items.first { it.title == secretTitle }.id
            val secure = api.get(secureId)
            assertEquals(secretTitle, secure.title)
            assertEquals(encryptedText, cipher.decrypt(secure.content, testNoteKey()))
            open(scenario, state, secureId)
            scenario.onActivity { it.findViewById<View>(R.id.backButton).performClick() }
            awaitHome(scenario, state)

            val connection = URL("$baseUrl/__test__/tamper/$secureId").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                assertEquals(204, connection.responseCode)
            } finally { connection.disconnect() }
            val beforeY = rowY(scenario, state, secureId)
            tapRow(scenario, state, secureId)
            await { messages.any { it.contains("Không thể giải mã: dữ liệu đã bị sửa") } && !state.busy }
            assertFalse(state.formVisible)
            assertTrue(messages.any { it == "Đang tải ghi chú…" })
            scenario.onActivity {
                assertEquals(View.GONE, it.findViewById<TextView>(R.id.listStatus).visibility)
            }
            assertEquals(beforeY, rowY(scenario, state, secureId))
            assertEquals(restoredText, api.get(id).content)
        }
    }

    private fun create(
        scenario: ActivityScenario<MainActivity>, state: NotesState, title: String, content: String, encrypted: Boolean,
    ) {
        awaitHome(scenario, state)
        scenario.onActivity { it.findViewById<View>(R.id.showCreateButton).performClick() }
        awaitForm(scenario, state)
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.noteTitle).setText(title)
            activity.findViewById<EditText>(R.id.noteInput).setText(content)
            activity.findViewById<Switch>(R.id.encryptionSwitch).isChecked = encrypted
            activity.findViewById<Button>(R.id.saveButton).performClick()
        }
        awaitHome(scenario, state)
        await { state.items.any { it.title == title } }
    }

    private fun save(
        scenario: ActivityScenario<MainActivity>, state: NotesState, id: Int, title: String, content: String, encrypted: Boolean,
    ) {
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.noteTitle).setText(title)
            activity.findViewById<EditText>(R.id.noteInput).setText(content)
            activity.findViewById<Switch>(R.id.encryptionSwitch).isChecked = encrypted
            activity.findViewById<Button>(R.id.saveButton).performClick()
        }
        awaitHome(scenario, state)
        await { state.items.any { it.id == id && it.title == title } }
    }

    private fun open(scenario: ActivityScenario<MainActivity>, state: NotesState, id: Int) {
        tapRow(scenario, state, id)
        awaitForm(scenario, state)
    }

    private fun tapRow(scenario: ActivityScenario<MainActivity>, state: NotesState, id: Int) {
        awaitHome(scenario, state)
        scenario.onActivity { activity ->
            val index = state.items.indexOfFirst { it.id == id }
            check(index >= 0)
            activity.findViewById<LinearLayout>(R.id.noteRows).getChildAt(index).performClick()
        }
    }

    private fun rowY(scenario: ActivityScenario<MainActivity>, state: NotesState, id: Int): Int {
        var y = 0
        scenario.onActivity { activity ->
            val location = IntArray(2)
            val index = state.items.indexOfFirst { it.id == id }
            activity.findViewById<LinearLayout>(R.id.noteRows).getChildAt(index).getLocationOnScreen(location)
            y = location[1]
        }
        return y
    }

    private fun rowTime(scenario: ActivityScenario<MainActivity>, state: NotesState, id: Int): String {
        var value = ""
        scenario.onActivity { activity ->
            val index = state.items.indexOfFirst { it.id == id }
            val row = activity.findViewById<LinearLayout>(R.id.noteRows).getChildAt(index) as LinearLayout
            value = (row.getChildAt(1) as TextView).text.toString()
        }
        return value
    }

    private fun awaitForm(scenario: ActivityScenario<MainActivity>, state: NotesState) = await {
        var ready = false
        scenario.onActivity {
            ready = state.formVisible && !state.busy && it.findViewById<View>(R.id.listScreen).visibility == View.GONE
        }
        ready
    }

    private fun awaitHome(scenario: ActivityScenario<MainActivity>, state: NotesState) = await {
        var ready = false
        scenario.onActivity {
            ready = !state.formVisible && !state.busy && it.findViewById<View>(R.id.createScreen).visibility == View.GONE
        }
        ready
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Hết thời gian chờ luồng App 6")
    }
}

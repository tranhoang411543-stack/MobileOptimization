package vn.edu.mobileoptimization.securestore

import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BiometricFlowTest {
    private val alias = "app6-biometric-flow-${UUID.randomUUID()}"
    private val cipher = NoteCipher(alias)

    @After
    fun tearDown() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
    }

    @Test
    fun createAndOpenRequireAuthenticationButEditingReusesUnlockedKey() {
        val gets = AtomicInteger()
        val creates = AtomicInteger()
        val updates = AtomicInteger()
        val saved = AtomicReference<RemoteNote?>(null)
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?): NotePage {
                val note = saved.get()
                return NotePage(if (note == null) 0 else 1,
                    note?.let { listOf(NoteSummary(it.id, it.title, it.encrypted)) } ?: emptyList(), null)
            }
            override fun get(id: Int): RemoteNote { gets.incrementAndGet(); return checkNotNull(saved.get()) }
            override fun create(note: CreateNoteRequest): NoteSummary {
                creates.incrementAndGet()
                saved.set(RemoteNote(1, note.title, note.content, note.encrypted, "a".repeat(64)))
                return NoteSummary(1, note.title, note.encrypted)
            }
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary {
                updates.incrementAndGet()
                assertEquals("a".repeat(64), note.expectedRevision)
                saved.set(RemoteNote(id, note.title, note.content, note.encrypted, "b".repeat(64)))
                return NoteSummary(id, note.title, note.encrypted)
            }
        }
        val state = NotesState(gateway, cipher)
        val biometric = TestBiometricUnlocker(automatic = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                (field.get(activity) as NotesState).listener = null
                field.set(activity, state)
                installTestBiometric(activity, biometric)
                val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                state.listener = { render.invoke(activity) }
                state.refreshOnReturn()
            }
            await { !state.busy && state.total != null }
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.showCreateButton).performClick()
                assertEquals(0, biometric.requests)
                activity.findViewById<EditText>(R.id.noteTitle).setText("Ghi chú mẫu")
                activity.findViewById<EditText>(R.id.noteInput).setText("Bí mật ban đầu")
                activity.findViewById<Switch>(R.id.encryptionSwitch).isChecked = true
                activity.findViewById<View>(R.id.saveButton).performClick()
                assertEquals(1, biometric.requests)
                assertEquals(0, creates.get())
                biometric.reject()
                assertEquals("Bí mật ban đầu", activity.findViewById<EditText>(R.id.noteInput).text.toString())
                activity.findViewById<View>(R.id.saveButton).performClick()
                assertEquals(2, biometric.requests)
                biometric.succeed()
            }
            await { creates.get() == 1 && !state.busy && !state.formVisible && state.items.isNotEmpty() }
            val created = checkNotNull(saved.get())
            assertFalse(created.content.contains("Bí mật ban đầu"))
            assertEquals("Bí mật ban đầu", cipher.decrypt(created.content, testNoteKey()))

            scenario.onActivity { activity ->
                activity.findViewById<LinearLayout>(R.id.noteRows).getChildAt(0).performClick()
                assertEquals(3, biometric.requests)
                assertEquals(0, gets.get())
                biometric.reject()
                assertFalse(state.formVisible)
                activity.findViewById<LinearLayout>(R.id.noteRows).getChildAt(0).performClick()
                assertEquals(4, biometric.requests)
                assertEquals(0, gets.get())
                biometric.succeed()
            }
            await { state.formVisible && !state.busy }
            scenario.onActivity { activity ->
                assertEquals("Bí mật ban đầu", activity.findViewById<EditText>(R.id.noteInput).text.toString())
                activity.findViewById<EditText>(R.id.noteInput).setText("Bí mật đã sửa")
                activity.findViewById<View>(R.id.saveButton).performClick()
                assertEquals("Không hỏi lại sinh trắc học khi lưu sửa", 4, biometric.requests)
            }
            await { updates.get() == 1 && !state.busy && !state.formVisible }
            assertEquals("Bí mật đã sửa", cipher.decrypt(checkNotNull(saved.get()).content, testNoteKey()))

            await { !state.busy && state.items.isNotEmpty() }
            scenario.onActivity { activity ->
                activity.findViewById<LinearLayout>(R.id.noteRows).getChildAt(0).performClick()
                assertEquals(5, biometric.requests)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(biometric.cancellations > 0)
            assertEquals(1, gets.get())
            assertFalse(state.formVisible)
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Hết thời gian chờ luồng sinh trắc App 6")
    }
}

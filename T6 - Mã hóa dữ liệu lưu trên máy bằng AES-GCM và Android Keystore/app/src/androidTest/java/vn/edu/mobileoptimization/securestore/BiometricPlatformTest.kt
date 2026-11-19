package vn.edu.mobileoptimization.securestore

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Opt-in interactive test; run only with the isolated app6Validation package. */
@RunWith(AndroidJUnit4::class)
class BiometricPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val suffix = UUID.randomUUID().toString()
    private val alias = "app6-biometric-platform-test-$suffix"
    private val fileName = "biometric-platform-test-$suffix.json"

    @After
    fun cleanup() {
        if (!context.packageName.endsWith(".validation")) return
        val base = File(context.filesDir, fileName)
        listOf(base, File(base.path + ".new"), File(base.path + ".bak")).forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
    }

    @Test
    fun actualBiometricUnlocksKeyForCreateOpenAndEdit() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("app6RealBiometric") == "true")
        assumeTrue(context.packageName.endsWith(".validation"))
        val backend = InstrumentationRegistry.getArguments().getString("app6IntegrationBaseUrl")
            ?.takeIf { it.isNotBlank() }?.let { NotesApi(it) }
        val note = AtomicReference<RemoteNote?>(null)
        val creates = AtomicInteger()
        val updates = AtomicInteger()
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?): NotePage {
                if (backend != null) return backend.list(afterId)
                val saved = note.get()
                return NotePage(if (saved == null) 0 else 1,
                    saved?.let { listOf(NoteSummary(it.id, it.title, it.encrypted)) } ?: emptyList(), null)
            }
            override fun get(id: Int) = backend?.get(id) ?: checkNotNull(note.get())
            override fun create(value: CreateNoteRequest): NoteSummary {
                val result = backend?.create(value) ?: NoteSummary(1, value.title, value.encrypted)
                creates.incrementAndGet()
                note.set(RemoteNote(result.id, value.title, value.content, value.encrypted, "a".repeat(64)))
                return result
            }
            override fun update(id: Int, value: UpdateNoteRequest): NoteSummary {
                val result = backend?.update(id, value) ?: NoteSummary(id, value.title, value.encrypted)
                updates.incrementAndGet()
                note.set(RemoteNote(id, value.title, value.content, value.encrypted, "b".repeat(64)))
                return result
            }
        }
        val state = NotesState(gateway)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                (field.get(activity) as NotesState).listener = null
                field.set(activity, state)
                val authField = MainActivity::class.java.getDeclaredField("biometric").apply { isAccessible = true }
                authField.set(activity, PlatformBiometricUnlocker(activity, BiometricKeyVault(context.filesDir, alias, fileName)))
                val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                state.listener = { render.invoke(activity) }
                state.refreshOnReturn()
            }
            await { !state.busy && state.total != null }
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.showCreateButton).performClick()
                activity.findViewById<EditText>(R.id.noteTitle).setText("Kiểm thử sinh trắc")
                activity.findViewById<EditText>(R.id.noteInput).setText("Bí mật lần đầu")
                activity.findViewById<Switch>(R.id.encryptionSwitch).isChecked = true
                activity.findViewById<View>(R.id.saveButton).performClick()
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("app6_stage", "WAIT_CREATE_FINGERPRINT") })
            await { creates.get() == 1 && !state.busy && !state.formVisible && state.items.isNotEmpty() }
            assertFalse(checkNotNull(note.get()).content.contains("Bí mật lần đầu"))
            if (backend != null) assertEquals(note.get()!!.content, backend.get(note.get()!!.id).content)

            scenario.onActivity { it.findViewById<LinearLayout>(R.id.noteRows).getChildAt(0).performClick() }
            instrumentation.sendStatus(2, Bundle().apply { putString("app6_stage", "WAIT_OPEN_FINGERPRINT") })
            await { state.formVisible && !state.busy }
            scenario.onActivity { activity ->
                assertEquals("Bí mật lần đầu", activity.findViewById<EditText>(R.id.noteInput).text.toString())
                activity.findViewById<EditText>(R.id.noteInput).setText("Bí mật sau sửa")
                activity.findViewById<View>(R.id.saveButton).performClick()
            }
            await { updates.get() == 1 && !state.busy && !state.formVisible }
            assertFalse(checkNotNull(note.get()).content.contains("Bí mật sau sửa"))
            if (backend != null) assertEquals(note.get()!!.content, backend.get(note.get()!!.id).content)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Hết thời gian chờ xác thực sinh trắc thực tế")
    }
}

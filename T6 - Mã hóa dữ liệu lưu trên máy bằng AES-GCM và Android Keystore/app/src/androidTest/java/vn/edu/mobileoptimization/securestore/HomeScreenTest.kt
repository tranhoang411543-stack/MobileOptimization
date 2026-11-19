package vn.edu.mobileoptimization.securestore

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @Test
    fun homeShowsCountAndTitlesAndRefreshesWhenReturning() {
        val calls = AtomicInteger()
        val notes = CopyOnWriteArrayList<NoteSummary>()
        val opening = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?): NotePage {
                calls.incrementAndGet()
                return NotePage(notes.size, notes.toList(), null)
            }
            override fun get(id: Int): RemoteNote {
                opening.countDown()
                check(releaseOpen.await(10, TimeUnit.SECONDS))
                return RemoteNote(id, notes.first { it.id == id }.title, "Nội dung", false, revision = "a".repeat(64))
            }
            override fun create(note: CreateNoteRequest): NoteSummary = error("Không tạo ghi chú trong kiểm thử này")
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary = error("Không sửa ghi chú trong kiểm thử này")
        }
        val state = NotesState(gateway)
        val rowLocation = IntArray(2)
        try {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val stateField = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                (stateField.get(activity) as NotesState).listener = null
                stateField.set(activity, state)
                installTestBiometric(activity, TestBiometricUnlocker())
                val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                state.listener = { render.invoke(activity) }
                state.refreshOnReturn()
            }
            await { isReady(scenario, state, calls, 1) }
            scenario.onActivity { activity ->
                val screen = activity.findViewById<LinearLayout>(R.id.listScreen)
                assertEquals((16 * activity.resources.displayMetrics.density).toInt(), screen.paddingLeft)
                assertEquals("Secure Note", (screen.getChildAt(0) as TextView).text.toString())
                assertEquals("Ghi chú (0)", activity.findViewById<TextView>(R.id.noteCount).text.toString())
                assertEquals("Chưa có ghi chú. Hãy tạo ghi chú đầu tiên.",
                    activity.findViewById<TextView>(R.id.listStatus).text.toString())
                activity.findViewById<Button>(R.id.showCreateButton).performClick()
                activity.findViewById<View>(R.id.backButton).performClick()
            }
            await { isReady(scenario, state, calls, 2) }

            notes.add(NoteSummary(1, "Ghi chú thứ nhất", false, Instant.parse("2026-11-17T01:05:00Z")))
            notes.add(NoteSummary(2, "Ghi chú thứ hai", true, Instant.parse("2026-11-17T01:05:00Z"),
                Instant.parse("2026-11-17T17:15:00Z")))
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { isReady(scenario, state, calls, 3) }
            await {
                var laidOut = false
                scenario.onActivity {
                    val rows = it.findViewById<LinearLayout>(R.id.noteRows)
                    laidOut = rows.childCount == 2 && rows.getChildAt(0).height > 0
                }
                laidOut
            }
            scenario.onActivity { activity ->
                assertEquals("Ghi chú (2)", activity.findViewById<TextView>(R.id.noteCount).text.toString())
                assertEquals(View.GONE, activity.findViewById<TextView>(R.id.listStatus).visibility)
                val rows = activity.findViewById<LinearLayout>(R.id.noteRows)
                assertEquals(2, rows.childCount)
                val first = rows.getChildAt(0) as LinearLayout
                assertEquals(2, first.childCount)
                assertEquals("Ghi chú thứ nhất", (first.getChildAt(0) as TextView).text.toString())
                assertEquals(noteTimeLabel(notes[0]), (first.getChildAt(1) as TextView).text.toString())
                assertEquals(noteTimeLabel(notes[1]), ((rows.getChildAt(1) as LinearLayout).getChildAt(1) as TextView).text.toString())
                val timestamp = first.getChildAt(1) as TextView
                assertEquals(0, (timestamp.layoutParams as LinearLayout.LayoutParams).topMargin)
                assertEquals(first.getChildAt(0).bottom, timestamp.top)
                assertEquals((16 * activity.resources.displayMetrics.density).toInt(), first.paddingLeft)
                assertTrue(first.height >= (48 * activity.resources.displayMetrics.density).toInt())
                assertEquals((8 * activity.resources.displayMetrics.density).toInt(),
                    (first.layoutParams as LinearLayout.LayoutParams).bottomMargin)
                first.getLocationOnScreen(rowLocation)
                first.performClick()
            }
            assertTrue(opening.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                assertEquals(View.GONE, activity.findViewById<TextView>(R.id.listStatus).visibility)
                val newLocation = IntArray(2)
                activity.findViewById<LinearLayout>(R.id.noteRows).getChildAt(0).getLocationOnScreen(newLocation)
                assertEquals(rowLocation[1], newLocation[1])
                assertNotNullToast(activity)
            }
            releaseOpen.countDown()
            await {
                var opened = false
                scenario.onActivity {
                    opened = state.formVisible && it.findViewById<View>(R.id.listScreen).visibility == View.GONE
                }
                opened
            }
            scenario.onActivity { activity ->
                assertEquals("Xem ghi chú", activity.findViewById<TextView>(R.id.editorTitle).text.toString())
                assertEquals("Ghi chú thứ nhất", activity.findViewById<EditText>(R.id.noteTitle).text.toString())
                assertEquals("Nội dung", activity.findViewById<EditText>(R.id.noteInput).text.toString())
                assertEquals("Lưu ghi chú", activity.findViewById<Button>(R.id.saveButton).text.toString())
                assertFalse(activity.findViewById<Switch>(R.id.encryptionSwitch).isChecked)
                activity.findViewById<View>(R.id.backButton).performClick()
            }
            await { isReady(scenario, state, calls, 4) }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Button>(R.id.showCreateButton).text.isEmpty())
            }
        }
        } finally {
            releaseOpen.countDown()
        }
    }

    @Test
    fun timestampsUseCreationOrLatestEditInDeviceTimeZone() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val created = Instant.parse("2026-11-17T01:05:00Z")
        val edited = Instant.parse("2026-11-17T17:15:00Z")
        assertEquals("08:05 - 17/11/2026", noteTimeLabel(NoteSummary(1, "Mẫu", false, created), zone))
        assertEquals("00:15 - 18/11/2026", noteTimeLabel(NoteSummary(1, "Mẫu", true, created, edited), zone))
        assertEquals("Chưa có thời gian", noteTimeLabel(NoteSummary(1, "Mẫu", false), zone))
    }

    private fun isReady(
        scenario: ActivityScenario<MainActivity>, state: NotesState, calls: AtomicInteger, expected: Int,
    ): Boolean {
        var ready = false
        scenario.onActivity { ready = !state.busy && state.total != null }
        return ready && calls.get() >= expected
    }

    private fun assertNotNullToast(activity: MainActivity) {
        val field = MainActivity::class.java.getDeclaredField("activeToast").apply { isAccessible = true }
        assertTrue("Phải báo trạng thái chờ bằng Toast", field.get(activity) != null)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Hết thời gian chờ màn hình chính App 6")
    }
}

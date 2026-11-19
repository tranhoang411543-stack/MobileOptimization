package vn.edu.mobileoptimization.securestore

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.MotionEvent
import android.widget.EditText
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureProtectionTest {
    @Test
    fun screenshotHidesNoteWindowWhenSecureFlagIsEnabled() {
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?) = NotePage(0, emptyList(), null)
            override fun get(id: Int): RemoteNote = error("Không mở ghi chú trong kiểm thử")
            override fun create(note: CreateNoteRequest): NoteSummary = error("Không tạo ghi chú trong kiểm thử")
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary = error("Không sửa ghi chú trong kiểm thử")
        }
        val state = NotesState(gateway)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val point = IntArray(2)
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                (field.get(activity) as NotesState).listener = null
                field.set(activity, state)
                val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                state.listener = { render.invoke(activity) }
                state.showCreate()
            }
            awaitScreen(scenario, form = true)
            scenario.onActivity { activity ->
                val note = activity.findViewById<EditText>(R.id.noteInput)
                note.setText("Nội dung giả lập")
                note.getLocationOnScreen(point)
                val offset = (8 * activity.resources.displayMetrics.density).toInt()
                point[0] += offset
                point[1] += offset
                activity.findViewById<Switch>(R.id.captureProtectionSwitch).performClick()
                assertFalse(isSecure(activity))
            }
            SystemClock.sleep(500)
            val off = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            assertNotNull("Cần chụp được cửa sổ khi tắt bảo vệ", off)
            val unprotected = pixel(off!!, point)
            off.recycle()
            assertTrue("Nền ô ghi chú phải hiển thị khi tắt bảo vệ", brightness(unprotected) > 180)

            scenario.onActivity { activity ->
                activity.findViewById<Switch>(R.id.captureProtectionSwitch).performClick()
                assertTrue(isSecure(activity))
            }
            SystemClock.sleep(500)
            val protected = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (protected != null) {
                val protectedPixel = pixel(protected, point)
                protected.recycle()
                assertTrue("Vùng ghi chú phải bị che khi bật FLAG_SECURE", brightness(protectedPixel) < 30)
            }
        }
    }

    @Test
    fun protectsFormsAndKeepsSelectedModeThroughRecreation() {
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?) = NotePage(0, emptyList(), null)
            override fun get(id: Int) = RemoteNote(id, "Mẫu", "Nội dung giả lập", false, revision = "a".repeat(64))
            override fun create(note: CreateNoteRequest): NoteSummary = error("Không tạo ghi chú trong kiểm thử")
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary = error("Không sửa ghi chú trong kiểm thử")
        }
        val state = NotesState(gateway)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                (field.get(activity) as NotesState).listener = null
                field.set(activity, state)
                val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                state.listener = { render.invoke(activity) }
                assertFalse(isSecure(activity))
                state.showCreate()
                assertTrue("Bảo vệ trước khi hiển thị biểu mẫu", isSecure(activity))
            }
            awaitScreen(scenario, form = true)
            scenario.onActivity { activity ->
                val toggle = activity.findViewById<Switch>(R.id.captureProtectionSwitch)
                assertTrue(toggle.isChecked)
                activity.findViewById<EditText>(R.id.noteInput).setText("Nội dung giả lập")
                toggle.performClick()
                assertFalse(isSecure(activity))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<Switch>(R.id.captureProtectionSwitch).isChecked)
                assertFalse(isSecure(activity))
                assertEquals("Nội dung giả lập", activity.findViewById<EditText>(R.id.noteInput).text.toString())
                activity.findViewById<Switch>(R.id.captureProtectionSwitch).performClick()
                assertTrue(isSecure(activity))
                activity.findViewById<View>(R.id.backButton).performClick()
            }
            clickDialogItem("", viewId = "android:id/button1")
            awaitScreen(scenario, form = false)
            await {
                var ready = false
                scenario.onActivity { ready = !state.busy }
                ready
            }
            scenario.onActivity { activity ->
                assertFalse("Danh sách không còn bật FLAG_SECURE sau chuyển màn hình", isSecure(activity))
                state.open(1, testNoteKey())
            }
            awaitScreen(scenario, form = true)
            scenario.onActivity { activity ->
                assertTrue("Ghi chú được mở lại phải mặc định được bảo vệ", isSecure(activity))
                assertTrue(activity.findViewById<Switch>(R.id.captureProtectionSwitch).isChecked)
                assertEquals("Nội dung giả lập", activity.findViewById<EditText>(R.id.noteInput).text.toString())
            }
        }
    }

    private fun isSecure(activity: MainActivity): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    private fun pixel(bitmap: Bitmap, point: IntArray): Int {
        assertTrue("Vị trí thử chụp phải nằm trong màn hình", point[0] in 0 until bitmap.width && point[1] in 0 until bitmap.height)
        return bitmap.getPixel(point[0], point[1])
    }

    private fun brightness(color: Int) = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3

    private fun awaitScreen(scenario: ActivityScenario<MainActivity>, form: Boolean) = await {
        var ready = false
        scenario.onActivity {
            val shown = it.findViewById<View>(if (form) R.id.createScreen else R.id.listScreen)
            val hidden = it.findViewById<View>(if (form) R.id.listScreen else R.id.createScreen)
            ready = shown.visibility == View.VISIBLE && hidden.visibility == View.GONE
        }
        ready
    }

    private fun clickDialogItem(text: String, viewId: String? = null) {
        val bounds = Rect()
        await {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            val node = if (viewId != null) root?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull()
            else root?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString() == text }
            node?.getBoundsInScreen(bounds)
            node != null && !bounds.isEmpty
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        val up = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Hết thời gian chờ kiểm thử FLAG_SECURE")
    }
}

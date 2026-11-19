package vn.edu.mobileoptimization.securestore

import android.animation.ValueAnimator
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class CreateScreenTest {
    @Test
    fun slidesBothWaysAndScrollsLongUnsavedNote() {
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?) = NotePage(0, emptyList(), null)
            override fun get(id: Int): RemoteNote = error("Không mở ghi chú trong kiểm thử này")
            override fun create(note: CreateNoteRequest): NoteSummary = error("Không tạo ghi chú trong kiểm thử này")
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary = error("Không sửa ghi chú trong kiểm thử này")
        }
        val state = NotesState(gateway)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = MainActivity::class.java.getDeclaredField("state").apply { isAccessible = true }
                (field.get(activity) as NotesState).listener = null
                field.set(activity, state)
                val render = MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible = true }
                state.listener = { render.invoke(activity) }
                state.refreshOnReturn()
            }
            await {
                var ready = false
                scenario.onActivity {
                    ready = !state.busy && state.total == 0 && it.findViewById<View>(R.id.rootContainer).width > 0
                }
                ready
            }
            scenario.onActivity { activity ->
                val status = activity.findViewById<TextView>(R.id.listStatus)
                assertEquals(Gravity.CENTER, status.gravity)
                val expectedTextSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 16f, activity.resources.displayMetrics,
                )
                assertEquals(expectedTextSize, status.textSize, 0.5f)
                activity.findViewById<View>(R.id.showCreateButton).performClick()
                if (ValueAnimator.areAnimatorsEnabled()) {
                    assertTrue(activity.findViewById<View>(R.id.createScreen).translationX > 0f)
                }
            }
            awaitScreen(scenario, form = true)
            scenario.onActivity { activity ->
                val back = activity.findViewById<View>(R.id.backButton)
                val iconSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 24f, activity.resources.displayMetrics,
                ).roundToInt()
                assertEquals(iconSize, back.width)
                assertEquals(iconSize, back.height)
                assertNotNull((back.parent as View).touchDelegate)
                back.performClick()
                if (ValueAnimator.areAnimatorsEnabled()) {
                    assertTrue(activity.findViewById<View>(R.id.listScreen).translationX < 0f)
                }
            }
            awaitScreen(scenario, form = false)
            await {
                var ready = false
                scenario.onActivity { ready = !state.busy }
                ready
            }
            scenario.onActivity { it.findViewById<View>(R.id.showCreateButton).performClick() }
            awaitScreen(scenario, form = true)
            scenario.onActivity { activity ->
                val density = activity.resources.displayMetrics.density
                val title = activity.findViewById<EditText>(R.id.noteTitle)
                val note = activity.findViewById<EditText>(R.id.noteInput)
                assertEquals((16 * density).roundToInt(), title.paddingLeft)
                assertEquals((16 * density).roundToInt(), title.paddingRight)
                assertEquals((4 * density).roundToInt(), (title.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin)
                assertEquals((4 * density).roundToInt(), (note.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin)
                assertEquals(6, note.maxLines)
                assertEquals((16 * density).roundToInt(), note.paddingTop)
                note.setText((1..12).joinToString("\n") { "Dòng ghi chú $it" })
                note.setSelection(0)
                note.scrollTo(0, 0)
            }
            await {
                var laidOut = false
                scenario.onActivity {
                    val note = it.findViewById<EditText>(R.id.noteInput)
                    laidOut = (note.layout?.lineCount ?: 0) >= 12 && note.canScrollVertically(1)
                }
                laidOut
            }
            val points = IntArray(3)
            scenario.onActivity { activity ->
                val note = activity.findViewById<EditText>(R.id.noteInput)
                val expectedMax = note.layout.getLineTop(6) + note.compoundPaddingTop + note.compoundPaddingBottom
                assertTrue("Ô nhập vượt quá chiều cao sáu dòng", note.height <= expectedMax + 4)
                val location = IntArray(2)
                note.getLocationOnScreen(location)
                points[0] = location[0] + note.width / 2
                points[1] = location[1] + note.height * 3 / 4
                points[2] = location[1] + note.height / 4
            }
            swipeUp(points[0], points[1], points[2])
            await {
                var scrolled = false
                scenario.onActivity { scrolled = it.findViewById<EditText>(R.id.noteInput).scrollY > 0 }
                scrolled
            }
            scenario.onActivity { it.findViewById<View>(R.id.backButton).performClick() }
            await { dialogMessage()?.contains("chưa được tạo") == true }
            clickDialogButton("android:id/button2")
            await { dialogMessage() == null }
            scenario.onActivity {
                assertTrue(it.findViewById<EditText>(R.id.noteInput).text.toString().contains("Dòng ghi chú 12"))
                it.findViewById<View>(R.id.backButton).performClick()
            }
            await { dialogMessage()?.contains("chưa được tạo") == true }
            clickDialogButton("android:id/button1")
            awaitScreen(scenario, form = false)
        }
    }

    private fun awaitScreen(scenario: ActivityScenario<MainActivity>, form: Boolean) = await {
        var ready = false
        scenario.onActivity {
            val shown = it.findViewById<View>(if (form) R.id.createScreen else R.id.listScreen)
            val hidden = it.findViewById<View>(if (form) R.id.listScreen else R.id.createScreen)
            ready = shown.visibility == View.VISIBLE && hidden.visibility == View.GONE
        }
        ready
    }

    private fun swipeUp(x: Int, fromY: Int, toY: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, fromY.toFloat())
        for (step in 1..6) {
            SystemClock.sleep(35)
            send(MotionEvent.ACTION_MOVE, fromY + (toY - fromY) * step / 6f)
        }
        send(MotionEvent.ACTION_UP, toY.toFloat())
    }

    private fun clickDialogButton(id: String) {
        val button = ownWindow()?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
        checkNotNull(button)
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun dialogMessage(): String? =
        ownWindow()?.findAccessibilityNodeInfosByViewId("android:id/message")?.firstOrNull()?.text?.toString()

    private fun ownWindow(): AccessibilityNodeInfo? {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return null
        return root.takeIf { it.packageName?.toString() == InstrumentationRegistry.getInstrumentation().targetContext.packageName }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Hết thời gian chờ màn hình tạo ghi chú")
    }
}

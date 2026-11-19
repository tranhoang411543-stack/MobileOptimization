package vn.edu.mobileoptimization.securestore

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var state: NotesState
    private lateinit var listScreen: View
    private lateinit var createScreen: View
    private lateinit var count: TextView
    private lateinit var listStatus: TextView
    private lateinit var editorTitle: TextView
    private lateinit var rows: LinearLayout
    private lateinit var titleInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var encryptionSwitch: Switch
    private lateinit var captureProtectionSwitch: Switch
    private lateinit var createButton: Button
    private lateinit var saveButton: Button
    private lateinit var backButton: View
    private lateinit var moreButton: Button
    private var renderedForm: Boolean? = null
    private var screenAnimator: AnimatorSet? = null
    private var activeToast: Toast? = null
    private var toastIsWaiting = false
    private lateinit var biometric: BiometricUnlocker
    private var biometricPending = false
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()
        state = lastNonConfigurationInstance as? NotesState ?: NotesState()
        biometric = PlatformBiometricUnlocker(this)
        listScreen = findViewById(R.id.listScreen)
        createScreen = findViewById(R.id.createScreen)
        count = findViewById(R.id.noteCount)
        listStatus = findViewById(R.id.listStatus)
        editorTitle = findViewById(R.id.editorTitle)
        rows = findViewById(R.id.noteRows)
        titleInput = findViewById(R.id.noteTitle)
        noteInput = findViewById(R.id.noteInput)
        encryptionSwitch = findViewById(R.id.encryptionSwitch)
        captureProtectionSwitch = findViewById(R.id.captureProtectionSwitch)
        createButton = findViewById(R.id.showCreateButton)
        saveButton = findViewById(R.id.saveButton)
        backButton = findViewById(R.id.backButton)
        moreButton = findViewById(R.id.loadMoreButton)

        createButton.setOnClickListener { state.showCreate() }
        saveButton.setOnClickListener { saveNote() }
        backButton.setOnClickListener { leaveForm() }
        moreButton.setOnClickListener { state.load(reset = false) }
        captureProtectionSwitch.setOnCheckedChangeListener { _, enabled ->
            state.captureProtectionEnabled = enabled
            if (state.formVisible) applyCaptureProtection(enabled)
        }
        expandTouchTarget(backButton)
        expandTouchTarget(encryptionSwitch)
        noteInput.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    view.parent.requestDisallowInterceptTouchEvent(
                        view.canScrollVertically(-1) || view.canScrollVertically(1),
                    )
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        state.listener = { render() }
        render()
    }

    override fun onStart() {
        super.onStart()
        started = true
    }

    override fun onResume() {
        super.onResume()
        if (!biometricPending) state.refreshOnReturn()
    }

    override fun onStop() {
        started = false
        cancelBiometric()
        super.onStop()
    }

    override fun onRetainNonConfigurationInstance(): Any {
        if (state.formVisible) {
            state.draftTitle = titleInput.text.toString()
            state.draftContent = noteInput.text.toString()
            state.draftEncrypted = encryptionSwitch.isChecked
        }
        return state
    }

    override fun onDestroy() {
        state.listener = null
        biometric.cancel()
        if (!isChangingConfigurations) state.dispose()
        activeToast?.cancel()
        val running = screenAnimator
        screenAnimator = null
        running?.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (biometricPending) cancelBiometric()
        else if (state.formVisible) leaveForm() else super.onBackPressed()
    }

    private fun saveNote() {
        val title = titleInput.text.toString().trim()
        val content = noteInput.text.toString()
        if (title.isEmpty()) {
            showToast("Hãy nhập tên ghi chú.")
            titleInput.requestFocus()
            return
        }
        if (content.isBlank()) {
            showToast("Hãy nhập nội dung ghi chú.")
            noteInput.requestFocus()
            return
        }
        if (content.toByteArray(Charsets.UTF_8).size > 32 * 1024) {
            showToast("Nội dung tối đa 32 KiB.")
            noteInput.requestFocus()
            return
        }
        val encrypted = encryptionSwitch.isChecked
        if (state.isEditing) {
            state.save(title, content, encrypted)
        } else {
            authenticate("Xác thực để tạo ghi chú", expectForm = true) { key ->
                state.save(title, content, encrypted, key)
            }
        }
    }

    private fun authenticate(title: String, expectForm: Boolean, action: (UnlockedNoteKey) -> Unit) {
        if (!started || state.busy || biometricPending || state.formVisible != expectForm) return
        biometricPending = true
        render()
        biometric.unlock(title, { key ->
            if (!biometricPending || !started || state.busy || state.formVisible != expectForm) {
                key.clear()
                if (biometricPending) {
                    biometricPending = false
                    render()
                }
            } else {
                biometricPending = false
                action(key)
            }
        }, { message ->
            if (biometricPending) {
                biometricPending = false
                render()
                showToast(message)
            }
        })
    }

    private fun cancelBiometric() {
        if (!biometricPending) return
        biometricPending = false
        biometric.cancel()
        render()
    }

    private fun leaveForm() {
        if (state.busy) return
        if (state.hasUnsavedChanges(titleInput.text.toString(), noteInput.text.toString(), encryptionSwitch.isChecked)) {
            val editing = state.isEditing
            AlertDialog.Builder(this).setTitle(if (editing) "Bỏ thay đổi?" else "Bỏ nội dung đang nhập?")
                .setMessage(if (editing) "Những thay đổi chưa lưu sẽ bị mất." else "Ghi chú chưa được tạo sẽ không được lưu.")
                .setNegativeButton("Tiếp tục nhập", null)
                .setPositiveButton(if (editing) "Bỏ thay đổi" else "Bỏ nội dung") { _, _ -> state.cancelCreate() }
                .show()
        } else state.cancelCreate()
    }

    private fun render() {
        if (renderedForm != state.formVisible) {
            val previous = renderedForm
            renderedForm = state.formVisible
            if (state.formVisible) {
                // Protect the window before the note text is populated or made visible.
                applyCaptureProtection(state.captureProtectionEnabled)
                captureProtectionSwitch.isChecked = state.captureProtectionEnabled
                titleInput.setText(state.draftTitle)
                noteInput.setText(state.draftContent)
                encryptionSwitch.isChecked = state.draftEncrypted
                editorTitle.text = if (state.isEditing) "Xem ghi chú" else "Tạo ghi chú"
                saveButton.text = if (state.isEditing) "Lưu ghi chú" else "Tạo ghi chú"
            } else if (previous == true) {
                val focused = currentFocus ?: noteInput
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(focused.windowToken, 0)
                focused.clearFocus()
            }
            showScreen(state.formVisible, animate = previous != null)
        }
        count.text = "Ghi chú (${state.total?.toString() ?: "—"})"
        listStatus.text = state.listStatus
        listStatus.visibility = if (state.listStatus.isEmpty()) View.GONE else View.VISIBLE
        val controlsEnabled = !state.busy && !biometricPending
        createButton.isEnabled = controlsEnabled
        moreButton.isEnabled = controlsEnabled
        moreButton.visibility = if (state.nextCursor != null) View.VISIBLE else View.GONE
        titleInput.isEnabled = controlsEnabled
        noteInput.isEnabled = controlsEnabled
        encryptionSwitch.isEnabled = controlsEnabled
        captureProtectionSwitch.isEnabled = controlsEnabled
        saveButton.isEnabled = controlsEnabled
        backButton.isEnabled = controlsEnabled
        renderRows()

        if (!state.busy && toastIsWaiting) {
            activeToast?.cancel()
            activeToast = null
            toastIsWaiting = false
        }
        state.pendingToast?.let {
            state.pendingToast = null
            showToast(it, waiting = state.busy)
        }
    }

    private fun showToast(message: String, waiting: Boolean = false) {
        activeToast?.cancel()
        toastIsWaiting = waiting
        activeToast = Toast.makeText(this, message, if (waiting) Toast.LENGTH_SHORT else Toast.LENGTH_LONG)
        activeToast?.show()
    }

    private fun applyCaptureProtection(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun showScreen(showForm: Boolean, animate: Boolean) {
        val running = screenAnimator
        screenAnimator = null
        running?.cancel()
        listScreen.translationX = 0f
        createScreen.translationX = 0f
        listScreen.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        createScreen.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        val incoming = if (showForm) createScreen else listScreen
        val outgoing = if (showForm) listScreen else createScreen
        val distance = findViewById<View>(R.id.rootContainer).width.toFloat()
        incoming.visibility = View.VISIBLE
        if (!animate || distance <= 0f || !ValueAnimator.areAnimatorsEnabled()) {
            outgoing.visibility = View.GONE
            if (!showForm) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            return
        }
        outgoing.visibility = View.VISIBLE
        outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val direction = if (showForm) 1f else -1f
        incoming.translationX = direction * distance
        val animator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(outgoing, View.TRANSLATION_X, 0f, -direction * distance),
                ObjectAnimator.ofFloat(incoming, View.TRANSLATION_X, direction * distance, 0f),
            )
            duration = 250
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (screenAnimator !== animation) return
                outgoing.visibility = View.GONE
                outgoing.translationX = 0f
                incoming.translationX = 0f
                outgoing.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                screenAnimator = null
                // Keep protection until the outgoing note screen is no longer visible.
                if (!showForm && !state.formVisible) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        })
        screenAnimator = animator
        animator.start()
    }

    private fun expandTouchTarget(view: View) {
        view.addOnLayoutChangeListener { target, _, _, _, _, _, _, _, _ ->
            val parent = target.parent as? View ?: return@addOnLayoutChangeListener
            val bounds = Rect()
            target.getHitRect(bounds)
            val extraWidth = (dp(48) - bounds.width()).coerceAtLeast(0)
            val extraHeight = (dp(48) - bounds.height()).coerceAtLeast(0)
            bounds.left -= extraWidth / 2
            bounds.right += extraWidth - extraWidth / 2
            bounds.top -= extraHeight / 2
            bounds.bottom += extraHeight - extraHeight / 2
            parent.touchDelegate = TouchDelegate(bounds, target)
        }
    }

    private fun renderRows() {
        rows.removeAllViews()
        state.items.forEach { note ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setBackgroundResource(R.drawable.surface)
                minimumHeight = dp(48)
                isClickable = true
                isFocusable = true
                isEnabled = !state.busy && !biometricPending
                val selectable = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                foreground = getDrawable(selectable.resourceId)
                contentDescription = "${note.title}. ${noteTimeLabel(note)}. Nhấn để xem."
                setOnClickListener {
                    authenticate("Xác thực để xem ghi chú", expectForm = false) { key ->
                        state.open(note.id, key)
                    }
                }
            }
            row.addView(TextView(this).apply {
                text = note.title
                textSize = 16f
                setTextColor(Color.parseColor("#152238"))
            })
            row.addView(TextView(this).apply {
                text = noteTimeLabel(note)
                textSize = 12f
                setTextColor(Color.parseColor("#687C9A"))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            rows.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun applySystemInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val root = findViewById<View>(R.id.rootContainer)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                insets
            }
            root.requestApplyInsets()
        }
    }
}

private val noteTimeFormat = DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy", Locale.ROOT)

internal fun noteTimeLabel(note: NoteSummary, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = note.updatedAt ?: note.createdAt ?: return "Chưa có thời gian"
    return noteTimeFormat.format(time.atZone(zone))
}

package vn.edu.mobileoptimization.cache

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.WindowInsets
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

fun Activity.applySafeInsets() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
        findViewById<View>(R.id.rootContainer).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, keyboard.bottom))
            insets
        }
    }
}

fun Activity.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
fun formatCount(value: Int): String = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value)
fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
    else -> String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
}
fun formatTime(value: Long): String = SimpleDateFormat("HH:mm:ss · dd/MM/yyyy", Locale.forLanguageTag("vi-VN"))
    .format(Date(value))
fun Activity.metricBackground(selected: Boolean, accent: Int) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = dp(8).toFloat()
    setColor(Color.WHITE)
    setStroke(dp(if (selected) 2 else 1), if (selected) accent else Color.rgb(226, 232, 241))
}

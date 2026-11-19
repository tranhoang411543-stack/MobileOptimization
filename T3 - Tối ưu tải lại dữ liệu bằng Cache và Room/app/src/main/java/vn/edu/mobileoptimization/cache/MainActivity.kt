package vn.edu.mobileoptimization.cache

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val app by lazy { application as CacheLabApp }
    private val preferences by lazy { getSharedPreferences("demo-settings", MODE_PRIVATE) }
    private var cacheInfoVersion = 0L
    private var destroyed = false
    private var detailDialog: AlertDialog? = null
    private lateinit var optimizationSwitch: Switch
    private lateinit var offCard: LinearLayout
    private lateinit var onCard: LinearLayout
    private lateinit var offMetrics: TextView
    private lateinit var onMetrics: TextView
    private lateinit var cacheInfo: TextView
    private lateinit var clearCache: Button
    private var seenChange = -1L
    private val syncObserver: (SyncView) -> Unit = { state ->
        if (state.contentChanged != seenChange) { seenChange = state.contentChanged; loadCacheInfo() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySafeInsets()
        optimizationSwitch = findViewById(R.id.optimizationSwitch)
        offCard = findViewById(R.id.offCard)
        onCard = findViewById(R.id.onCard)
        offMetrics = findViewById(R.id.offMetrics)
        onMetrics = findViewById(R.id.onMetrics)
        cacheInfo = findViewById(R.id.cacheInfo)
        clearCache = findViewById(R.id.clearCache)

        optimizationSwitch.isChecked = preferences.getBoolean("optimized", false)
        optimizationSwitch.setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean("optimized", checked).apply()
            app.sync.configure(checked)
            renderMode()
        }
        findViewById<View>(R.id.openCatalog).setOnClickListener {
            startActivity(Intent(this, CatalogActivity::class.java).apply {
                putExtra(CatalogActivity.EXTRA_OPTIMIZED, optimizationSwitch.isChecked)
            })
        }
        findViewById<View>(R.id.resetMetrics).setOnClickListener {
            Measurements.clear()
            app.sync.resetMetrics()
            renderMetrics()
            Toast.makeText(this, "Đã xóa số đo; cache Room vẫn còn.", Toast.LENGTH_SHORT).show()
        }
        clearCache.setOnClickListener { clearStoredCache() }
        offCard.setOnClickListener { showDetails(false) }
        onCard.setOnClickListener { showDetails(true) }
        renderMode()
        renderMetrics()
    }

    override fun onResume() {
        super.onResume()
        renderMetrics()
        loadCacheInfo()
    }

    override fun onStart() { super.onStart(); app.sync.observe(syncObserver) }
    override fun onStop() { app.sync.removeObserver(syncObserver); super.onStop() }

    private fun renderMode() {
        val enabled = optimizationSwitch.isChecked
        offCard.background = metricBackground(!enabled, getColor(R.color.warning))
        onCard.background = metricBackground(enabled, getColor(R.color.success))
    }

    private fun renderMetrics() {
        offMetrics.text = formatMeasurements(Measurements.off)
        onMetrics.text = formatMeasurements(Measurements.on)
        offCard.contentDescription = "Tắt tối ưu. ${offMetrics.text.toString().replace('\n', ' ')}"
        onCard.contentDescription = "Bật tối ưu. ${onMetrics.text.toString().replace('\n', ' ')}"
    }

    private fun formatMeasurements(visits: List<VisitMeasurement>): String {
        val requests = visits.sumOf { it.attempts.size }
        val bytes = visits.sumOf { visit -> visit.attempts.sumOf { it.bytes } }
        val last = visits.lastOrNull()
        val source = last?.firstSource ?: if (last == null) "—" else "Chưa có"
        val duration = last?.firstContentMs?.let { "$it ms" } ?: "—"
        val count = last?.firstCount?.let(::formatCount) ?: "—"
        return "Đã mở: ${visits.size}\nTải danh mục: $requests\nDữ liệu: ${formatBytes(bytes)}" +
            "\n\nLần gần nhất:\nNguồn: $source\nHiển thị: $duration\nSách: $count\n\nXem chi tiết  ›"
    }

    private fun loadCacheInfo() {
        val version = ++cacheInfoVersion
        app.diskExecutor.execute {
            val result = runCatching { app.database.catalogDao().readSnapshot() }
            handler.post {
                if (destroyed || version != cacheInfoVersion) return@post
                cacheInfo.text = result.fold(
                    onSuccess = { snapshot ->
                        if (snapshot == null) "Chưa có dữ liệu trong Room."
                        else {
                            "${formatCount(snapshot.books.size)} sách\nCập nhật: ${formatTime(snapshot.savedAt)}"
                        }
                    },
                    onFailure = { "Không đọc được dữ liệu Room." },
                )
            }
        }
    }

    private fun clearStoredCache() {
        app.sync.invalidateCache()
        cacheInfoVersion++
        clearCache.isEnabled = false
        app.diskExecutor.execute {
            val result = runCatching { app.database.catalogDao().clearSnapshot() }
            handler.post {
                if (destroyed) return@post
                clearCache.isEnabled = true
                Toast.makeText(this, if (result.isSuccess) "Đã xóa cache Room." else "Không xóa được cache Room.",
                    Toast.LENGTH_SHORT).show()
                loadCacheInfo()
                app.sync.requestCheck()
            }
        }
    }

    private fun showDetails(optimized: Boolean) {
        if (detailDialog?.isShowing == true) return
        val records = Measurements.forMode(optimized)
        val details = ListView(this).apply {
            adapter = VisitAdapter(records)
            dividerHeight = dp(1)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(520), (resources.displayMetrics.heightPixels * 0.58f).toInt()),
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (optimized) "Chi tiết · BẬT TỐI ƯU" else "Chi tiết · TẮT TỐI ƯU")
            .setView(details).setPositiveButton("Đóng", null).create()
        detailDialog = dialog
        dialog.setOnDismissListener { detailDialog = null }
        dialog.show()
    }

    private inner class VisitAdapter(private val records: List<VisitMeasurement>) : BaseAdapter() {
        override fun getCount() = max(1, records.size)
        override fun getItem(position: Int): VisitMeasurement? = records.getOrNull(records.lastIndex - position)
        override fun getItemId(position: Int) = (records.size - position).toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(getColor(R.color.ink))
                textSize = 14f
            }
            val visit = getItem(position)
            if (visit == null) {
                view.text = "Chưa có lượt mở danh mục nào."
                return view
            }
            val number = records.size - position
            val source = visit.firstSource ?: "Chưa có nội dung"
            val time = visit.firstContentMs?.let { "$it ms" } ?: "—"
            val bytes = visit.attempts.sumOf { it.bytes }
            val attempts = visit.attempts.mapIndexed { index, attempt ->
                val http = if (attempt.httpMs < 0) "—" else "${attempt.httpMs} ms"
                val db = if (attempt.databaseMs < 0) "—" else String.format(Locale.US, "%.2f ms", attempt.databaseMs)
                val connection = attempt.connectionId?.take(8)?.let { " · kết nối $it, lần ${attempt.connectionNumber}" } ?: ""
                "\n  ${index + 1}. ${attempt.status} · ${formatCount(attempt.books)} sách · ${formatBytes(attempt.bytes)}" +
                    "\n     HTTP: $http · CSDL: $db$connection"
            }.joinToString("")
            view.text = "Lượt #$number · ${formatTime(visit.openedAt)}" +
                "\nNguồn đầu: $source · Hiển thị: $time · Sách: ${visit.firstCount?.let(::formatCount) ?: "—"}" +
                "\nTải danh mục: ${visit.attempts.size} · Dữ liệu: ${formatBytes(bytes)}\n${visit.outcome}$attempts"
            return view
        }
    }

    override fun onDestroy() {
        destroyed = true
        cacheInfoVersion++
        detailDialog?.dismiss()
        super.onDestroy()
    }
}

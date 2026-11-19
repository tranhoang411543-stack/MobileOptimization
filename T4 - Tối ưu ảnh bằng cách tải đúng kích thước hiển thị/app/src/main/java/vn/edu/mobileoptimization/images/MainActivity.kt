package vn.edu.mobileoptimization.images

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max

class MainActivity : Activity() {
    private enum class ImageState { EMPTY, LOADING, READY, FAILED }

    private class ProductHolder(view: View) {
        val image: ImageView = view.findViewById(R.id.productImage)
        val name: TextView = view.findViewById(R.id.productName)
        val price: TextView = view.findViewById(R.id.productPrice)
        val status: TextView = view.findViewById(R.id.imageStatus)
        var token = 0L
        var session = -1L
        var product: Product? = null
        var state = ImageState.EMPTY
        var future: Future<*>? = null
        var owner: ImageMetrics? = null
        var heldBytes = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val catalogExecutor = Executors.newSingleThreadExecutor()
    private val products = mutableListOf<Product>()
    private val holders = mutableSetOf<ProductHolder>()
    private var offMeasurement = ImageMetrics()
    private var onMeasurement = ImageMetrics()
    @Volatile private var generation = 0L
    @Volatile private var stopped = false
    private var catalogLoading = false
    private var catalogError: String? = null
    private var renderPending = false
    private var detailDialog: AlertDialog? = null
    private var detailAdapter: ImageHistoryAdapter? = null
    private lateinit var list: ListView
    private lateinit var adapter: ProductAdapter
    private lateinit var footer: TextView
    private lateinit var optimizationSwitch: Switch
    private lateinit var productHeading: TextView
    private lateinit var offCard: LinearLayout
    private lateinit var onCard: LinearLayout
    private lateinit var offMetrics: TextView
    private lateinit var onMetrics: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemInsets()
        list = findViewById(R.id.productList)
        val header = layoutInflater.inflate(R.layout.image_header, list, false)
        optimizationSwitch = header.findViewById(R.id.optimizationSwitch)
        productHeading = header.findViewById(R.id.productHeading)
        offCard = header.findViewById(R.id.offCard)
        onCard = header.findViewById(R.id.onCard)
        offMetrics = header.findViewById(R.id.offMetrics)
        onMetrics = header.findViewById(R.id.onMetrics)
        footer = TextView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextColor(getColor(R.color.muted))
            textSize = 14f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { if (catalogError != null && !catalogLoading) loadProducts() }
        }
        list.addHeaderView(header, null, false)
        list.addFooterView(footer, null, true)
        adapter = ProductAdapter()
        list.adapter = adapter
        list.setRecyclerListener { view ->
            (view.tag as? ProductHolder)?.let(::releaseHolder)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            if (position == list.headerViewsCount + products.size && catalogError != null && !catalogLoading) {
                loadProducts()
            }
        }

        optimizationSwitch.isChecked = savedInstanceState?.getBoolean("optimized", false) ?: false
        optimizationSwitch.setOnCheckedChangeListener { _, checked -> beginSession(checked) }
        header.findViewById<View>(R.id.resetButton).setOnClickListener {
            offMeasurement = ImageMetrics()
            onMeasurement = ImageMetrics()
            beginSession(optimizationSwitch.isChecked)
        }
        offCard.setOnClickListener { showDetails(false) }
        onCard.setOnClickListener { showDetails(true) }
        beginSession(optimizationSwitch.isChecked)
        loadProducts()
    }

    private fun beginSession(optimized: Boolean) {
        generation++
        holders.forEach(::releaseHolder)
        if (optimized) onMeasurement = ImageMetrics() else offMeasurement = ImageMetrics()
        offCard.background = metricBackground(!optimized, getColor(R.color.warning))
        onCard.background = metricBackground(optimized, getColor(R.color.success))
        adapter.notifyDataSetChanged()
        list.setSelection(0)
        renderMetrics()
    }

    private fun loadProducts() {
        if (catalogLoading) return
        catalogLoading = true
        catalogError = null
        updateFooter()
        catalogExecutor.execute {
            val result = runCatching { ImageApi.products() }
            main.post {
                if (stopped) return@post
                catalogLoading = false
                result.fold(
                    onSuccess = { rows ->
                        products.clear()
                        products.addAll(rows)
                        productHeading.text = "Danh sách sản phẩm (${products.size})"
                        adapter.notifyDataSetChanged()
                    },
                    onFailure = { catalogError = "Không tải được sản phẩm. Kiểm tra backend rồi nhấn để thử lại." },
                )
                updateFooter()
            }
        }
    }

    private fun updateFooter() {
        footer.text = when {
            catalogLoading -> "Đang tải danh sách sản phẩm…"
            catalogError != null -> catalogError
            products.isEmpty() -> "Chưa có sản phẩm."
            else -> "Đã hết danh sách sản phẩm."
        }
        footer.isClickable = catalogError != null
        footer.isFocusable = catalogError != null
        footer.setTextColor(getColor(if (catalogError != null) R.color.error else R.color.muted))
    }

    private inner class ProductAdapter : BaseAdapter() {
        private val priceFormat = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
        override fun getCount() = products.size
        override fun getItem(position: Int) = products[position]
        override fun getItemId(position: Int) = products[position].id.toLong()
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(   R.layout.product_item, parent, false).also { row ->
                val holder = ProductHolder(row)
                row.tag = holder
                holders.add(holder)
                holder.image.setOnClickListener {
                    val product = holder.product
                    if (holder.state == ImageState.FAILED && product != null) startImage(holder, product)
                }
            }
            val holder = view.tag as ProductHolder
            val product = getItem(position)
            holder.name.text = product.name
            holder.price.text = "${priceFormat.format(product.priceVnd)} đ"
            if (holder.product?.id != product.id || holder.session != generation || holder.state == ImageState.EMPTY) {
                startImage(holder, product)
            }
            return view
        }
    }

    private fun startImage(holder: ProductHolder, product: Product) {
        releaseHolder(holder)
        holder.product = product
        holder.session = generation
        holder.state = ImageState.LOADING
        holder.status.visibility = View.VISIBLE
        holder.status.setTextColor(getColor(R.color.muted))
        holder.status.text = "Đang tải ảnh…"
        holder.image.isClickable = false
        holder.image.isFocusable = false
        holder.image.contentDescription = "Ảnh ${product.name}"
        val token = holder.token
        val session = generation
        val optimized = optimizationSwitch.isChecked
        val measurement = measurementFor(optimized)
        val scheduledAt = SystemClock.elapsedRealtimeNanos()
        val targetWidth = holder.image.width.takeIf { it > 0 } ?: dp(IMAGE_DP)
        val targetHeight = holder.image.height.takeIf { it > 0 } ?: dp(IMAGE_DP)

        holder.future = imageExecutor.submit {
            if (stopped || generation != session || Thread.currentThread().isInterrupted) return@submit
            val record = ImageRecord(productName = product.name)
            main.post {
                if (!stopped && measurementFor(optimized) === measurement) {
                    measurement.add(record)
                    scheduleRender()
                }
            }
            val response = ImageApi.image(product.imagePath)
            val networkMs = response.elapsedMs
            val networkBytes = response.receivedBytes
            val connectionId = response.connectionId
            val connectionNumber = response.connectionNumber
            var error = response.error
            var decoded: DecodedImage? = null
            if (error == null && response.payload != null) {
                if (stopped || generation != session || Thread.currentThread().isInterrupted) {
                    error = "Đã hủy"
                } else {
                    try {
                        decoded = BitmapDecoder.decode(response.payload, optimized, targetWidth, targetHeight)
                    } catch (_: OutOfMemoryError) {
                        error = "Không đủ bộ nhớ để giải mã ảnh"
                    } catch (_: Exception) {
                        error = "Không giải mã được ảnh"
                    }
                }
            }
            val image = decoded
            val failure = error
            main.post {
                if (stopped || measurementFor(optimized) !== measurement || record.number == 0) {
                    image?.bitmap?.recycle()
                    return@post
                }
                record.httpMs = networkMs
                record.responseBytes = networkBytes
                record.connectionId = connectionId
                record.connectionNumber = connectionNumber
                measurement.receivedBytes += networkBytes
                if (image != null) {
                    record.originalWidth = image.originalWidth
                    record.originalHeight = image.originalHeight
                    record.decodedWidth = image.bitmap.width
                    record.decodedHeight = image.bitmap.height
                    record.bitmapBytes = image.bitmap.allocationByteCount.toLong()
                    record.sampleSize = image.sampleSize
                    record.decodeMs = image.decodeMs
                    measurement.decodedCount++
                    measurement.totalDecodeMs += image.decodeMs
                    measurement.lastWidth = record.decodedWidth
                    measurement.lastHeight = record.decodedHeight
                    measurement.lastBitmapBytes = record.bitmapBytes
                }
                if (holder.token != token || holder.session != session || generation != session) {
                    record.status = if (image == null) failure ?: "Đã hủy" else "Đã giải mã, không còn hiển thị"
                    image?.bitmap?.recycle() // This bitmap never reached an ImageView.
                } else if (image == null) {
                    holder.future = null
                    holder.state = ImageState.FAILED
                    holder.status.visibility = View.VISIBLE
                    holder.status.setTextColor(getColor(R.color.error))
                    holder.status.text = "${failure ?: "Không tải được ảnh"}. Nhấn ảnh để thử lại."
                    holder.image.isClickable = true
                    holder.image.isFocusable = true
                    holder.image.contentDescription = "Thử tải lại ảnh ${product.name}"
                    record.status = failure ?: "Không tải được ảnh"
                } else {
                    holder.future = null
                    holder.state = ImageState.READY
                    holder.status.visibility = View.GONE
                    holder.owner = measurement
                    holder.heldBytes = record.bitmapBytes
                    measurement.heldBytes += record.bitmapBytes
                    measurement.peakHeldBytes = max(measurement.peakHeldBytes, measurement.heldBytes)
                    holder.image.setImageBitmap(image.bitmap)
                    record.status = "Đã giải mã"
                    measureDisplay(holder, token, session, measurement, record, scheduledAt)
                }
                scheduleRender()
            }
        }
    }

    private fun measureDisplay(
        holder: ProductHolder,
        token: Long,
        session: Long,
        measurement: ImageMetrics,
        record: ImageRecord,
        scheduledAt: Long,
    ) {
        val observer = holder.image.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!observer.isAlive) return true
                if (stopped || holder.token != token || holder.session != session || generation != session) {
                    observer.removeOnPreDrawListener(this)
                    return true
                }
                val visible = Rect()
                if (holder.image.isShown && holder.image.getGlobalVisibleRect(visible)) {
                    observer.removeOnPreDrawListener(this)
                    holder.image.post {
                        if (!stopped && holder.token == token && holder.session == session && generation == session &&
                            measurementFor(optimizationSwitch.isChecked) === measurement && record.displayMs < 0) {
                            record.displayMs = (SystemClock.elapsedRealtimeNanos() - scheduledAt) / 1_000_000.0
                            record.status = "Đã hiển thị"
                            measurement.displayedCount++
                            measurement.totalDisplayMs += record.displayMs
                            scheduleRender()
                        }
                    }
                }
                return true
            }
        })
        holder.image.invalidate()
    }

    private fun releaseHolder(holder: ProductHolder) {
        holder.token++
        holder.future?.cancel(true)
        holder.future = null
        if (holder.heldBytes > 0) {
            holder.owner?.let { it.heldBytes = max(0L, it.heldBytes - holder.heldBytes) }
            holder.heldBytes = 0
            holder.owner = null
            scheduleRender()
        }
        // Once displayed, let Android reclaim the bitmap rather than recycling a potentially in-use frame.
        holder.image.setImageDrawable(null)
        holder.product = null
        holder.state = ImageState.EMPTY
        holder.session = -1L
    }

    private fun scheduleRender() {
        if (renderPending || stopped) return
        renderPending = true
        main.post {
            renderPending = false
            if (!stopped) renderMetrics()
        }
    }

    private fun renderMetrics() {
        offMetrics.text = formatMetrics(offMeasurement)
        onMetrics.text = formatMetrics(onMeasurement)
        offCard.contentDescription = "Tắt tối ưu. ${offMetrics.text.toString().replace('\n', ' ')}"
        onCard.contentDescription = "Bật tối ưu. ${onMetrics.text.toString().replace('\n', ' ')}"
        detailAdapter?.notifyDataSetChanged()
    }

    private fun formatMetrics(value: ImageMetrics): String {
        fun average(total: Double, count: Int) = if (count == 0) "—" else String.format(Locale.US, "%.1f ms", total / count)
        val bitmap = if (value.lastWidth == 0) "—\n" else "${value.lastWidth} × ${value.lastHeight} px\n${formatBytes(value.lastBitmapBytes)}"
        return "Ảnh đã hiện: ${value.displayedCount}\nReq ảnh: ${value.requests}\nĐã nhận: ${formatBytes(value.receivedBytes)}" +
            "\n\nGiải mã TB: ${average(value.totalDecodeMs, value.decodedCount)}" +
            "\nHiển thị TB: ${average(value.totalDisplayMs, value.displayedCount)}" +
            "\n\nBitmap gần nhất:\n$bitmap\n\nĐang giữ: ${formatBytes(value.heldBytes)}" +
            "\nGiữ cao nhất: ${formatBytes(value.peakHeldBytes)}\n\nXem chi tiết  ›"
    }

    private fun showDetails(optimized: Boolean) {
        if (detailDialog?.isShowing == true) return
        val history = ImageHistoryAdapter(measurementFor(optimized))
        val details = ListView(this).apply {
            adapter = history
            dividerHeight = dp(1)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(520), (resources.displayMetrics.heightPixels * 0.58f).toInt()),
            )
        }
        detailAdapter = history
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (optimized) "Chi tiết · BẬT TỐI ƯU" else "Chi tiết · TẮT TỐI ƯU")
            .setView(details).setPositiveButton("Đóng", null).create()
        detailDialog = dialog
        dialog.setOnDismissListener { detailDialog = null; detailAdapter = null }
        dialog.show()
    }

    private inner class ImageHistoryAdapter(private val measurement: ImageMetrics) : BaseAdapter() {
        override fun getCount() = max(1, measurement.records.size)
        override fun getItem(position: Int): ImageRecord? = measurement.records.getOrNull(measurement.records.lastIndex - position)
        override fun getItemId(position: Int) = getItem(position)?.number?.toLong() ?: -1L
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(getColor(R.color.ink))
                textSize = 14f
            }
            val record = getItem(position)
            if (record == null) {
                view.text = "Chưa có request ảnh nào trong phiên đo này."
                return view
            }
            fun duration(value: Double) = if (value < 0) "—" else String.format(Locale.US, "%.1f ms", value)
            val http = if (record.httpMs < 0) "—" else "${record.httpMs} ms"
            val image = if (record.decodedWidth == 0) "Chưa giải mã" else
                "Gốc: ${record.originalWidth} × ${record.originalHeight} px" +
                "\nBitmap: ${record.decodedWidth} × ${record.decodedHeight} px · ${formatBytes(record.bitmapBytes)}" +
                " · inSampleSize ${record.sampleSize}"
            val connection = record.connectionId?.take(8)?.let { "\nKết nối: $it · lần ${record.connectionNumber}" } ?: ""
            view.text = "#${record.number} · ${record.productName}\n${record.status}" +
                "\nHTTP: $http · JPEG: ${formatBytes(record.responseBytes)}\n$image" +
                "\nGiải mã: ${duration(record.decodeMs)} · Hiển thị: ${duration(record.displayMs)}$connection"
            return view
        }
    }

    private fun measurementFor(optimized: Boolean) = if (optimized) onMeasurement else offMeasurement
    private fun formatBytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
        else -> String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun metricBackground(selected: Boolean, accent: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(8).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(if (selected) 2 else 1), if (selected) accent else Color.rgb(226, 232, 241))
    }

    private fun applySystemInsets() {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("optimized", optimizationSwitch.isChecked)
    }

    override fun onDestroy() {
        stopped = true
        generation++
        detailDialog?.dismiss()
        holders.forEach(::releaseHolder)
        imageExecutor.shutdownNow()
        catalogExecutor.shutdownNow()
        super.onDestroy()
    }

    private companion object { const val IMAGE_DP = 96 }
}

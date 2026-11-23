package vn.edu.mobileoptimization.coil

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max

private val Ink = Color(0xFF152238)
private val Muted = Color(0xFF526174)
private val Indigo = Color(0xFF3658D4)
private val Success = Color(0xFF087A62)
private val Warning = Color(0xFFA34C12)
private val Background = Color(0xFFF3F5FA)
private val Border = Color(0xFFE2E8F1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Indigo, background = Background, surface = Color.White, onSurface = Ink),
            ) {
                ImageDemoApp()
            }
        }
    }
}

@Composable
private fun ImageDemoApp() {
    var optimized by rememberSaveable { mutableStateOf(false) }
    var generation by rememberSaveable { mutableIntStateOf(0) }
    val offMeasurement = remember { ImageMetrics() }
    val onMeasurement = remember { ImageMetrics() }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var detailsFor by rememberSaveable { mutableStateOf<Boolean?>(null) }

    Scaffold(containerColor = Background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Image Loading with Coil", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    OutlinedIconButton(
                        onClick = {
                            if (optimized) onMeasurement.apply {
                                records.clear(); requests = 0; receivedBytes = 0L; decodedCount = 0
                                totalDecodeMs = 0.0; displayedCount = 0; totalDisplayMs = 0.0
                                lastWidth = 0; lastHeight = 0; lastBitmapBytes = 0L; heldBytes = 0L; peakHeldBytes = 0L
                            } else offMeasurement.apply {
                                records.clear(); requests = 0; receivedBytes = 0L; decodedCount = 0
                                totalDecodeMs = 0.0; displayedCount = 0; totalDisplayMs = 0.0
                                lastWidth = 0; lastHeight = 0; lastBitmapBytes = 0L; heldBytes = 0L; peakHeldBytes = 0L
                            }
                            generation++
                            refreshTrigger++
                        },
                        modifier = Modifier.size(40.dp),
                    ) { Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Làm lại phép đo") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricCard("TẮT TỐI ƯU", Warning, offMeasurement, !optimized, Modifier.weight(1f)) { detailsFor = false }
                    MetricCard("BẬT TỐI ƯU", Success, onMeasurement, optimized, Modifier.weight(1f)) { detailsFor = true }
                }
            }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bật/Tắt tối ưu Coil", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Switch(
                            checked = optimized,
                            onCheckedChange = { checked ->
                                optimized = checked
                                generation++
                                refreshTrigger++
                            },
                        )
                    }
                    Text(
                        if (optimized) "Coil tự động lấy mẫu ảnh theo kích thước composable, có memory/disk cache."
                        else "Giải mã ảnh đầy đủ bằng BitmapFactory, không dùng Coil (không cache).",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            item {
                Text("Danh sách sản phẩm", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
            item {
                ProductListContent(
                    optimized = optimized,
                    generation = generation,
                    measurement = if (optimized) onMeasurement else offMeasurement,
                    refreshTrigger = refreshTrigger,
                )
            }
        }
    }

    detailsFor?.let { mode ->
        ImageDetailsDialog(
            title = if (mode) "Chi tiết · BẬT TỐI ƯU" else "Chi tiết · TẮT TỐI ƯU",
            measurement = if (mode) onMeasurement else offMeasurement,
            onDismiss = { detailsFor = null },
        )
    }
}

@Composable
private fun ProductListContent(
    optimized: Boolean,
    generation: Int,
    measurement: ImageMetrics,
    refreshTrigger: Int,
) {
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(refreshTrigger) {
        loading = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ImageApi.products() } }
            result.fold(
                onSuccess = { products = it; loading = false },
                onFailure = { error = "Không tải được sản phẩm. Kiểm tra backend."; loading = false }
            )
        }
    }

    when {
        loading -> Text("Đang tải danh sách sản phẩm…", color = Muted, fontSize = 14.sp)
        error != null -> Text(error ?: "", color = Color.Red, fontSize = 14.sp)
        products.isEmpty() -> Text("Chưa có sản phẩm.", color = Muted, fontSize = 14.sp)
        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                products.forEach { product ->
                    ProductRow(product, optimized, generation, measurement)
                }
            }
        }
    }
}

@Composable
private fun ProductRow(product: Product, optimized: Boolean, generation: Int, measurement: ImageMetrics) {
    val currency = remember { NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")) }
    Surface(shape = RoundedCornerShape(8.dp), color = Color.White, border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProductImage(product, optimized, generation, measurement, Modifier.size(96.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(product.name, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("${currency.format(product.priceVnd)} đ", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun ProductImage(
    product: Product,
    optimized: Boolean,
    generation: Int,
    measurement: ImageMetrics,
    modifier: Modifier = Modifier,
) {
    if (optimized) {
        // Chế độ bật: dùng Coil AsyncImage, Coil tự động resize + cache
        CoilProductImage(product, generation, measurement, modifier)
    } else {
        // Chế độ tắt: giải mã thủ công bằng BitmapFactory (không tối ưu)
        ManualProductImage(product, generation, measurement, modifier)
    }
}

@Composable
private fun CoilProductImage(
    product: Product,
    generation: Int,
    measurement: ImageMetrics,
    modifier: Modifier = Modifier,
) {
    val context = LocalPlatformContext.current
    val record = remember(product.id, generation) { ImageRecord(productName = product.name) }
    var loaded by remember(product.id, generation) { mutableStateOf(false) }

    LaunchedEffect(product.id, generation) {
        if (!loaded) {
            measurement.add(record)
            loaded = true
        }
    }

    DisposableEffect(product.id, generation) {
        onDispose {
            if (record.bitmapBytes > 0) {
                measurement.heldBytes = max(0L, measurement.heldBytes - record.bitmapBytes)
            }
        }
    }

    Box(modifier = modifier.background(Color(0xFFF0F0F0), RoundedCornerShape(4.dp))) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(ImageApi.baseUrl() + product.imagePath)
                .crossfade(false)
                .allowHardware(false)
                .listener(
                    onStart = {
                        record.status = "Đang tải…"
                    },
                    onSuccess = { _, result ->
                        // Coil đã xử lý, ghi nhận metrics cơ bản
                        record.status = "Đã giải mã (Coil)"
                        record.decodedWidth = result.image.width
                        record.decodedHeight = result.image.height
                        record.bitmapBytes = (result.image.width * result.image.height * 4).toLong()
                        measurement.decodedCount++
                        measurement.lastWidth = record.decodedWidth
                        measurement.lastHeight = record.decodedHeight
                        measurement.lastBitmapBytes = record.bitmapBytes
                        measurement.heldBytes += record.bitmapBytes
                        measurement.peakHeldBytes = max(measurement.peakHeldBytes, measurement.heldBytes)
                        measurement.displayedCount++
                    },
                    onError = { _, _ ->
                        record.status = "Lỗi tải ảnh"
                    }
                )
                .build(),
            contentDescription = "Ảnh ${product.name}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ManualProductImage(
    product: Product,
    generation: Int,
    measurement: ImageMetrics,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var bitmap by remember(product.id, generation) { mutableStateOf<Bitmap?>(null) }
    var error by remember(product.id, generation) { mutableStateOf(false) }

    LaunchedEffect(product.id, generation) {
        val record = ImageRecord(productName = product.name)
        measurement.add(record)

        scope.launch(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtimeNanos()
            val response = ImageApi.image(product.imagePath)
            record.httpMs = response.elapsedMs
            record.responseBytes = response.receivedBytes
            record.connectionId = response.connectionId
            record.connectionNumber = response.connectionNumber
            measurement.receivedBytes += response.receivedBytes

            if (response.error == null && response.payload != null) {
                val decodeStart = SystemClock.elapsedRealtimeNanos()
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(response.payload, 0, response.payload.size, options)
                record.originalWidth = options.outWidth
                record.originalHeight = options.outHeight

                val decoded = BitmapFactory.decodeByteArray(response.payload, 0, response.payload.size)
                val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStart) / 1_000_000.0

                if (decoded != null) {
                    record.decodedWidth = decoded.width
                    record.decodedHeight = decoded.height
                    record.bitmapBytes = decoded.allocationByteCount.toLong()
                    record.sampleSize = 1
                    record.decodeMs = decodeMs
                    record.status = "Đã giải mã"
                    measurement.decodedCount++
                    measurement.totalDecodeMs += decodeMs
                    measurement.lastWidth = decoded.width
                    measurement.lastHeight = decoded.height
                    measurement.lastBitmapBytes = record.bitmapBytes
                    measurement.heldBytes += record.bitmapBytes
                    measurement.peakHeldBytes = max(measurement.peakHeldBytes, measurement.heldBytes)

                    bitmap = decoded
                    withContext(Dispatchers.Main) {
                        withFrameNanos { }
                        record.displayMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                        measurement.displayedCount++
                        measurement.totalDisplayMs += record.displayMs
                    }
                } else {
                    record.status = "Không giải mã được ảnh"
                    error = true
                }
            } else {
                record.status = response.error ?: "Không tải được ảnh"
                error = true
            }
        }
    }

    DisposableEffect(product.id, generation) {
        onDispose {
            val bytes = bitmap?.allocationByteCount?.toLong() ?: 0L
            if (bytes > 0) {
                measurement.heldBytes = max(0L, measurement.heldBytes - bytes)
            }
            bitmap?.recycle()
        }
    }

    Box(modifier = modifier.background(Color(0xFFF0F0F0), RoundedCornerShape(4.dp))) {
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = "Ảnh ${product.name}",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            error -> Text("Lỗi", color = Color.Red, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            else -> Box(Modifier.fillMaxSize().background(Color(0xFFE0E0E0)))
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color.White, border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun MetricCard(
    title: String,
    accent: Color,
    measurement: ImageMetrics,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp), color = Color.White,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else Border),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(formatMetrics(measurement), color = Ink, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
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

@Composable
private fun ImageDetailsDialog(title: String, measurement: ImageMetrics, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        text = {
            if (measurement.records.isEmpty()) Text("Chưa có request ảnh nào trong phiên đo này.")
            else LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(measurement.records.reversed(), key = { it.number }) { record ->
                    Text(formatRecord(record), color = Ink, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 10.dp))
                    HorizontalDivider(color = Border)
                }
            }
        },
    )
}

private fun formatRecord(record: ImageRecord): String {
    fun duration(value: Double) = if (value < 0) "—" else String.format(Locale.US, "%.1f ms", value)
    val http = if (record.httpMs < 0) "—" else "${record.httpMs} ms"
    val image = if (record.decodedWidth == 0) "Chưa giải mã" else
        "Gốc: ${record.originalWidth} × ${record.originalHeight} px" +
            "\nBitmap: ${record.decodedWidth} × ${record.decodedHeight} px · ${formatBytes(record.bitmapBytes)}" +
            " · inSampleSize ${record.sampleSize}"
    val connection = record.connectionId?.take(8)?.let { "\nKết nối: $it · lần ${record.connectionNumber}" } ?: ""
    return "#${record.number} · ${record.productName}\n${record.status}" +
        "\nHTTP: $http · JPEG: ${formatBytes(record.responseBytes)}\n$image" +
        "\nGiải mã: ${duration(record.decodeMs)} · Hiển thị: ${duration(record.displayMs)}$connection"
}

fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
    else -> String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
}

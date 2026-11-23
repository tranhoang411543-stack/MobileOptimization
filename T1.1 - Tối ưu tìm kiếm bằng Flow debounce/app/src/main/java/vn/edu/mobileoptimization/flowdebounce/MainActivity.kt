package vn.edu.mobileoptimization.flowdebounce

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // Chỉ giữ ấm kết nối khi màn hình hiển thị; tự dừng khi app xuống nền nhờ repeatOnLifecycle.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { keepConnectionWarm(Network.api) }
        }
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Indigo, background = Background, surface = Color.White, onSurface = Ink,
                ),
            ) { SearchScreen() }
        }
    }
}

/** Tương đương ConnectionKeeper của T1, nhưng chỉ cần một vòng lặp coroutine. */
private suspend fun keepConnectionWarm(api: BooksApi, intervalMs: Long = 2_000L) {
    var failed = false
    while (true) {
        try {
            api.keepAlive()
            if (failed) Log.i("KeepAlive", "Đã kết nối lại heartbeat.")
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!failed) Log.w("KeepAlive", "Heartbeat thất bại (${error.javaClass.simpleName}); sẽ thử lại.")
            failed = true
        }
        delay(intervalMs)
    }
}

@Composable
private fun SearchScreen(
    viewModel: SearchViewModel = viewModel { SearchViewModel(RetrofitSearchRepository(Network.api)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var detailsFor by rememberSaveable { mutableStateOf<Boolean?>(null) }

    Scaffold(containerColor = Background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Search with Flow", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    OutlinedIconButton(onClick = viewModel::reset, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Làm lại phép đo")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricCard("TẮT TỐI ƯU", Warning, state.off, selected = !state.optimized,
                        modifier = Modifier.weight(1f)) { detailsFor = false }
                    MetricCard("BẬT TỐI ƯU", Success, state.on, selected = state.optimized,
                        modifier = Modifier.weight(1f)) { detailsFor = true }
                }
            }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bật/Tắt tối ưu Flow debounce", color = Ink, fontSize = 16.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Switch(checked = state.optimized, onCheckedChange = viewModel::setOptimized)
                    }
                    Text("Tìm kiếm", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { if (it.length <= 100) viewModel.onQueryChanged(it) },
                        placeholder = { Text("Ví dụ: Android, Kotlin...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                val count = (state.results as? ResultsState.Books)?.books?.size
                Text(if (count == null) "Kết quả tìm kiếm" else "Kết quả tìm kiếm ($count)",
                    color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            item { Panel { Results(state.results) } }
        }
    }

    detailsFor?.let { optimized ->
        RequestDetailsDialog(
            title = if (optimized) "Chi tiết · BẬT TỐI ƯU" else "Chi tiết · TẮT TỐI ƯU",
            metrics = if (optimized) state.on else state.off,
            onDismiss = { detailsFor = null },
        )
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
    metrics: ModeMetrics,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else Border),
        modifier = modifier.clickable(onClickLabel = "Xem chi tiết yêu cầu", onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(formatMetrics(metrics), color = Ink, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun formatMetrics(metrics: ModeMetrics): String {
    fun duration(value: Long) = if (value < 0) "—" else "$value ms"
    fun millis(value: Double) = String.format(Locale.US, "%.2f ms", value)
    val database = if (metrics.databaseMs < 0) "—" else millis(metrics.databaseMs)
    val totals = metrics.totals()
    val totalHttp = if (totals.httpCount == 0) "—" else "${totals.httpMs} ms"
    val totalDatabase = if (totals.databaseCount == 0) "—" else millis(totals.databaseMs)
    return "Yêu cầu: ${metrics.requests}\nĐã hủy: ${metrics.cancelled}\n\nKết quả: ${duration(metrics.httpMs)}" +
        "\nCSDL: $database\n\nTổng HTTP (${totals.httpCount}): $totalHttp" +
        "\nTổng CSDL (${totals.databaseCount}): $totalDatabase\n\nXem chi tiết  ›"
}

@Composable
private fun Results(results: ResultsState) {
    when (results) {
        ResultsState.Idle -> Text("Thử tìm “Android” hoặc “Kotlin”.", color = Muted, fontSize = 16.sp)
        is ResultsState.Message -> Text(results.text, color = Muted, fontSize = 16.sp)
        is ResultsState.Books -> {
            if (results.books.isEmpty()) {
                Text("Không tìm thấy kết quả phù hợp.", color = Muted, fontSize = 16.sp)
                return
            }
            val currency = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
            results.books.forEachIndexed { index, book ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Border)
                Text(book.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("${book.author}  ·  ${currency.format(book.priceVnd)} đ", color = Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun RequestDetailsDialog(title: String, metrics: ModeMetrics, onDismiss: () -> Unit) {
    val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.forLanguageTag("vi-VN"))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        title = { Text(title) },
        text = {
            if (metrics.records.isEmpty()) {
                Text("Chưa có request nào. Hãy nhập từ khóa để bắt đầu đo.", color = Ink)
            } else {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(metrics.records.asReversed(), key = { it.number }) { record ->
                        val outcome = when (record.state) {
                            RequestState.PENDING -> "Đang thực hiện…"
                            RequestState.CANCELLED -> "Đã hủy sau ${record.httpMs} ms vì có từ khóa mới"
                            RequestState.ERROR -> "${record.error ?: "Không hoàn tất"} · ${record.httpMs} ms"
                            RequestState.SUCCESS -> {
                                val database = String.format(Locale.US, "%.2f ms", record.databaseMs)
                                val shown = if (record.displayed) "Đã hiển thị" else "Kết quả cũ, không ghi đè giao diện"
                                "HTTP: ${record.httpMs} ms · CSDL: $database\nThành công (HTTP ${record.statusCode}) · $shown"
                            }
                        }
                        Text(
                            "#${record.number} · “${record.query}”\n${clock.format(Date(record.startedAtMillis))}\n$outcome",
                            color = Ink, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp),
                        )
                        HorizontalDivider(color = Border)
                    }
                }
            }
        },
    )
}

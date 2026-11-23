package vn.edu.mobileoptimization.paging3

import android.os.Bundle
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import java.text.NumberFormat
import java.util.Locale

private val Ink = Color(0xFF152238)
private val Muted = Color(0xFF526174)
private val Indigo = Color(0xFF3658D4)
private val Success = Color(0xFF087A62)
private val Warning = Color(0xFFA34C12)
private val Error = Color(0xFFAE3535)
private val Background = Color(0xFFF3F5FA)
private val Border = Color(0xFFE2E8F1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Indigo, background = Background, surface = Color.White, onSurface = Ink),
            ) { CatalogScreen() }
        }
    }
}

@Composable
private fun CatalogScreen(
    viewModel: CatalogViewModel = viewModel { CatalogViewModel(RetrofitCatalogRepository(Network.api)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagesFlow by viewModel.pages.collectAsStateWithLifecycle()
    val pagedItems = pagesFlow?.collectAsLazyPagingItems()
    var detailsFor by rememberSaveable { mutableStateOf<Boolean?>(null) }

    val shownCount = if (state.optimized) pagedItems?.itemCount ?: 0 else state.allBooks.size
    LaunchedEffect(state.session, shownCount > 0) {
        if (shownCount > 0) {
            withFrameNanos { } // Chờ khung hình có danh sách được vẽ xong.
            viewModel.onFirstContentDrawn(state.session)
        }
    }

    Scaffold(containerColor = Background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paging 3 Catalog", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    OutlinedIconButton(onClick = viewModel::reset, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Làm lại phép đo")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricCard("TẮT TỐI ƯU", Warning, state.off, !state.optimized, Modifier.weight(1f)) { detailsFor = false }
                    MetricCard("BẬT TỐI ƯU", Success, state.on, state.optimized, Modifier.weight(1f)) { detailsFor = true }
                }
            }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bật/Tắt tối ưu Paging 3", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        Switch(checked = state.optimized, onCheckedChange = viewModel::setOptimized)
                    }
                    Text(
                        if (state.optimized) "Tải 20 sách đầu, cuộn gần cuối sẽ tự tải thêm (keyset afterId)."
                        else "Tải toàn bộ danh sách trong một request.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = viewModel::load, enabled = !state.allLoading,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Tải danh sách") }
                }
            }
            item {
                Text(if (shownCount > 0) "Danh sách sách (${formatNumber(shownCount.toLong())})" else "Danh sách sách",
                    color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            when {
                !state.loadRequested -> item { StatusText("Nhấn “Tải danh sách” để bắt đầu.") }
                state.optimized && pagedItems != null -> pagedBooks(pagedItems)
                else -> allBooks(state, viewModel::retryAll)
            }
        }
    }

    detailsFor?.let { optimized ->
        RequestDetailsDialog(
            title = if (optimized) "Chi tiết · BẬT TỐI ƯU" else "Chi tiết · TẮT TỐI ƯU",
            measurement = state.measurement(optimized),
            onDismiss = { detailsFor = null },
        )
    }
}

private fun LazyListScope.pagedBooks(items: LazyPagingItems<BookDto>) {
    items(count = items.itemCount, key = items.itemKey { it.id }) { index ->
        items[index]?.let { BookRow(it) } // Truy cập items[index] là tín hiệu để Paging tải trang tiếp.
    }
    val refresh = items.loadState.refresh
    val append = items.loadState.append
    item {
        when {
            refresh is LoadState.Loading -> StatusText("Đang tải danh sách…")
            refresh is LoadState.Error -> StatusText("Không tải được danh sách (${refresh.error.message}). Nhấn để thử lại.",
                color = Error, onClick = items::retry)
            append is LoadState.Loading -> StatusText("Đang tải thêm…")
            append is LoadState.Error -> StatusText("Không tải được trang tiếp (${append.error.message}). Nhấn để thử lại.",
                color = Error, onClick = items::retry)
            append.endOfPaginationReached && items.itemCount > 0 -> StatusText("Đã hết danh sách.")
        }
    }
}

private fun LazyListScope.allBooks(state: CatalogUiState, retry: () -> Unit) {
    items(state.allBooks, key = { it.id }) { BookRow(it) }
    item {
        when {
            state.allLoading -> StatusText("Đang tải danh sách…")
            state.allError != null -> StatusText(state.allError, color = Error, onClick = retry)
        }
    }
}

@Composable
private fun BookRow(book: BookDto) {
    val currency = NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN"))
    Surface(shape = RoundedCornerShape(8.dp), color = Color.White, border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("#${book.id} · ${book.title}", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${book.author}  ·  ${currency.format(book.priceVnd)} đ", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color = Muted, onClick: (() -> Unit)? = null) {
    Text(text, color = color, fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp))
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = Color.White, border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun MetricCard(title: String, accent: Color, value: Measurement, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else Border),
        modifier = modifier.clickable(onClickLabel = "Xem chi tiết yêu cầu", onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(formatMeasurement(value), color = Ink, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun formatMeasurement(value: Measurement): String {
    val initial = value.initialBooks?.let { "${formatNumber(it.toLong())} sách" } ?: "—"
    val firstBytes = value.initialBytes?.let(::formatBytes) ?: "—"
    val firstFrame = value.firstFrameMs?.let { "${formatNumber(it)} ms" } ?: "—"
    val before = value.heapBefore?.let(::formatBytes) ?: "—"
    val after = value.heapAfter?.let(::formatBytes) ?: "—"
    return "Sách đầu: $initial\nDữ liệu đầu: $firstBytes\nHiển thị đầu: $firstFrame" +
        "\nHeap Java:\n$before → $after\n\nTổng: ${value.records.size} req" +
        "\n${formatNumber(value.receivedBooks.toLong())} sách · ${formatBytes(value.receivedBytes)}\n\nXem chi tiết  ›"
}

@Composable
private fun RequestDetailsDialog(title: String, measurement: Measurement, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        title = { Text(title) },
        text = {
            if (measurement.records.isEmpty()) {
                Text("Chưa có request nào. Nhấn “Tải danh sách” để bắt đầu đo.", color = Ink)
            } else {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(measurement.records, key = { it.number }) { record ->
                        val target = record.afterId?.let { "afterId = $it" } ?: "Toàn bộ"
                        val database = if (record.databaseMs >= 0) String.format(Locale.US, "%.2f ms", record.databaseMs) else "—"
                        val http = if (record.httpMs >= 0) "${record.httpMs} ms" else "—"
                        val connection = when {
                            record.connectionId == null || record.connectionNumber < 1 -> "Kết nối: không rõ"
                            record.connectionNumber == 1 -> "Kết nối mới (${record.connectionId})"
                            else -> "Dùng lại kết nối (${record.connectionId}, lần ${record.connectionNumber})"
                        }
                        Text(
                            "#${record.number} · $target\n${record.status}\nHTTP: $http · CSDL: $database" +
                                "\n${formatNumber(record.returned.toLong())} sách · ${formatBytes(record.bytes)}\n$connection",
                            color = Ink, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp),
                        )
                        HorizontalDivider(color = Border)
                    }
                }
            }
        },
    )
}

private fun formatNumber(value: Long) = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value)

private fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
    else -> String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
}

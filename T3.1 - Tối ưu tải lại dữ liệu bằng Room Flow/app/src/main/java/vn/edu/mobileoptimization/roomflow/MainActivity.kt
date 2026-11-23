package vn.edu.mobileoptimization.roomflow

import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.map
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
        val container = (application as RoomFlowApp).container
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Indigo, background = Background, surface = Color.White, onSurface = Ink),
            ) {
                val navController = rememberNavController()
                NavHost(navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(container, onOpenCatalog = { optimized -> navController.navigate("catalog/$optimized") })
                    }
                    composable("catalog/{optimized}", arguments = listOf(navArgument("optimized") { type = NavType.BoolType })) { entry ->
                        val optimized = entry.arguments?.getBoolean("optimized") ?: false
                        // ViewModel gắn với mục điều hướng này: bị hủy khi người dùng quay lại.
                        val viewModel = viewModel {
                            CatalogViewModel(
                                optimized = optimized,
                                snapshots = container.dao.observeSnapshot(),
                                dao = container.dao,
                                remote = container.remote,
                                requestSyncCheck = container.sync::requestCheck,
                                measurements = container.measurements,
                                offlineReason = container::offlineReason,
                            )
                        }
                        CatalogScreen(viewModel, optimized, container.sync, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

private data class CacheInfo(val summary: CacheSummary?)

@Composable
private fun HomeScreen(container: AppContainer, onOpenCatalog: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val optimized by container.settings.optimized.collectAsStateWithLifecycle(initialValue = null)
    val visits by container.measurements.visits.collectAsStateWithLifecycle()
    // Room Flow: số sách tự tăng khi đồng bộ ghi vào Room, không cần listener "nội dung đã đổi".
    // null = chưa đọc xong; CacheInfo(null) = Room chưa có cache.
    val cacheInfo by remember { container.dao.observeSummary().map(::CacheInfo) }.collectAsStateWithLifecycle(initialValue = null)
    var clearing by remember { mutableStateOf(false) }
    var detailsFor by rememberSaveable { mutableStateOf<Boolean?>(null) }

    Scaffold(containerColor = Background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reload with Room Flow", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                OutlinedIconButton(
                    onClick = {
                        container.measurements.clear()
                        Toast.makeText(context, "Đã xóa số đo; cache Room vẫn còn.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(40.dp),
                ) { Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Làm lại phép đo") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCard("TẮT TỐI ƯU", Warning, visits.filter { !it.optimized }, optimized == false,
                    Modifier.weight(1f)) { detailsFor = false }
                MetricCard("BẬT TỐI ƯU", Success, visits.filter { it.optimized }, optimized == true,
                    Modifier.weight(1f)) { detailsFor = true }
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bật/Tắt tối ưu Cache", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    Switch(
                        checked = optimized == true,
                        enabled = optimized != null,
                        onCheckedChange = { checked -> scope.launch { container.settings.setOptimized(checked) } },
                    )
                }
                Text(
                    if (optimized == true) "Đọc Room trước, đồng bộ sách mới qua WebSocket khi app đang hiển thị."
                    else "Mỗi lần mở danh mục đều tải từ server, không dùng Room.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = { onOpenCatalog(optimized == true) }, enabled = optimized != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Mở danh mục") }
            }
            Panel {
                Text("Dữ liệu lưu trong Room", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (val summary = cacheInfo?.summary) {
                        null -> if (cacheInfo == null) "Đang kiểm tra cache…" else "Chưa có dữ liệu trong Room."
                        else -> "${formatCount(summary.count)} sách\nCập nhật: ${formatTime(summary.savedAt)}"
                    },
                    color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = {
                        clearing = true
                        container.scope.launch {
                            val result = runCatching { container.dao.clearSnapshot() }
                            clearing = false
                            Toast.makeText(context, if (result.isSuccess) "Đã xóa cache Room." else "Không xóa được cache Room.",
                                Toast.LENGTH_SHORT).show()
                            container.sync.requestCheck()
                        }
                    },
                    enabled = !clearing,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Xóa cache Room") }
            }
        }
    }

    detailsFor?.let { mode ->
        VisitDetailsDialog(
            title = if (mode) "Chi tiết · BẬT TỐI ƯU" else "Chi tiết · TẮT TỐI ƯU",
            visits = visits.filter { it.optimized == mode },
            onDismiss = { detailsFor = null },
        )
    }
}

@Composable
private fun CatalogScreen(viewModel: CatalogViewModel, optimized: Boolean, sync: CatalogSync, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncView by sync.view.collectAsStateWithLifecycle()
    LaunchedEffect(state.hasContent) {
        if (state.hasContent) {
            withFrameNanos { } // Chờ khung hình có danh sách được vẽ xong.
            viewModel.onFirstContentDrawn()
        }
    }

    Scaffold(containerColor = Background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Quay lại") }
                    Text("Danh mục sách", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    OutlinedIconButton(onClick = viewModel::refresh, enabled = !state.busy, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Làm mới")
                    }
                }
            }
            item {
                Panel {
                    Text(state.source, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(state.updated, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Text(state.status, color = Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        if (optimized) "WebSocket: ${syncView.socket} · ${syncView.message}" else "Đồng bộ tự động tắt.",
                        color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item {
                Text(if (state.hasContent) "Danh sách sách (${formatCount(state.books.size)})" else "Danh sách sách",
                    color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            if (state.books.isEmpty()) {
                item { Text(state.empty, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            items(state.books, key = { it.id }) { BookRow(it) }
        }
    }
}

@Composable
private fun BookRow(book: CachedBook) {
    val currency = remember { NumberFormat.getNumberInstance(Locale.forLanguageTag("vi-VN")) }
    Surface(shape = RoundedCornerShape(8.dp), color = Color.White, border = BorderStroke(1.dp, Border),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(book.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${book.author}  ·  ${currency.format(book.priceVnd)} đ", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
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
    visits: List<VisitMeasurement>,
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
            Text(formatMeasurements(visits), color = Ink, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
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

@Composable
private fun VisitDetailsDialog(title: String, visits: List<VisitMeasurement>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
        text = {
            if (visits.isEmpty()) Text("Chưa có lượt mở danh mục nào.")
            else LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(visits.withIndex().reversed().toList(), key = { it.value.id }) { (index, visit) ->
                    Text(formatVisit(index + 1, visit), color = Ink, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 10.dp))
                    HorizontalDivider(color = Border)
                }
            }
        },
    )
}

private fun formatVisit(number: Int, visit: VisitMeasurement): String {
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
    return "Lượt #$number · ${formatTime(visit.openedAt)}" +
        "\nNguồn đầu: $source · Hiển thị: $time · Sách: ${visit.firstCount?.let(::formatCount) ?: "—"}" +
        "\nTải danh mục: ${visit.attempts.size} · Dữ liệu: ${formatBytes(bytes)}\n${visit.outcome}$attempts"
}

fun formatCount(value: Int): String = NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(value)
fun formatBytes(value: Long): String = when {
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
    else -> String.format(Locale.US, "%.1f MiB", value / (1024.0 * 1024.0))
}
fun formatTime(value: Long): String = SimpleDateFormat("HH:mm:ss · dd/MM/yyyy", Locale.forLanguageTag("vi-VN"))
    .format(Date(value))

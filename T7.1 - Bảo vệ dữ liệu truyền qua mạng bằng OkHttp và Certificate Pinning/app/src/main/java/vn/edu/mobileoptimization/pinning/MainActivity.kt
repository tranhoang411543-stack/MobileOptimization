package vn.edu.mobileoptimization.pinning

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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

private data class Entry(val time: Long, val result: DemoResult)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(primary = Indigo, background = Background, surface = Color.White, onSurface = Ink),
            ) {
                TransportScreen()
            }
        }
    }
}

@Composable
private fun TransportScreen() {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var httpsEnabled by rememberSaveable { mutableStateOf(false) }
    var untrustedEnabled by rememberSaveable { mutableStateOf(false) }
    var pinningEnabled by rememberSaveable { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val httpEntries = remember { mutableStateListOf<Entry>() }
    val httpsEntries = remember { mutableStateListOf<Entry>() }
    var httpTotal by remember { mutableStateOf(0) }
    var httpsTotal by remember { mutableStateOf(0) }
    var detailsFor by rememberSaveable { mutableStateOf<DemoMode?>(null) }

    val client = remember { TransportClient() }
    val scope = rememberCoroutineScope()

    fun selectedMode(): DemoMode = when {
        !httpsEnabled -> DemoMode.HTTP
        untrustedEnabled -> DemoMode.UNTRUSTED
        else -> DemoMode.HTTPS
    }

    fun execute(mode: DemoMode) {
        if (busy) return
        when {
            username.isBlank() -> {
                statusMessage = "Vui lòng nhập Username giả lập."
                return
            }
            password.isEmpty() -> {
                statusMessage = "Vui lòng nhập Password giả lập."
                return
            }
        }

        busy = true
        statusMessage = "Đang gửi yêu cầu..."
        scope.launch {
            val result = client.execute(mode, username, password, pinningEnabled)
            busy = false

            val stats = when (mode) {
                DemoMode.HTTP -> httpEntries
                DemoMode.HTTPS, DemoMode.UNTRUSTED -> httpsEntries
            }
            stats.add(Entry(System.currentTimeMillis(), result))
            if (stats.size > 30) stats.removeAt(0)

            when (mode) {
                DemoMode.HTTP -> httpTotal++
                DemoMode.HTTPS, DemoMode.UNTRUSTED -> httpsTotal++
            }

            statusMessage = when {
                result.certificateRejected -> "Đã từ chối chứng chỉ hoặc tên máy chủ. Chưa gửi nội dung."
                result.unexpectedTrust -> result.outcome
                result.successful -> result.outcome
                mode == DemoMode.UNTRUSTED -> "Chưa thể kết luận về chứng chỉ: ${result.outcome}"
                else -> "${result.outcome} Chạy Start.cmd và thử lại."
            }
        }
    }

    fun reset() {
        username = ""
        password = ""
        httpEntries.clear()
        httpsEntries.clear()
        httpTotal = 0
        httpsTotal = 0
        statusMessage = ""
    }

    Scaffold(containerColor = Background) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OkHttp & Pinning", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                OutlinedIconButton(onClick = { reset() }, enabled = !busy, modifier = Modifier.size(40.dp)) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Làm lại")
                }
            }

            Panel {
                Text("Thông tin giả lập", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Chỉ nhập dữ liệu giả lập, không dùng tài khoản thật.", color = Muted, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username giả lập") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password giả lập") },
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            Panel {
                Text("Cấu hình", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Bật/Tắt bảo vệ HTTPS", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = httpsEnabled,
                        onCheckedChange = { httpsEnabled = it; statusMessage = "" },
                        enabled = !busy,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Dùng chứng chỉ không tin cậy", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = untrustedEnabled,
                        onCheckedChange = { untrustedEnabled = it; statusMessage = "" },
                        enabled = !busy && httpsEnabled,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Bật Certificate Pinning", color = Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = pinningEnabled,
                        onCheckedChange = { pinningEnabled = it; statusMessage = "" },
                        enabled = !busy && httpsEnabled,
                    )
                }
                if (pinningEnabled && httpsEnabled) {
                    Text(
                        "Pin SHA-256 public key của CA demo. Chứng chỉ sai sẽ bị chặn.",
                        color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(
                    onClick = { execute(selectedMode()) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) { Text("Gửi dữ liệu giả lập") }
                if (statusMessage.isNotBlank()) {
                    Text(statusMessage, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MetricCard("HTTP", Warning, httpEntries, httpTotal, Modifier.weight(1f)) { detailsFor = DemoMode.HTTP }
                MetricCard("HTTPS", Success, httpsEntries, httpsTotal, Modifier.weight(1f)) { detailsFor = DemoMode.HTTPS }
            }
        }
    }

    detailsFor?.let { mode ->
        val entries = if (mode == DemoMode.HTTP) httpEntries else httpsEntries
        DetailsDialog(
            title = if (mode == DemoMode.HTTP) "Chi tiết · HTTP" else "Chi tiết · HTTPS",
            entries = entries,
            onDismiss = { detailsFor = null },
        )
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun MetricCard(label: String, color: Color, entries: List<Entry>, total: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("$total lần", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp))
            val last = entries.lastOrNull()?.result
            if (last != null) {
                Text(
                    "${if (last.successful) "✓" else "✗"} ${last.elapsedMillis} ms",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
                if (last.pinned) {
                    Text("📌 Pinned", color = Indigo, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailsDialog(title: String, entries: List<Entry>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (entries.isEmpty()) {
                    Text("Chưa có dữ liệu.", color = Muted, fontSize = 14.sp)
                } else {
                    entries.reversed().forEach { entry ->
                        val r = entry.result
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(formatTime(entry.time), color = Muted, fontSize = 12.sp)
                            Text(
                                "${if (r.successful) "✓" else "✗"} ${r.mode.name} · ${r.elapsedMillis} ms · ${r.httpStatus ?: "—"}",
                                color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            )
                            if (r.pinned) {
                                Text("📌 Certificate Pinning bật", color = Indigo, fontSize = 12.sp)
                            }
                            if (r.recordId != null) {
                                Text("Mã: ${r.recordId}", color = Success, fontSize = 12.sp)
                            }
                            if (r.certificateRejected) {
                                Text("Chứng chỉ bị từ chối", color = Warning, fontSize = 12.sp)
                            }
                            Text(r.outcome, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                        HorizontalDivider(color = Border, modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Đóng") } },
    )
}

private fun formatTime(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))

package vn.edu.mobileoptimization.roomflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class SyncView(
    val socket: String = "Chưa kết nối",
    val message: String = "Bật tối ưu để đồng bộ.",
)

/**
 * Bản coroutine của CatalogSync trong T3.
 * - Không cần cờ `running/pending`: kênh CONFLATED gộp các yêu cầu kiểm tra đến khi lần hiện tại xong.
 * - Không cần token `generation` trong bộ nhớ: dừng đồng bộ = hủy coroutine; cache bị xóa giữa chừng thì
 *   transaction kiểm tra `CatalogSyncState` và từ chối ghi.
 * - Không cần báo "nội dung đã đổi" cho màn hình: giao diện quan sát Flow của Room nên tự cập nhật.
 */
class CatalogSync(
    private val dao: CatalogDao,
    private val remote: CatalogRemote,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val _view = MutableStateFlow(SyncView())
    val view: StateFlow<SyncView> = _view.asStateFlow()

    fun requestCheck() { triggers.trySend(Unit) }

    /** Chạy khi app ở màn hình trước và đang bật tối ưu; bị hủy khi một trong hai điều kiện mất. */
    suspend fun run(socketEvents: Flow<SocketEvent>): Nothing = coroutineScope {
        _view.update { it.copy(message = "Đang kiểm tra dữ liệu Room…") }
        launch {
            socketEvents.collect { event ->
                when (event) {
                    SocketEvent.Connecting -> _view.update { it.copy(socket = "Đang kết nối") }
                    SocketEvent.Connected -> { _view.update { it.copy(socket = "Đã kết nối") }; requestCheck() }
                    SocketEvent.CatalogChanged -> {
                        _view.update { it.copy(message = "Nhận tín hiệu thay đổi từ backend.") }
                        requestCheck()
                    }
                    SocketEvent.Lost -> _view.update { it.copy(socket = "Mất kết nối · Đang thử lại") }
                }
            }
        }
        requestCheck()
        var retry: Job? = null
        for (signal in triggers) {
            retry?.cancel()
            _view.update { it.copy(message = "Đang kiểm tra phiên bản…") }
            val message = try {
                synchronize()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                retry = launch { delay(5000); requestCheck() }
                "Chưa đồng bộ: ${error.message ?: "lỗi kết nối"}"
            }
            _view.update { it.copy(message = message) }
        }
        awaitCancellation()
    }

    fun idle(reason: String) = _view.update { SyncView(socket = "Đã dừng", message = reason) }

    internal suspend fun synchronize(): String {
        var local = dao.ensureSyncState() ?: return "Chưa có cache. Mở danh mục để tải lần đầu."
        val remoteHead = remote.state()

        if (local.epoch.isNotEmpty() && (local.epoch != remoteHead.epoch || local.cursor > remoteHead.version)) {
            // Backend được tạo lại: giữ cache cũ cho tới khi có bản thay thế đầy đủ.
            val base = remote.fetchCatalog()
            val baseBooks = base.books ?: throw IOException(base.error ?: "Không tải được danh mục gốc")
            val all = mutableListOf<CachedBook>()
            var cursor = 0L
            do {
                val page = remote.changes(remoteHead.epoch, cursor)
                all += page.books
                if (all.size > 50_000) throw IOException("Danh sách đồng bộ vượt giới hạn demo")
                cursor = page.cursor
            } while (page.hasMore)
            if (cursor < remoteHead.version) throw IOException("Dữ liệu thay đổi chưa đầy đủ")
            if (!dao.resetSyncSnapshot(local, remoteHead.epoch, cursor, baseBooks + all, now())) {
                return "Cache đã thay đổi; sẽ kiểm tra lại."
            }
            return "Đã khôi phục cache và đồng bộ ${all.size} sách."
        }

        var updated = 0
        var cursor = if (local.epoch.isEmpty()) 0L else local.cursor
        if (remoteHead.version > cursor) {
            do {
                val page = remote.changes(remoteHead.epoch, cursor)
                if (!dao.applySyncPage(local, remoteHead.epoch, page.cursor, page.books, now())) {
                    return "Cache đã thay đổi; sẽ kiểm tra lại."
                }
                local = local.copy(epoch = remoteHead.epoch, cursor = page.cursor)
                cursor = page.cursor
                updated += page.books.size
            } while (page.hasMore)
            if (cursor < remoteHead.version) throw IOException("Dữ liệu thay đổi chưa đầy đủ")
        } else if (local.epoch.isEmpty()) {
            if (!dao.applySyncPage(local, remoteHead.epoch, 0, emptyList(), now())) {
                return "Cache đã thay đổi; sẽ kiểm tra lại."
            }
        }
        return if (updated > 0) "Đã đồng bộ $updated sách mới vào Room." else "Không có sách mới từ script."
    }
}

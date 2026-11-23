package vn.edu.mobileoptimization.roomflow

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NetworkAttempt(
    val status: String = "Đang thực hiện…",
    val httpMs: Long = -1,
    val bytes: Long = 0,
    val books: Int = 0,
    val databaseMs: Double = -1.0,
    val connectionId: String? = null,
    val connectionNumber: Int = 0,
)

data class VisitMeasurement(
    val id: Long,
    val optimized: Boolean,
    val openedAt: Long,
    val attempts: List<NetworkAttempt> = emptyList(),
    val firstSource: String? = null,
    val firstContentMs: Long? = null,
    val firstCount: Int? = null,
    val outcome: String = "Đang mở…",
)

/** Số đo bất biến trong StateFlow: màn hình chính tự vẽ lại khi lượt mở danh mục cập nhật. */
class MeasurementStore {
    private val _visits = MutableStateFlow<List<VisitMeasurement>>(emptyList())
    val visits: StateFlow<List<VisitMeasurement>> = _visits.asStateFlow()
    private var nextId = 1L

    fun start(optimized: Boolean): Long {
        val id = nextId++
        _visits.update { it + VisitMeasurement(id, optimized, openedAt = System.currentTimeMillis()) }
        return id
    }

    fun update(id: Long, transform: (VisitMeasurement) -> VisitMeasurement) =
        _visits.update { list -> list.map { if (it.id == id) transform(it) else it } }

    fun clear() = _visits.update { emptyList() }
}

private val Context.dataStore by preferencesDataStore(name = "demo-settings")
private val OPTIMIZED = booleanPreferencesKey("optimized")

/** DataStore thay cho SharedPreferences: đọc/ghi bất đồng bộ, phát Flow khi giá trị đổi. */
class Settings(private val context: Context) {
    val optimized: Flow<Boolean> = context.dataStore.data.map { it[OPTIMIZED] ?: false }
    suspend fun setOptimized(value: Boolean) { context.dataStore.edit { it[OPTIMIZED] = value } }
}

class AppContainer(private val context: Context) {
    val scope = MainScope()
    val database: CatalogDatabase = Room.databaseBuilder(context, CatalogDatabase::class.java, "catalog-cache.db").build()
    val dao: CatalogDao = database.catalogDao()
    val remote: CatalogRemote = RetrofitCatalogRemote(Network.service)
    val sync = CatalogSync(dao, remote)
    val settings = Settings(context)
    val measurements = MeasurementStore()

    fun offlineReason(): String? = context.offlineReason()

    /**
     * Đồng bộ chỉ chạy khi app ở màn hình trước VÀ bật tối ưu.
     * `collectLatest` hủy lần chạy cũ (đóng WebSocket, hủy request) khi điều kiện thay đổi.
     * Thay cho việc tự đếm Activity trong ActivityLifecycleCallbacks ở T3.
     */
    fun startSyncSupervisor() = scope.launch {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
        combine(foreground, settings.optimized) { visible, optimized -> visible to optimized }
            .distinctUntilChanged()
            .collectLatest { (visible, optimized) ->
                if (visible && optimized) sync.run(syncSocketEvents(Network.client))
                else sync.idle(if (!optimized) "Đang tắt tối ưu." else "App không ở màn hình trước.")
            }
    }
}

class RoomFlowApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startSyncSupervisor()
    }
}

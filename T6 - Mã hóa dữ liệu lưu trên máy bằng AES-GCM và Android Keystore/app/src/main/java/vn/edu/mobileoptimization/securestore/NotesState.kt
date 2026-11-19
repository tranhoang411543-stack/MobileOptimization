package vn.edu.mobileoptimization.securestore

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.AEADBadTagException

private object NoteWorker {
    val executor = Executors.newSingleThreadExecutor()
}

private data class OriginalNote(
    val id: Int, val title: String, val content: String, val encrypted: Boolean, val revision: String,
)
private data class PreparedNote(
    val title: String, val clearContent: String, val encrypted: Boolean, val original: OriginalNote?,
    val create: CreateNoteRequest?, val update: UpdateNoteRequest?,
)

/** Retained during configuration changes; network and Keystore work stays off the UI thread. */
internal class NotesState(
    private val api: NotesGateway = NotesApi(),
    private val cipher: NoteCipher = NoteCipher("vn.edu.mobileoptimization.securestore.aesgcm.v1"),
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var prepared: PreparedNote? = null
    private var original: OriginalNote? = null
    private var sessionKey: UnlockedNoteKey? = null
    private var disposed = false
    private var refreshPending = false
    private var toastAfterRefresh: String? = null
    var listener: (() -> Unit)? = null
    var formVisible = false
        private set
    val isEditing: Boolean get() = original != null
    var busy = false
        private set
    var total: Int? = null
        private set
    var nextCursor: Int? = null
        private set
    val items = mutableListOf<NoteSummary>()
    // This is the empty state only. Loading and errors are delivered as Toasts.
    var listStatus = ""
        private set
    var draftTitle = ""
    var draftContent = ""
    var draftEncrypted = false
    var pendingToast: String? = null
    var captureProtectionEnabled = true

    fun showCreate() {
        if (busy) return
        clearSession()
        original = null
        prepared = null
        draftTitle = ""
        draftContent = ""
        draftEncrypted = false
        captureProtectionEnabled = true
        formVisible = true
        notifyChanged()
    }

    fun hasUnsavedChanges(title: String, content: String, encrypted: Boolean): Boolean {
        val saved = original
        return if (saved == null) title.isNotBlank() || content.isNotBlank()
        else title != saved.title || content != saved.content || encrypted != saved.encrypted
    }

    fun cancelCreate() {
        if (busy) return
        clearSession()
        formVisible = false
        original = null
        prepared = null
        draftTitle = ""
        draftContent = ""
        draftEncrypted = false
        notifyChanged()
        refreshOnReturn()
    }

    fun refreshOnReturn() {
        if (formVisible) return
        if (busy) {
            refreshPending = true
            return
        }
        refreshPending = false
        load(reset = true)
    }

    fun load(reset: Boolean, announce: Boolean = true) {
        if (busy || (!reset && nextCursor == null)) return
        val cursor = if (reset) null else nextCursor
        busy = true
        if (announce) pendingToast = if (reset) "Đang tải danh sách…" else "Đang tải thêm ghi chú…"
        notifyChanged()
        NoteWorker.executor.execute {
            val result = runCatching { api.list(cursor) }
            mainHandler.post {
                busy = false
                if (disposed) return@post
                result.fold(onSuccess = { page ->
                    if (reset) items.clear()
                    items.addAll(page.items)
                    total = page.total
                    nextCursor = page.nextCursor
                    listStatus = if (page.total == 0) "Chưa có ghi chú. Hãy tạo ghi chú đầu tiên." else ""
                    pendingToast = if (page.items.any { it.createdAt == null }) {
                        val warning = "Backend chưa cung cấp thời gian. Hãy khởi động lại backend."
                        toastAfterRefresh?.let { "$it $warning" } ?: warning
                    } else toastAfterRefresh
                }, onFailure = {
                    pendingToast = if (toastAfterRefresh != null) {
                        "${toastAfterRefresh} Không tải được danh sách."
                    } else "Không tải được danh sách. Kiểm tra backend rồi quay lại màn hình này."
                })
                toastAfterRefresh = null
                notifyChanged()
                refreshIfPending()
            }
        }
    }

    fun save(title: String, content: String, encrypted: Boolean, newKey: UnlockedNoteKey? = null) {
        if (busy || disposed) { newKey?.clear(); return }
        val editing = original
        if (editing != null) newKey?.clear()
        val activeKey = if (editing == null) newKey else sessionKey
        if (activeKey == null) {
            newKey?.clear()
            pendingToast = "Cần xác thực sinh trắc học trước khi lưu ghi chú."
            notifyChanged()
            return
        }
        val previous = prepared?.takeIf {
            it.title == title && it.clearContent == content && it.encrypted == encrypted && it.original == editing
        }
        busy = true
        pendingToast = if (editing == null) "Đang tạo ghi chú…" else "Đang lưu ghi chú…"
        notifyChanged()
        NoteWorker.executor.execute {
            var candidate = previous
            val result = runCatching {
                if (candidate == null) {
                    val storedContent = if (encrypted) cipher.encrypt(content, activeKey) else content
                    candidate = if (editing == null) {
                        PreparedNote(title, content, encrypted, null,
                            CreateNoteRequest(UUID.randomUUID().toString(), title, storedContent, encrypted), null)
                    } else {
                        PreparedNote(title, content, encrypted, editing, null,
                            UpdateNoteRequest(editing.revision, title, storedContent, encrypted))
                    }
                }
                if (editing == null) api.create(candidate!!.create!!)
                else api.update(editing.id, candidate!!.update!!)
            }
            mainHandler.post {
                busy = false
                if (disposed) {
                    activeKey.clear()
                    sessionKey = null
                    return@post
                }
                result.fold(onSuccess = {
                    activeKey.clear()
                    sessionKey = null
                    prepared = null
                    original = null
                    draftTitle = ""
                    draftContent = ""
                    draftEncrypted = false
                    formVisible = false
                    pendingToast = null
                    toastAfterRefresh = if (editing == null) "Đã tạo ghi chú." else "Đã lưu ghi chú."
                    notifyChanged()
                    refreshOnReturn()
                }, onFailure = { error ->
                    if (editing == null) activeKey.clear()
                    prepared = candidate
                    pendingToast = when (error) {
                        is ApiException -> when (error.statusCode) {
                            404 -> "Không tìm thấy ghi chú. Hãy mở lại danh sách."
                            409 -> "Ghi chú đã thay đổi. Hãy quay lại và mở lại trước khi lưu."
                            else -> "Backend chưa chấp nhận ghi chú (HTTP ${error.statusCode})."
                        }
                        is GeneralSecurityException -> "Không thể mã hóa ghi chú trên thiết bị này."
                        else -> "Chưa xác nhận được kết quả. Kiểm tra backend rồi thử lại cùng nội dung."
                    }
                    notifyChanged()
                })
            }
        }
    }

    fun open(id: Int, key: UnlockedNoteKey) {
        if (busy || disposed) { key.clear(); return }
        busy = true
        pendingToast = "Đang tải ghi chú…"
        notifyChanged()
        NoteWorker.executor.execute {
            val result = runCatching {
                val note = api.get(id)
                val content = if (note.encrypted) cipher.decrypt(note.content, key) else note.content
                OriginalNote(note.id, note.title, content, note.encrypted, note.revision)
            }
            mainHandler.post {
                busy = false
                if (disposed) { key.clear(); return@post }
                result.fold(onSuccess = { loaded ->
                    clearSession()
                    sessionKey = key
                    original = loaded
                    prepared = null
                    draftTitle = loaded.title
                    draftContent = loaded.content
                    draftEncrypted = loaded.encrypted
                    captureProtectionEnabled = true
                    formVisible = true
                    pendingToast = null
                }, onFailure = { error ->
                    key.clear()
                    pendingToast = when (error) {
                        is AEADBadTagException -> "Không thể giải mã: dữ liệu đã bị sửa hoặc khóa không khớp."
                        is KeyUnavailableException -> "Không thể giải mã: thiết bị này không có khóa của ghi chú."
                        is InvalidEncryptedNoteException -> "Không thể giải mã: dữ liệu mã hóa không hợp lệ."
                        is GeneralSecurityException -> "Không thể giải mã ghi chú trên thiết bị này."
                        is BackendOutdatedException -> "Backend chưa hỗ trợ sửa ghi chú. Hãy khởi động lại backend."
                        is ApiException -> if (error.statusCode == 404) {
                            "Không tìm thấy ghi chú. Danh sách có thể đã thay đổi."
                        } else "Không tải được ghi chú (HTTP ${error.statusCode})."
                        is IOException -> "Không tải được ghi chú. Kiểm tra backend và thử lại."
                        else -> "Không đọc được phản hồi từ backend."
                    }
                })
                notifyChanged()
                refreshIfPending()
            }
        }
    }

    private fun refreshIfPending() {
        if (refreshPending && !formVisible) {
            refreshPending = false
            // Do not replace an error Toast with a background refresh notification.
            load(reset = true, announce = false)
        }
    }

    fun dispose() {
        disposed = true
        listener = null
        if (!busy) clearSession()
    }

    private fun clearSession() {
        sessionKey?.clear()
        sessionKey = null
    }

    private fun notifyChanged() { listener?.invoke() }
}

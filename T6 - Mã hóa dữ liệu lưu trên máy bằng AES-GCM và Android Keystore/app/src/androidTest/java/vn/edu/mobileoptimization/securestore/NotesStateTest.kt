package vn.edu.mobileoptimization.securestore

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NotesStateTest {
    private val alias = "app6-state-test-${UUID.randomUUID()}"
    private val cipher = NoteCipher(alias)

    @After
    fun tearDown() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
    }

    @Test
    fun readingTamperedBackendContentProducesToastAndNoDetail() {
        val encrypted = JSONObject(cipher.encrypt("Nội dung gốc"))
        val bytes = Base64.decode(encrypted.getString("ciphertext_and_tag"), Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        encrypted.put("ciphertext_and_tag", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?) = NotePage(1, listOf(NoteSummary(1, "Mẫu", true)), null)
            override fun get(id: Int) = RemoteNote(id, "Mẫu", encrypted.toString(), true)
            override fun create(note: CreateNoteRequest): NoteSummary = error("Không tạo trong kiểm thử này")
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary = error("Không sửa trong kiểm thử này")
        }
        val state = NotesState(gateway, cipher)
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            state.listener = { if (!state.busy) done.countDown() }
            state.open(1, testNoteKey())
        }
        assertTrue("Không nhận được kết quả", done.await(10, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(state.pendingToast!!.contains("Không thể giải mã"))
            assertFalse(state.formVisible)
            assertEquals("", state.listStatus)
        }
    }

    @Test
    fun returnDuringLoadingRefreshesAgainAfterTheCurrentRequest() {
        val calls = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val refreshed = CountDownLatch(1)
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?): NotePage {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS))
                    return NotePage(0, emptyList(), null)
                }
                return NotePage(1, listOf(NoteSummary(1, "Ghi chú mới", false)), null)
            }
            override fun get(id: Int): RemoteNote = error("Không mở ghi chú trong kiểm thử này")
            override fun create(note: CreateNoteRequest): NoteSummary = error("Không tạo ghi chú trong kiểm thử này")
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary = error("Không sửa ghi chú trong kiểm thử này")
        }
        val state = NotesState(gateway, cipher)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                state.listener = { if (!state.busy && state.total == 1) refreshed.countDown() }
                state.refreshOnReturn()
            }
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { state.refreshOnReturn() }
            releaseFirst.countDown()
            assertTrue(refreshed.await(10, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals("Ghi chú mới", state.items.single().title)
            }
        } finally {
            releaseFirst.countDown()
        }
    }

    @Test
    fun backgroundRefreshDoesNotReplaceOpenErrorToast() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refreshed = CountDownLatch(1)
        val messages = CopyOnWriteArrayList<String>()
        val listCalls = AtomicInteger()
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?): NotePage {
                listCalls.incrementAndGet()
                return NotePage(0, emptyList(), null)
            }
            override fun get(id: Int): RemoteNote {
                opened.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                throw IOException("Không kết nối được")
            }
            override fun create(note: CreateNoteRequest): NoteSummary = error("Không tạo trong kiểm thử này")
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary = error("Không sửa trong kiểm thử này")
        }
        val state = NotesState(gateway, cipher)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                state.listener = {
                    state.pendingToast?.let { message ->
                        messages.add(message)
                        state.pendingToast = null
                    }
                    if (!state.busy && listCalls.get() > 0) refreshed.countDown()
                }
                state.open(1, testNoteKey())
            }
            assertTrue(opened.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { state.refreshOnReturn() }
            release.countDown()
            assertTrue(refreshed.await(10, TimeUnit.SECONDS))
            assertEquals("Không tải được ghi chú. Kiểm tra backend và thử lại.", messages.last())
            assertFalse(messages.any { it == "Đang tải danh sách…" })
        } finally {
            release.countDown()
        }
    }

    @Test
    fun encryptedEditRetriesSamePayloadAndKeepsTitleReadable() {
        val sent = CopyOnWriteArrayList<UpdateNoteRequest>()
        val opened = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val refreshed = CountDownLatch(1)
        val saved = AtomicReference<UpdateNoteRequest?>(null)
        val gateway = object : NotesGateway {
            override fun list(afterId: Int?): NotePage {
                val note = saved.get() ?: return NotePage(0, emptyList(), null)
                return NotePage(1, listOf(NoteSummary(1, note.title, note.encrypted)), null)
            }
            override fun get(id: Int) = RemoteNote(id, "Tên cũ", "Nội dung cũ", false, revision = "a".repeat(64))
            override fun create(note: CreateNoteRequest): NoteSummary = error("Không tạo trong kiểm thử này")
            override fun update(id: Int, note: UpdateNoteRequest): NoteSummary {
                sent.add(note)
                if (sent.size == 1) throw IOException("Mất phản hồi")
                saved.set(note)
                return NoteSummary(id, note.title, note.encrypted)
            }
        }
        val state = NotesState(gateway, cipher)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            state.listener = {
                if (!state.busy && state.formVisible && state.isEditing && sent.isEmpty()) opened.countDown()
                if (!state.busy && state.formVisible && sent.size == 1 && state.pendingToast?.contains("Chưa xác nhận") == true) failed.countDown()
                if (!state.busy && !state.formVisible && state.items.singleOrNull()?.title == "Tên mới") refreshed.countDown()
            }
            state.open(1, testNoteKey())
        }
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertEquals("Tên cũ", state.draftTitle)
            assertEquals("Nội dung cũ", state.draftContent)
            state.save("Tên mới", "Nội dung mới", true)
        }
        assertTrue(failed.await(10, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(state.formVisible)
            state.save("Tên mới", "Nội dung mới", true)
        }
        assertTrue(refreshed.await(10, TimeUnit.SECONDS))
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        assertEquals("Tên mới", sent[0].title)
        assertFalse(sent[0].content.contains("Nội dung mới"))
        assertEquals("Nội dung mới", cipher.decrypt(sent[0].content, testNoteKey()))
    }
}

package vn.edu.mobileoptimization.securestore

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class BiometricCipherTest {
    private val alias = "app6-biometric-legacy-test-${UUID.randomUUID()}"
    private val cipher = NoteCipher(alias)

    @After
    fun tearDown() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
    }

    @Test
    fun unlockedKeyEncryptsEditsAndStillReadsLegacyNotes() {
        val legacy = cipher.encrypt("Ghi chú cũ")
        val session = testNoteKey()
        assertEquals("Ghi chú cũ", cipher.decrypt(legacy, session))

        val first = cipher.encrypt("Nội dung mới", session)
        assertEquals("biometric-wrapped-v1", JSONObject(first).getString("key_scope"))
        assertFalse(first.contains("Nội dung mới"))
        assertEquals("Nội dung mới", cipher.decrypt(first, session))

        val edited = cipher.encrypt("Nội dung đã sửa", session)
        assertEquals("Nội dung đã sửa", cipher.decrypt(edited, session))
        assertTrue(JSONObject(first).getString("iv") != JSONObject(edited).getString("iv"))

        val wrong = UnlockedNoteKey(ByteArray(32) { 99 })
        try {
            cipher.decrypt(edited, wrong)
            fail("Khóa khác không được giải mã ghi chú")
        } catch (_: AEADBadTagException) {
            // Expected: the backend envelope cannot be read with another content key.
        } finally {
            wrong.clear()
            session.clear()
        }
    }
}

package vn.edu.mobileoptimization.securestore

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import javax.crypto.AEADBadTagException

@RunWith(AndroidJUnit4::class)
class NoteCipherTest {
    private val alias = "app6-test-${UUID.randomUUID()}"
    private val cipher = NoteCipher(alias)
    private val sample = "Ghi chú giả lập: Android và AES-GCM — 12345"

    @After
    fun tearDown() {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
    }

    @Test
    fun roundTripGeneratesNewIvAndKeepsKeyInKeystore() {
        val first = cipher.encrypt(sample)
        val second = cipher.encrypt(sample)
        val firstJson = JSONObject(first)
        val secondJson = JSONObject(second)
        assertFalse(first.contains(sample))
        assertEquals(12, Base64.decode(firstJson.getString("iv"), Base64.NO_WRAP).size)
        assertEquals(sample.toByteArray(Charsets.UTF_8).size + 16,
            Base64.decode(firstJson.getString("ciphertext_and_tag"), Base64.NO_WRAP).size)
        assertNotEquals(firstJson.getString("iv"), secondJson.getString("iv"))
        assertNotEquals(firstJson.getString("ciphertext_and_tag"), secondJson.getString("ciphertext_and_tag"))
        assertEquals(sample, cipher.decrypt(first))
        assertEquals(sample, cipher.decrypt(second))
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertNull((keys.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey.encoded)
    }

    @Test
    fun changedCiphertextIsRejectedWithoutChangingOriginal() {
        val original = cipher.encrypt(sample)
        val json = JSONObject(original)
        val bytes = Base64.decode(json.getString("ciphertext_and_tag"), Base64.NO_WRAP)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        json.put("ciphertext_and_tag", Base64.encodeToString(bytes, Base64.NO_WRAP))
        expect<AEADBadTagException> { cipher.decrypt(json.toString()) }
        assertEquals(sample, cipher.decrypt(original))
    }

    @Test
    fun missingKeyDoesNotCreateAReplacementWhileReading() {
        val original = cipher.encrypt(sample)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry(alias)
        expect<KeyUnavailableException> { cipher.decrypt(original) }
        assertFalse(keys.containsAlias(alias))
    }

    @Test
    fun invalidEnvelopeAndOversizeInputAreRejected() {
        expect<InvalidEncryptedNoteException> { cipher.decrypt("{invalid}") }
        expect<IllegalArgumentException> { cipher.encrypt("ữ".repeat(16_385)) }
        assertTrue(cipher.encrypt("Hợp lệ").isNotBlank())
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}

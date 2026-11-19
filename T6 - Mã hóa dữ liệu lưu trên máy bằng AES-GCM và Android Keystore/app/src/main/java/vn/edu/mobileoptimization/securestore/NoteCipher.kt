package vn.edu.mobileoptimization.securestore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class KeyUnavailableException : GeneralSecurityException("Không tìm thấy khóa trên thiết bị này.")
internal class InvalidEncryptedNoteException : GeneralSecurityException("Dữ liệu mã hóa không hợp lệ.")

/** The content key lives only for the authenticated form session. */
internal class UnlockedNoteKey(bytes: ByteArray) {
    private val material = bytes.copyOf()
    private var cleared = false

    init { require(material.size == 32) }

    fun secretKey(): SecretKey {
        check(!cleared) { "Khóa phiên đã được xóa." }
        return SecretKeySpec(material, "AES")
    }

    fun clear() {
        material.fill(0)
        cleared = true
    }
}

/** The key remains in Android Keystore; only the encrypted envelope is sent to the backend. */
internal class NoteCipher(private val alias: String) {
    // Legacy helpers keep existing notes readable; new writes use the unlocked content key.
    fun encrypt(text: String): String = encryptWithKey(text, getOrCreateKey(), false)
    fun encrypt(text: String, key: UnlockedNoteKey): String = encryptWithKey(text, key.secretKey(), true)

    private fun encryptWithKey(text: String, key: SecretKey, biometric: Boolean): String {
        val clear = text.toByteArray(Charsets.UTF_8)
        try {
            require(clear.isNotEmpty() && clear.size <= MAX_NOTE_BYTES) { "Nội dung phải có tối đa 32 KiB." }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: throw GeneralSecurityException("Không tạo được IV.")
            if (iv.size != IV_BYTES) throw GeneralSecurityException("Độ dài IV không hợp lệ.")
            cipher.updateAAD(if (biometric) BIOMETRIC_AAD else AAD)
            val encrypted = cipher.doFinal(clear)
            if (encrypted.size != clear.size + TAG_BYTES) throw GeneralSecurityException("Độ dài tag không hợp lệ.")
            val envelope = JSONObject().put("version", 1).put("algorithm", "AES-256-GCM")
                .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                .put("ciphertext_and_tag", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            if (biometric) envelope.put("key_scope", BIOMETRIC_SCOPE)
            return envelope.toString()
        } finally {
            clear.fill(0)
        }
    }

    fun decrypt(content: String): String = decrypt(content, null)

    fun decrypt(content: String, unlocked: UnlockedNoteKey?): String {
        val envelope = decode(content)
        // Reading an existing note must never generate a replacement key.
        val key = if (envelope.biometric) unlocked?.secretKey() ?: throw KeyUnavailableException()
            else existingKey() ?: throw KeyUnavailableException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, envelope.iv))
        cipher.updateAAD(if (envelope.biometric) BIOMETRIC_AAD else AAD)
        val clear = cipher.doFinal(envelope.encrypted)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(clear)).toString()
        } catch (_: CharacterCodingException) {
            throw InvalidEncryptedNoteException()
        } finally {
            clear.fill(0)
        }
    }

    private data class Envelope(val iv: ByteArray, val encrypted: ByteArray, val biometric: Boolean)

    private fun decode(content: String): Envelope {
        if (content.toByteArray(Charsets.UTF_8).size > MAX_ENVELOPE_BYTES) throw InvalidEncryptedNoteException()
        try {
            val json = JSONObject(content)
            if (json.getInt("version") != 1 || json.getString("algorithm") != "AES-256-GCM") {
                throw InvalidEncryptedNoteException()
            }
            val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
            val encrypted = Base64.decode(json.getString("ciphertext_and_tag"), Base64.NO_WRAP)
            val scope = if (json.has("key_scope")) json.opt("key_scope") else null
            if (scope != null && scope != BIOMETRIC_SCOPE) throw InvalidEncryptedNoteException()
            if (iv.size != IV_BYTES || encrypted.size <= TAG_BYTES || encrypted.size > MAX_NOTE_BYTES + TAG_BYTES) {
                throw InvalidEncryptedNoteException()
            }
            return Envelope(iv, encrypted, scope == BIOMETRIC_SCOPE)
        } catch (error: InvalidEncryptedNoteException) {
            throw error
        } catch (_: Exception) {
            throw InvalidEncryptedNoteException()
        }
    }

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) return null
        return (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw GeneralSecurityException("Khóa Keystore không hợp lệ.")
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_NOTE_BYTES = 32 * 1024
        const val MAX_ENVELOPE_BYTES = 64 * 1024
        const val BIOMETRIC_SCOPE = "biometric-wrapped-v1"
        val AAD = "vn.edu.mobileoptimization.securestore.note.v1".toByteArray(Charsets.UTF_8)
        val BIOMETRIC_AAD = "vn.edu.mobileoptimization.securestore.note.biometric.v1".toByteArray(Charsets.UTF_8)
    }
}

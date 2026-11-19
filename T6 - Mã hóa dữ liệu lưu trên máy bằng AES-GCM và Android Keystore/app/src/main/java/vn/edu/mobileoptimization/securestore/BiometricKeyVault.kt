package vn.edu.mobileoptimization.securestore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class BiometricKeyUnavailableException : GeneralSecurityException(
    "Không thể mở khóa ghi chú. Khóa sinh trắc học trên thiết bị không còn khả dụng.",
)

internal class PreparedKeyUnlock(
    val cipher: Cipher,
    val wrapped: ByteArray?,
    val fresh: ByteArray?,
) {
    fun clear() { fresh?.fill(0) }
}

/** Wraps the content key with a Keystore key that requires biometric authorization per use. */
internal class BiometricKeyVault(
    directory: File,
    private val alias: String = "vn.edu.mobileoptimization.securestore.biometric.wrap.v1",
    fileName: String = "biometric_content_key_v1.json",
) {
    private val wrappedFile = AtomicFile(File(directory, fileName))

    fun prepare(): PreparedKeyUnlock {
        val stored = readWrapped()
        val key = existingKey() ?: if (stored == null) createKey() else throw BiometricKeyUnavailableException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        return if (stored == null) {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            PreparedKeyUnlock(cipher, null, ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) })
        } else {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, stored.first))
            PreparedKeyUnlock(cipher, stored.second, null)
        }
    }

    /** Must be called only with the exact Cipher authorized by BiometricPrompt. */
    fun finish(prepared: PreparedKeyUnlock, authorized: Cipher): UnlockedNoteKey {
        require(prepared.cipher === authorized) { "Thao tác mật mã không khớp." }
        authorized.updateAAD(AAD)
        val material = if (prepared.wrapped != null) {
            authorized.doFinal(prepared.wrapped)
        } else {
            val fresh = checkNotNull(prepared.fresh)
            val encrypted = authorized.doFinal(fresh)
            writeWrapped(authorized.iv, encrypted)
            fresh.copyOf()
        }
        return try {
            if (material.size != KEY_BYTES) throw BiometricKeyUnavailableException()
            UnlockedNoteKey(material)
        } finally {
            material.fill(0)
        }
    }

    private fun readWrapped(): Pair<ByteArray, ByteArray>? {
        val bytes = try { wrappedFile.openRead().use { it.readBytes() } }
        catch (_: FileNotFoundException) { return null }
        if (bytes.size > 4096) throw BiometricKeyUnavailableException()
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.getInt("version") != 1) throw BiometricKeyUnavailableException()
            val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
            val wrapped = Base64.decode(json.getString("wrapped_key"), Base64.NO_WRAP)
            if (iv.size != 12 || wrapped.size != KEY_BYTES + 16) throw BiometricKeyUnavailableException()
            return iv to wrapped
        } catch (error: BiometricKeyUnavailableException) {
            throw error
        } catch (_: Exception) {
            throw BiometricKeyUnavailableException()
        }
    }

    private fun writeWrapped(iv: ByteArray, encrypted: ByteArray) {
        if (iv.size != 12 || encrypted.size != KEY_BYTES + 16) throw BiometricKeyUnavailableException()
        val bytes = JSONObject().put("version", 1)
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("wrapped_key", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString().toByteArray(Charsets.UTF_8)
        val output = wrappedFile.startWrite()
        try {
            output.write(bytes)
            wrappedFile.finishWrite(output)
        } catch (error: Exception) {
            wrappedFile.failWrite(output)
            throw error
        }
    }

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) return null
        return (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw BiometricKeyUnavailableException()
    }

    @Suppress("DEPRECATION")
    private fun createKey(): SecretKey {
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            // Adding a new device biometric must not silently destroy the only content key.
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(builder.build())
            generateKey()
        }
    }

    private companion object {
        const val KEY_BYTES = 32
        val AAD = "vn.edu.mobileoptimization.securestore.biometric.key.v1".toByteArray(Charsets.UTF_8)
    }
}

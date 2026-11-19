package vn.edu.mobileoptimization.securestore

import android.hardware.biometrics.BiometricManager
import android.os.Build
import android.security.keystore.KeyInfo
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKeyFactory

@RunWith(AndroidJUnit4::class)
class BiometricKeyVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val suffix = UUID.randomUUID().toString()
    private val alias = "app6-biometric-vault-test-$suffix"
    private val fileName = "biometric-key-test-$suffix.json"
    private val file = File(context.filesDir, fileName)
    private val vault = BiometricKeyVault(context.filesDir, alias, fileName)

    @After
    fun tearDown() {
        listOf(file, File(file.path + ".new"), File(file.path + ".bak")).forEach { it.delete() }
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(alias)) keys.deleteEntry(alias)
    }

    @Test
    fun missingWrappingKeyDoesNotReplaceExistingWrappedContentKey() {
        val saved = JSONObject().put("version", 1)
            .put("iv", Base64.encodeToString(ByteArray(12), Base64.NO_WRAP))
            .put("wrapped_key", Base64.encodeToString(ByteArray(48), Base64.NO_WRAP)).toString()
        file.writeText(saved)
        try {
            vault.prepare()
            fail("Không được tạo khóa thay thế khi đã có dữ liệu khóa cũ")
        } catch (_: BiometricKeyUnavailableException) {
            assertTrue(file.readText() == saved)
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keys.containsAlias(alias))
        }
    }

    @Test
    fun wrappingKeyCannotBeUsedBeforeBiometricAuthorization() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val manager = context.getSystemService(BiometricManager::class.java)
        assumeTrue(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS)
        val prepared = vault.prepare()
        try {
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (keys.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
            val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            assertTrue(info.isUserAuthenticationRequired)
            assertTrue(info.userAuthenticationValidityDurationSeconds == 0 || info.userAuthenticationValidityDurationSeconds == -1)
            var failed = false
            try { vault.finish(prepared, prepared.cipher) } catch (_: Exception) { failed = true }
            assertTrue("Khóa phải từ chối thao tác khi chưa xác thực", failed)
            assertFalse(file.exists())
        } finally {
            prepared.clear()
        }
    }
}

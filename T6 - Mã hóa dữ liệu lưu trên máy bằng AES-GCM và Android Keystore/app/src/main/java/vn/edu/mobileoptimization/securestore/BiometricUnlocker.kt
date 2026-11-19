package vn.edu.mobileoptimization.securestore

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.UnrecoverableKeyException
import java.util.concurrent.Executors
import javax.crypto.Cipher

internal interface BiometricUnlocker {
    fun unlock(title: String, onSuccess: (UnlockedNoteKey) -> Unit, onFailure: (String) -> Unit)
    fun cancel()
}

private object BiometricWorker { val executor = Executors.newSingleThreadExecutor() }

/** A fresh, auth-per-use CryptoObject unlocks the content key for one form session. */
internal class PlatformBiometricUnlocker(
    private val activity: Activity,
    private val vault: BiometricKeyVault = BiometricKeyVault(activity.filesDir),
) : BiometricUnlocker {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var signal: CancellationSignal? = null
    private var dialog: AlertDialog? = null
    private var pending: PreparedKeyUnlock? = null

    override fun unlock(title: String, onSuccess: (UnlockedNoteKey) -> Unit, onFailure: (String) -> Unit) {
        cancel()
        val unavailable = availabilityError()
        if (unavailable != null) { onFailure(unavailable); return }
        val token = ++generation
        BiometricWorker.executor.execute {
            val prepared = runCatching { vault.prepare() }
            main.post {
                if (token != generation) {
                    prepared.getOrNull()?.clear()
                    return@post
                }
                prepared.fold(
                    onSuccess = { beginPrompt(title, token, it, onSuccess, onFailure) },
                    onFailure = { onFailure(keyError(it)) },
                )
            }
        }
    }

    override fun cancel() {
        generation++
        signal?.cancel()
        signal = null
        dialog?.dismiss()
        dialog = null
        pending?.clear()
        pending = null
    }

    private fun beginPrompt(
        title: String,
        token: Long,
        prepared: PreparedKeyUnlock,
        onSuccess: (UnlockedNoteKey) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        pending = prepared
        val cancellation = CancellationSignal()
        signal = cancellation
        var terminal = false

        fun fail(message: String) {
            if (terminal || token != generation) return
            terminal = true
            signal = null
            cancellation.cancel()
            dialog?.dismiss()
            dialog = null
            pending = null
            prepared.clear()
            onFailure(message)
        }

        fun authenticated(authorized: Cipher?) {
            if (terminal || token != generation) return
            if (authorized == null || authorized !== prepared.cipher) {
                fail("Không nhận được khóa đã xác thực. Hãy thử lại.")
                return
            }
            terminal = true
            signal = null
            dialog?.dismiss()
            dialog = null
            pending = null // The worker now owns and clears the prepared key material.
            BiometricWorker.executor.execute {
                val result = try { runCatching { vault.finish(prepared, authorized) } }
                    finally { prepared.clear() }
                main.post {
                    if (token != generation) {
                        result.getOrNull()?.clear()
                    } else {
                        result.fold(onSuccess, { onFailure(keyError(it)) })
                    }
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                showSystemPrompt(title, cancellation, prepared.cipher, ::authenticated, ::fail)
            } else {
                showFingerprintDialog(title, cancellation, prepared.cipher, ::authenticated, ::fail)
            }
        } catch (_: Exception) {
            fail("Không mở được xác thực sinh trắc học. Hãy kiểm tra cài đặt và thử lại.")
        }
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun showSystemPrompt(
        title: String,
        cancellation: CancellationSignal,
        cipher: Cipher,
        success: (Cipher?) -> Unit,
        failure: (String) -> Unit,
    ) {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setSubtitle("Xác thực để mở khóa ghi chú")
            .setNegativeButton("Hủy", activity.mainExecutor) { _, _ -> failure("Đã hủy xác thực sinh trắc học.") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }
        builder.build().authenticate(BiometricPrompt.CryptoObject(cipher), cancellation, activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    success(result.cryptoObject?.cipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    failure(when (errorCode) {
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED ->
                            "Đã hủy xác thực sinh trắc học."
                        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT, BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT ->
                            "Sinh trắc học đang bị khóa. Hãy mở khóa thiết bị rồi thử lại."
                        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ->
                            "Hãy đăng ký vân tay hoặc sinh trắc học mạnh trong cài đặt thiết bị."
                        else -> "Không thể xác thực sinh trắc học. Hãy kiểm tra cảm biến rồi thử lại."
                    })
                }
            })
    }

    @Suppress("DEPRECATION")
    private fun showFingerprintDialog(
        title: String,
        cancellation: CancellationSignal,
        cipher: Cipher,
        success: (Cipher?) -> Unit,
        failure: (String) -> Unit,
    ) {
        val manager = activity.getSystemService(FingerprintManager::class.java)
        val prompt = AlertDialog.Builder(activity).setTitle(title)
            .setMessage("Chạm cảm biến vân tay để mở khóa ghi chú.")
            .setNegativeButton("Hủy") { _, _ -> failure("Đã hủy xác thực vân tay.") }
            .setOnCancelListener { failure("Đã hủy xác thực vân tay.") }
            .create()
        dialog = prompt
        prompt.show()
        prompt.setCanceledOnTouchOutside(false)
        manager.authenticate(FingerprintManager.CryptoObject(cipher), cancellation, 0,
            object : FingerprintManager.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult) {
                    success(result.cryptoObject?.cipher)
                }
                override fun onAuthenticationFailed() {
                    prompt.setMessage("Vân tay chưa khớp. Hãy thử lại hoặc nhấn Hủy.")
                }
                override fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence) {
                    prompt.setMessage(helpString)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    failure("Không thể xác thực vân tay. Hãy kiểm tra cảm biến rồi thử lại.")
                }
            }, main)
    }

    @Suppress("DEPRECATION")
    private fun availabilityError(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = activity.getSystemService(BiometricManager::class.java)
            val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            } else manager.canAuthenticate()
            return when (status) {
                BiometricManager.BIOMETRIC_SUCCESS -> null
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "Hãy đăng ký vân tay hoặc sinh trắc học mạnh trong cài đặt thiết bị."
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Thiết bị không có cảm biến sinh trắc học phù hợp."
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Cảm biến sinh trắc học chưa sẵn sàng. Hãy thử lại."
                else -> "Sinh trắc học mạnh chưa khả dụng. Hãy kiểm tra cài đặt thiết bị."
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val manager = activity.getSystemService(FingerprintManager::class.java)
            return when {
                manager == null || !manager.isHardwareDetected -> "Thiết bị không có cảm biến vân tay phù hợp."
                !manager.hasEnrolledFingerprints() -> "Hãy đăng ký vân tay trong cài đặt thiết bị."
                else -> null
            }
        }
        return null
    }

    private fun keyError(error: Throwable): String {
        var cause: Throwable? = error
        var checked = 0
        while (cause != null && checked++ < 12) {
            if (cause is BiometricKeyUnavailableException || cause is KeyPermanentlyInvalidatedException ||
                cause is UnrecoverableKeyException) {
                return "Không mở được khóa ghi chú trên thiết bị này. Dữ liệu cũ vẫn được giữ nguyên."
            }
            cause = cause.cause?.takeUnless { it === cause }
        }
        return "Không mở được khóa sinh trắc học. Hãy kiểm tra cài đặt thiết bị và thử lại."
    }
}

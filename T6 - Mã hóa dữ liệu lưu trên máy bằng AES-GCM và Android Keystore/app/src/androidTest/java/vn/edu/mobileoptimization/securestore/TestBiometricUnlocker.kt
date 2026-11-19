package vn.edu.mobileoptimization.securestore

internal fun testNoteKey() = UnlockedNoteKey(ByteArray(32) { (it + 1).toByte() })

internal class TestBiometricUnlocker(private val automatic: Boolean = true) : BiometricUnlocker {
    var requests = 0
        private set
    var cancellations = 0
        private set
    private var success: ((UnlockedNoteKey) -> Unit)? = null
    private var failure: ((String) -> Unit)? = null

    override fun unlock(title: String, onSuccess: (UnlockedNoteKey) -> Unit, onFailure: (String) -> Unit) {
        requests++
        success = onSuccess
        failure = onFailure
        if (automatic) succeed()
    }

    fun succeed() {
        val callback = checkNotNull(success)
        success = null
        failure = null
        callback(testNoteKey())
    }

    fun reject() {
        val callback = checkNotNull(failure)
        success = null
        failure = null
        callback("Đã hủy xác thực sinh trắc học.")
    }

    override fun cancel() {
        cancellations++
        success = null
        failure = null
    }
}

internal fun installTestBiometric(activity: MainActivity, biometric: TestBiometricUnlocker) {
    val field = MainActivity::class.java.getDeclaredField("biometric").apply { isAccessible = true }
    field.set(activity, biometric)
}

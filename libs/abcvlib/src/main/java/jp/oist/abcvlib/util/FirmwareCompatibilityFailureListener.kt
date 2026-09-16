package jp.oist.abcvlib.util

fun interface FirmwareCompatibilityFailureListener {
    fun onFirmwareCompatibilityFailure(message: String)
}

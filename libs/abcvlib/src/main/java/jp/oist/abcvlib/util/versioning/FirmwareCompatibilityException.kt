package jp.oist.abcvlib.util.versioning

class FirmwareCompatibilityException(
    message: String,
    val userFacingMessage: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    companion object {
        private fun expectedVersionMessage() =
            "Expected firmware version: ${expectedFirmwareVersionDescription()}."

        fun invalidVersionPayload(actualSize: Int) = FirmwareCompatibilityException(
            message = "GET_VERSION payload must be exactly 3 bytes, got $actualSize",
            userFacingMessage = "Robot firmware did not provide a valid version response. " +
                    "${expectedVersionMessage()} Update the robot firmware or install a matching app version."
        )

        fun unsupportedVersion(version: Version) = FirmwareCompatibilityException(
            message = "Unsupported firmware version: $version",
            userFacingMessage = "Robot firmware version $version is not compatible with this app. " +
                    "${expectedVersionMessage()} Update the robot firmware or install a matching app version."
        )

        fun versionRequestTimedOut(timeoutMs: Long) = FirmwareCompatibilityException(
            message = "Firmware version request timed out after $timeoutMs ms",
            userFacingMessage = "Robot firmware did not respond to the version check. " +
                    "${expectedVersionMessage()} Update the robot firmware or install a matching app version."
        )

        fun versionRequestFailed(cause: Throwable) = FirmwareCompatibilityException(
            message = "Firmware version request failed",
            userFacingMessage = "Robot firmware version could not be verified. " +
                    "${expectedVersionMessage()} Update the robot firmware or install a matching app version.",
            cause = cause
        )
    }
}

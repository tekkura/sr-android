package jp.oist.abcvlib.util.versioning

const val MINIMUM_SUPPORTED_VERSION = "1.0.0"
const val MAXIMUM_SUPPORTED_VERSION = "1.0.100"
private val BLACKLIST = emptyList<String>()

fun expectedFirmwareVersionDescription(): String =
    "$MINIMUM_SUPPORTED_VERSION through $MAXIMUM_SUPPORTED_VERSION"

fun checkVersionSupport(
    versionMajor: Int,
    versionMinor: Int,
    versionPatch: Int
) : Boolean {
    val firmwareVersion = Version(versionMajor, versionMinor, versionPatch)
    val minVersion = Version.parse(MINIMUM_SUPPORTED_VERSION)
    val maxVersion = Version.parse(MAXIMUM_SUPPORTED_VERSION)
    val blacklist = BLACKLIST.mapNotNull { Version.parse(it) }

    if (minVersion == null || maxVersion == null)
        throw IllegalStateException("Invalid version strings")

    if (blacklist.contains(firmwareVersion))
        return false

    return firmwareVersion in minVersion..maxVersion
}

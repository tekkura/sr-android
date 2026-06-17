package jp.oist.abcvlib.util.versioning

private const val MINIMUM_SUPPORTED_VERSION = "1.2.0"
private const val MAXIMUM_SUPPORTED_VERSION = "1.2.100"
private val BLACKLIST = emptyList<String>()

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
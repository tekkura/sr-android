package jp.oist.abcvlib.util.versioning

data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        // Compares major, then minor if majors are equal, then patch if minors are equal
        return compareValuesBy(this, other,
            { it.major },
            { it.minor },
            { it.patch }
        )
    }

    companion object {
        fun parse(version: String): Version? {
            val parts = version.split('.')
            if (parts.size != 3)
                return null

            val major = parts[0].toIntOrNull()
                ?: return null

            val minor = parts[1].toIntOrNull()
                ?: return null

            val patch = parts[2].toIntOrNull()
                ?: return null

            return Version(major, minor, patch)
        }
    }
}
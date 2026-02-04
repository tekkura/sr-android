package jp.oist.abcvlib

/**
 * Git-based versioning utilities for Android apps and libraries.
 *
 * Version format: MAJOR.MINOR.PATCH-COMMIT_COUNT-DIRTY_STATUS
 * Example: 1.2.3-5-dirty (5 commits after tag v1.2.3, with uncommitted changes)
 *
 * Version code formula: MAJOR * 100000 + MINOR * 10000 + PATCH * 1000 + COMMIT_COUNT
 * Example: v1.2.3 with 5 commits = 123005
 */
object AppVersioning {

    private const val DEFAULT_VERSION_NAME = "0.0.0-0-unknown"
    private const val DEFAULT_VERSION_CODE = 1

    /**
     * Execute shell command and return output
     */
    private fun String.execute(): String =
        Runtime.getRuntime().exec(this).inputStream.bufferedReader().readText()

    /**
     * Get version info from git tags (single git call)
     *
     * Format: MAJOR.MINOR.PATCH-COMMIT_COUNT-DIRTY_STATUS
     * Version code: MAJOR * 100000 + MINOR * 10000 + PATCH * 1000 + COMMIT_COUNT
     */
    fun getVersionInfo(): VersionInfo {
        return try {
            // git describe --tags --long --dirty returns: v1.2.3-5-g1234567 or v1.2.3-5-g1234567-dirty
            val longVersionName = "git describe --tags --long --dirty".execute().trim()
            val isDirty = longVersionName.endsWith("-dirty")
            val cleanVersion = longVersionName.removeSuffix("-dirty")
            val (fullVersionTag, commitCount, gitSha) = cleanVersion.split("-", limit = 3)
            val (versionMajor, versionMinor, versionPatch) = fullVersionTag.removePrefix("v").split(".")

            val dirtyStatus = if (isDirty) "dirty" else "clean"
            val versionName = "$versionMajor.$versionMinor.$versionPatch-$commitCount-$dirtyStatus"
            val versionCode = versionMajor.toInt() * 100000 +
                    versionMinor.toInt() * 10000 +
                    versionPatch.toInt() * 1000 +
                    commitCount.toInt()

            println("versionName = $versionName")
            println("versionCode = $versionCode")

            VersionInfo(versionName, versionCode)
        } catch (e: Exception) {
            println("Warning: No tags present or failed to fetch version from git. Setting default version. Error: ${e.message}")
            VersionInfo(DEFAULT_VERSION_NAME, DEFAULT_VERSION_CODE)
        }
    }

    /**
     * Get git version for publishing (group version)
     * Returns tag name if on tag, otherwise full version string
     */
    fun gitVersion(): String {
        return try {
            val longVersionName = "git describe --tags --long".execute().trim()
            val parts = longVersionName.split("-")
            val fullVersionTag = parts[0]
            val commitCount = parts[1].toInt()

            if (commitCount == 0) fullVersionTag else longVersionName
        } catch (e: Exception) {
            println("Warning: Failed to fetch git version. Error: ${e.message}")
            DEFAULT_VERSION_NAME
        }
    }

    /**
     * Get SCM tag for publishing metadata
     * Returns VERSION env var or git describe + hash
     */
    fun scmTag(): String {
        return try {
            val envVersion = System.getenv("VERSION")
            if (!envVersion.isNullOrEmpty() && envVersion != "null") {
                val tokens = envVersion.split("/")
                if (tokens.size > 2) tokens[2] else tokens[0]
            } else {
                val tag = "git describe --tags --dirty".execute().trim()
                val hash = "git rev-parse HEAD".execute().trim()
                "${tag}_$hash"
            }
        } catch (e: Exception) {
            println("Warning: Failed to fetch SCM tag. Error: ${e.message}")
            "0-unknown"
        }
    }

    /**
     * Get current git commit hash
     */
    fun gitHash(): String {
        return try {
            "git rev-parse HEAD".execute().trim()
        } catch (e: Exception) {
            println("Warning: Failed to fetch git hash. Error: ${e.message}")
            "unknown"
        }
    }

    /**
     * Check if working directory has uncommitted changes
     */
    fun isDirty(): Boolean {
        return try {
            val output = "git describe --tags --dirty".execute().trim()
            output.split("-").last() == "dirty"
        } catch (e: Exception) {
            println("Warning: Failed to determine if repository is dirty. Error: ${e.message}")
            false
        }
    }

    /**
     * Check if HEAD is exactly on a tag (no commits after)
     */
    fun isTagged(): Boolean {
        return try {
            val longVersionName = "git describe --tags --long".execute().trim()
            val commitCount = longVersionName.split("-")[1].toInt()
            commitCount == 0
        } catch (e: Exception) {
            println("Warning: Failed to determine if repository is tagged. Error: ${e.message}")
            false
        }
    }
}

data class VersionInfo(
    val versionName: String,
    val versionCode: Int
)

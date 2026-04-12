package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.versioning.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VersioningTest {

    @Test
    fun testVersionParseSuccess() {
        val version = Version.parse("1.2.3")
        assertNotNull("Version parsing failed", version)
        assertEquals("Major version mismatch", 1, version!!.major)
        assertEquals("Minor version mismatch", 2, version.minor)
        assertEquals("Patch version mismatch", 3, version.patch)
    }

    @Test
    fun testVersionParseFailure() {
        val badVersions = listOf("1.2", "1.2.3.4", "invalid", "v1.0.0", "1.0.2-alpha")
        for (versionString in badVersions) {
            val version = Version.parse(versionString)
            assertNull("Version parsing should have failed for '$versionString'", version)
        }
    }

    @Test
    fun testVersionComparison() {
        val version1 = Version(1, 2, 3)
        val version2 = Version(2, 0, 0)
        val version3 = Version(1, 3, 0)
        val version4 = Version(1, 2, 4)
        val version5 = Version(1, 2, 3)

        assert(version1 < version2)
        assert(version1 < version3)
        assert(version1 < version4)
        assert(version1 == version5)
        assert(version2 > version3)
        assert(version2 > version4)
        assert(version3 > version4)
    }
}
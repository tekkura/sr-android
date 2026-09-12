package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.ByteArrayExtensions.toCrc
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteArrayExtensionsTest {

    @Test
    fun `toCrc matches CRC-16 CCITT-FALSE check vector`() {
        val crc = "123456789".toByteArray(Charsets.US_ASCII).toCrc()

        assertEquals(0x29B1, crc.toInt() and 0xFFFF)
    }
}

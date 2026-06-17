package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.ByteArrayExtensions.toCrc
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteArrayExtensionsTest {
    @Test
    fun `crc matches firmware ccitt implementation`() {
        assertEquals(0x9B6A.toShort(), byteArrayOf(0x01, 0x00, 0x06).toCrc())
        assertEquals(0xC8EE.toShort(), byteArrayOf(0x03, 0x00, 0x01, 0x00, 0x00).toCrc())
        assertEquals(0x7E7A.toShort(), byteArrayOf(0x04, 0x00, 0x06, 0x01, 0x02, 0x00).toCrc())
    }
}

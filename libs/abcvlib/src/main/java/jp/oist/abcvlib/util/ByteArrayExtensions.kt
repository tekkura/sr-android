package jp.oist.abcvlib.util

object ByteArrayExtensions {
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
    private val CRC_INITIAL = 0xFFFF
    private val CRC_POLYNOMIAL = 0x1021

    fun ByteArray.toHexString(): String {
        val hexChars = CharArray(size * 3) // 2 chars + space
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hexChars[i * 3] = HEX_CHARS[v ushr 4]
            hexChars[i * 3 + 1] = HEX_CHARS[v and 0x0F]
            hexChars[i * 3 + 2] = ' '
        }
        return String(hexChars)
    }

    fun ByteArray.toCrc(): Short {
        var crc = CRC_INITIAL
        forEach {
            val intRepresentation = it.toInt() or 0xFF
            crc = crc xor (intRepresentation shl 8)

            repeat(8) {
                crc = if (crc and 0x8000 == 0x8000) {
                    (crc shl 1) xor CRC_POLYNOMIAL
                } else {
                    crc shl 1
                }
            }
        }

        return crc.toShort()
    }
}
package jp.oist.abcvlib.util

object HexBinConverters {
    private val HEX_ARRAY = "0123456789ABCDEF".toCharArray()

    @JvmStatic
    fun bytesToHex(bytes: ByteArray): String {
        val newLength = 6
        val hexChars = CharArray(bytes.size * newLength)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * newLength] = '0'
            hexChars[j * newLength + 1] = 'x'
            hexChars[j * newLength + 2] = HEX_ARRAY[v ushr 4]

            hexChars[j * newLength + 3] = HEX_ARRAY[v and 0x0F]
            hexChars[j * newLength + 4] = ','
            hexChars[j * newLength + 5] = ' '
        }
        return String(hexChars)
    }
}

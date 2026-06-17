package jp.oist.abcvlib.util

object ByteArrayExtensions {
    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

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
}
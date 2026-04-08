package jp.oist.abcvlib.util.latency

fun toIteration(left: Byte, right: Byte): Int {
    val leftInt = (left.toInt()) shl 8 and 0xFF00
    val rightInt = right.toInt() and 0xFF
    return leftInt or rightInt
}

fun fromIteration(iteration: Int): Pair<Byte, Byte> {
    val left = iteration and 0xFF00 shr 8
    val right = iteration and 0xFF

    return Pair(left.toByte(), right.toByte())
}
package jp.oist.abcvlib.basicassembler

internal fun Double.normalizedIn(minValue: Double, maxValue: Double): Double {
    require(maxValue > minValue) { "maxValue must be greater than minValue" }
    return ((this - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0)
}

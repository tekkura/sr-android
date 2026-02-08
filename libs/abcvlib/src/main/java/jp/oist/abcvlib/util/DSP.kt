package jp.oist.abcvlib.util

object DSP {
    @JvmStatic
    fun exponentialAvg(sample: Double, expAvg: Double, weighting: Double): Double {
        return (1.0 - weighting) * expAvg + (weighting * sample)
    }
}

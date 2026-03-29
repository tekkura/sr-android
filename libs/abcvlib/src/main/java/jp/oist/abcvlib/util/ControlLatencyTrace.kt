package jp.oist.abcvlib.util

object ControlLatencyTrace {
    @Volatile
    var requestedLeft: Float = 0f

    @Volatile
    var requestedRight: Float = 0f

    @Volatile
    var sentLeft: Float = 0f

    @Volatile
    var sentRight: Float = 0f

    @Volatile
    var outputsDtMs: Long = 0L

    @Volatile
    var queueToSendMs: Long = 0L
}

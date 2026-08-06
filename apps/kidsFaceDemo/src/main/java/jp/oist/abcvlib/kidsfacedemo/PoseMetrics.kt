package jp.oist.abcvlib.kidsfacedemo

data class PoseMetrics(
    val person: Boolean,
    val targetVisible: Boolean,
    val targetX: Float,
    val targetY: Float,
    val leftWheel: Float,
    val rightWheel: Float,
    val stopped: Boolean
) {
    companion object {
        val EMPTY = PoseMetrics(
            person = false,
            targetVisible = false,
            targetX = 0f,
            targetY = 0f,
            leftWheel = 0f,
            rightWheel = 0f,
            stopped = true
        )
    }
}

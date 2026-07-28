package jp.oist.abcvlib.kidsfacedemo

data class PoseMetrics(
    val person: Boolean,
    val leftRaised: Boolean,
    val rightRaised: Boolean,
    val targetX: Float,
    val targetY: Float,
    val leftWheel: Float,
    val rightWheel: Float,
    val stopped: Boolean
) {
    companion object {
        val EMPTY = PoseMetrics(
            person = false,
            leftRaised = false,
            rightRaised = false,
            targetX = 0f,
            targetY = 0f,
            leftWheel = 0f,
            rightWheel = 0f,
            stopped = true
        )
    }
}

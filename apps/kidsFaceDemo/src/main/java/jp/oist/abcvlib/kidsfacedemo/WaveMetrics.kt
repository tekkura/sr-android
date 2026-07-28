package jp.oist.abcvlib.kidsfacedemo

data class WaveMetrics(
    val person: Boolean,
    val leftRaised: Boolean,
    val rightRaised: Boolean,
    val leftMotion: Float,
    val rightMotion: Float,
    val shoulderWidth: Float,
    val normalizedMotion: Float,
    val score: Float,
    val samples: Int
) {
    companion object {
        val EMPTY = WaveMetrics(
            person = false,
            leftRaised = false,
            rightRaised = false,
            leftMotion = 0f,
            rightMotion = 0f,
            shoulderWidth = 0f,
            normalizedMotion = 0f,
            score = 0f,
            samples = 0
        )
    }
}

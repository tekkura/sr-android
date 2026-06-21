package jp.oist.abcvlib.basicassembler

import jp.oist.abcvlib.basicassembler.databinding.ActivityMainBinding
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer.TimeStepData
import jp.oist.abcvlib.core.inputs.phone.OrientationData
import java.text.DecimalFormat
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.log10

class GuiUpdater(
    private val binding: ActivityMainBinding,
    private val maxTimeStepCount: Int,
    private val maxEpisodeCount: Int
) {
    private val df = DecimalFormat("#.00")

    @Volatile
    var timeStep: String = ""

    @Volatile
    var episode: String = ""

    @Volatile
    var batteryVoltage: Double = 0.0

    @Volatile
    var chargerVoltage: Double = 0.0

    @Volatile
    var coilVoltage: Double = 0.0

    @Volatile
    var thetaDeg: Double = 0.0

    @Volatile
    var angularVelocityDeg: Double = 0.0

    @Volatile
    var wheelCountL: Int = 0

    @Volatile
    var wheelCountR: Int = 0

    @Volatile
    var wheelDistanceL: Double = 0.0

    @Volatile
    var wheelDistanceR: Double = 0.0

    @Volatile
    var wheelSpeedInstantL: Double = 0.0

    @Volatile
    var wheelSpeedInstantR: Double = 0.0

    @Volatile
    var wheelSpeedBufferedL: Double = 0.0

    @Volatile
    var wheelSpeedBufferedR: Double = 0.0

    @Volatile
    var wheelSpeedExpAvgL: Double = 0.0

    @Volatile
    var wheelSpeedExpAvgR: Double = 0.0

    @Volatile
    var audioDataString: String = ""

    @Volatile
    var audioLevel: Double = 0.0

    @Volatile
    var frameRateString: String = ""

    init {
        binding.tiltGauge.configure("Tilt", "deg", -45.0, 45.0)
        binding.angularVelocityGauge.configure("Angular velocity", "deg/s", -360.0, 360.0)
        binding.leftWheelGauge.configure("Left wheel", "mm/s", -500.0, 500.0)
        binding.rightWheelGauge.configure("Right wheel", "mm/s", -500.0, 500.0)
    }

    fun displayGUIValues() {
        binding.timeStep.text = timeStep
        binding.episodeCount.text = episode
        binding.voltageBattLevel.text = "${df.format(batteryVoltage)} V"
        binding.voltageChargerLevel.text = "${df.format(chargerVoltage)} V"
        binding.coilVoltageText.text = "${df.format(coilVoltage)} V"
        binding.frameRate.text = "$frameRateString fps"
        binding.tiltGauge.setValue(thetaDeg)
        binding.angularVelocityGauge.setValue(angularVelocityDeg)
        binding.leftWheelGauge.setValue(wheelSpeedBufferedL)
        binding.rightWheelGauge.setValue(wheelSpeedBufferedR)
        binding.batteryGauge.progress = scaledProgress(batteryVoltage, 3.0, 4.3)
        binding.chargerGauge.progress = scaledProgress(chargerVoltage, 0.0, 6.0)
        binding.coilGauge.progress = scaledProgress(coilVoltage, 0.0, 6.0)
        binding.soundGauge.progress = scaledLogProgress(audioLevel)
        val left = df.format(wheelCountL.toLong()) + " : " +
                df.format(wheelDistanceL) + " : " +
                df.format(wheelSpeedBufferedL) + " : " +
                df.format(wheelSpeedExpAvgL)
        val right = df.format(wheelCountR.toLong()) + " : " +
                df.format(wheelDistanceR) + " : " +
                df.format(wheelSpeedBufferedR) + " : " +
                df.format(wheelSpeedExpAvgR)
        binding.soundData.text = audioDataString
    }

    fun updateGUIValues(data: TimeStepData, timeStepCount: Int, episodeCount: Int) {
        if (timeStepCount <= maxTimeStepCount) {
            timeStep = (timeStepCount + 1).toString() + " of " + maxTimeStepCount
        }
        if (episodeCount <= maxEpisodeCount) {
            episode = (episodeCount + 1).toString() + " of " + maxEpisodeCount
        }
        if (data.batteryData.getVoltage().size > 0) {
            batteryVoltage = data.batteryData.getVoltage()[0] // just taking the first recorded one
        }
        if (data.chargerData.getChargerVoltage().size > 0) {
            chargerVoltage = data.chargerData.getChargerVoltage()[0]
            coilVoltage = data.chargerData.getCoilVoltage()[0]
        }
        if (data.orientationData.getTiltAngle().size > 20) {
            thetaDeg = OrientationData.getThetaDeg(data.orientationData.getTiltAngle()[0])
            angularVelocityDeg = OrientationData.getAngularVelocityDeg(
                data.orientationData.getAngularVelocity()[0]
            )
        }
        val leftCounts = data.wheelData.getLeft().getCounts()
        val rightCounts = data.wheelData.getRight().getCounts()
        if (leftCounts.isNotEmpty() && rightCounts.isNotEmpty()) {
            val leftDistances = data.wheelData.getLeft().getDistances()
            val rightDistances = data.wheelData.getRight().getDistances()
            val leftSpeedsInstant = data.wheelData.getLeft().getSpeedsInstantaneous()
            val rightSpeedsInstant = data.wheelData.getRight().getSpeedsInstantaneous()
            val leftSpeedsBuffered = data.wheelData.getLeft().getSpeedsBuffered()
            val rightSpeedsBuffered = data.wheelData.getRight().getSpeedsBuffered()
            val leftSpeedsExpAvg = data.wheelData.getLeft().getSpeedsExpAvg()
            val rightSpeedsExpAvg = data.wheelData.getRight().getSpeedsExpAvg()
            val leftSampleCount = minOf(
                leftCounts.size,
                leftDistances.size,
                leftSpeedsInstant.size,
                leftSpeedsBuffered.size,
                leftSpeedsExpAvg.size
            )
            val rightSampleCount = minOf(
                rightCounts.size,
                rightDistances.size,
                rightSpeedsInstant.size,
                rightSpeedsBuffered.size,
                rightSpeedsExpAvg.size
            )

            for (i in 0 until leftSampleCount) {
                binding.leftWheelGraph.addSample(
                    leftCounts[i],
                    leftDistances[i],
                    leftSpeedsInstant[i],
                    leftSpeedsBuffered[i],
                    leftSpeedsExpAvg[i]
                )
            }
            for (i in 0 until rightSampleCount) {
                binding.rightWheelGraph.addSample(
                    rightCounts[i],
                    rightDistances[i],
                    rightSpeedsInstant[i],
                    rightSpeedsBuffered[i],
                    rightSpeedsExpAvg[i]
                )
            }

            val latestLeft = leftSampleCount - 1
            val latestRight = rightSampleCount - 1
            wheelCountL = leftCounts[latestLeft]
            wheelCountR = rightCounts[latestRight]
            wheelDistanceL = leftDistances[latestLeft]
            wheelDistanceR = rightDistances[latestRight]
            wheelSpeedInstantL = leftSpeedsInstant[latestLeft]
            wheelSpeedInstantR = rightSpeedsInstant[latestRight]
            wheelSpeedBufferedL = leftSpeedsBuffered[latestLeft]
            wheelSpeedBufferedR = rightSpeedsBuffered[latestRight]
            wheelSpeedExpAvgL = leftSpeedsExpAvg[latestLeft]
            wheelSpeedExpAvgR = rightSpeedsExpAvg[latestRight]
        }
        val levels = data.soundData.getLevels()
        if (levels.isNotEmpty()) {
            audioLevel = levels.maxOf { abs(it.toDouble()) }.coerceIn(0.0, 1.0)
            val arraySlice = levels.copyOfRange(0, 5.coerceAtMost(levels.size))
            val df = DecimalFormat("0.#E0")
            val arraySliceString = arraySlice.joinToString(", ") { v -> df.format(v) }
            audioDataString = arraySliceString
        }
        if (data.imageData.images.size > 1) {
            val timestamps = data.imageData.images.map { it.timestamp }.sorted()
            val deltaNanos = timestamps.zipWithNext()
                .map { (previous, next) -> next - previous }
                .firstOrNull { it > 0 }
            if (deltaNanos != null) {
                val frameRate = 1_000_000_000.0 / deltaNanos
                frameRateString = frameRate.toInt().toString()
            }
        }
    }

    private fun scaledProgress(value: Double, min: Double, max: Double): Int {
        val normalized = ((value - min) / (max - min)).coerceIn(0.0, 1.0)
        return (normalized * 1000).toInt()
    }

    private fun scaledLogProgress(value: Double): Int {
        val clamped = value.coerceIn(1e-6, 1.0)
        val normalized = ((log10(clamped) + 6.0) / 6.0).coerceIn(0.0, 1.0)
        return (normalized * 1000).toInt()
    }
}

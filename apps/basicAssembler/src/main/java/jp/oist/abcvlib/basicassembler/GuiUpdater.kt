package jp.oist.abcvlib.basicassembler

import jp.oist.abcvlib.basicassembler.databinding.ActivityMainBinding
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer.TimeStepData
import jp.oist.abcvlib.core.inputs.phone.OrientationData
import java.text.DecimalFormat
import kotlin.concurrent.Volatile

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
    var frameRateString: String = ""

    fun displayGUIValues() {
        binding.timeStep.text = timeStep
        binding.episodeCount.text = episode
        binding.voltageBattLevel.text = df.format(batteryVoltage)
        binding.voltageChargerLevel.text = df.format(chargerVoltage)
        binding.coilVoltageText.text = df.format(coilVoltage)
        binding.tiltAngle.text = df.format(thetaDeg)
        binding.angularVelcoity.text = df.format(angularVelocityDeg)
        val left = df.format(wheelCountL.toLong()) + " : " +
                df.format(wheelDistanceL) + " : " +
                df.format(wheelSpeedInstantL) + " : " +
                df.format(wheelSpeedBufferedL) + " : " +
                df.format(wheelSpeedExpAvgL)
        val right = df.format(wheelCountR.toLong()) + " : " +
                df.format(wheelDistanceR) + " : " +
                df.format(wheelSpeedInstantR) + " : " +
                df.format(wheelSpeedBufferedR) + " : " +
                df.format(wheelSpeedExpAvgR)
        binding.leftWheelCount.text = left
        binding.rightWheelCount.text = right
        binding.soundData.text = audioDataString
        binding.frameRate.text = frameRateString
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
        if (data.wheelData.getLeft().getCounts().size > 0) {
            wheelCountL = data.wheelData.getLeft().getCounts()[0]
            wheelCountR = data.wheelData.getRight().getCounts()[0]
            wheelDistanceL = data.wheelData.getLeft().getDistances()[0]
            wheelDistanceR = data.wheelData.getRight().getDistances()[0]
            wheelSpeedInstantL = data.wheelData.getLeft().getSpeedsInstantaneous()[0]
            wheelSpeedInstantR = data.wheelData.getRight().getSpeedsInstantaneous()[0]
            wheelSpeedBufferedL = data.wheelData.getLeft().getSpeedsBuffered()[0]
            wheelSpeedBufferedR = data.wheelData.getRight().getSpeedsBuffered()[0]
            wheelSpeedExpAvgL = data.wheelData.getLeft().getSpeedsExpAvg()[0]
            wheelSpeedExpAvgR = data.wheelData.getRight().getSpeedsExpAvg()[0]
        }
        if (data.soundData.getLevels().size > 0) {
            val arraySlice = data.soundData.getLevels().copyOfRange(0, 5)
            val df = DecimalFormat("0.#E0")
            val arraySliceString = arraySlice.joinToString(", ") { v -> df.format(v) }
            audioDataString = arraySliceString
        }
        if (data.imageData.images.size > 1) {
            // just taking difference between two but one could do an average over all differences
            val frameRate = 1.0 / ((data.imageData.images[1].timestamp
                    - data.imageData.images[0].timestamp) / 1000000000.0)
            val df = DecimalFormat("#.0000000000000")
            frameRateString = df.format(frameRate)
        }
    }
}

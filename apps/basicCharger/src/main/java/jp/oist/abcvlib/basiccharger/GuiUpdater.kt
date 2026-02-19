package jp.oist.abcvlib.basiccharger

import jp.oist.abcvlib.basiccharger.databinding.ActivityMainBinding
import java.text.DecimalFormat
import kotlin.concurrent.Volatile

class GuiUpdater(private val binding: ActivityMainBinding) {
    private val df = DecimalFormat("#.00")

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

    @Volatile
    var qrDataString: String = ""

    @Volatile
    var objectDetectorString: String = ""


    fun displayGUIValues() {
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
        binding.leftWheelData.text = left
        binding.rightWheelData.text = right
        binding.soundData.text = audioDataString
        binding.frameRate.text = frameRateString
        binding.qrData.text = qrDataString
        binding.objectDetector.text = objectDetectorString
    }
}

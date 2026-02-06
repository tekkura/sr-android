package jp.oist.abcvlib.basicsubscriber

import jp.oist.abcvlib.basicsubscriber.databinding.ActivityMainBinding
import java.text.DecimalFormat
import java.util.Locale
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
        val left = String.format(
            Locale.getDefault(), "%d : %.2f : %.2f : %.2f : %.2f",
            wheelCountL, wheelDistanceL, wheelSpeedInstantL, wheelSpeedBufferedL, wheelSpeedExpAvgL
        )
        val right = String.format(
            Locale.getDefault(), "%d : %.2f : %.2f : %.2f : %.2f",
            wheelCountR, wheelDistanceR, wheelSpeedInstantR, wheelSpeedBufferedR, wheelSpeedExpAvgR
        )
        binding.leftWheelData.text = left
        binding.rightWheelData.text = right
        binding.soundData.text = audioDataString
        binding.frameRate.text = frameRateString
        binding.qrData.text = qrDataString
        binding.objectDetector.text = objectDetectorString
    }
}

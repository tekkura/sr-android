package jp.oist.abcvlib.basicsubscriberPython

import android.app.Activity
import jp.oist.abcvlib.basicsubscriberPython.databinding.ActivityMainBinding
import java.text.DecimalFormat
import kotlin.concurrent.Volatile

class GuiUpdater(
    private val binding: ActivityMainBinding,
    private val activity: Activity
) {
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
    var wheelLeftData: String = ""

    @Volatile
    var wheelRightData: String = ""

    @Volatile
    var audioDataString: String = ""

    @Volatile
    var frameRateString: String = ""

    @Volatile
    var qrDataString: String = ""

    @Volatile
    var objectDetectorString: String = ""

    fun displayValues() {
        activity.runOnUiThread {
            binding.voltageBattLevel.text = df.format(batteryVoltage)
            binding.voltageChargerLevel.text = df.format(chargerVoltage)
            binding.coilVoltageText.text = df.format(coilVoltage)
            binding.tiltAngle.text = df.format(thetaDeg)
            binding.angularVelcoity.text = df.format(angularVelocityDeg)
            binding.leftWheelData.text = wheelLeftData
            binding.rightWheelData.text = wheelRightData
            binding.soundData.text = audioDataString
            binding.frameRate.text = frameRateString
            binding.qrData.text = qrDataString
            binding.objectDetector.text = objectDetectorString
        }
    }
}

package jp.oist.abcvlib.backandforth

import android.os.Bundle
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.util.Logger

/**
 * Android application showing connection to Robot3.1 PCB, and Android Sensors
 * Also includes a simple controller making the robot move back and forth at a set interval and speed
 * @author Christopher Buckley [...](https://github.com/topherbuckley)
 */
class MainActivity : AbcvlibActivity() {
    private var speed: Float = 0.0f
    private var increment: Float = 0.4f

    override fun onCreate(savedInstanceState: Bundle?) {
        // Passes Android App information up to parent classes for various usages. Do not modify
        super.onCreate(savedInstanceState)

        // Setup Android GUI. Point this method to your main activity xml file or corresponding int
        // ID within the R class
        setContentView(R.layout.activity_main)
    }

    // Main loop for any application extending AbcvlibActivity. This is where you will put your main code
    override fun abcvlibMainLoop() {
        Logger.i("BackAndForth", "Current command speed: $speed")
        outputs.setWheelOutput(speed, speed, false, false)
        if (speed >= 1.00f || speed <= -1.00f) {
            increment = -increment
        }
        speed += increment
    }
}
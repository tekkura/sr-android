package jp.oist.abcvlib.backandforth;

import android.os.Bundle;
import jp.oist.abcvlib.util.Logger;
import jp.oist.abcvlib.core.AbcvlibActivity;
/**
 * Android application showing connection to Robot3.1 PCB, and Android Sensors
 * Also includes a simple controller making the robot move back and forth at a set interval and speed
 * @author Christopher Buckley <a href="https://github.com/topherbuckley">...</a>
 */
public class MainActivity extends AbcvlibActivity {
    float speed = 0.0f;
    float increment = 0.4f;

    protected void onCreate(Bundle savedInstanceState) {
        // Passes Android App information up to parent classes for various usages. Do not modify
        super.onCreate(savedInstanceState);

        // Setup Android GUI. Point this method to your main activity xml file or corresponding int
        // ID within the R class
        setContentView(R.layout.activity_main);
    }

    // Main loop for any application extending AbcvlibActivity. This is where you will put your main code
    @Override
    protected void abcvlibMainLoop(){
        Logger.i("BackAndForth", "Current command speed: " + speed);
        outputs.setWheelOutput(speed, speed, false, false);
        if (speed >= 1.00f || speed <= -1.00f) {
            increment = -increment;
        }
        speed += increment;
    }
}
package jp.oist.abcvlib.pidbalancer;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import java.util.concurrent.TimeUnit;

import jp.oist.abcvlib.core.AbcvlibActivity;
import jp.oist.abcvlib.core.inputs.PublisherManager;
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData;
import jp.oist.abcvlib.core.inputs.phone.OrientationData;
import jp.oist.abcvlib.tests.BalancePIDController;
import jp.oist.abcvlib.fragments.PidGuiFragament;
import jp.oist.abcvlib.util.UsbSerial;

/**
 * Android application showing connection to IOIOBoard, Hubee Wheels, and Android Sensors
 * Initializes socket connection with external python server
 * Runs PID controller locally on Android, but takes PID parameters from python GUI
 *
 * @author Christopher Buckley https://github.com/topherbuckley
 */
public class MainActivity extends AbcvlibActivity {

    private final String TAG = getClass().getName();
    private BalancePIDController balancePIDController;
    private PidGuiFragament pidGuiFragament;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // Setup Android GUI. Point this method to your main activity xml file or corresponding int
        // ID within the R class
        setContentView(R.layout.activity_main);

        // Passes Android App information up to parent classes for various usages. Do not modify
        super.onCreate(savedInstanceState);
    }

    public void displayPID_GUI() {
        pidGuiFragament = PidGuiFragament.newInstance(balancePIDController);
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.replace(R.id.main_fragment, pidGuiFragament).commit();
    }

    private void buttonClick(View view) {
        Button button = (Button) view;
        if (button.getText().equals("Start")) {
            // Sets initial values rather than wait for slider change
            pidGuiFragament.updatePID();
            button.setText("Stop");
            balancePIDController.startController();

        } else {
            button.setText("Start");
            balancePIDController.stopController();
        }
    }

    @Override
    public void onSerialReady(UsbSerial usbSerial) {
        // Create your data publisher objects
        PublisherManager publisherManager = new PublisherManager();
        OrientationData orientationData = new OrientationData.Builder(this, publisherManager).build();
        WheelData wheelData = new WheelData.Builder(this, publisherManager).build();
        // Initialize all publishers (i.e. start their threads and data streams)
        publisherManager.initializePublishers();

        // Create your controller/subscriber
        balancePIDController = (BalancePIDController) new BalancePIDController().setInitDelay(0)
                .setName("BalancePIDController").setThreadCount(1)
                .setThreadPriority(Thread.NORM_PRIORITY).setTimestep(5)
                .setTimeUnit(TimeUnit.MILLISECONDS);

        // Attach the controller/subscriber to the publishers
        orientationData.addSubscriber(balancePIDController);
        wheelData.addSubscriber(balancePIDController);

        // Start your publishers
        publisherManager.startPublishers();

        super.onSerialReady(usbSerial);
    }

    @Override
    protected void onOutputsReady() {
        // Adds your custom controller to the compounding master controller.
        getOutputs().getMasterController().addController(balancePIDController);
        // Start the master controller after adding and starting any customer controllers.
        getOutputs().startMasterController();

        runOnUiThread(this::displayPID_GUI);
        super.onOutputsReady();
    }

    @Override
    protected void abcvlibMainLoop() {
        super.abcvlibMainLoop();
    }
}

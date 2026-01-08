package jp.oist.abcvlib.basicsubscriber;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.media.AudioTimestamp;
import android.os.Bundle;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import jp.oist.abcvlib.core.AbcvlibActivity;
import jp.oist.abcvlib.core.inputs.PublisherManager;
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData;
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryDataSubscriber;
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData;
import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber;
import jp.oist.abcvlib.core.inputs.phone.ImageDataRawSubscriber;
import jp.oist.abcvlib.core.inputs.phone.MicrophoneData;
import jp.oist.abcvlib.core.inputs.phone.MicrophoneDataSubscriber;
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorData;
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorDataSubscriber;
import jp.oist.abcvlib.core.inputs.phone.OrientationData;
import jp.oist.abcvlib.core.inputs.phone.OrientationDataSubscriber;
import jp.oist.abcvlib.core.inputs.phone.QRCodeData;
import jp.oist.abcvlib.core.inputs.phone.QRCodeDataSubscriber;
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory;
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException;
import jp.oist.abcvlib.util.SerialCommManager;
import jp.oist.abcvlib.util.SerialReadyListener;
import jp.oist.abcvlib.util.UsbSerial;

/**
 * Most basic Android application showing connection to IOIOBoard and Android Sensors
 * Shows basics of setting up any standard Android Application framework. This MainActivity class
 * implements the various listener interfaces in order to subscribe to updates from various sensor
 * data. Sensor data publishers are running in the background but only write data when a subscriber
 * has been established (via implementing a listener and it's associated method) or a custom
 * {@link jp.oist.abcvlib.core.learning.Trial object has been established} setting
 * up such an assembler will be illustrated in a different module.
 *
 * Optional commented out lines in each listener method show how to write the data to the Android
 * logcat log. As these occur VERY frequently (tens of microseconds) this spams the logcat and such
 * I have reserved them only for when necessary. The updates to the GUI via the GuiUpdate object
 * are intentionally delayed or sampled every 100 ms so as not to spam the GUI thread and make it
 * unresponsive.
 * @author Christopher Buckley https://github.com/topherbuckley
 */
public class MainActivity extends AbcvlibActivity implements SerialReadyListener,
        BatteryDataSubscriber, OrientationDataSubscriber, WheelDataSubscriber,
        MicrophoneDataSubscriber, ImageDataRawSubscriber, QRCodeDataSubscriber,
        ObjectDetectorDataSubscriber {

    private long lastFrameTime = System.nanoTime();
    private GuiUpdater guiUpdater;
    private final String TAG = getClass().getName();
    float speed = 0.35f;
    float increment = 0.01f;
    private PublisherManager publisherManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Setup Android GUI object references such that we can write data to them later.
        setContentView(R.layout.activity_main);

        // Creates an another thread that schedules updates to the GUI every 100 ms. Updaing the GUI every 100 microseconds would bog down the CPU
        ScheduledExecutorServiceWithException executor = new ScheduledExecutorServiceWithException(1, new ProcessPriorityThreadFactory(Thread.MIN_PRIORITY, "GuiUpdates"));
        guiUpdater = new GuiUpdater(this);
        executor.scheduleAtFixedRate(guiUpdater, 0, 100, TimeUnit.MILLISECONDS);

        // Passes Android App information up to parent classes for various usages. Do not modify
        super.onCreate(savedInstanceState);
    }


    @Override
    protected List<String> getRequiredPermissions() {
        return List.of(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
        );
    }

    @Override
    public void onSerialReady(UsbSerial usbSerial){
        /*
         * Each {XXX}Data class has a builder that you can set various construction input parameters
         * with. Neglecting to set them will assume default values. See each class for its corresponding
         * default values and available builder set methods. Context is passed for permission requests,
         * and {XXX}Listeners are what are used to set the subscriber to the {XXX}Data class.
         * The subscriber in this example is this (MainActivity) class. It can equally be any other class
         * that implements the appropriate listener interface.
         */
        publisherManager = new PublisherManager();

        // Note how BatteryData and WheelData objects must have a reference such that they can
        // passed to the SerialCommManager object.
        BatteryData batteryData = new BatteryData.Builder(this, publisherManager).build();
        batteryData.addSubscriber(this);
        WheelData wheelData = new WheelData.Builder(this, publisherManager).build();
        wheelData.addSubscriber(this);

        // These publishers do not need references as they are not passed to the SerialCommManager
//        new ImageDataRaw.Builder(this, publisherManager, this).build().addSubscriber(this);
        new MicrophoneData.Builder(this, publisherManager).build().addSubscriber(this);
        new ObjectDetectorData.Builder(this, publisherManager, this).setPreviewView(findViewById(R.id.camera_x_preview)).build().addSubscriber(this);
        new QRCodeData.Builder(this, publisherManager, this).build().addSubscriber(this);
        new OrientationData.Builder(this, publisherManager).build().addSubscriber(this);

        setSerialCommManager(new SerialCommManager(usbSerial, batteryData, wheelData));
        super.onSerialReady(usbSerial);
    }

    @Override
    public void onOutputsReady() {
        publisherManager.initializePublishers();
        publisherManager.startPublishers();
    }

    // Main loop for any application extending AbcvlibActivity. This is where you will put your main code
    @Override
    protected void abcvlibMainLoop(){
//        Logger.i("basicSubscriber", "Current command speed: " + speed);
        outputs.setWheelOutput(speed, speed, false, false);
        if (speed >= 1.00f || speed <= -1.00f) {
            increment = -increment;
        }
        speed += increment;
    }

    @Override
    public void onBatteryVoltageUpdate(long timestamp, double voltage) {
//        Logger.i(TAG, "Battery Update: Voltage=" + voltage + " Timestemp=" + timestamp);
        guiUpdater.batteryVoltage = voltage; // make volitile
    }

    @Override
    public void onChargerVoltageUpdate(long timestamp, double chargerVoltage, double coilVoltage) {
//        Logger.i(TAG, "Charger Update: Voltage=" + voltage + " Timestemp=" + timestamp);
        guiUpdater.chargerVoltage = chargerVoltage;
        guiUpdater.coilVoltage = coilVoltage;
    }

    @Override
    public void onOrientationUpdate(long timestamp, double thetaRad, double angularVelocityRad) {
//        Logger.i(TAG, "Orientation Data Update: Timestamp=" + timestamp + " thetaRad=" + thetaRad
//                + " angularVelocity=" + angularVelocityRad);
//
        // You can also convert them to degrees using the following static utility methods.
        double thetaDeg = OrientationData.getThetaDeg(thetaRad);
        double angularVelocityDeg = OrientationData.getAngularVelocityDeg(angularVelocityRad);
        guiUpdater.thetaDeg = thetaDeg;
        guiUpdater.angularVelocityDeg = angularVelocityDeg;
    }

    @Override
    public void onWheelDataUpdate(long timestamp, int wheelCountL, int wheelCountR,
                                  double wheelDistanceL, double wheelDistanceR,
                                  double wheelSpeedInstantL, double wheelSpeedInstantR,
                                  double wheelSpeedBufferedL, double wheelSpeedBufferedR,
                                  double wheelSpeedExpAvgL, double wheelSpeedExpAvgR) {
//        Logger.i(TAG, "Wheel Data Update: Timestamp=" + timestamp + " countLeft=" + countLeft +
//                " countRight=" + countRight);
//        double distanceLeft = WheelData.countsToDistance(countLeft);
        guiUpdater.wheelCountL = wheelCountL;
        guiUpdater.wheelCountR = wheelCountR;
        guiUpdater.wheelDistanceL = wheelDistanceL;
        guiUpdater.wheelDistanceR = wheelDistanceR;
        guiUpdater.wheelSpeedInstantL = wheelSpeedInstantL;
        guiUpdater.wheelSpeedInstantR = wheelSpeedInstantR;
        guiUpdater.wheelSpeedBufferedL = wheelSpeedBufferedL;
        guiUpdater.wheelSpeedBufferedR = wheelSpeedBufferedR;
        guiUpdater.wheelSpeedExpAvgL = wheelSpeedExpAvgL;
        guiUpdater.wheelSpeedExpAvgR = wheelSpeedExpAvgR;
    }

    /**
     * Takes the first 10 samples from the sampled audio data and sends them to the GUI.
     * @param audioData most recent sampling from buffer
     * @param numSamples number of samples copied from buffer
     */
    @Override
    public void onMicrophoneDataUpdate(float[] audioData, int numSamples, int sampleRate, AudioTimestamp startTime, AudioTimestamp endTime) {
        float[] arraySlice = Arrays.copyOfRange(audioData, 0, 5);
        DecimalFormat df = new DecimalFormat("0.#E0");
        String arraySliceString = "";
        for (double v : arraySlice) {
            arraySliceString = arraySliceString.concat(df.format(v)) + ", ";
        }
//        Logger.i("MainActivity", "Microphone Data Update: First 10 Samples=" + arraySliceString +
//                " of " + numSamples + " total samples");
        guiUpdater.audioDataString = arraySliceString;
    }

    /**
     * Calculates the frame rate and sends it to the GUI. Other input params are ignored in this
     * example, but one could process each bitmap as necessary here.
     * @param timestamp in nanoseconds see {@link System#nanoTime()}
     * @param width in pixels
     * @param height in pixels
     * @param bitmap compressed bitmap object
     */
    @Override
    public void onImageDataRawUpdate(long timestamp, int width, int height, Bitmap bitmap) {
//        Logger.i(TAG, "Image Data Update: Timestamp=" + timestamp + " dims=" + width + " x "
//                + height);
        double frameRate = 1.0 / ((System.nanoTime() - lastFrameTime) / 1000000000.0);
        lastFrameTime = System.nanoTime();
        frameRate = Math.round(frameRate);
        guiUpdater.frameRateString = String.format(Locale.JAPAN,"%.0f", frameRate);
    }

    @Override
    public void onQRCodeDetected(String qrDataDecoded) {
        guiUpdater.qrDataString = qrDataDecoded;
    }

    @Override
    public void onObjectsDetected(Bitmap bitmap, TensorImage tensorImage, List<Detection> results, long inferenceTime, int height, int width) {
        try{
            // Note you can also get the bounding box here. See https://www.tensorflow.org/lite/api_docs/java/org/tensorflow/lite/task/vision/detector/Detection
            Category category = results.get(0).getCategories().get(0); //todo not sure if there will ever be more than one category (multiple detections). If so are they ordered by higheest score?
            String label = category.getLabel();
            @SuppressLint("DefaultLocale") String score = String.format("%.2f", category.getScore());
            @SuppressLint("DefaultLocale") String time = String.format("%d", inferenceTime);
            guiUpdater.objectDetectorString = label + " : " + score + " : " + time + "ms";
        }catch (IndexOutOfBoundsException e){
            guiUpdater.objectDetectorString = "No results from ObjectDetector";
        }
    }
}


package jp.oist.abcvlib.basiccharger;

import static java.lang.Math.abs;
import static java.lang.Math.random;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.AudioTimestamp;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;

import androidx.camera.view.PreviewView;

import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.vision.detector.Detection;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jp.oist.abcvlib.basiccharger.R;
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
        BatteryDataSubscriber, WheelDataSubscriber,
        ObjectDetectorDataSubscriber {

    private long lastFrameTime = System.nanoTime();
    private GuiUpdater guiUpdater;
    private final String TAG = getClass().getName();
    private float speedL = 0.0f;
    private float speedR = 0.0f;
    private float forwardBias = 0.3f; // for moving forward while centering
    private float p_controller = 0.2f;
    private float leftWheelCompensation = 1.1f;
    private PublisherManager publisherManager;
    private OverlayView overlayView;
    private PreviewView previewView;
    private enum StateX{
        UNCENTERED,
        CENTERED,
    }
    private enum StateY{
        CLOSE_TO_BOTTOM,
        FAR_FROM_BOTTOM,
    }
    private enum Action{
        SEARCHING,
        APPROACH,
        MOUNT,
        DISMOUNT,
        RESET
    }
    private StateX stateX = StateX.UNCENTERED;
    private StateY stateY = StateY.FAR_FROM_BOTTOM;
    private Action action = Action.APPROACH;
    private int centeredPuck = 0;
    private int centeredPuckLimit = 5;
    private int puckCloseToBottom = 0;
    private int puckCloseToBottomLimit = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Setup Android GUI object references such that we can write data to them later.
        setContentView(R.layout.activity_main);
        previewView = findViewById(R.id.camera_x_preview);
        overlayView = findViewById(R.id.overlayView);

        // Creates an another thread that schedules updates to the GUI every 100 ms. Updaing the GUI every 100 microseconds would bog down the CPU
        ScheduledExecutorServiceWithException executor = new ScheduledExecutorServiceWithException(1, new ProcessPriorityThreadFactory(Thread.MIN_PRIORITY, "GuiUpdates"));
        guiUpdater = new GuiUpdater(this);
        executor.scheduleAtFixedRate(guiUpdater, 0, 100, TimeUnit.MILLISECONDS);

        // Passes Android App information up to parent classes for various usages. Do not modify
        super.onCreate(savedInstanceState);

        // Get dimensions of the PreviewView after it is laid out
        previewView.post(() -> {
            int previewWidth = previewView.getWidth();
            int previewHeight = previewView.getHeight();
            overlayView.setPreviewDimensions(previewWidth, previewHeight);
        });
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

        new ObjectDetectorData.Builder(this, publisherManager, this)
                .setModel("model.tflite")
                .setPreviewView(previewView)
                .build()
                .addSubscriber(this);

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
        Log.d("MAIN_LOOP", "speedL: " + speedL + " speedR: " + speedR);
        outputs.setWheelOutput(speedL, speedR, false, false);
    }

    @Override
    public void onBatteryVoltageUpdate(long timestamp, double voltage) {
//        Log.i(TAG, "Battery Update: Voltage=" + voltage + " Timestemp=" + timestamp);
        guiUpdater.batteryVoltage = voltage; // make volitile
    }

    @Override
    public void onChargerVoltageUpdate(long timestamp, double chargerVoltage, double coilVoltage) {
//        Log.i(TAG, "Charger Update: Voltage=" + voltage + " Timestemp=" + timestamp);
        guiUpdater.chargerVoltage = chargerVoltage;
        guiUpdater.coilVoltage = coilVoltage;
    }

    @Override
    public void onWheelDataUpdate(long timestamp, int wheelCountL, int wheelCountR,
                                  double wheelDistanceL, double wheelDistanceR,
                                  double wheelSpeedInstantL, double wheelSpeedInstantR,
                                  double wheelSpeedBufferedL, double wheelSpeedBufferedR,
                                  double wheelSpeedExpAvgL, double wheelSpeedExpAvgR) {
//        Log.i(TAG, "Wheel Data Update: Timestamp=" + timestamp + " countLeft=" + countLeft +
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

    @Override
    public void onObjectsDetected(Bitmap bitmap, TensorImage tensorImage, List<Detection> results, long inferenceTime, int height, int width) {
        // for some unknown reason I cannot get the image roated properly before this point so hack
        int height_ = width;
        width = height;
        height = height_;
        try{
            // Note you can also get the bounding box here. See https://www.tensorflow.org/lite/api_docs/java/org/tensorflow/lite/task/vision/detector/Detection
            boolean puckDetected = false;
            Category category = null;
            RectF boundingBox = null;
            String label = "";

            for (Detection result : results) {
                Category currentCategory = result.getCategories().get(0);
                if (currentCategory.getLabel().equals("puck")) {
                    puckDetected = true;
                    category = currentCategory;
                    label = category.getLabel();
                    boundingBox = result.getBoundingBox();
                    Log.d("PUCK", "Puck detected");
                    break;
                }
            }

            overlayView.setImageDimensions(width, height);
            if (puckDetected && boundingBox != null) overlayView.setRect(boundingBox);
            puckMountController(puckDetected, puckDetected ? boundingBox.centerX() : 0, puckDetected ? boundingBox.centerY() : 0, width, height);
            if (category != null) {
                @SuppressLint("DefaultLocale") String score = String.format("%.2f", category.getScore());
                @SuppressLint("DefaultLocale") String time = String.format("%d", inferenceTime);
                Log.v("PUCK", "ObjectDetector: " + label + " : " + score + " : " + time + "ms");
                guiUpdater.objectDetectorString = label + " : " + score + " : " + time + "ms";
            } else {
                guiUpdater.objectDetectorString = "No puck detected";
            }
        }catch (IndexOutOfBoundsException e){
            guiUpdater.objectDetectorString = "No results from ObjectDetector";
        }
    }

    public void puckMountController(boolean visible, float centerX, float centerY, int width, int height){
        /**
         * Center puck
         * Move forward only when puck centered within range
         * After centerY is close enough to bottom of image, override, and move forward for X seconds
         */

        if (action == Action.MOUNT){
            // Ignore all else and just continue routine
        }
        else if (action == Action.APPROACH && visible){
            float errorX = 2f * ((centerX / width) - 0.5f); // Error normalized from -1 to 1 from horizontal center
            float errorY = 1 - (centerY / height); // Error normalized to 1 from bottom. 1 - as origin apparently at top of image
            Log.v("PUCK", "ErrorX: " + errorX + " ErrorY: " + errorY);
            Log.v("PUCK", "centeredPuck: " + centeredPuck + " puckCloseToBottom: " + puckCloseToBottom);

            // Implement hysteresis on both error signals to prevent jitter
            float centeredLowerThreshold = 0.5f;
            float centeredUpperThreshold = 0.1f;
            float closeLowerThreshold = 0.1f;
            float closeUpperThreshold = 0.2f;

            if (abs(errorX) < centeredLowerThreshold){
                centeredPuck++;
            }else if (abs(errorX) > centeredUpperThreshold){
                centeredPuck = 0;
            }
            if (abs(errorY) < closeLowerThreshold){
                puckCloseToBottom++;
            }else if (abs(errorY) > closeUpperThreshold){
                puckCloseToBottom = 0;
            }

            if (centeredPuck >= centeredPuckLimit && puckCloseToBottom >= puckCloseToBottomLimit) {
                mount();
            }else{
                approach(errorX, errorY);
            }
        }
        else if (action == Action.APPROACH){
            // Implied !visible
            searching();
        }
    }

    private void searching(){
//        action = Action.SEARCHING;
        Log.v("PUCK", "Action.SEARCHING");
        speedL = 0.3f;
        speedR = -0.3f;
    }

    private void approach(float errorX, float errorY){
        action = Action.APPROACH;
        Log.i("PUCK", "Action.APPROACH");

        speedL = (-errorX * p_controller) + forwardBias;
        speedR = (errorX * p_controller) + forwardBias;
    }

    private void mount(){
        action = Action.MOUNT;
        Log.i("PUCK", "Action.MOUNT");
        speedL = 1f;
        speedR = 1f;
        // start async timer task to call dismount 5 seconds later
        ScheduledExecutorService actionExecutor = Executors.newSingleThreadScheduledExecutor();
        actionExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                stop();
                ScheduledExecutorService actionExecutor = Executors.newSingleThreadScheduledExecutor();
                actionExecutor.schedule(() -> dismount(), 5, TimeUnit.SECONDS);
            }
        }, 1000, TimeUnit.MILLISECONDS);
    }

    private void dismount(){
        action = Action.DISMOUNT;
        Log.i("PUCK", "Action.DISMOUNT");
        speedL = -1.0f;
        speedR = -1.0f;
        // start async timer task to call reset 3 seconds later
        ScheduledExecutorService actionExecutor = Executors.newSingleThreadScheduledExecutor();
        actionExecutor.schedule(this::reset, 2, TimeUnit.SECONDS);
    }

    private void reset(){
        action = Action.RESET;
        Log.i("PUCK", "Action.RESET");
        // Turn in random direction for 3 seconds then set state to APPROACH
        Random random = new Random();
        int direction = random.nextBoolean() ? 1 : -1;
        speedL = direction * 1.0f;
        speedR = -direction * 1.0f;
        // start async timer task to set action to APPROACH 3 seconds later
        ScheduledExecutorService actionExecutor = Executors.newSingleThreadScheduledExecutor();
        actionExecutor.schedule(() -> action = Action.APPROACH, 2, TimeUnit.SECONDS);
    }

    private void stop(){
        speedL = 0.0f;
        speedR = 0.0f;
    }
}


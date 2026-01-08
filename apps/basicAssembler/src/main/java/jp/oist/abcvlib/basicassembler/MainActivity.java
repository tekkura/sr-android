package jp.oist.abcvlib.basicassembler;

import android.os.Bundle;

import java.util.List;
import java.util.concurrent.TimeUnit;

import jp.oist.abcvlib.core.AbcvlibActivity;
import jp.oist.abcvlib.core.AbcvlibLooper;
import jp.oist.abcvlib.core.IOReadyListener;
import jp.oist.abcvlib.core.inputs.PublisherManager;
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer;
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData;
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData;
import jp.oist.abcvlib.core.inputs.phone.ImageDataRaw;
import jp.oist.abcvlib.core.inputs.phone.MicrophoneData;
import jp.oist.abcvlib.core.inputs.phone.OrientationData;
import jp.oist.abcvlib.core.learning.ActionSpace;
import jp.oist.abcvlib.core.learning.CommActionSpace;
import jp.oist.abcvlib.core.learning.MetaParameters;
import jp.oist.abcvlib.core.learning.MotionActionSpace;
import jp.oist.abcvlib.core.learning.StateSpace;
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory;
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException;
import jp.oist.abcvlib.util.SerialCommManager;
import jp.oist.abcvlib.util.SerialReadyListener;
import jp.oist.abcvlib.util.UsbSerial;

/**
 * Most basic Android application showing connection to IOIOBoard and Android Sensors
 * Shows basics of setting up any standard Android Application framework. This MainActivity class
 * does not implement the various listener interfaces in order to subscribe to updates from various
 * sensor data, but instead sets up a custom
 * {@link jp.oist.abcvlib.core.learning.Trial} object that handles setting up the
 * subscribers and assembles the data into a {@link TimeStepDataBuffer}
 * comprising of multiple {@link TimeStepDataBuffer.TimeStepData} objects
 * that each represent all the data gathered from one or more sensors over the course of one timestep
 *
 * Optional commented out lines in each listener method show how to write the data to the Android
 * logcat log. As these occur VERY frequently (tens of microseconds) this spams the logcat and such
 * I have reserved them only for when necessary. The updates to the GUI via the GuiUpdate object
 * are intentionally delayed or sampled every 100 ms so as not to spam the GUI thread and make it
 * unresponsive.
 * @author Christopher Buckley https://github.com/topherbuckley
 */
public class MainActivity extends AbcvlibActivity implements SerialReadyListener {

    private GuiUpdater guiUpdater;
    private final int maxEpisodeCount = 3;
    private final int maxTimeStepCount = 40;
    private StateSpace stateSpace;
    private ActionSpace actionSpace;
    private TimeStepDataBuffer timeStepDataBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        // Setup Android GUI object references such that we can write data to them later.
        setContentView(R.layout.activity_main);

        // Creates an another thread that schedules updates to the GUI every 100 ms. Updaing the GUI every 100 microseconds would bog down the CPU
        ScheduledExecutorServiceWithException executor = new ScheduledExecutorServiceWithException(1, new ProcessPriorityThreadFactory(Thread.MIN_PRIORITY, "GuiUpdates"));
        guiUpdater = new GuiUpdater(this, maxTimeStepCount, maxEpisodeCount);
        executor.scheduleAtFixedRate(guiUpdater, 0, 100, TimeUnit.MILLISECONDS);

        timeStepDataBuffer = new TimeStepDataBuffer(10);

        // Passes Android App information up to parent classes for various usages. Do not modify
        super.onCreate(savedInstanceState);
    }


    @Override
    protected List<String> getRequiredPermissions() {
        return List.of(android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
        );
    }

    @Override
    public void onSerialReady(UsbSerial usbSerial) {
        /*------------------------------------------------------------------------------
        ------------------------------ Define Action Space -----------------------------
        --------------------------------------------------------------------------------
         */
        // Defining custom actions
        CommActionSpace commActionSpace = new CommActionSpace(3);
        commActionSpace.addCommAction("action1", (byte) 0); // I'm just overwriting an existing to show how
        commActionSpace.addCommAction("action2", (byte) 1);
        commActionSpace.addCommAction("action3", (byte) 2);

        MotionActionSpace motionActionSpace = new MotionActionSpace(5);
        motionActionSpace.addMotionAction("stop", (byte) 0, 0, 0, false, false); // I'm just overwriting an existing to show how
        motionActionSpace.addMotionAction("forward", (byte) 1, 1, 1, false, false);
        motionActionSpace.addMotionAction("backward", (byte) 2, -1, -1, false, false);
        motionActionSpace.addMotionAction("left", (byte) 3, -1, 1, false, false);
        motionActionSpace.addMotionAction("right", (byte) 4, 1, -1, false, false);

        actionSpace = new ActionSpace(commActionSpace, motionActionSpace);

        /*------------------------------------------------------------------------------
        ------------------------------ Define State Space ------------------------------
        --------------------------------------------------------------------------------
         */

        PublisherManager publisherManager = new PublisherManager();

        WheelData wheelData = new WheelData.Builder(this, publisherManager)
                .setBufferLength(50)
                .setExpWeight(0.01)
                .build();
        wheelData.addSubscriber(timeStepDataBuffer);

        BatteryData batteryData = new BatteryData.Builder(this, publisherManager).build();
        batteryData.addSubscriber(timeStepDataBuffer);

        MicrophoneData microphoneData = new MicrophoneData.Builder(this, publisherManager).build();
        microphoneData.addSubscriber(timeStepDataBuffer);

        ImageDataRaw imageDataRaw = new ImageDataRaw.Builder(this, publisherManager, this)
                .setPreviewView(findViewById(R.id.camera_x_preview)).build();
        imageDataRaw.addSubscriber(timeStepDataBuffer);

        OrientationData orientationData = new OrientationData.Builder(this, publisherManager).build();
        orientationData.addSubscriber(timeStepDataBuffer);

        stateSpace = new StateSpace(publisherManager);
        setSerialCommManager(new SerialCommManager(usbSerial, batteryData, wheelData));
        super.onSerialReady(usbSerial);
    }

    @Override
    protected void onOutputsReady(){
        /*------------------------------------------------------------------------------
        ------------------------------ Set MetaParameters ------------------------------
        --------------------------------------------------------------------------------
         */
        // Note this whole block is not in onCreate because `outputs` is not initialized until onSerialReady is called
        MetaParameters metaParameters = new MetaParameters(this, 100, maxTimeStepCount,
                100, maxEpisodeCount, null, timeStepDataBuffer, getOutputs(), 1);
        /*------------------------------------------------------------------------------
        ------------------------------ Initialize and Start Trial ----------------------
        --------------------------------------------------------------------------------
         */
        MyTrial myTrial = new MyTrial(this, guiUpdater, metaParameters, actionSpace, stateSpace);
        myTrial.startTrail();
    }
}


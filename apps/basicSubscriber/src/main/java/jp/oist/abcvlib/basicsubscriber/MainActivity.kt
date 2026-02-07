package jp.oist.abcvlib.basicsubscriber

import android.graphics.Bitmap
import android.media.AudioTimestamp
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import jp.oist.abcvlib.basicsubscriber.databinding.ActivityMainBinding
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryDataSubscriber
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.ImageDataRawSubscriber
import jp.oist.abcvlib.core.inputs.phone.MicrophoneData
import jp.oist.abcvlib.core.inputs.phone.MicrophoneDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorData
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.OrientationData
import jp.oist.abcvlib.core.inputs.phone.OrientationDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.QRCodeData
import jp.oist.abcvlib.core.inputs.phone.QRCodeDataSubscriber
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Most basic Android application showing connection to IOIOBoard and Android Sensors
 * Shows basics of setting up any standard Android Application framework. This MainActivity class
 * implements the various listener interfaces in order to subscribe to updates from various sensor
 * data. Sensor data publishers are running in the background but only write data when a subscriber
 * has been established (via implementing a listener, and it's associated method) or a custom
 * [object has been established][jp.oist.abcvlib.core.learning.Trial] setting
 * up such an assembler will be illustrated in a different module.
 * 
 * Optional commented out lines in each listener method show how to write the data to the Android
 * logcat log. As these occur VERY frequently (tens of microseconds) this spams the logcat and such
 * I have reserved them only for when necessary. The updates to the GUI via the GuiUpdate object
 * are intentionally delayed or sampled every 100 ms so as not to spam the GUI thread and make it
 * unresponsive.
 * @author Christopher Buckley https://github.com/topherbuckley
 */
class MainActivity : AbcvlibActivity(), SerialReadyListener, BatteryDataSubscriber,
    OrientationDataSubscriber, WheelDataSubscriber, MicrophoneDataSubscriber,
    ImageDataRawSubscriber, QRCodeDataSubscriber, ObjectDetectorDataSubscriber {
    private lateinit var binding: ActivityMainBinding
    private lateinit var guiUpdater: GuiUpdater
    private var lastFrameTime = System.nanoTime()
    private var speed: Float = 0.35f
    private var increment: Float = 0.01f
    private lateinit var publisherManager: PublisherManager


    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initUiUpdater()

        super.onCreate(savedInstanceState)
    }

    /**
     * Refreshes UI values every 100ms.
     */
    private fun initUiUpdater() {
        guiUpdater = GuiUpdater(binding)
        lifecycleScope.launch {
            while (isActive) {
                guiUpdater.displayGUIValues()
                delay(100.milliseconds)
            }
        }
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        /*
         * Each {XXX}Data class has a builder that you can set various construction input parameters
         * with. Neglecting to set them will assume default values. See each class for its corresponding
         * default values and available builder set methods. Context is passed for permission requests,
         * and {XXX}Listeners are what are used to set the subscriber to the {XXX}Data class.
         * The subscriber in this example is this (MainActivity) class. It can equally be any other class
         * that implements the appropriate listener interface.
         */
        publisherManager = PublisherManager()

        // Note how BatteryData and WheelData objects must have a reference such that they can
        // be passed to the SerialCommManager object.
        val batteryData = BatteryData.Builder(this, publisherManager).build()
        batteryData.addSubscriber(this)
        val wheelData = WheelData.Builder(this, publisherManager).build()
        wheelData.addSubscriber(this)

        // These publishers do not need references as they are not passed to the SerialCommManager
        OrientationData.Builder(this, publisherManager).build().addSubscriber(this)
        // new ImageDataRaw.Builder(this, publisherManager, this).build().addSubscriber(this);
        MicrophoneData.Builder(this, publisherManager).build().addSubscriber(this)
        ObjectDetectorData.Builder(this, publisherManager, this)
            .setPreviewView(binding.cameraXPreview).build()
            .addSubscriber(this)
        QRCodeData.Builder(this, publisherManager, this).build().addSubscriber(this)

        setSerialCommManager(SerialCommManager(usbSerial, batteryData, wheelData))
        super.onSerialReady(usbSerial)
    }

    public override fun onOutputsReady() {
        publisherManager.initializePublishers()
        publisherManager.startPublishers()
    }

    // Main loop for any application extending AbcvlibActivity. This is where you will put your main code
    override fun abcvlibMainLoop() {
        // Logger.i("basicSubscriber", "Current command speed: $speed");
        outputs.setWheelOutput(speed, speed, false, false)
        if (speed >= 1.00f || speed <= -1.00f) {
            increment = -increment
        }
        speed += increment
    }

    override fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
        // Logger.i(TAG, "Battery Update: Voltage=$voltage Timestamp=$timestamp");
        guiUpdater.batteryVoltage = voltage // make volatile
    }

    override fun onChargerVoltageUpdate(
        timestamp: Long,
        chargerVoltage: Double,
        coilVoltage: Double
    ) {
        //Logger.i(TAG, "Charger Update: Voltage=$chargerVoltage Timestamp=$timestamp");
        guiUpdater.chargerVoltage = chargerVoltage
        guiUpdater.coilVoltage = coilVoltage
    }

    override fun onOrientationUpdate(
        timestamp: Long,
        thetaRad: Double,
        angularVelocityRad: Double
    ) {
        // Logger.i(TAG, "Orientation Data Update: Timestamp=" + timestamp + " thetaRad=" + thetaRad
        //         + " angularVelocity=" + angularVelocityRad);
        // You can also convert them to degrees using the following static utility methods.
        val thetaDeg = OrientationData.getThetaDeg(thetaRad)
        val angularVelocityDeg = OrientationData.getAngularVelocityDeg(angularVelocityRad)
        guiUpdater.thetaDeg = thetaDeg
        guiUpdater.angularVelocityDeg = angularVelocityDeg
    }

    override fun onWheelDataUpdate(
        timestamp: Long, wheelCountL: Int, wheelCountR: Int,
        wheelDistanceL: Double, wheelDistanceR: Double,
        wheelSpeedInstantL: Double, wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double, wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double, wheelSpeedExpAvgR: Double
    ) {
        // Logger.i(TAG, "Wheel Data Update: Timestamp=$timestamp countLeft=$countLeft countRight=$countRight");
        // double distanceLeft = WheelData.countsToDistance(countLeft);
        guiUpdater.wheelCountL = wheelCountL
        guiUpdater.wheelCountR = wheelCountR
        guiUpdater.wheelDistanceL = wheelDistanceL
        guiUpdater.wheelDistanceR = wheelDistanceR
        guiUpdater.wheelSpeedInstantL = wheelSpeedInstantL
        guiUpdater.wheelSpeedInstantR = wheelSpeedInstantR
        guiUpdater.wheelSpeedBufferedL = wheelSpeedBufferedL
        guiUpdater.wheelSpeedBufferedR = wheelSpeedBufferedR
        guiUpdater.wheelSpeedExpAvgL = wheelSpeedExpAvgL
        guiUpdater.wheelSpeedExpAvgR = wheelSpeedExpAvgR
    }

    /**
     * Takes the first 10 samples from the sampled audio data and sends them to the GUI.
     * @param audioData most recent sampling from buffer
     * @param numSamples number of samples copied from buffer
     */
    override fun onMicrophoneDataUpdate(
        audioData: FloatArray,
        numSamples: Int,
        sampleRate: Int,
        startTime: AudioTimestamp,
        endTime: AudioTimestamp
    ) {
        val arraySlice = audioData.copyOfRange(0, 5)
        val df = DecimalFormat("0.#E0")
        val arraySliceString = arraySlice.joinToString(", ") { v -> df.format(v) }
        // Logger.i("MainActivity", "Microphone Data Update: First 10 Samples=" + arraySliceString +
        // " of " + numSamples + " total samples");
        guiUpdater.audioDataString = arraySliceString
    }

    /**
     * Calculates the frame rate and sends it to the GUI. Other input params are ignored in this
     * example, but one could process each bitmap as necessary here.
     * @param timestamp in nanoseconds see [System.nanoTime]
     * @param width in pixels
     * @param height in pixels
     * @param bitmap compressed bitmap object
     */
    override fun onImageDataRawUpdate(timestamp: Long, width: Int, height: Int, bitmap: Bitmap) {
        // Logger.i(TAG, "Image Data Update: Timestamp=$timestamp dims=$width x $height");
        var frameRate = 1.0 / ((System.nanoTime() - lastFrameTime) / 1000000000.0)
        lastFrameTime = System.nanoTime()
        frameRate = frameRate.roundToInt().toDouble()
        guiUpdater.frameRateString = String.format(Locale.getDefault(), "%.0f", frameRate)
    }

    override fun onQRCodeDetected(qrDataDecoded: String) {
        guiUpdater.qrDataString = qrDataDecoded
    }

    override fun onObjectsDetected(
        bitmap: Bitmap,
        tensorImage: TensorImage,
        results: MutableList<Detection>,
        inferenceTime: Long,
        height: Int,
        width: Int
    ) {
        try {
            // Note you can also get the bounding box here. See https://www.tensorflow.org/lite/api_docs/java/org/tensorflow/lite/task/vision/detector/Detection
            val category =
                results[0].categories[0] //todo not sure if there will ever be more than one category (multiple detections). If so are they ordered by highest score?
            val label = category.label
            val score = String.format(Locale.getDefault(), "%.2f", category.score)
            val time = String.format(Locale.getDefault(), "%d", inferenceTime)
            guiUpdater.objectDetectorString = label + " : " + score + " : " + time + "ms"
        } catch (e: IndexOutOfBoundsException) {
            guiUpdater.objectDetectorString = "No results from ObjectDetector"
        }
    }
}


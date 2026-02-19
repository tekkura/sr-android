package jp.oist.abcvlib.basiccharger

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Bundle
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import jp.oist.abcvlib.basiccharger.databinding.ActivityMainBinding
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryDataSubscriber
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorData
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorDataSubscriber
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.label.Category
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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
    WheelDataSubscriber, ObjectDetectorDataSubscriber {
    private val TAG: String = javaClass.name

    private var speedL = 0.0f
    private var speedR = 0.0f
    private val forwardBias = 0.5f // for moving forward while centering
    private val pController = 0.2f

    private lateinit var binding: ActivityMainBinding
    private lateinit var publisherManager: PublisherManager
    private lateinit var guiUpdater: GuiUpdater
    private lateinit var overlayView: OverlayView
    private lateinit var previewView: PreviewView

    private enum class StateX {
        UNCENTERED,
        CENTERED,
    }

    private enum class StateY {
        CLOSE_TO_BOTTOM,
        FAR_FROM_BOTTOM,
    }

    private enum class Action {
        SEARCHING,
        APPROACH,
        MOUNT,
        DISMOUNT,
        RESET
    }

    private val stateX = StateX.UNCENTERED
    private val stateY = StateY.FAR_FROM_BOTTOM
    private var action = Action.APPROACH
    private var centeredPuck = 0
    private val centeredPuckLimit = 5
    private var puckCloseToBottom = 0
    private val puckCloseToBottomLimit = 2


    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        previewView = binding.cameraXPreview
        overlayView = binding.overlayView


        guiUpdater = GuiUpdater(binding)

        // Creates coroutine that schedules updates to the GUI every 100 ms. Updating the GUI every 100 microseconds would bog down the CPU
        lifecycleScope.launch {
            while (isActive) {
                guiUpdater.displayGUIValues()
                delay(100.milliseconds)
            }
        }

        // Passes Android App information up to parent classes for various usages. Do not modify
        super.onCreate(savedInstanceState)

        // Get dimensions of the PreviewView after it is laid out
        previewView.post {
            val previewWidth = previewView.width
            val previewHeight = previewView.height
            overlayView.setPreviewDimensions(previewWidth, previewHeight)
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

        ObjectDetectorData.Builder(this, publisherManager, this)
            .setModel("model.tflite")
            .setPreviewView(previewView)
            .build()
            .addSubscriber(this)

        setSerialCommManager(SerialCommManager(usbSerial, batteryData, wheelData))
        super.onSerialReady(usbSerial)
    }

    public override fun onOutputsReady() {
        publisherManager.initializePublishers()
        publisherManager.startPublishers()
    }

    // Main loop for any application extending AbcvlibActivity. This is where you will put your main code
    override fun abcvlibMainLoop() {
        Logger.d("MAIN_LOOP", "speedL: $speedL speedR: $speedR")
        outputs.setWheelOutput(speedL, speedR, false, false)
    }

    override fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
        // Logger.i(TAG, "Battery Update: Voltage=" + voltage + " Timestamp=" + timestamp);
        guiUpdater.batteryVoltage = voltage
    }

    override fun onChargerVoltageUpdate(
        timestamp: Long,
        chargerVoltage: Double,
        coilVoltage: Double
    ) {
        // Logger.i(TAG, "Charger Update: Voltage=" + voltage + " Timestamp=" + timestamp);
        guiUpdater.chargerVoltage = chargerVoltage
        guiUpdater.coilVoltage = coilVoltage
    }

    override fun onWheelDataUpdate(
        timestamp: Long, wheelCountL: Int, wheelCountR: Int,
        wheelDistanceL: Double, wheelDistanceR: Double,
        wheelSpeedInstantL: Double, wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double, wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double, wheelSpeedExpAvgR: Double
    ) {
        // Logger.i(TAG, "Wheel Data Update: Timestamp=" + timestamp + " countLeft=" + countLeft + " countRight=" + countRight);
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

    override fun onObjectsDetected(
        bitmap: Bitmap,
        tensorImage: TensorImage,
        results: MutableList<Detection>,
        inferenceTime: Long,
        height: Int,
        width: Int
    ) {
        // for some unknown reason I cannot get the image rotated properly before this point so hack
        val rotatedWidth = height
        val rotatedHeight = width

        try {
            // Note you can also get the bounding box here. See https://www.tensorflow.org/lite/api_docs/java/org/tensorflow/lite/task/vision/detector/Detection
            var puckDetected = false
            var category: Category? = null
            var boundingBox: RectF? = null
            var label = ""

            for (result in results) {
                val currentCategory = result.categories[0]
                if (currentCategory.label == "puck") {
                    puckDetected = true
                    category = currentCategory
                    label = category.label
                    boundingBox = result.boundingBox
                    Logger.d("PUCK", "Puck detected")
                    break
                }
            }

            overlayView.setImageDimensions(rotatedWidth, rotatedHeight)
            if (puckDetected && boundingBox != null) overlayView.setRect(boundingBox)

            puckMountController(
                puckDetected,
                if (puckDetected) boundingBox!!.centerX() else 0f,
                if (puckDetected) boundingBox!!.centerY() else 0f,
                rotatedWidth,
                rotatedHeight
            )
            if (category != null) {
                val score = String.format(Locale.getDefault(), "%.2f", category.score)
                val time = String.format(Locale.getDefault(), "%d", inferenceTime)
                Logger.v("PUCK", "ObjectDetector: " + label + " : " + score + " : " + time + "ms")
                guiUpdater.objectDetectorString = label + " : " + score + " : " + time + "ms"
            } else {
                guiUpdater.objectDetectorString = "No puck detected"
            }
        } catch (e: IndexOutOfBoundsException) {
            guiUpdater.objectDetectorString = "No results from ObjectDetector"
        }
    }

    private fun puckMountController(
        visible: Boolean,
        centerX: Float,
        centerY: Float,
        width: Int,
        height: Int
    ) {
        /**
         * Center puck
         * Move forward only when puck centered within range
         * After centerY is close enough to bottom of image, override, and move forward for X seconds
         */

        if (action == Action.MOUNT) {
            // Ignore all else and just continue routine
        } else if (action == Action.APPROACH && visible) {
            val errorX =
                2f * ((centerX / width) - 0.5f) // Error normalized from -1 to 1 from horizontal center
            val errorY =
                1 - (centerY / height) // Error normalized to 1 from bottom. 1 - as origin apparently at top of image
            Logger.v("PUCK", "ErrorX: $errorX ErrorY: $errorY")
            Logger.v(
                "PUCK",
                "centeredPuck: $centeredPuck puckCloseToBottom: $puckCloseToBottom"
            )

            // Implement hysteresis on both error signals to prevent jitter
            val centeredLowerThreshold = 0.5f
            val centeredUpperThreshold = 0.1f
            val closeLowerThreshold = 0.1f
            val closeUpperThreshold = 0.2f

            if (abs(errorX) < centeredLowerThreshold) {
                centeredPuck++
            } else if (abs(errorX) > centeredUpperThreshold) {
                centeredPuck = 0
            }
            if (abs(errorY) < closeLowerThreshold) {
                puckCloseToBottom++
            } else if (abs(errorY) > closeUpperThreshold) {
                puckCloseToBottom = 0
            }

            if (centeredPuck >= centeredPuckLimit && puckCloseToBottom >= puckCloseToBottomLimit) {
                mount()
            } else {
                approach(errorX, errorY)
            }
        } else if (action == Action.APPROACH) {
            // Implied !visible
            searching()
        }
    }

    private fun searching() {
        // action = Action.SEARCHING;
        Logger.v("PUCK", "Action.SEARCHING")
        speedL = 0.3f
        speedR = -0.3f
    }

    private fun approach(errorX: Float, errorY: Float) {
        action = Action.APPROACH
        Logger.i("PUCK", "Action.APPROACH")

        speedL = (-errorX * pController) + forwardBias
        speedR = (errorX * pController) + forwardBias
    }

    private fun mount() {
        action = Action.MOUNT
        Logger.i("PUCK", "Action.MOUNT")
        speedL = 1f
        speedR = 1f
        // start async timer task to call dismount 5 seconds later
        val actionExecutor = Executors.newSingleThreadScheduledExecutor()
        actionExecutor.schedule({
            stop()
            val delayExecutor = Executors.newSingleThreadScheduledExecutor()
            delayExecutor.schedule(::dismount, 5, TimeUnit.SECONDS)
        }, 1000, TimeUnit.MILLISECONDS)
    }

    private fun dismount() {
        action = Action.DISMOUNT
        Logger.i("PUCK", "Action.DISMOUNT")
        speedL = -1.0f
        speedR = -1.0f
        // start async timer task to call reset 3 seconds later
        val actionExecutor = Executors.newSingleThreadScheduledExecutor()
        actionExecutor.schedule(::reset, 2, TimeUnit.SECONDS)
    }

    private fun reset() {
        action = Action.RESET
        Logger.i("PUCK", "Action.RESET")
        // Turn in random direction for 3 seconds then set state to APPROACH
        val random = Random()
        val direction = if (random.nextBoolean()) 1 else -1
        speedL = direction * 1.0f
        speedR = -direction * 1.0f
        // start async timer task to set action to APPROACH 3 seconds later
        val actionExecutor = Executors.newSingleThreadScheduledExecutor()
        actionExecutor.schedule(
            { action = Action.APPROACH }, 2, TimeUnit.SECONDS
        )
    }

    private fun stop() {
        speedL = 0.0f
        speedR = 0.0f
    }
}

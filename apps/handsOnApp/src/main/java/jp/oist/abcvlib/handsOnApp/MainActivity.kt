package jp.oist.abcvlib.handsOnApp

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.core.graphics.scale
import androidx.lifecycle.lifecycleScope
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryDataSubscriber
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorData
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorDataSubscriber
import jp.oist.abcvlib.handsOnApp.databinding.ActivityMainBinding
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

/**
 * Demo app for HandsOn
 * 
 * @author Yuji Kanagawa https://github.com/kngwyu
 */
class MainActivity : AbcvlibActivity(), BatteryDataSubscriber, SerialReadyListener,
    WheelDataSubscriber, ObjectDetectorDataSubscriber {
    private lateinit var binding: ActivityMainBinding
    private lateinit var publisherManager: PublisherManager
    private lateinit var debugInfo: DebugInfoViewer
    private var countL = 0
    private var countR = 0
    private var nLoopCalled = 0
    private var imageLabel = "Nothing"

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        debugInfo = DebugInfoViewer(binding)
        lifecycleScope.launch {
            while (isActive) {
                debugInfo.update()
                delay(100.milliseconds)
            }
        }

        super.onCreate(savedInstanceState)

    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        publisherManager = PublisherManager()

        val wheelData = WheelData.Builder(this, publisherManager)
            .setBufferLength(50)
            .setExpWeight(0.01).build()
        wheelData.addSubscriber(this)
        val batteryData = BatteryData.Builder(this, publisherManager).build()
        batteryData.addSubscriber(this)
        val detectorData = ObjectDetectorData.Builder(this, publisherManager, this)
            .setModel("efficientdet-lite1.tflite")
            .build()
        detectorData.addSubscriber(this)
        setSerialCommManager(SerialCommManager(usbSerial, batteryData, wheelData))
        super.onSerialReady(usbSerial)
    }

    public override fun onOutputsReady() {
        publisherManager.initializePublishers()
        publisherManager.startPublishers()
    }

    override fun abcvlibMainLoop() {
        nLoopCalled += 1
        // Example code for controlling robots
        // Set wheel output
        // Stop when detected something
        if (imageLabel != "Nothing") {
            outputs.setWheelOutput(0.0f, 0.0f, false, false)
        } else if (100 < nLoopCalled && nLoopCalled < 120) {
            outputs.setWheelOutput(0.0f, 1.0f, false, false)
        } else if (120 < nLoopCalled && nLoopCalled < 140) {
            outputs.setWheelOutput(-1.0f, -1.0f, false, false)
        } else {
            outputs.setWheelOutput(1.0f, 1.0f, false, false)
        }
        debugInfo.text1 = String.format(
            Locale.getDefault(), "MainLoopCount: %d", nLoopCalled
        )
    }

    override fun onWheelDataUpdate(
        timestamp: Long,
        wheelCountL: Int,
        wheelCountR: Int,
        wheelDistanceL: Double,
        wheelDistanceR: Double,
        wheelSpeedInstantL: Double,
        wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double,
        wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double,
        wheelSpeedExpAvgR: Double
    ) {
        countL = wheelCountL
        // TODO(kngwyu) why negated?
        countR = -wheelCountR
        debugInfo.text2 = String.format(
            Locale.getDefault(), "WheelCount: %d %d", countL, countR
        )
    }

    override fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
    }

    override fun onChargerVoltageUpdate(
        timestamp: Long,
        chargerVoltage: Double,
        coilVoltage: Double
    ) {
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
            val matrix = Matrix()
            matrix.postRotate(270f)
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap,
                0, 0,
                bitmap.width,
                bitmap.height,
                matrix, true
            )
            val scaledBitmap = rotatedBitmap.scale(
                rotatedBitmap.width * 4, rotatedBitmap.height * 4
            )
            debugInfo.image = scaledBitmap
            val category = results[0].categories[0]
            imageLabel = category.label
            debugInfo.text3 = String.format(
                Locale.getDefault(),
                "Label: %s (score: %.2f)",
                imageLabel,
                category.score
            )
        } catch (e: IndexOutOfBoundsException) {
            imageLabel = "Nothing"
            debugInfo.text3 = "No object detected"
        }
    }
}

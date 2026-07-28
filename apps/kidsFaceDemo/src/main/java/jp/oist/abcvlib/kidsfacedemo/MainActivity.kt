package jp.oist.abcvlib.kidsfacedemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.kidsfacedemo.databinding.ActivityMainBinding
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.UsbSerial
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AbcvlibActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var imageExecutor: ExecutorService
    private lateinit var publisherManager: PublisherManager
    private var gestureRecognizer: GestureRecognizer? = null
    private var lastMetricsLogAtMs = 0L
    private var debugViewVisible = false
    private var cameraStarted = false
    private var currentGestureFace: String? = null
    @Volatile private var currentKnownGesture: String? = null
    @Volatile private var thumbsUpMotionActive = false
    @Volatile private var thumbsUpMotionStep = 0
    @Volatile private var stopGestureActive = false
    @Volatile private var latestMetrics = PoseMetrics.EMPTY
    @Volatile private var latestTargetAtMs = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCameraAnalysis()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        binding.viewToggleButton.setOnClickListener {
            setDebugViewVisible(!debugViewVisible)
        }
        setDebugViewVisible(debugViewVisible)

        imageExecutor = Executors.newSingleThreadExecutor()
        gestureRecognizer = GestureRecognizer.createFromOptions(
            this,
            GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(GESTURE_MODEL_ASSET)
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(1)
                .setResultListener(::onGestureResult)
                .setErrorListener { error -> Log.e(TAG, "GestureRecognizer error", error) }
                .build()
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraAnalysis()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setDebugViewVisible(visible: Boolean) {
        debugViewVisible = visible
        binding.cameraPreview.visibility = if (visible) View.VISIBLE else View.GONE
        binding.poseOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        binding.faceView.visibility = if (visible) View.GONE else View.VISIBLE
        binding.viewToggleButton.text = if (visible) "Face" else "Debug"
        if (cameraStarted) {
            startCameraAnalysis()
        }
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        publisherManager = PublisherManager()
        val batteryData = BatteryData.Builder(this, publisherManager).build()
        val wheelData = WheelData.Builder(this, publisherManager).build()
        setSerialCommManager(SerialCommManager(usbSerial, batteryData, wheelData))
        super.onSerialReady(usbSerial)
    }

    public override fun onOutputsReady() {
        publisherManager.initializePublishers()
        publisherManager.startPublishers()
    }

    override fun abcvlibMainLoop() {
        if (thumbsUpMotionActive) {
            if (thumbsUpMotionStep < THUMBS_UP_BACKWARD_LOOPS) {
                thumbsUpMotionStep += 1
                outputs.setWheelOutput(-MAX_WHEEL_SPEED, -MAX_WHEEL_SPEED, false, false)
            } else if (thumbsUpMotionStep < THUMBS_UP_TOTAL_MOTION_LOOPS) {
                thumbsUpMotionStep += 1
                outputs.setWheelOutput(MAX_WHEEL_SPEED, MAX_WHEEL_SPEED, false, false)
            } else {
                thumbsUpMotionActive = false
                outputs.setWheelOutput(0f, 0f, false, false)
            }
            return
        }

        if (currentKnownGesture == THUMBS_UP_GESTURE) {
            outputs.setWheelOutput(0f, 0f, false, false)
            return
        }

        val metrics = latestMetrics
        val targetIsFresh = SystemClock.uptimeMillis() - latestTargetAtMs <= TARGET_TIMEOUT_MS
        if (!targetIsFresh || metrics.stopped) {
            outputs.setWheelOutput(0f, 0f, false, false)
            return
        }
        outputs.setWheelOutput(metrics.leftWheel, metrics.rightWheel, false, false)
    }

    private fun startCameraAnalysis() {
        cameraStarted = true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val targetRotation = binding.cameraPreview.display.rotation
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetRotation(targetRotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val preview = Preview.Builder()
                .setTargetRotation(targetRotation)
                .build()
            preview.surfaceProvider = binding.cameraPreview.surfaceProvider

            imageAnalysis.setAnalyzer(imageExecutor) { imageProxy ->
                analyzeFrame(imageProxy)
            }

            cameraProvider.unbindAll()
            if (debugViewVisible) {
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } else {
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    imageAnalysis
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalGetImage
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val image = imageProxy.image
        if (image == null) {
            imageProxy.close()
            return
        }

        val mpImage = MediaImageBuilder(image).build()
        val options = ImageProcessingOptions.builder()
            .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
            .build()
        val timestampMs = SystemClock.uptimeMillis()
        gestureRecognizer?.recognizeAsync(mpImage, options, timestampMs)
        imageProxy.close()
    }

    private fun onGestureResult(
        result: GestureRecognizerResult,
        input: MPImage
    ) {
        val topGesture = result.gestures()
            .flatten()
            .maxByOrNull { it.score() }
        val gesturePoints = result.landmarks().firstOrNull()
            ?.map { it.toPosePoint().toNormalizedPoint() }
            ?: emptyList()
        val detectedKnownGesture = topGesture
            ?.takeIf { it.score() >= GESTURE_SCORE_THRESHOLD }
            ?.categoryName()
            ?.takeIf { it in KNOWN_GESTURES }
        if (detectedKnownGesture == THUMBS_UP_GESTURE &&
            currentKnownGesture != THUMBS_UP_GESTURE
        ) {
            thumbsUpMotionActive = true
            thumbsUpMotionStep = 0
        }
        currentKnownGesture = detectedKnownGesture
        stopGestureActive = detectedKnownGesture == STOP_GESTURE
        val metrics = gestureMetrics(gesturePoints)
        latestMetrics = metrics
        latestTargetAtMs = if (metrics.targetVisible) SystemClock.uptimeMillis() else 0L
        logMetrics(metrics)
        val overlayGesture = if (topGesture != null || gesturePoints.isNotEmpty()) {
            OverlayGesture(
                label = result.gestures()
                    .flatten()
                    .sortedByDescending { it.score() }
                    .take(DEBUG_GESTURE_COUNT)
                    .joinToString(" ") {
                        "${it.categoryName()}=${"%.2f".format(it.score())}"
                    }
                    .ifEmpty { "None" },
                landmarks = gesturePoints,
                metrics = metrics
            )
        } else {
            null
        }
        runOnUiThread {
            updateGestureFace(detectedKnownGesture)
            binding.poseOverlay.updatePose(null, input.height, input.width)
            binding.poseOverlay.updateGesture(overlayGesture)
        }
    }

    private fun updateGestureFace(gesture: String?) {
        val faceGesture = gesture?.takeIf { it in FACE_GESTURES }
        if (faceGesture == currentGestureFace) {
            return
        }
        currentGestureFace = faceGesture
        binding.faceView.setImageResource(
            when (faceGesture) {
                LOVE_GESTURE -> R.drawable.face_love
                STOP_GESTURE -> R.drawable.face_stop
                VICTORY_GESTURE -> R.drawable.face_victory
                THUMBS_UP_GESTURE -> R.drawable.face_pointing_up
                else -> R.drawable.face_default
            }
        )
    }

    private fun gestureMetrics(gesturePoints: List<NormalizedPoint>): PoseMetrics {
        val targetVisible = gesturePoints.isNotEmpty()
        val targetX = if (targetVisible) gesturePoints.sumOf { it.x.toDouble() }.toFloat() / gesturePoints.size else 0f
        val targetY = if (targetVisible) gesturePoints.sumOf { it.y.toDouble() }.toFloat() / gesturePoints.size else 0f
        val stopped = stopGestureActive || !targetVisible
        val turn = targetX.deadband(CENTER_DEADBAND) * TURN_GAIN
        val forward = ((targetY - TARGET_GESTURE_Y) * FORWARD_GAIN)
            .coerceIn(0f, MAX_FORWARD_SPEED)
        val leftWheel = if (stopped) 0f else (forward + turn).coerceIn(-MAX_WHEEL_SPEED, MAX_WHEEL_SPEED)
        val rightWheel = if (stopped) 0f else (forward - turn).coerceIn(-MAX_WHEEL_SPEED, MAX_WHEEL_SPEED)

        return PoseMetrics(
            person = true,
            targetVisible = targetVisible,
            targetX = targetX,
            targetY = targetY,
            leftWheel = leftWheel,
            rightWheel = rightWheel,
            stopped = stopped
        )
    }

    private fun logMetrics(metrics: PoseMetrics) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMetricsLogAtMs < METRICS_LOG_INTERVAL_MS) {
            return
        }
        lastMetricsLogAtMs = now
        Log.i(
            TAG,
            "poseMetrics " +
                "person=${metrics.person} " +
                "targetVisible=${metrics.targetVisible} " +
                "targetX=${"%.4f".format(metrics.targetX)} " +
                "targetY=${"%.4f".format(metrics.targetY)} " +
                "leftWheel=${"%.4f".format(metrics.leftWheel)} " +
                "rightWheel=${"%.4f".format(metrics.rightWheel)} " +
                "stopGestureActive=$stopGestureActive " +
                "stopped=${metrics.stopped}"
        )
    }

    private fun Float.deadband(deadband: Float): Float {
        if (this > deadband) {
            return this - deadband
        }
        if (this < -deadband) {
            return this + deadband
        }
        return 0f
    }

    private fun NormalizedLandmark.toPosePoint(): PosePoint {
        val visibleX = 1f - y()
        return PosePoint(
            x = visibleX * 2f - 1f,
            y = x(),
            isVisible = visibility().orElse(1f) >= MIN_VISIBILITY
        )
    }

    private fun PosePoint.toNormalizedPoint(): NormalizedPoint {
        return NormalizedPoint(x, y)
    }

    override fun onDestroy() {
        gestureRecognizer?.close()
        imageExecutor.shutdown()
        super.onDestroy()
    }

    private data class PosePoint(
        val x: Float,
        val y: Float,
        val isVisible: Boolean
    )

    private companion object {
        const val TAG = "KidsFaceDemo"
        const val GESTURE_MODEL_ASSET = "gesture_recognizer.task"
        const val LOVE_GESTURE = "ILoveYou"
        const val STOP_GESTURE = "Open_Palm"
        const val VICTORY_GESTURE = "Victory"
        const val THUMBS_UP_GESTURE = "Thumb_Up"
        const val GESTURE_SCORE_THRESHOLD = 0.6f
        const val THUMBS_UP_BACKWARD_LOOPS = 10
        const val THUMBS_UP_TOTAL_MOTION_LOOPS = 20
        const val DEBUG_GESTURE_COUNT = 3
        const val TARGET_TIMEOUT_MS = 500L
        const val METRICS_LOG_INTERVAL_MS = 100L
        const val MIN_VISIBILITY = 0.4f
        const val TARGET_GESTURE_Y = 0.1f
        const val CENTER_DEADBAND = 0.08f
        const val FORWARD_GAIN = 0.85f
        const val TURN_GAIN = 0.20f
        const val MAX_FORWARD_SPEED = 0.75f
        const val MAX_WHEEL_SPEED = 1.0f
        val KNOWN_GESTURES = setOf(
            LOVE_GESTURE,
            STOP_GESTURE,
            VICTORY_GESTURE,
            THUMBS_UP_GESTURE
        )
        val FACE_GESTURES = setOf(
            LOVE_GESTURE,
            STOP_GESTURE,
            VICTORY_GESTURE,
            THUMBS_UP_GESTURE
        )
    }
}

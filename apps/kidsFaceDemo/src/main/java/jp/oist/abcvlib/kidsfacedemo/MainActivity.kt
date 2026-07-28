package jp.oist.abcvlib.kidsfacedemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
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
    private var poseLandmarker: PoseLandmarker? = null
    private var lastMetricsLogAtMs = 0L
    @Volatile private var latestMetrics = PoseMetrics.EMPTY
    @Volatile private var latestPoseAtMs = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startPoseAnalysis()
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

        imageExecutor = Executors.newSingleThreadExecutor()
        poseLandmarker = PoseLandmarker.createFromOptions(
            this,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(POSE_MODEL_ASSET)
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setResultListener(::onPoseResult)
                .setErrorListener { error -> Log.e(TAG, "PoseLandmarker error", error) }
                .build()
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startPoseAnalysis()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
        val metrics = latestMetrics
        val poseIsFresh = SystemClock.uptimeMillis() - latestPoseAtMs <= POSE_TIMEOUT_MS
        if (!poseIsFresh || metrics.stopped) {
            outputs.setWheelOutput(0f, 0f, false, false)
            return
        }
        outputs.setWheelOutput(metrics.leftWheel, metrics.rightWheel, false, false)
    }

    private fun startPoseAnalysis() {
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
                analyzePose(imageProxy)
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalGetImage
    private fun analyzePose(imageProxy: ImageProxy) {
        val image = imageProxy.image
        if (image == null) {
            imageProxy.close()
            return
        }

        val mpImage = MediaImageBuilder(image).build()
        poseLandmarker?.detectAsync(mpImage, SystemClock.uptimeMillis())
        imageProxy.close()
    }

    private fun onPoseResult(result: PoseLandmarkerResult, input: MPImage) {
        val landmarks = result.landmarks().firstOrNull()
        val posePoints = landmarks?.toPosePoints()
        val metrics = if (posePoints == null) PoseMetrics.EMPTY else poseMetrics(posePoints)
        val overlayPose = posePoints?.let { toOverlayPose(it, metrics) }
        latestMetrics = metrics
        latestPoseAtMs = if (metrics.person) SystemClock.uptimeMillis() else 0L
        logMetrics(metrics)
        runOnUiThread { binding.poseOverlay.updatePose(overlayPose, input.height, input.width) }
    }

    private fun toOverlayPose(
        posePoints: PosePoints,
        metrics: PoseMetrics
    ): OverlayPose {
        return OverlayPose(
            leftShoulder = posePoints.leftShoulder.toNormalizedPoint(),
            rightShoulder = posePoints.rightShoulder.toNormalizedPoint(),
            leftWrist = posePoints.leftWrist.toNormalizedPoint(),
            rightWrist = posePoints.rightWrist.toNormalizedPoint(),
            metrics = metrics
        )
    }

    private fun poseMetrics(posePoints: PosePoints): PoseMetrics {
        val leftShoulder = posePoints.leftShoulder
        val rightShoulder = posePoints.rightShoulder
        val leftWrist = posePoints.leftWrist
        val rightWrist = posePoints.rightWrist
        val leftRaised = leftWrist.isVisible && leftWrist.y > leftShoulder.y + RAISED_MARGIN
        val rightRaised = rightWrist.isVisible && rightWrist.y > rightShoulder.y + RAISED_MARGIN
        val stopped = rightRaised || !leftWrist.isVisible
        val turn = leftWrist.x.deadband(CENTER_DEADBAND) * TURN_GAIN
        val forward = ((leftWrist.y - TARGET_HAND_Y) * FORWARD_GAIN)
            .coerceIn(0f, MAX_FORWARD_SPEED)
        val leftWheel = if (stopped) 0f else (forward + turn).coerceIn(-MAX_WHEEL_SPEED, MAX_WHEEL_SPEED)
        val rightWheel = if (stopped) 0f else (forward - turn).coerceIn(-MAX_WHEEL_SPEED, MAX_WHEEL_SPEED)

        return PoseMetrics(
            person = true,
            leftRaised = leftRaised,
            rightRaised = rightRaised,
            targetX = leftWrist.x,
            targetY = leftWrist.y,
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
                "leftRaised=${metrics.leftRaised} " +
                "rightRaised=${metrics.rightRaised} " +
                "targetX=${"%.4f".format(metrics.targetX)} " +
                "targetY=${"%.4f".format(metrics.targetY)} " +
                "leftWheel=${"%.4f".format(metrics.leftWheel)} " +
                "rightWheel=${"%.4f".format(metrics.rightWheel)} " +
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

    private fun List<NormalizedLandmark>.toPosePoints(): PosePoints {
        return PosePoints(
            leftShoulder = this[LEFT_SHOULDER].toPosePoint(),
            rightShoulder = this[RIGHT_SHOULDER].toPosePoint(),
            leftWrist = this[LEFT_WRIST].toPosePoint(),
            rightWrist = this[RIGHT_WRIST].toPosePoint()
        )
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
        poseLandmarker?.close()
        imageExecutor.shutdown()
        super.onDestroy()
    }

    private data class PosePoints(
        val leftShoulder: PosePoint,
        val rightShoulder: PosePoint,
        val leftWrist: PosePoint,
        val rightWrist: PosePoint
    )

    private data class PosePoint(
        val x: Float,
        val y: Float,
        val isVisible: Boolean
    )

    private companion object {
        const val TAG = "KidsFaceDemo"
        const val POSE_MODEL_ASSET = "pose_landmarker_lite.task"
        const val POSE_TIMEOUT_MS = 500L
        const val METRICS_LOG_INTERVAL_MS = 100L
        const val RAISED_MARGIN = 0.03f
        const val MIN_VISIBILITY = 0.4f
        const val TARGET_HAND_Y = 0.1f
        const val CENTER_DEADBAND = 0.08f
        const val FORWARD_GAIN = 0.85f
        const val TURN_GAIN = 0.30f
        const val MAX_FORWARD_SPEED = 0.75f
        const val MAX_WHEEL_SPEED = 1.0f
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
    }
}

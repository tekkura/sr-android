package jp.oist.abcvlib.kidsfacedemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import jp.oist.abcvlib.kidsfacedemo.databinding.ActivityMainBinding
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var imageExecutor: ExecutorService
    private var poseLandmarker: PoseLandmarker? = null
    private val wristHistory = ArrayDeque<WristFrame>()
    private var lastMetricsLogAtMs = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startPoseAnalysis()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        val metrics = if (posePoints == null) WaveMetrics.EMPTY else waveMetrics(posePoints)
        val overlayPose = posePoints?.let { toOverlayPose(it, metrics) }
        logMetrics(metrics)
        runOnUiThread { binding.poseOverlay.updatePose(overlayPose, input.height, input.width) }
    }

    private fun toOverlayPose(
        posePoints: PosePoints,
        metrics: WaveMetrics
    ): OverlayPose {
        return OverlayPose(
            leftShoulder = posePoints.leftShoulder.toNormalizedPoint(),
            rightShoulder = posePoints.rightShoulder.toNormalizedPoint(),
            leftWrist = posePoints.leftWrist.toNormalizedPoint(),
            rightWrist = posePoints.rightWrist.toNormalizedPoint(),
            metrics = metrics
        )
    }

    private fun waveMetrics(posePoints: PosePoints): WaveMetrics {
        val now = SystemClock.uptimeMillis()
        val leftShoulder = posePoints.leftShoulder
        val rightShoulder = posePoints.rightShoulder
        val leftWrist = posePoints.leftWrist
        val rightWrist = posePoints.rightWrist
        val shoulderWidth = (rightShoulder.x - leftShoulder.x).coerceAtLeast(MIN_SHOULDER_WIDTH)
        val leftRaised = leftWrist.isVisible && leftWrist.y > leftShoulder.y + RAISED_MARGIN
        val rightRaised = rightWrist.isVisible && rightWrist.y > rightShoulder.y + RAISED_MARGIN

        wristHistory.addLast(
            WristFrame(
                timeMs = now,
                leftX = leftWrist.x.takeIf { leftRaised },
                rightX = rightWrist.x.takeIf { rightRaised }
            )
        )
        while (wristHistory.isNotEmpty() && now - wristHistory.first.timeMs > HISTORY_MS) {
            wristHistory.removeFirst()
        }

        val leftMotion = horizontalMotion { it.leftX }
        val rightMotion = horizontalMotion { it.rightX }
        val shoulderNormalizedMotion = max(leftMotion, rightMotion) / shoulderWidth
        val score = (shoulderNormalizedMotion / WAVE_RANGE_SHOULDER_UNITS).coerceIn(0f, 1f)
        return WaveMetrics(
            person = true,
            leftRaised = leftRaised,
            rightRaised = rightRaised,
            leftMotion = leftMotion,
            rightMotion = rightMotion,
            shoulderWidth = shoulderWidth,
            normalizedMotion = shoulderNormalizedMotion,
            score = score,
            samples = wristHistory.size
        )
    }

    private fun logMetrics(metrics: WaveMetrics) {
        val now = SystemClock.uptimeMillis()
        if (now - lastMetricsLogAtMs < METRICS_LOG_INTERVAL_MS) {
            return
        }
        lastMetricsLogAtMs = now
        Log.i(
            TAG,
            "waveMetrics " +
                "person=${metrics.person} " +
                "leftRaised=${metrics.leftRaised} " +
                "rightRaised=${metrics.rightRaised} " +
                "leftMotion=${"%.4f".format(metrics.leftMotion)} " +
                "rightMotion=${"%.4f".format(metrics.rightMotion)} " +
                "shoulderWidth=${"%.4f".format(metrics.shoulderWidth)} " +
                "normalizedMotion=${"%.4f".format(metrics.normalizedMotion)} " +
                "score=${"%.4f".format(metrics.score)} " +
                "samples=${metrics.samples}"
        )
    }

    private fun horizontalMotion(selector: (WristFrame) -> Float?): Float {
        val values = wristHistory.mapNotNull(selector)
        if (values.size < MIN_WAVE_SAMPLES) {
            return 0f
        }
        return values.max() - values.min()
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
        return PosePoint(
            x = 1f - y(),
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

    private data class WristFrame(
        val timeMs: Long,
        val leftX: Float?,
        val rightX: Float?
    )

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
        const val HISTORY_MS = 900L
        const val METRICS_LOG_INTERVAL_MS = 100L
        const val WAVE_RANGE_SHOULDER_UNITS = 0.8f
        const val MIN_SHOULDER_WIDTH = 0.05f
        const val RAISED_MARGIN = 0.03f
        const val MIN_VISIBILITY = 0.4f
        const val MIN_WAVE_SAMPLES = 3
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
    }
}

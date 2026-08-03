package jp.oist.abcvlib.core.inputs.phone

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.util.Logger
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService

/*
 * @param previewView: e.g. from your Activity findViewById(R.id.camera_x_preview)
 * @param imageAnalysis: If set to null will generate default imageAnalysis object.
 */
class ObjectDetectorData(
    context: Context, publisherManager: PublisherManager,
    lifecycleOwner: LifecycleOwner, previewView: PreviewView?,
    imageAnalysis: ImageAnalysis?, imageExecutor: ExecutorService?,
    currentDelegate: delegates,
    modelPath: String
) : ImageData<ObjectDetectorDataSubscriber>(
    context,
    publisherManager,
    lifecycleOwner,
    previewView,
    imageAnalysis,
    imageExecutor
), ImageAnalysis.Analyzer {

    override fun setDefaultImageAnalysis() {
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setImageQueueDepth(20)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
    }

    enum class delegates {
        DELEGATE_CPU,
        DELEGATE_GPU,
        DELEGATE_NNAPI
    }

    private lateinit var objectDetector: ObjectDetector
    private val threshold = 0.5f
    private val maxResults = 3
    private val numThreads = 2
    private val inferenceTimesMs = ArrayDeque<Long>()
    private val loopTimesMs = ArrayDeque<Long>()
    private var lastDetectCompletedAtMs = 0L

    init {
        setupObjectDetector(currentDelegate, modelPath)
    }

    // We must specify T to define the extending subclass, S to specify the subscriber type used by the extending subclass, and B to reference the extending subclasses' builder class.
    class Builder(
        context: Context,
        publisherManager: PublisherManager,
        lifecycleOwner: LifecycleOwner
    ) : ImageData.Builder<ObjectDetectorData, ObjectDetectorDataSubscriber, Builder>(
        context,
        publisherManager,
        lifecycleOwner
    ) {
        private var delegate = delegates.DELEGATE_CPU //default CPU

        // default to custom model using pucks/robots only. This is located in the abcvlib/src/main/assets folder
        private var modelPath: String = "model.tflite"


        fun build(): ObjectDetectorData {
            return ObjectDetectorData(
                context,
                publisherManager,
                lifecycleOwner,
                previewView,
                imageAnalysis,
                imageExecutor,
                delegate,
                modelPath
            )
        }

        override fun self(): Builder {
            return this
        }

        fun setDelegate(delegate: delegates): Builder {
            this.delegate = delegate
            return this
        }

        fun setModel(modelPath: String): Builder {
            this.modelPath = modelPath
            return this
        }
    }

    private fun setupObjectDetector(currentDelegate: delegates, modelPath: String) {
        val optionsBuilder = ObjectDetector.ObjectDetectorOptions
            .builder()
            .setScoreThreshold(threshold)
            .setMaxResults(maxResults)
            .setRunningMode(RunningMode.IMAGE)

        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(modelPath)

        // Use the specified hardware for running the model.
        // MediaPipe/LiteRT will handle the internal compatibility check.
        baseOptionsBuilder.setDelegate(
            when (currentDelegate) {
                delegates.DELEGATE_CPU -> Delegate.CPU
                delegates.DELEGATE_GPU -> Delegate.GPU
                delegates.DELEGATE_NNAPI -> Delegate.NPU
            }
        )

        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        // Open the file
        try {
            objectDetector = ObjectDetector.createFromOptions(
                context,
                optionsBuilder.build()
            )
        } catch (e: IOException) {
            Logger.e(TAG, "Failed to open TFLite model file from assets")
            throw RuntimeException(e)
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "TFLite failed to load model with error: " + e.message)
        }
    }


    override fun customAnalysis(
        imageData: ByteArray,
        rotation: Int,
        format: Int,
        width: Int,
        height: Int,
        timestamp: Long,
        bitmap: Bitmap
    ) {
        // Inference time is the difference between the system time at the start and finish of the
        // process
        val detectStartedAtNs = SystemClock.elapsedRealtimeNanos()
        var inferenceTime = SystemClock.uptimeMillis()

        // Preprocess the image and convert it into a TensorImage for detection.
        val mpImage = BitmapImageBuilder(bitmap).build()

        val imageProcessingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(rotation)
            .build()

        val results = objectDetector.detect(mpImage, imageProcessingOptions)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime
        val detectCompletedAtNs = SystemClock.elapsedRealtimeNanos()
        val detectCompletedAtMs = SystemClock.uptimeMillis()
        val loopTime = if (lastDetectCompletedAtMs == 0L) {
            0L
        } else {
            detectCompletedAtMs - lastDetectCompletedAtMs
        }
        lastDetectCompletedAtMs = detectCompletedAtMs

        addRollingSample(inferenceTimesMs, inferenceTime)
        if (loopTime > 0L) {
            addRollingSample(loopTimesMs, loopTime)
        }

        Logger.v(
            TAG,
            "detect rotation=$rotation image=${width}x${height} " +
                "detections=${results.detections().size} " +
                "inferenceMs=$inferenceTime " +
                "avgInferenceMs10=${averageMs(inferenceTimesMs)} " +
                "loopMs=$loopTime " +
                "avgLoopMs10=${averageMs(loopTimesMs)}"
        )

        for (subscriber in subscribers) {
            subscriber.onObjectsDetected(
                bitmap,
                mpImage,
                results.detections(),
                inferenceTime,
                timestamp,
                detectStartedAtNs,
                detectCompletedAtNs,
                height,
                width
            )
        }
    }

    private fun addRollingSample(samples: ArrayDeque<Long>, value: Long) {
        samples.addLast(value)
        while (samples.size > 10) {
            samples.removeFirst()
        }
    }

    private fun averageMs(samples: ArrayDeque<Long>): Long {
        if (samples.isEmpty()) {
            return 0L
        }
        return samples.sum() / samples.size
    }
}

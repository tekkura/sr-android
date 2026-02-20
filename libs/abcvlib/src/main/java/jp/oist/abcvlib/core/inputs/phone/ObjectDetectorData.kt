package jp.oist.abcvlib.core.inputs.phone

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.util.Logger
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import org.tensorflow.lite.task.vision.detector.ObjectDetector.ObjectDetectorOptions
import java.io.IOException
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

    enum class delegates {
        DELEGATE_CPU,
        DELEGATE_GPU,
        DELEGATE_NNAPI
    }

    private lateinit var objectDetector: ObjectDetector
    private val threshold = 0.5f
    private val maxResults = 3
    private val numThreads = 2

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
        val optionsBuilder = ObjectDetectorOptions.builder()
            .setScoreThreshold(threshold)
            .setMaxResults(maxResults)

        val baseOptionsBuilder = BaseOptions.builder().setNumThreads(numThreads)

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            delegates.DELEGATE_CPU -> {}
            delegates.DELEGATE_GPU -> {
                val isSupported: Boolean
                CompatibilityList().use { compatibilityList ->
                    // Use the compatibilityList instance here
                    isSupported = compatibilityList.isDelegateSupportedOnThisDevice
                }
                if (isSupported) {
                    baseOptionsBuilder.useGpu()
                } else {
                    Logger.e(TAG, "Could not use GPU")
                }
            }

            delegates.DELEGATE_NNAPI -> baseOptionsBuilder.useNnapi()
        }
        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        // Open the file
        try {
            objectDetector =
                ObjectDetector.createFromFileAndOptions(context, modelPath, optionsBuilder.build())
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

        var inferenceTime = SystemClock.uptimeMillis()

        // Create preprocessor for the image.
        // See https://www.tensorflow.org/lite/inference_with_metadata/
        //            lite_support#imageprocessor_architecture
        val imageProcessor = ImageProcessor.Builder()
            .add(Rot90Op(-rotation / 90))
            .build() //todo check if this rotation is correct

        // Preprocess the image and convert it into a TensorImage for detection.
        val tensorImage: TensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))

        val results = objectDetector.detect(tensorImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime

        for (subscriber in subscribers) {
            subscriber.onObjectsDetected(
                bitmap,
                tensorImage,
                results,
                inferenceTime,
                height,
                width
            )
        }
    }
}

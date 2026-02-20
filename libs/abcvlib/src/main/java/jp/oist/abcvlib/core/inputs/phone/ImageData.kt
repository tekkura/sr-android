package jp.oist.abcvlib.core.inputs.phone

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.os.Handler
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.google.common.util.concurrent.ListenableFuture
import jp.oist.abcvlib.core.inputs.Publisher
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.Subscriber
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.YuvToRgbConverter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

abstract class ImageData<S : Subscriber>(
    context: Context,
    publisherManager: PublisherManager,
    protected val lifecycleOwner: LifecycleOwner,
    private var previewView: PreviewView?,
    protected var imageAnalysis: ImageAnalysis?,
    protected var imageExecutor: ExecutorService?
) : Publisher<S>(context, publisherManager), ImageAnalysis.Analyzer {

    private lateinit var mCameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var yuvToRgbConverter: YuvToRgbConverter? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Initialize countDownLatch with count of 1 if no preview view, 2 if preview view exists
    // Waits for both analysis and preview to be running before sending a signal that it is ready
    private val countDownLatch: CountDownLatch = CountDownLatch(if (previewView != null) 2 else 1)

    // We must specify T to define the extending subclass, S to specify the subscriber type used by the extending subclass, and B to reference the extending subclasses' builder class.
    abstract class Builder<T : ImageData<S>, S : Subscriber, B : Builder<T, S, B>>(
        protected val context: Context,
        protected val publisherManager: PublisherManager,
        protected val lifecycleOwner: LifecycleOwner
    ) {
        protected var previewView: PreviewView? = null

        protected var imageAnalysis: ImageAnalysis? = null

        protected var imageExecutor: ExecutorService? = null

        protected var imageDataSubtype: T? = null

        protected abstract fun self(): B

        fun setPreviewView(previewView: PreviewView): B {
            this.previewView = previewView
            return self()
        }

        fun setImageAnalysis(imageAnalysis: ImageAnalysis?): B {
            this.imageAnalysis = imageAnalysis
            return self()
        }

        fun setImageExecutor(imageExecutor: ExecutorService?): B {
            this.imageExecutor = imageExecutor
            return self()
        }
    }

    override fun getRequiredPermissions(): ArrayList<String> {
        return arrayListOf(Manifest.permission.CAMERA)
    }

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        countDownLatch.countDown()
        if (subscribers.isNotEmpty() && !paused) {
            val image = imageProxy.image
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (image != null) {
                // Copy the image buffer, as it appears to get overwritten or read from externally
                val byteBuffer = image.planes[0].buffer
                val imageData = ByteArray(byteBuffer.capacity())
                byteBuffer.get(imageData)
                val format = image.format
                val width = image.width
                val height = image.height
                val timestamp = image.timestamp
                val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                yuvToRgbConverter!!.yuvToRgb(image, bitmap)
                customAnalysis(imageData, rotation, format, width, height, timestamp, bitmap)
            }
        }
        imageProxy.close() // You must call these two lines at the end of the child's analyze method
    }

    protected abstract fun customAnalysis(
        imageData: ByteArray,
        rotation: Int,
        format: Int,
        width: Int,
        height: Int,
        timestamp: Long,
        bitmap: Bitmap
    )

    protected open fun setDefaultImageAnalysis() {
        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(10, 10))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setImageQueueDepth(20)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
    }

    override fun start() {
        if (imageAnalysis == null) {
            setDefaultImageAnalysis()
        }
        if (imageExecutor == null) {
            imageExecutor = Executors.newCachedThreadPool(
                ProcessPriorityThreadFactory(1, "imageAnalysis")
            )
        }
        if (subscribers.isNotEmpty()) {
            yuvToRgbConverter = YuvToRgbConverter(context)
            imageAnalysis!!.setAnalyzer(imageExecutor!!, this)
        }
        if (previewView != null) {
            val handler = Handler(context.mainLooper)
            handler.post { previewView!!.setScaleType(PreviewView.ScaleType.FIT_CENTER) }
        }
        bindAll(lifecycleOwner)
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                Logger.i(TAG, "Waiting for preview and analysis to start")
                countDownLatch.await()
                Logger.i(TAG, "Preview and analysis started")
                publisherManager.onPublisherInitialized()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        executor.shutdown()
        super.start()
    }

    override fun stop() {
        imageAnalysis!!.clearAnalyzer()
        imageAnalysis = null
        imageExecutor!!.shutdown()
        yuvToRgbConverter = null
        previewView = null
        mCameraProviderFuture.cancel(false)
        cameraProvider!!.unbindAll()
        cameraProvider = null
        super.stop()
    }

    private fun bindAll(lifecycleOwner: LifecycleOwner) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        mCameraProviderFuture = ProcessCameraProvider.getInstance(context)
        mCameraProviderFuture.addListener({
            try {
                cameraProvider = mCameraProviderFuture.get()
                val previewView = this.previewView
                if (previewView != null) {
                    val preview = Preview.Builder()
                        .build()

                    cameraProvider!!.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )

                    preview.surfaceProvider = previewView.getSurfaceProvider()

                    val previewViewObserver = Observer<PreviewView.StreamState> { streamState ->
                        Logger.i("previewView", "PreviewState: $streamState")
                        if (streamState.name == "STREAMING") {
                            countDownLatch.countDown()
                        }
                    }
                    previewView.previewStreamState.observe(lifecycleOwner, previewViewObserver)
                } else {
                    cameraProvider!!.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
                }
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    fun test() {
        Logger.v("lifecycle", "onAny")
    }
}

package jp.oist.abcvlib.core.inputs.phone

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

abstract class ImageData<S : Subscriber>(
    context: Context,
    publisherManager: PublisherManager,
    protected val lifecycleOwner: LifecycleOwner,
    private var previewView: PreviewView?,
    protected var imageAnalysis: ImageAnalysis?,
    protected var imageExecutor: ExecutorService?
) : Publisher<S>(context, publisherManager), ImageAnalysis.Analyzer {

    private var yuvToRgbConverter: YuvToRgbConverter? = null

    @Volatile
    private var cameraAttempt: CameraAttempt? = null

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

    @ExperimentalGetImage
    override fun start() {
        cameraAttempt?.let { previousAttempt ->
            check(previousAttempt.cleanupStarted) {
                "Camera initialization is already active"
            }
            previousAttempt.cleanupComplete.await()
        }

        val waitsForAnalysis = subscribers.isNotEmpty()
        var latchSize = 1
        if (waitsForAnalysis) latchSize++
        if (previewView != null) latchSize++

        val readinessLatch = CountDownLatch(latchSize)
        val analysisReady = AtomicBoolean()
        val initializationFailure = AtomicReference<Throwable>()
        val ownsImageAnalysis = imageAnalysis == null
        if (imageAnalysis == null) {
            setDefaultImageAnalysis()
        }
        val ownsImageExecutor = imageExecutor == null
        if (imageExecutor == null) {
            imageExecutor = Executors.newCachedThreadPool(
                ProcessPriorityThreadFactory(1, "imageAnalysis")
            )
        }
        val attempt = CameraAttempt(
            requireNotNull(imageAnalysis),
            requireNotNull(imageExecutor),
            ownsImageAnalysis,
            ownsImageExecutor
        )
        cameraAttempt = attempt

        try {
            if (waitsForAnalysis) {
                yuvToRgbConverter = YuvToRgbConverter(context)
                attempt.imageAnalysis.setAnalyzer(attempt.imageExecutor) { imageProxy ->
                    if (analysisReady.compareAndSet(false, true)) {
                        readinessLatch.countDown()
                    }
                    analyze(imageProxy)
                }
            }

            if (previewView != null) {
                val handler = Handler(context.mainLooper)
                handler.post { previewView!!.setScaleType(PreviewView.ScaleType.FIT_CENTER) }
            }
            bindAll(attempt, lifecycleOwner, readinessLatch, initializationFailure)
            super.start()
            Logger.i(TAG, "Waiting for preview and analysis to start")
            readinessLatch.await()
            initializationFailure.get()?.let { throw it }
            Logger.i(TAG, "Preview and analysis started")
            reportInitializationSucceeded()
        } catch (failure: Exception) {
            releaseCameraAttempt(attempt, preserveConfiguration = true)
            throw failure
        }
    }

    override fun stop() {
        cameraAttempt?.let {
            releaseCameraAttempt(it, preserveConfiguration = false)
        }
        super.stop()
    }

    private fun bindAll(
        attempt: CameraAttempt,
        lifecycleOwner: LifecycleOwner,
        readinessLatch: CountDownLatch,
        initializationFailure: AtomicReference<Throwable>
    ) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        attempt.providerFuture = providerFuture
        providerFuture.addListener({
            if (cameraAttempt !== attempt || attempt.cleanupStarted) {
                return@addListener
            }
            try {
                val cameraProvider = providerFuture.get()
                attempt.cameraProvider = cameraProvider
                val previewView = this.previewView
                if (previewView != null) {
                    val preview = Preview.Builder()
                        .build()
                    attempt.preview = preview

                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        attempt.imageAnalysis
                    )

                    preview.surfaceProvider = previewView.getSurfaceProvider()

                    val previewReady = AtomicBoolean()
                    val previewViewObserver = Observer<PreviewView.StreamState> { streamState ->
                        Logger.i("previewView", "PreviewState: $streamState")
                        if (
                            streamState.name == "STREAMING" &&
                            previewReady.compareAndSet(false, true)
                        ) {
                            readinessLatch.countDown()
                        }
                    }
                    attempt.previewObserver = previewViewObserver
                    previewView.previewStreamState.observe(lifecycleOwner, previewViewObserver)
                } else {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        attempt.imageAnalysis
                    )
                }

                readinessLatch.countDown()
            } catch (failure: Exception) {
                initializationFailure.set(
                    (failure as? ExecutionException)?.cause ?: failure
                )
                while (readinessLatch.count > 0) {
                    readinessLatch.countDown()
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun releaseCameraAttempt(
        attempt: CameraAttempt,
        preserveConfiguration: Boolean
    ) {
        synchronized(attempt) {
            if (attempt.cleanupStarted) return
            attempt.cleanupStarted = true
        }
        attempt.providerFuture?.cancel(false)

        val cleanup = Runnable {
            attempt.imageAnalysis.clearAnalyzer()
            attempt.previewObserver?.let { observer ->
                previewView?.previewStreamState?.removeObserver(observer)
            }
            attempt.cameraProvider?.let { provider ->
                runCatching {
                    attempt.preview?.let { preview ->
                        provider.unbind(attempt.imageAnalysis, preview)
                    } ?: provider.unbind(attempt.imageAnalysis)
                }
            }
            if (attempt.ownsImageExecutor || !preserveConfiguration) {
                attempt.imageExecutor.shutdownNow()
            }
            synchronized(this) {
                if (cameraAttempt === attempt) {
                    if (attempt.ownsImageAnalysis || !preserveConfiguration) {
                        imageAnalysis = null
                    }
                    if (attempt.ownsImageExecutor || !preserveConfiguration) {
                        imageExecutor = null
                    }
                    if (!preserveConfiguration) {
                        previewView = null
                    }
                    yuvToRgbConverter = null
                    cameraAttempt = null
                }
            }
            attempt.cleanupComplete.countDown()
        }

        if (Looper.myLooper() == context.mainLooper) {
            cleanup.run()
        } else {
            ContextCompat.getMainExecutor(context).execute(cleanup)
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    fun test() {
        Logger.v("lifecycle", "onAny")
    }

    private data class CameraAttempt(
        val imageAnalysis: ImageAnalysis,
        val imageExecutor: ExecutorService,
        val ownsImageAnalysis: Boolean,
        val ownsImageExecutor: Boolean,
        val cleanupComplete: CountDownLatch = CountDownLatch(1),
        var providerFuture: ListenableFuture<ProcessCameraProvider>? = null,
        var cameraProvider: ProcessCameraProvider? = null,
        var preview: Preview? = null,
        var previewObserver: Observer<PreviewView.StreamState>? = null,
        @Volatile var cleanupStarted: Boolean = false
    )
}

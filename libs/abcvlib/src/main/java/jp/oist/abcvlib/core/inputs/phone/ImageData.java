package jp.oist.abcvlib.core.inputs.phone;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.Image;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.lifecycle.OnLifecycleEvent;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jp.oist.abcvlib.core.inputs.Publisher;
import jp.oist.abcvlib.core.inputs.PublisherManager;
import jp.oist.abcvlib.core.inputs.Subscriber;
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory;
import jp.oist.abcvlib.util.YuvToRgbConverter;

public abstract class ImageData<S extends Subscriber> extends Publisher<S> implements ImageAnalysis.Analyzer {

    protected ImageAnalysis imageAnalysis;
    private YuvToRgbConverter yuvToRgbConverter;
    private PreviewView previewView;
    protected final LifecycleOwner lifecycleOwner;
    protected ExecutorService imageExecutor;

    private ListenableFuture<ProcessCameraProvider> mCameraProviderFuture;

    private ProcessCameraProvider cameraProvider;
    private final String TAG = getClass().getName();
    private final CountDownLatch countDownLatch; // Waits for both analysis and preview to be running before sending a signal that it is ready

    /*
     * @param previewView: e.g. from your Activity findViewById(R.id.camera_x_preview)
     * @param imageAnalysis: If set to null will generate default imageAnalysis object.
     */
    public ImageData(Context context, PublisherManager publisherManager, LifecycleOwner lifecycleOwner, PreviewView previewView,
                     ImageAnalysis imageAnalysis, ExecutorService imageExecutor){
        super(context, publisherManager);
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.imageAnalysis = imageAnalysis;
        this.imageExecutor = imageExecutor;
        // Initialize countDownLatch with count of 1 if no preview view, 2 if preview view exists
        this.countDownLatch = new CountDownLatch(previewView != null ? 2 : 1);
    }

    // We must specify T to define the extending subclass, S to specify the subscriber type used by the extending subclass, and B to reference the extending subclasses' builder class.
    public abstract static class Builder<T extends ImageData<S>,S extends Subscriber, B extends Builder<T, S, B>>{
        protected final Context context;
        protected final PublisherManager publisherManager;
        protected final LifecycleOwner lifecycleOwner;
        protected PreviewView previewView;
        protected ImageAnalysis imageAnalysis;
        protected ExecutorService imageExecutor;
        protected T imageDataSubtype;
        protected B self;

        public Builder(Context context, PublisherManager publisherManager, LifecycleOwner lifecycleOwner){
            this.context = context;
            this.publisherManager = publisherManager;
            this.lifecycleOwner = lifecycleOwner;
        }

        protected abstract B self();

        public B setPreviewView(PreviewView previewView){
            this.previewView = previewView;
            return self;
        }

        public B setImageAnalysis(ImageAnalysis imageAnalysis){
            this.imageAnalysis = imageAnalysis;
            return self;
        }

        public B setImageExecutor(ExecutorService imageExecutor){
            this.imageExecutor = imageExecutor;
            return self;
        }
    }

    @Override
    public ArrayList<String> getRequiredPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        return permissions;
    }

    @androidx.camera.core.ExperimentalGetImage
    @Override
    public void analyze(@NonNull ImageProxy imageProxy) {
        countDownLatch.countDown();
        if (subscribers.size() > 0 && !paused){
            Image image = imageProxy.getImage();
            int rotation = imageProxy.getImageInfo().getRotationDegrees();
            if (image != null) {
                // Copy the image buffer, as it appears to get overwritten or read from externally
                ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
                byte[] imageData = new byte[byteBuffer.capacity()];
                byteBuffer.get(imageData);
                int format = image.getFormat();
                int width = image.getWidth();
                int height = image.getHeight();
                long timestamp = image.getTimestamp();
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                yuvToRgbConverter.yuvToRgb(image, bitmap);
                customAnalysis(imageData, rotation, format, width, height, timestamp, bitmap);
            }
        }
        imageProxy.close(); // You must call these two lines at the end of the child's analyze method
    }

    protected abstract void customAnalysis(byte[] imageData, int rotation, int format, int width, int height, long timestamp, Bitmap bitmap);

    protected void setDefaultImageAnalysis(){
        imageAnalysis =
                new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(10, 10))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setImageQueueDepth(20)
                        .setTargetRotation(Surface.ROTATION_0)
                        .build();
    }

    @Override
    public void start() {
        if (imageAnalysis == null) {
            setDefaultImageAnalysis();
        }
        if (imageExecutor == null){
            imageExecutor = Executors.newCachedThreadPool(new ProcessPriorityThreadFactory(1, "imageAnalysis"));
        }
        if (subscribers.size() > 0){
            yuvToRgbConverter = new YuvToRgbConverter(context);
            imageAnalysis.setAnalyzer(imageExecutor, this);
        }
        if (previewView != null){
            Handler handler = new Handler(context.getMainLooper());
            handler.post(() -> previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER));
        }
        bindAll(lifecycleOwner);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                Log.i(TAG, "Waiting for preview and analysis to start");
                countDownLatch.await();
                Log.i(TAG, "Preview and analysis started");
                publisherManager.onPublisherInitialized();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        executor.shutdown();
        super.start();
    }

    @Override
    public void stop() {
        imageAnalysis.clearAnalyzer();
        imageAnalysis = null;
        imageExecutor.shutdown();
        yuvToRgbConverter = null;
        previewView = null;
        mCameraProviderFuture.cancel(false);
        cameraProvider.unbindAll();
        cameraProvider = null;
        super.stop();
    }

    private void bindAll(LifecycleOwner lifecycleOwner) {

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build();

        mCameraProviderFuture = ProcessCameraProvider.getInstance(context);
        mCameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = mCameraProviderFuture.get();
                if (previewView != null){
                    Preview preview = new Preview.Builder()
                            .build();

                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis);

                    preview.setSurfaceProvider(previewView.getSurfaceProvider());

                    final Observer<PreviewView.StreamState> previewViewObserver = new Observer<PreviewView.StreamState>() {
                        @Override
                        public void onChanged(PreviewView.StreamState streamState) {
                            Log.i("previewView", "PreviewState: " + streamState.toString());
                            if (streamState.name().equals("STREAMING")) {
                                countDownLatch.countDown();
                            }
                        }
                    };
                    this.previewView.getPreviewStreamState().observe(lifecycleOwner, previewViewObserver);
                }else{
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis);
                }
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    public void test() {
        Log.v("lifecycle", "onAny");
    }
}

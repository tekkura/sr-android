package jp.oist.abcvlib.core.inputs;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.camera.core.ImageProxy;

import com.intentfilter.androidpermissions.PermissionManager;
import com.intentfilter.androidpermissions.models.DeniedPermissions;

import java.util.ArrayList;

import jp.oist.abcvlib.core.inputs.phone.ImageDataRawSubscriber;

/**
 * A publisher is any data stream, e.g. {@link jp.oist.abcvlib.core.inputs.microcontroller.BatteryData}, {@link jp.oist.abcvlib.core.inputs.microcontroller.WheelData}, etc.
 * <br><br>
 * A publisher is created via a default constructor or Builder subclass. When initialized it should
 * pass the {@link Context} and {@link PublisherManager} to this parent class via
 * super(context, publisherManager) within the onCreate method. After which point this class will
 * add the individual publisher to the PublisherManager instance and  and request the permissions
 * specific to that publisher.
 * <br><br>
 * After this class requests and is granted the necessary permissions, it informs the publisherManager
 * that the permission has been granted. After all you have initialized all the publishers you plan
 * to use, you can call {@link PublisherManager#initializePublishers()}. This will initialize all
 * the publisher's data streams but not yet start recording any data. This may take some time
 * especially for CPU hogs like CameraX. You can call this in a {@link Handler} or other async task
 * if you want to start initializing other things in the meantime.
 * <br><br>
 * A publisher must implement the {@link #getRequiredPermissions()} abstract method and return an
 * {@link ArrayList} of Strings specifying the required permissions for that particular data stream.
 * <br><br>
 * A publisher must also implement the {@link #start()} and {@link #stop()} abstract methods to
 * specify how to properly start/stop the data stream.
 * <br><br>
 * @param <T> The {@link Subscriber} subclass that can accept the data published by your publisher.
 *           e.g. the {@link jp.oist.abcvlib.core.inputs.phone.ImageData class extends Publisher<ImageDataRawSubscriber>}
 *           where {@link ImageDataRawSubscriber} implements the
 *           {@link ImageDataRawSubscriber#onImageDataUpdate(long, int, int, Bitmap, byte[])}
 *           method accepting the data from the last part of {@link jp.oist.abcvlib.core.inputs.phone.ImageData#analyze(ImageProxy)}
 */
public abstract class Publisher<T extends Subscriber> implements PermissionManager.PermissionRequestListener{
    protected ArrayList<T> subscribers = new ArrayList<>();
    protected Context context;
    protected Handler handler;
    protected HandlerThread mHandlerThread;
    protected volatile boolean paused = true;
    protected PublisherManager publisherManager;
    protected final String TAG = getClass().getName();
    protected PermissionManager permissionManager;
    protected PublisherState state;

    public Publisher(Context context, PublisherManager publisherManager){
        this.context = context;
        this.publisherManager = publisherManager;
        publisherManager.add(this);
        permissionManager = PermissionManager.getInstance(context);
        Log.i(TAG, "Requesting permissions: " + getRequiredPermissions());
        permissionManager.checkPermissions(getRequiredPermissions(), this);
    }

    public void start(){
        this.state = PublisherState.STARTED;
    }
    public void stop(){
        this.state = PublisherState.STOPPED;
    }
    public abstract ArrayList<String> getRequiredPermissions();

    public void pause() {
        this.state = PublisherState.PAUSED;
        this.paused = true;
    }

    public void resume() {
        this.state = PublisherState.STARTED;
        this.paused = false;
    }

    public PublisherState getState() {
        return state;
    }

    public Publisher<T> addSubscriber(T subscriber){
        this.subscribers.add(subscriber);
        return this;
    }

    public Publisher<T> addSubscribers(ArrayList<T> subscribers){
        this.subscribers = subscribers;
        permissionManager.checkPermissions(getRequiredPermissions(), this);
        return this;
    }

    @Override
    public void onPermissionGranted() {
        Log.i(TAG, "Permissions granted for " + this.getClass().getName());
        publisherManager.onPublisherPermissionsGranted(this);
    }

    @Override
    public void onPermissionDenied(DeniedPermissions deniedPermissions) {
        Log.e(TAG, "Permission Error: Unable to get the following permissions: " + deniedPermissions.toString());
    }
}
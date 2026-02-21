package jp.oist.abcvlib.core.inputs

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.intentfilter.androidpermissions.PermissionManager
import com.intentfilter.androidpermissions.PermissionManager.PermissionRequestListener
import com.intentfilter.androidpermissions.models.DeniedPermissions
import jp.oist.abcvlib.util.Logger
import kotlin.concurrent.Volatile

/**
 * A publisher is any data stream, e.g. [BatteryData][jp.oist.abcvlib.core.inputs.microcontroller.BatteryData],
 * [WheelData][jp.oist.abcvlib.core.inputs.microcontroller.WheelData], etc.
 *
 * A publisher is created via a default constructor or Builder subclass. When initialized it should
 * pass the [Context] and [PublisherManager] to this parent class via
 * super(context, publisherManager) within the onCreate method. After which point this class will
 * add the individual publisher to the PublisherManager instance and request the permissions
 * specific to that publisher.
 *
 * After this class requests and is granted the necessary permissions, it informs the publisherManager
 * that the permission has been granted. After all you have initialized all the publishers you plan
 * to use, you can call [PublisherManager.initializePublishers]. This will initialize all
 * the publisher's data streams but not yet start recording any data. This may take some time
 * especially for CPU hogs like CameraX. You can call this in a [Handler] or other async task
 * if you want to start initializing other things in the meantime.
 *
 * A publisher must implement the [getRequiredPermissions] abstract method and return an
 * [ArrayList] of Strings specifying the required permissions for that particular data stream.
 *
 * A publisher must also implement the [start] and [stop] abstract methods to
 * specify how to properly start/stop the data stream.
 *
 * @param T The [Subscriber] subclass that can accept the data published by your publisher.
 *   e.g. the [ImageData][jp.oist.abcvlib.core.inputs.phone.ImageData] class extends Publisher<ImageDataRawSubscriber>
 *   where [ImageDataRawSubscriber] implements the
 *   [ImageDataRawSubscriber.onImageDataUpdate] method accepting the data from the last part of
 *   [ImageData.analyze][jp.oist.abcvlib.core.inputs.phone.ImageData.analyze]
 */
abstract class Publisher<T : Subscriber>(
    @JvmField protected var context: Context,
    @JvmField protected var publisherManager: PublisherManager
) : PermissionRequestListener {
    @JvmField
    protected var subscribers: ArrayList<T> = ArrayList()

    open var state: PublisherState? = null
        protected set
    protected val permissionManager: PermissionManager
    protected lateinit var mHandlerThread: HandlerThread
    protected lateinit var handler: Handler

    @JvmField
    @Volatile
    protected var paused: Boolean = true

    @JvmField
    protected val TAG: String = javaClass.name


    init {
        publisherManager.add(this)
        permissionManager = PermissionManager.getInstance(context)
        Logger.i(TAG, "Requesting permissions: ${getRequiredPermissions()}")
        permissionManager.checkPermissions(getRequiredPermissions(), this)
    }

    open fun start() {
        state = PublisherState.STARTED
    }

    open fun stop() {
        state = PublisherState.STOPPED
    }

    abstract fun getRequiredPermissions(): ArrayList<String>

    open fun pause() {
        state = PublisherState.PAUSED
        this.paused = true
    }

    open fun resume() {
        state = PublisherState.STARTED
        this.paused = false
    }

    open fun addSubscriber(subscriber: T): Publisher<T> {
        this.subscribers.add(subscriber)
        return this
    }

    open fun addSubscribers(subscribers: ArrayList<T>): Publisher<T> {
        this.subscribers = subscribers
        permissionManager.checkPermissions(getRequiredPermissions(), this)
        return this
    }

    override fun onPermissionGranted() {
        Logger.i(TAG, "Permissions granted for ${this.javaClass.name}")
        publisherManager.onPublisherPermissionsGranted(this)
    }

    override fun onPermissionDenied(deniedPermissions: DeniedPermissions) {
        Logger.e(
            TAG,
            "Permission Error: Unable to get the following permissions: $deniedPermissions"
        )
    }
}

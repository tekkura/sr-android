package jp.oist.abcvlib.core.inputs.publisher

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.intentfilter.androidpermissions.PermissionManager
import com.intentfilter.androidpermissions.PermissionManager.PermissionRequestListener
import com.intentfilter.androidpermissions.models.DeniedPermissions
import jp.oist.abcvlib.core.inputs.Subscriber
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
 * the publisher's data streams but not yet start recording any data. Call it from a worker thread:
 * it blocks until publisher initialization calls return or reach their deadline, and publishers
 * such as CameraX may require the main thread to deliver initialization callbacks.
 *
 * A publisher must implement the [getRequiredPermissions] abstract method and return an
 * [ArrayList] of Strings specifying the required permissions for that particular data stream.
 *
 * A publisher must also implement the [start] and [stop] abstract methods to
 * specify how to properly start/stop the data stream. Publishers that finish initialization
 * asynchronously should capture [initializationSucceededCallback] and
 * [initializationFailedCallback] during [start], then invoke the appropriate callback when
 * initialization finishes.
 *
 * @param T The [jp.oist.abcvlib.core.inputs.Subscriber] subclass that can accept the data published by your publisher.
 *   e.g. the [ImageData][jp.oist.abcvlib.core.inputs.phone.ImageData] class extends Publisher<ImageDataRawSubscriber>
 *   where [ImageDataRawSubscriber] implements the
 *   [ImageDataRawSubscriber.onImageDataUpdate] method accepting the data from the last part of
 *   [ImageData.analyze][jp.oist.abcvlib.core.inputs.phone.ImageData.analyze]
 */
abstract class Publisher<T : Subscriber>(
    protected val context: Context,
    protected var publisherManager: PublisherManager
) : PermissionRequestListener {
    protected var subscribers: ArrayList<T> = ArrayList()

    private var state: PublisherState = PublisherState.STOPPED
    private val initializationAttempt = ThreadLocal<Long>()
    protected val permissionManager: PermissionManager
    protected lateinit var mHandlerThread: HandlerThread
    protected lateinit var handler: Handler

    @Volatile
    protected var paused: Boolean = true

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

    fun pause() {
        state = PublisherState.PAUSED
        this.paused = true
    }

    fun resume() {
        state = PublisherState.STARTED
        this.paused = false
    }

    fun addSubscriber(subscriber: T): Publisher<T> {
        this.subscribers.add(subscriber)
        return this
    }

    fun addSubscribers(subscribers: ArrayList<T>): Publisher<T> {
        this.subscribers = subscribers
        permissionManager.checkPermissions(getRequiredPermissions(), this)
        return this
    }

    fun getState(): PublisherState {
        return state
    }

    override fun onPermissionGranted() {
        Logger.i(TAG, "Permissions granted for ${this.javaClass.simpleName}")
        publisherManager.onPublisherPermissionsGranted(this)
    }

    override fun onPermissionDenied(deniedPermissions: DeniedPermissions) {
        Logger.e(
            TAG,
            "Permission Error: Unable to get the following permissions: $deniedPermissions"
        )

        publisherManager.onPublisherPermissionsDenied(
            PublisherStartupFailure(
                this,
                "Unable to get the following permissions: $deniedPermissions"
            )
        )
    }

    internal fun requestPermissions() {
        permissionManager.checkPermissions(getRequiredPermissions(), this)
    }

    /**
     * Reports that this publisher has finished initialization.
     */
    protected fun reportInitializationSucceeded() {
        reportInitializationSucceeded(currentInitializationAttempt())
    }

    private fun reportInitializationSucceeded(attemptId: Long) {
        publisherManager.onPublisherInitializationSucceeded(this, attemptId)
    }

    /**
     * Captures a completion callback that may be invoked from another thread.
     */
    protected fun initializationSucceededCallback(): () -> Unit {
        val attemptId = currentInitializationAttempt()
        return { reportInitializationSucceeded(attemptId) }
    }

    /**
     * Captures a failure callback that may be invoked from another thread.
     */
    protected fun initializationFailedCallback(): (String?, Throwable?) -> Unit {
        val attemptId = currentInitializationAttempt()
        return { message, cause -> reportInitializationFailed(attemptId, message, cause) }
    }

    /**
     * Reports that this publisher could not finish initialization.
     */
    @JvmOverloads
    protected fun reportInitializationFailed(
        message: String? = null,
        cause: Throwable? = null
    ) {
        reportInitializationFailed(currentInitializationAttempt(), message, cause)
    }

    private fun reportInitializationFailed(
        attemptId: Long,
        message: String? = null,
        cause: Throwable? = null
    ) {
        publisherManager.onPublisherInitializationFailed(
            attemptId,
            PublisherStartupFailure(this, message, cause)
        )
    }

    private fun currentInitializationAttempt(): Long {
        return checkNotNull(initializationAttempt.get()) {
            "Publisher is not running an initialization attempt"
        }
    }

    internal fun runInitialization(attemptId: Long) {
        state = PublisherState.INITIALIZING
        initializationAttempt.set(attemptId)
        try {
            start()
        } finally {
            initializationAttempt.remove()
        }
    }

    internal fun initializationSucceeded() {
        state = PublisherState.INITIALIZED
    }

    internal fun initializationFailed() {
        state = PublisherState.FAILED
    }
}

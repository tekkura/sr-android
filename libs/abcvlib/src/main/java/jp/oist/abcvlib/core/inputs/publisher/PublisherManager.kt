package jp.oist.abcvlib.core.inputs.publisher

import android.os.Handler
import android.os.Looper
import androidx.annotation.WorkerThread
import jp.oist.abcvlib.util.Logger
import java.util.concurrent.Executors
import java.util.concurrent.Phaser
import java.util.concurrent.TimeUnit

/**
 * Manages the permission lifecycle of a group of publishers
 * In order to synchronize the lifecycle of all publishers, this creates a Phaser that waits for
 * each phase to finish for all publishers before allowing the next phase to start.
 * phase 0 = permissions of publisher objects
 * phase 1 = initialization of publisher object streams/threads
 * phase 2 = initialize publisher objects (i.e. initialize recording data)
 */
class PublisherManager(
    private val permissionTimeoutMillis: Long = DEFAULT_PERMISSION_TIMEOUT_MILLIS,
    initializationTimeoutMillis: Long = DEFAULT_INITIALIZATION_TIMEOUT_MILLIS
) {
    private val registry = PublisherRegistry()
    val publishers: ArrayList<Publisher<*>>
        get() = registry.publishers

    private val phaser = Phaser(1)
    private val permissionTimeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    private val initializationRunner = PublisherInitializationRunner(
        initializationTimeoutMillis,
        ::completePublisherInitialization,
        ::recordInitializationFailure
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupListeners = ArrayList<PublisherManagerStartupListener>()
    private val TAG: String = javaClass.name
    private var initializationStarted = false
    private var startupStarted = false

    @Volatile
    var startupResult: PublisherManagerStartupResult? = null
        private set

    init {
        require(permissionTimeoutMillis > 0) {
            "permissionTimeoutMillis must be positive"
        }
        require(initializationTimeoutMillis > 0) {
            "initializationTimeoutMillis must be positive"
        }
    }

    //========================================Phase 0===============================================
    @Synchronized
    fun add(publisher: Publisher<*>): PublisherManager {
        Logger.i(TAG, "Adding publisher: " + publisher.javaClass.name)
        registry.add(publisher)
        phaser.register()
        return this
    }

    @Synchronized
    fun setRequirement(
        publisher: Publisher<*>,
        requirement: PublisherRequirement
    ): PublisherManager {
        registry.setRequirement(publisher, requirement)
        return this
    }

    @Synchronized
    fun getRequirement(publisher: Publisher<*>): PublisherRequirement {
        return registry.getRequirement(publisher)
    }

    @Synchronized
    fun onPublisherPermissionsGranted(grantedPublisher: Publisher<*>) {
        if (!registry.recordPermissionGranted(grantedPublisher)) return

        Logger.i(TAG, "Publisher permissions granted for: " + grantedPublisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    @Synchronized
    internal fun onPublisherPermissionsDenied(failure: PublisherStartupFailure) {
        if (!registry.recordPermissionFailure(failure)) return

        failure.publisher.initializationFailed()
        phaser.arriveAndDeregister()
    }

    //========================================Phase 1===============================================
    private fun initialize(publisher: Publisher<*>) {
        Logger.i(TAG, "Registering publisher for phase 1: " + publisher.javaClass.name)
        phaser.register()
        initializationRunner.initialize(publisher)
    }

    @Deprecated("Publishers should call reportInitializationSucceeded()")
    fun onPublisherInitialized() {
        initializationRunner.reportLegacyInitializationSucceeded()
    }

    internal fun onPublisherInitializationSucceeded(publisher: Publisher<*>, attemptId: Long) {
        initializationRunner.reportInitializationSucceeded(publisher, attemptId)
    }

    internal fun onPublisherInitializationFailed(
        attemptId: Long,
        failure: PublisherStartupFailure
    ) {
        initializationRunner.reportInitializationFailed(attemptId, failure)
    }

    @Synchronized
    private fun completePublisherInitialization(publisher: Publisher<*>) {
        registry.recordInitializationSucceeded(publisher)
        publisher.initializationSucceeded()
        Logger.i(TAG, "Publisher initialized: " + publisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    @Synchronized
    private fun recordInitializationFailure(failure: PublisherStartupFailure) {
        registry.recordInitializationFailure(failure)
        failure.publisher.initializationFailed()
        Logger.e(TAG, "Publisher initialization failed: " + failure.publisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    /**
     * Initializes registered publishers and waits for their start calls to return or time out.
     *
     * This must not run on the main thread because a publisher may need main-thread callbacks to
     * finish its start call.
     */
    @WorkerThread
    fun initializePublishers() {
        synchronized(this) {
            registry.lock()
            if (initializationStarted) return

            initializationStarted = true
        }

        val permissionTimeout = permissionTimeoutExecutor.schedule(
            ::failPendingPermissions,
            permissionTimeoutMillis,
            TimeUnit.MILLISECONDS
        )

        phaser.arrive()
        Logger.i(TAG, "Starting initializePublishers with " + publishers.size + " publishers")
        Logger.i(TAG, "Waiting on all publishers to initialize before starting")
        phaser.awaitAdvance(0) // Waits to initialize if not finished with initPhase
        permissionTimeout.cancel(false)
        permissionTimeoutExecutor.shutdown()
        Logger.i(TAG, "Phase 0 complete, starting publisher initialization")
        for (publisher in registry.availablePublishers()) {
            Logger.i(TAG, "Initializing publisher: " + publisher.javaClass.name)
            initialize(publisher)
        }

        initializationRunner.finishLaunching()
    }

    /**
     * Retries the initialization process for publishers that failed during a previous startup attempt.
     *
     * This method resets the internal state for failed publishers, re-requests necessary permissions,
     * and attempts to re-initialize them. It can only be called after an initial startup has
     * completed (i.e., [startupResult] is not null).
     *
     * This method blocks while permissions are resolved and publishers are re-initialized. It must
     * not be called from the main thread because permission results are delivered there.
     *
     * @throws IllegalStateException If called before the initial startup process has finished.
     */
    @WorkerThread
    fun retryFailedPublishers() {
        retryFailedPublishersInternal(null)
    }

    /**
     * Retries the initialization process for publishers that failed during a previous startup attempt.
     *
     * This method resets the internal state for failed publishers, re-requests necessary permissions,
     * and attempts to re-initialize them. It can only be called after an initial startup has
     * completed (i.e., [startupResult] is not null).
     *
     * This method blocks while permissions are resolved and publishers are re-initialized. It must
     * not be called from the main thread because permission results are delivered there.
     *
     * @param listener A [PublisherManagerStartupListener] to be notified of the
     * results of the retry attempt.
     * @throws IllegalStateException If called before the initial startup process has finished.
     */
    @WorkerThread
    fun retryFailedPublishers(listener: PublisherManagerStartupListener) {
        retryFailedPublishersInternal(listener)
    }

    private fun retryFailedPublishersInternal(listener: PublisherManagerStartupListener?) {
        val failedPublishers = synchronized(this) {
            checkNotNull(startupResult) {
                "Publishers cannot be retried before startup finishes"
            }

            registry.prepareRetry().also {
                startupResult = null
                startupStarted = false
                repeat(it.size) { phaser.register() }
            }
        }

        awaitRetryPermissions(failedPublishers)
        initializationRunner.reset()
        failedPublishers
            .filter(registry::isAvailable)
            .forEach(::initialize)
        initializationRunner.finishLaunching()
        startPublishersInternal(listener)
    }

    private fun awaitRetryPermissions(publishers: List<Publisher<*>>) {
        val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val timeout = timeoutExecutor.schedule(
            ::failPendingPermissions,
            permissionTimeoutMillis,
            TimeUnit.MILLISECONDS
        )

        publishers.forEach(Publisher<*>::requestPermissions)
        val phase = phaser.arrive()
        phaser.awaitAdvance(phase)
        timeout.cancel(false)
        timeoutExecutor.shutdown()
    }

    private fun failPendingPermissions() {
        registry.pendingPermissionPublishers()
            .forEach { publisher ->
                onPublisherPermissionsDenied(
                    PublisherStartupFailure(
                        publisher,
                        "Timed out waiting for publisher permissions"
                    )
                )
            }
    }

    //========================================Phase 2===============================================
    fun startPublishers() {
        startPublishersInternal(null)
    }

    fun startPublishers(listener: PublisherManagerStartupListener) {
        startPublishersInternal(listener)
    }

    private fun startPublishersInternal(listener: PublisherManagerStartupListener?) {
        val shouldStart = synchronized(this) {
            val result = startupResult
            if (result != null) {
                listener?.let { mainHandler.post { it.onStartupResult(result) } }
                false
            } else {
                listener?.let(startupListeners::add)
                val prev = startupStarted
                startupStarted = true

                !prev
            }
        }

        if (!shouldStart) return

        val phase = phaser.arrive()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            Logger.i(TAG, "Waiting on phase 1 to finish before starting")
            phaser.awaitAdvance(phase)

            val snapshot = registry.startupSnapshot()

            val result = if (snapshot.requiredFailures.isEmpty()) {
                Logger.i(TAG, "Publisher initialization complete. Starting available publishers")
                snapshot.availablePublishers.forEach(Publisher<*>::resume)
                PublisherManagerStartupResult.Success(snapshot.optionalFailures)
            } else {
                Logger.e(TAG, "Required publisher initialization failed")
                PublisherManagerStartupResult.Failure(
                    snapshot.requiredFailures,
                    snapshot.optionalFailures
                )
            }

            val listeners = synchronized(this) {
                startupResult = result
                startupListeners.toList().also { startupListeners.clear() }
            }

            listeners.forEach { mainHandler.post { it.onStartupResult(result) } }

            executor.shutdown() // Shut down the executor after the task is completed
        }
    }

    //====================================Non-phase Related=========================================
    fun pausePublishers() {
        for (publisher in publishers) {
            publisher.pause()
        }
    }

    fun resumePublishers() {
        for (publisher in publishers) {
            publisher.resume()
        }
    }

    fun stopPublishers() {
        for (publisher in publishers) {
            publisher.stop()
        }
    }

    private companion object {
        const val DEFAULT_PERMISSION_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_INITIALIZATION_TIMEOUT_MILLIS = 10_000L
    }
}

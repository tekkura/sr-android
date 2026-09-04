package jp.oist.abcvlib.core.inputs

import android.os.Handler
import android.os.Looper
import androidx.annotation.WorkerThread
import jp.oist.abcvlib.core.inputs.publisher.PublisherInitializationRunner
import jp.oist.abcvlib.core.inputs.publisher.PublisherManagerStartupListener
import jp.oist.abcvlib.core.inputs.publisher.PublisherManagerStartupResult
import jp.oist.abcvlib.core.inputs.publisher.PublisherRequirement
import jp.oist.abcvlib.core.inputs.publisher.PublisherStartupFailure
import jp.oist.abcvlib.util.Logger
import java.util.concurrent.Executors
import java.util.concurrent.Phaser
import java.util.concurrent.TimeUnit

/**
 * Coordinates startup and group lifecycle operations for a fixed set of [Publisher] instances.
 *
 * This manager owns:
 * - publisher registration and required/optional policy;
 * - permission-resolution bookkeeping and its deadline;
 * - aggregation of publisher outcomes into one [PublisherManagerStartupResult]; and
 * - group start, retry, pause, resume, and stop operations.
 *
 * Individual publishers own their hardware resources and cleanup. They report initialization
 * completion to this manager, while [PublisherInitializationRunner] owns execution deadlines and
 * rejects results from expired initialization attempts. Callers remain responsible for deciding
 * how a startup result is presented to the user and whether application-specific work may begin.
 *
 * Startup progresses through permission resolution, publisher initialization, and activation.
 * Activation resumes initialized publishers only when all required publishers initialized
 * successfully. Optional failures are included in the aggregate result without blocking activation.
 */
class PublisherManager(
    private val permissionTimeoutMillis: Long = DEFAULT_PERMISSION_TIMEOUT_MILLIS,
    initializationTimeoutMillis: Long = DEFAULT_INITIALIZATION_TIMEOUT_MILLIS
) {
    private val registrations = LinkedHashMap<Publisher<*>, PublisherRegistration>()
    private var registrationsLocked = false

    val publishers: ArrayList<Publisher<*>>
        get() = synchronized(this) { ArrayList(registrations.keys) }

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
    private var retryInProgress = false
    private var retryCandidates: List<Publisher<*>>? = null

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
        check(!registrationsLocked) {
            "Publishers cannot be added after initialization has started"
        }
        registrations[publisher] = PublisherRegistration()
        phaser.register()
        return this
    }

    @Synchronized
    fun setRequirement(
        publisher: Publisher<*>,
        requirement: PublisherRequirement
    ) {
        check(!registrationsLocked) {
            "Publisher requirements cannot change after initialization has started"
        }
        registrationFor(publisher).requirement = requirement
    }

    @Synchronized
    fun getRequirement(publisher: Publisher<*>): PublisherRequirement {
        return registrationFor(publisher).requirement
    }

    @Synchronized
    fun onPublisherPermissionsGranted(grantedPublisher: Publisher<*>) {
        val registration = registrationFor(grantedPublisher)
        if (registration.permissionResolved) return
        registration.permissionResolved = true

        Logger.i(TAG, "Publisher permissions granted for: " + grantedPublisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    @Synchronized
    internal fun onPublisherPermissionsDenied(failure: PublisherStartupFailure) {
        val registration = registrationFor(failure.publisher)
        if (registration.permissionResolved) return
        registration.permissionResolved = true
        registration.failure = failure

        failure.publisher.initializationFailed()
        phaser.arriveAndDeregister()
    }

    //========================================Phase 1===============================================
    private fun initialize(publisher: Publisher<*>) {
        Logger.i(TAG, "Registering publisher for phase 1: " + publisher.javaClass.name)
        phaser.register()
        initializationRunner.initialize(publisher)
    }

    @Deprecated(
        message = "Synchronous publishers should call reportInitializationSucceeded(); " +
            "asynchronous publishers should use initializationSucceededCallback()",
        level = DeprecationLevel.ERROR
    )
    fun onPublisherInitialized() {
        initializationRunner.reportLegacyInitializationSucceeded()
    }

    internal fun onPublisherInitializationSucceeded() =
        initializationRunner.reportInitializationSucceeded()

    internal fun onPublisherInitializationFailed(failure: PublisherStartupFailure) =
        initializationRunner.reportInitializationFailed(failure)

    internal fun publisherInitializationSucceededCallback() =
        initializationRunner.initializationSucceededCallback()

    internal fun publisherInitializationFailedCallback() =
        initializationRunner.initializationFailedCallback()

    @Synchronized
    private fun completePublisherInitialization(publisher: Publisher<*>) {
        publisher.initializationSucceeded()
        Logger.i(TAG, "Publisher initialized: " + publisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    @Synchronized
    private fun recordInitializationFailure(failure: PublisherStartupFailure) {
        registrationFor(failure.publisher).apply {
            this.failure = failure
        }
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
            registrationsLocked = true
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
        for (publisher in availablePublishers()) {
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
            check(!retryInProgress) {
                "Publisher retry is already in progress"
            }

            (retryCandidates ?: failedPublishers().also { retryCandidates = it }).also {
                prepareRetry(it)
                retryInProgress = true
                repeat(it.size) { phaser.register() }
            }
        }

        try {
            awaitRetryPermissions(failedPublishers)
            initializationRunner.reset()
            failedPublishers
                .filter(::isAvailable)
                .forEach(::initialize)
            initializationRunner.finishLaunching()
        } catch (failure: Exception) {
            synchronized(this) { retryInProgress = false }
            throw failure
        }

        synchronized(this) {
            startupResult = null
            startupStarted = false
            retryInProgress = false
            retryCandidates = null
        }
        startPublishersInternal(listener)
    }

    private fun awaitRetryPermissions(publishers: List<Publisher<*>>) {
        val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        val timeout = timeoutExecutor.schedule(
            ::failPendingPermissions,
            permissionTimeoutMillis,
            TimeUnit.MILLISECONDS
        )

        publishers.forEach { publisher ->
            try {
                publisher.requestPermissions()
            } catch (failure: Exception) {
                onPublisherPermissionsDenied(
                    PublisherStartupFailure(
                        publisher,
                        failure.message ?: "Unable to request publisher permissions",
                        failure
                    )
                )
            }
        }
        val phase = phaser.arrive()
        phaser.awaitAdvance(phase)
        timeout.cancel(false)
        timeoutExecutor.shutdown()
    }

    private fun failPendingPermissions() {
        pendingPermissionPublishers()
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

            val snapshot = startupSnapshot()

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

    @Synchronized
    private fun availablePublishers(): List<Publisher<*>> {
        return registrations.filterValues { it.failure == null }.keys.toList()
    }

    @Synchronized
    private fun isAvailable(publisher: Publisher<*>): Boolean {
        return registrationFor(publisher).failure == null
    }

    @Synchronized
    private fun pendingPermissionPublishers(): List<Publisher<*>> {
        return registrations.filterValues { !it.permissionResolved }.keys.toList()
    }

    @Synchronized
    private fun startupSnapshot(): StartupSnapshot {
        fun failures(requirement: PublisherRequirement): List<PublisherStartupFailure> {
            return registrations.values
                .filter { it.requirement == requirement }
                .mapNotNull { it.failure }
        }

        return StartupSnapshot(
            availablePublishers = registrations
                .filterValues { it.failure == null }
                .keys
                .toList(),
            requiredFailures = failures(PublisherRequirement.REQUIRED),
            optionalFailures = failures(PublisherRequirement.OPTIONAL)
        )
    }

    @Synchronized
    private fun failedPublishers(): List<Publisher<*>> {
        return registrations
            .filterValues { it.failure != null }
            .keys
            .toList()
            .also {
                check(it.isNotEmpty()) {
                    "There are no failed publishers to retry"
                }
            }
    }

    @Synchronized
    private fun prepareRetry(failedPublishers: List<Publisher<*>>) {
        check(failedPublishers.isNotEmpty()) {
            "There are no failed publishers to retry"
        }

        failedPublishers.forEach { publisher ->
            registrationFor(publisher).apply {
                permissionResolved = false
                failure = null
            }
        }
    }

    private fun registrationFor(publisher: Publisher<*>): PublisherRegistration {
        return requireNotNull(registrations[publisher]) {
            "Publisher is not registered with this manager"
        }
    }

    private data class PublisherRegistration(
        var requirement: PublisherRequirement = PublisherRequirement.REQUIRED,
        var permissionResolved: Boolean = false,
        var failure: PublisherStartupFailure? = null
    )

    private data class StartupSnapshot(
        val availablePublishers: List<Publisher<*>>,
        val requiredFailures: List<PublisherStartupFailure>,
        val optionalFailures: List<PublisherStartupFailure>
    )

    private companion object {
        const val DEFAULT_PERMISSION_TIMEOUT_MILLIS = 30_000L
        const val DEFAULT_INITIALIZATION_TIMEOUT_MILLIS = 10_000L
    }
}

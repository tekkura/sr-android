package jp.oist.abcvlib.core.inputs

import android.os.Handler
import android.os.Looper
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
    private val permissionTimeoutMillis: Long = DEFAULT_PERMISSION_TIMEOUT_MILLIS
) {
    private val registrations = LinkedHashMap<Publisher<*>, PublisherRegistration>()
    val publishers: ArrayList<Publisher<*>>
        get() = synchronized(this) { ArrayList(registrations.keys) }

    private val phaser = Phaser(1)
    private val initializingPublisher = ThreadLocal<Publisher<*>>()
    private val permissionTimeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupListeners = ArrayList<PublisherManagerStartupListener>()
    private val TAG: String = javaClass.name
    private var registrationsLocked = false
    private var initializationStarted = false
    private var startupStarted = false

    @Volatile
    var startupResult: PublisherManagerStartupResult? = null
        private set

    init {
        require(permissionTimeoutMillis > 0) {
            "permissionTimeoutMillis must be positive"
        }
    }

    //========================================Phase 0===============================================
    @Synchronized
    fun add(publisher: Publisher<*>): PublisherManager {
        check(!registrationsLocked) {
            "Publishers cannot be added after initialization has started"
        }

        Logger.i(TAG, "Adding publisher: " + publisher.javaClass.name)
        registrations[publisher] = PublisherRegistration()
        phaser.register()
        return this
    }

    @Synchronized
    fun setRequirement(
        publisher: Publisher<*>,
        requirement: PublisherRequirement
    ): PublisherManager {
        check(!registrationsLocked) {
            "Publisher requirements cannot change after initialization has started"
        }

        val registration = registrations[publisher]
        requireNotNull(registration) {
            "Publisher is not registered with this manager"
        }

        registration.requirement = requirement
        return this
    }

    @Synchronized
    fun getRequirement(publisher: Publisher<*>): PublisherRequirement {
        return requireNotNull(registrations[publisher]) {
            "Publisher is not registered with this manager"
        }.requirement
    }

    @Synchronized
    fun onPublisherPermissionsGranted(grantedPublisher: Publisher<*>) {
        val registration = registrations[grantedPublisher]
        requireNotNull(registration) {
            "Publisher is not registered with this manager"
        }
        if (registration.permissionResolved) return

        registration.permissionResolved = true
        Logger.i(TAG, "Publisher permissions granted for: " + grantedPublisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    @Synchronized
    internal fun onPublisherPermissionsDenied(failure: PublisherStartupFailure) {
        val registration = registrations[failure.publisher]
        requireNotNull(registration) {
            "Publisher is not registered with this manager"
        }

        if (registration.permissionResolved) return

        registration.permissionResolved = true
        registration.failure = failure
        failure.publisher.markFailed()
        phaser.arriveAndDeregister()
    }

    //========================================Phase 1===============================================
    private fun initialize(publisher: Publisher<*>) {
        Logger.i(TAG, "Registering publisher for phase 1: " + publisher.javaClass.name)
        phaser.register()
        publisher.beginInitialization()
        initializingPublisher.set(publisher)
        try {
            publisher.start()
        } finally {
            initializingPublisher.remove()
        }
    }

    @Deprecated("Publishers should call reportInitializationSucceeded()")
    fun onPublisherInitialized() {
        val publisher = checkNotNull(initializingPublisher.get()) {
            "Asynchronous publishers must use initializationSucceededCallback()"
        }
        onPublisherInitializationSucceeded(publisher)
    }

    @Synchronized
    internal fun onPublisherInitializationSucceeded(publisher: Publisher<*>) {
        val registration = registrations[publisher]
        requireNotNull(registration) {
            "Publisher is not registered with this manager"
        }

        if (registration.completed) return

        registration.completed = true
        Logger.i(TAG, "Publisher initialized: " + publisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    @Synchronized
    internal fun onPublisherInitializationFailed(failure: PublisherStartupFailure) {
        val registration = registrations[failure.publisher]
        requireNotNull(registration) {
            "Publisher is not registered with this manager"
        }

        if (registration.completed) return

        registration.completed = true
        registration.failure = failure
        Logger.e(TAG, "Publisher initialization failed: " + failure.publisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

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
        for (publisher in registrations.filterValues { it.failure == null }.keys) {
            Logger.i(TAG, "Initializing publisher: " + publisher.javaClass.name)
            initialize(publisher)
        }
    }

    @Synchronized
    private fun failPendingPermissions() {
        registrations
            .filterValues { !it.permissionResolved }
            .forEach { (publisher, registration) ->
                val failure = PublisherStartupFailure(
                    publisher,
                    "Timed out waiting for publisher permissions"
                )
                registration.permissionResolved = true
                registration.failure = failure
                publisher.markFailed()
                phaser.arriveAndDeregister()
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

        phaser.arrive()
        val executor = Executors.newSingleThreadExecutor()
        executor.submit {
            Logger.i(TAG, "Waiting on phase 1 to finish before starting")
            phaser.awaitAdvance(1)

            val requiredFailures = registrations.values
                .filter { it.requirement == PublisherRequirement.REQUIRED }
                .mapNotNull { it.failure }
            val optionalFailures = registrations.values
                .filter { it.requirement == PublisherRequirement.OPTIONAL }
                .mapNotNull { it.failure }

            val result = if (requiredFailures.isEmpty()) {
                Logger.i(TAG, "Publisher initialization complete. Starting available publishers")
                registrations
                    .filterValues { it.failure == null }
                    .keys
                    .forEach(Publisher<*>::resume)
                PublisherManagerStartupResult.Success(optionalFailures)
            } else {
                Logger.e(TAG, "Required publisher initialization failed")
                PublisherManagerStartupResult.Failure(requiredFailures, optionalFailures)
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

    private data class PublisherRegistration(
        var requirement: PublisherRequirement = PublisherRequirement.REQUIRED,
        var permissionResolved: Boolean = false,
        var completed: Boolean = false,
        var failure: PublisherStartupFailure? = null
    )

    private companion object {
        const val DEFAULT_PERMISSION_TIMEOUT_MILLIS = 30_000L
    }
}

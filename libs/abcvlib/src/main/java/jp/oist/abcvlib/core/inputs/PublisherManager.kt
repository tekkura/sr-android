package jp.oist.abcvlib.core.inputs

import jp.oist.abcvlib.util.Logger
import java.util.concurrent.Executors
import java.util.concurrent.Phaser

/**
 * Manages the permission lifecycle of a group of publishers
 * In order to synchronize the lifecycle of all publishers, this creates a Phaser that waits for
 * each phase to finish for all publishers before allowing the next phase to start.
 * phase 0 = permissions of publisher objects
 * phase 1 = initialization of publisher object streams/threads
 * phase 2 = initialize publisher objects (i.e. initialize recording data)
 */
class PublisherManager {
    private val registrations = LinkedHashMap<Publisher<*>, PublisherRegistration>()
    val publishers: ArrayList<Publisher<*>>
        get() = synchronized(this) { ArrayList(registrations.keys) }

    private val phaser = Phaser(1)
    private val initializingPublisher = ThreadLocal<Publisher<*>>()
    private val TAG: String = javaClass.name
    private var registrationsLocked = false

    @Volatile
    var startupResult: PublisherManagerStartupResult? = null
        private set

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

    fun onPublisherPermissionsGranted(grantedPublisher: Publisher<*>) { // Accept the publisher
        Logger.i(TAG, "Publisher permissions granted for: " + grantedPublisher.javaClass.name)
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
        }

        phaser.arrive()
        Logger.i(TAG, "Starting initializePublishers with " + publishers.size + " publishers")
        Logger.i(TAG, "Waiting on all publishers to initialize before starting")
        phaser.awaitAdvance(0) // Waits to initialize if not finished with initPhase
        Logger.i(TAG, "Phase 0 complete, starting publisher initialization")
        for (publisher in publishers) {
            Logger.i(TAG, "Initializing publisher: " + publisher.javaClass.name)
            initialize(publisher)
        }
    }

    //========================================Phase 2===============================================
    fun startPublishers() {
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

            startupResult = if (requiredFailures.isEmpty()) {
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
        var completed: Boolean = false,
        var failure: PublisherStartupFailure? = null
    )
}

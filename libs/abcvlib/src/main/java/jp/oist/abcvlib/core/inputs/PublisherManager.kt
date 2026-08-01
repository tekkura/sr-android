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
    val publishers: ArrayList<Publisher<*>> = ArrayList()
    private val requirements = HashMap<Publisher<*>, PublisherRequirement>()
    private val phaser = Phaser(1)
    private val TAG: String = javaClass.name
    private var registrationsLocked = false

    //========================================Phase 0===============================================
    @Synchronized
    fun add(publisher: Publisher<*>): PublisherManager {
        check(!registrationsLocked) {
            "Publishers cannot be added after initialization has started"
        }

        Logger.i(TAG, "Adding publisher: " + publisher.javaClass.name)
        publishers.add(publisher)
        requirements[publisher] = PublisherRequirement.REQUIRED
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
        require(requirements.containsKey(publisher)) {
            "Publisher is not registered with this manager"
        }

        requirements[publisher] = requirement
        return this
    }

    @Synchronized
    fun getRequirement(publisher: Publisher<*>): PublisherRequirement {
        return requireNotNull(requirements[publisher]) {
            "Publisher is not registered with this manager"
        }
    }

    fun onPublisherPermissionsGranted(grantedPublisher: Publisher<*>) { // Accept the publisher
        Logger.i(TAG, "Publisher permissions granted for: " + grantedPublisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    //========================================Phase 1===============================================
    private fun initialize(publisher: Publisher<*>) {
        Logger.i(TAG, "Registering publisher for phase 1: " + publisher.javaClass.name)
        phaser.register()
        publisher.start()
    }

    fun onPublisherInitialized() {
        Logger.i(TAG, "Publisher deregistering: " + Thread.currentThread().stackTrace[2].className)
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
            Logger.i(TAG, "All publishers initialized. Starting publishers")
            for (publisher in publishers) {
                publisher.resume()
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
}

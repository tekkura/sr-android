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
    private val publishersLock = Any()
    private val phaser = Phaser(1)
    private val TAG: String = javaClass.name

    @Volatile
    private var lifecyclePaused = false

    //========================================Phase 0===============================================
    fun add(publisher: Publisher<*>): PublisherManager {
        Logger.i(TAG, "Adding publisher: " + publisher.javaClass.name)
        synchronized(publishersLock) {
            publishers.add(publisher)
            phaser.register()
            if (lifecyclePaused) {
                publisher.pause()
            }
        }
        return this
    }

    fun onPublisherPermissionsGranted(grantedPublisher: Publisher<*>) { // Accept the publisher
        Logger.i(TAG, "Publisher permissions granted for: " + grantedPublisher.javaClass.name)
        phaser.arriveAndDeregister()
    }

    //========================================Phase 1===============================================
    private fun initialize(publisher: Publisher<*>) {
        Logger.i(TAG, "Registering publisher for phase 1: " + publisher.javaClass.name)
        synchronized(publishersLock) {
            phaser.register()
            publisher.start()
            if (lifecyclePaused) {
                publisher.pause()
            }
        }
    }

    fun onPublisherInitialized() {
        Logger.i(TAG, "Publisher deregistering: " + Thread.currentThread().stackTrace[2].className)
        phaser.arriveAndDeregister()
    }

    fun initializePublishers() {
        phaser.arrive()
        val publisherCount = synchronized(publishersLock) { publishers.size }
        Logger.i(TAG, "Starting initializePublishers with " + publisherCount + " publishers")
        Logger.i(TAG, "Waiting on all publishers to initialize before starting")
        phaser.awaitAdvance(0) // Waits to initialize if not finished with initPhase
        Logger.i(TAG, "Phase 0 complete, starting publisher initialization")
        val publishersSnapshot = synchronized(publishersLock) { publishers.toList() }
        for (publisher in publishersSnapshot) {
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
            synchronized(publishersLock) {
                if (!lifecyclePaused) {
                    for (publisher in publishers) {
                        publisher.resume()
                    }
                }
            }
            executor.shutdown() // Shut down the executor after the task is completed
        }
    }

    //====================================Non-phase Related=========================================
    fun pausePublishers() {
        synchronized(publishersLock) {
            lifecyclePaused = true
            for (publisher in publishers) {
                if (publisher.getState() == PublisherState.STARTED) {
                    publisher.pause()
                }
            }
        }
    }

    fun resumePublishers() {
        synchronized(publishersLock) {
            lifecyclePaused = false
            for (publisher in publishers) {
                if (publisher.getState() != PublisherState.STOPPED) {
                    publisher.resume()
                }
            }
        }
    }

    fun stopPublishers() {
        synchronized(publishersLock) {
            for (publisher in publishers) {
                publisher.stop()
            }
        }
    }
}

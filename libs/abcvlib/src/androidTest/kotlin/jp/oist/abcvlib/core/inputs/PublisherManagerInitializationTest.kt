package jp.oist.abcvlib.core.inputs

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublisherManagerInitializationTest {
    @Test
    fun initializePublishersCanBeCalledTwice() {
        val manager = PublisherManager()
        val publisher = TestPublisher(context, manager, shouldFail = false)

        manager.initializePublishers()
        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Success)
        assertEquals(1, publisher.initializationCount)
    }

    @Test
    fun capturedInitializationCallbackWorksAsynchronously() {
        val manager = PublisherManager()
        AsyncPublisher(context, manager)

        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Success)
    }

    @Test
    fun capturedInitializationFailureCallbackWorksAsynchronously() {
        val manager = PublisherManager()
        AsyncFailurePublisher(context, manager)

        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Failure)
        val failure = (result as PublisherManagerStartupResult.Failure).requiredFailures.single()
        assertEquals("Async failure", failure.message)
    }

    @Test
    fun startPublishersCanBeCalledTwiceWhileInitializationIsPending() {
        val manager = PublisherManager()
        val publisher = ControlledAsyncPublisher(context, manager)
        val firstResult = CountDownLatch(1)
        val secondResult = CountDownLatch(1)

        manager.initializePublishers()
        manager.startPublishers(PublisherManagerStartupListener { firstResult.countDown() })
        manager.startPublishers(PublisherManagerStartupListener { secondResult.countDown() })

        assertFalse(firstResult.await(200, TimeUnit.MILLISECONDS))
        assertFalse(secondResult.await(200, TimeUnit.MILLISECONDS))
        publisher.completeInitialization.countDown()
        assertTrue(firstResult.await(3, TimeUnit.SECONDS))
        assertTrue(secondResult.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun legacyCallbackSupportsPublishersOfTheSameClass() {
        val manager = PublisherManager()
        LegacyPublisher(context, manager, shouldFail = false)
        val failedPublisher = LegacyPublisher(context, manager, shouldFail = true)

        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Failure)
        val failure = (result as PublisherManagerStartupResult.Failure).requiredFailures.single()
        assertTrue(failure.publisher === failedPublisher)
    }

    @Test
    fun initializationTimeoutBecomesRequiredFailure() {
        val manager = PublisherManager(initializationTimeoutMillis = 100)
        val publisher = BlockingInitializationPublisher(context, manager)

        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Failure)
        assertEquals(PublisherState.FAILED, publisher.getState())
    }

    @Test
    fun publishersInitializeConcurrently() {
        val manager = PublisherManager(initializationTimeoutMillis = 2_000)
        val publishersStarted = CountDownLatch(2)
        ConcurrentInitializationPublisher(context, manager, publishersStarted)
        ConcurrentInitializationPublisher(context, manager, publishersStarted)

        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Success)
    }

    @Test
    fun initializePublishersWaitsForPublisherStartsToReturn() {
        val manager = PublisherManager(initializationTimeoutMillis = 2_000)
        val publisher = ReturningInitializationPublisher(context, manager)
        val initializationReturned = CountDownLatch(1)
        val initializationThread = Thread {
            manager.initializePublishers()
            initializationReturned.countDown()
        }

        initializationThread.start()
        assertTrue(publisher.startEntered.await(3, TimeUnit.SECONDS))
        assertFalse(initializationReturned.await(200, TimeUnit.MILLISECONDS))

        publisher.allowStartToReturn.countDown()
        assertTrue(initializationReturned.await(3, TimeUnit.SECONDS))
        initializationThread.join(3_000)
    }

    @Test
    fun permissionTimeoutBecomesRequiredFailure() {
        val manager = PublisherManager(permissionTimeoutMillis = 100)
        val publisher = PendingPermissionPublisher(context, manager)

        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Failure)
        assertEquals(PublisherState.FAILED, publisher.getState())
    }

    @Test
    fun requiredFailureKeepsSuccessfulPublisherPaused() {
        val manager = PublisherManager()
        val failedPublisher = TestPublisher(context, manager, shouldFail = true)
        val successfulPublisher = TestPublisher(context, manager, shouldFail = false)

        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Failure)
        assertEquals(PublisherState.FAILED, failedPublisher.getState())
        assertTrue(successfulPublisher.isPaused())
        assertEquals(PublisherState.INITIALIZED, successfulPublisher.getState())
    }

    @Test
    fun optionalFailureStartsSuccessfulPublisher() {
        val manager = PublisherManager()
        val failedPublisher = TestPublisher(context, manager, shouldFail = true)
        val successfulPublisher = TestPublisher(context, manager, shouldFail = false)
        manager.setRequirement(failedPublisher, PublisherRequirement.OPTIONAL)
        manager.setRequirement(successfulPublisher, PublisherRequirement.OPTIONAL)

        manager.initializePublishers()
        val result = startAndAwait(manager)

        assertTrue(result is PublisherManagerStartupResult.Success)
        assertEquals(
            1,
            (result as PublisherManagerStartupResult.Success).optionalFailures.size
        )

        assertEquals(PublisherState.FAILED, failedPublisher.getState())
        assertFalse(successfulPublisher.isPaused())
        assertEquals(PublisherState.STARTED, successfulPublisher.getState())
    }

    private fun startAndAwait(manager: PublisherManager): PublisherManagerStartupResult {
        val latch = CountDownLatch(1)
        var startupResult: PublisherManagerStartupResult? = null
        manager.startPublishers(PublisherManagerStartupListener {
            startupResult = it
            latch.countDown()
        })

        assertTrue("Timed out waiting for startup result", latch.await(3, TimeUnit.SECONDS))
        return requireNotNull(startupResult)
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class TestPublisher(
        context: Context,
        publisherManager: PublisherManager,
        private val shouldFail: Boolean
    ) : Publisher<Subscriber>(context, publisherManager) {
        var initializationCount = 0
            private set

        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            initializationCount++
            if (shouldFail) {
                reportInitializationFailed("Test failure")
                return
            }
            super.start()
            reportInitializationSucceeded()
        }

        fun isPaused(): Boolean = paused
    }

    private class AsyncPublisher(
        context: Context,
        publisherManager: PublisherManager
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            val initializationSucceeded = initializationSucceededCallback()
            super.start()
            Thread {
                initializationSucceeded()
            }.start()
        }
    }

    private class AsyncFailurePublisher(
        context: Context,
        publisherManager: PublisherManager
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            val initializationFailed = initializationFailedCallback()
            Thread {
                initializationFailed("Async failure", null)
            }.start()
        }
    }

    private class ControlledAsyncPublisher(
        context: Context,
        publisherManager: PublisherManager
    ) : Publisher<Subscriber>(context, publisherManager) {
        val completeInitialization = CountDownLatch(1)

        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            val initializationSucceeded = initializationSucceededCallback()
            super.start()
            Thread {
                completeInitialization.await()
                initializationSucceeded()
            }.start()
        }
    }

    @Suppress("DEPRECATION")
    private class LegacyPublisher(
        context: Context,
        publisherManager: PublisherManager,
        private val shouldFail: Boolean
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            if (shouldFail) {
                reportInitializationFailed("Test failure")
            } else {
                super.start()
                publisherManager.onPublisherInitialized()
            }
        }
    }

    private class PendingPermissionPublisher(
        context: Context,
        publisherManager: PublisherManager
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun onPermissionGranted() = Unit
    }

    private class BlockingInitializationPublisher(
        context: Context,
        publisherManager: PublisherManager
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                // The manager is expected to interrupt this worker at the deadline.
            }
        }
    }

    private class ConcurrentInitializationPublisher(
        context: Context,
        publisherManager: PublisherManager,
        private val publishersStarted: CountDownLatch
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            publishersStarted.countDown()
            if (publishersStarted.await(1, TimeUnit.SECONDS)) {
                reportInitializationSucceeded()
            } else {
                reportInitializationFailed("Publishers were initialized sequentially")
            }
        }
    }

    private class ReturningInitializationPublisher(
        context: Context,
        publisherManager: PublisherManager
    ) : Publisher<Subscriber>(context, publisherManager) {
        val startEntered = CountDownLatch(1)
        val allowStartToReturn = CountDownLatch(1)

        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            super.start()
            reportInitializationSucceeded()
            startEntered.countDown()
            allowStartToReturn.await()
        }
    }
}

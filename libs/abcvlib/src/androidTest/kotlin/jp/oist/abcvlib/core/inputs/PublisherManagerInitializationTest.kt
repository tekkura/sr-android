package jp.oist.abcvlib.core.inputs

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublisherManagerInitializationTest {
    @Test
    fun capturedInitializationCallbackWorksAsynchronously() {
        val manager = PublisherManager()
        AsyncPublisher(context, manager)

        manager.initializePublishers()
        manager.startPublishers()
        val result = awaitStartupResult(manager)

        assertTrue(result is PublisherManagerStartupResult.Success)
    }

    @Test
    fun capturedInitializationFailureCallbackWorksAsynchronously() {
        val manager = PublisherManager()
        AsyncFailurePublisher(context, manager)

        manager.initializePublishers()
        manager.startPublishers()
        val result = awaitStartupResult(manager)

        assertTrue(result is PublisherManagerStartupResult.Failure)
        val failure = (result as PublisherManagerStartupResult.Failure).requiredFailures.single()
        assertEquals("Async failure", failure.message)
    }

    @Test
    fun legacyCallbackSupportsPublishersOfTheSameClass() {
        val manager = PublisherManager()
        LegacyPublisher(context, manager, shouldFail = false)
        val failedPublisher = LegacyPublisher(context, manager, shouldFail = true)

        manager.initializePublishers()
        manager.startPublishers()
        val result = awaitStartupResult(manager)

        assertTrue(result is PublisherManagerStartupResult.Failure)
        val failure = (result as PublisherManagerStartupResult.Failure).requiredFailures.single()
        assertTrue(failure.publisher === failedPublisher)
    }

    @Test
    fun requiredFailureKeepsSuccessfulPublisherPaused() {
        val manager = PublisherManager()
        val failedPublisher = TestPublisher(context, manager, shouldFail = true)
        val successfulPublisher = TestPublisher(context, manager, shouldFail = false)

        manager.initializePublishers()
        manager.startPublishers()
        val result = awaitStartupResult(manager)

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
        manager.startPublishers()
        val result = awaitStartupResult(manager)

        assertTrue(result is PublisherManagerStartupResult.Success)
        assertEquals(
            1,
            (result as PublisherManagerStartupResult.Success).optionalFailures.size
        )

        assertEquals(PublisherState.FAILED, failedPublisher.getState())
        assertFalse(successfulPublisher.isPaused())
        assertEquals(PublisherState.STARTED, successfulPublisher.getState())
    }

    private fun awaitStartupResult(manager: PublisherManager): PublisherManagerStartupResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (manager.startupResult == null && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertNotNull("Timed out waiting for startup result", manager.startupResult)
        return requireNotNull(manager.startupResult)
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class TestPublisher(
        context: Context,
        publisherManager: PublisherManager,
        private val shouldFail: Boolean
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
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
}

package jp.oist.abcvlib.core.inputs.publisher

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.oist.abcvlib.core.inputs.Publisher
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.Subscriber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublisherStartupHandlerTest {
    @Test
    fun activityConstructorCanBeCalledFromWorkerThread() {
        lateinit var activity: ComponentActivity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ComponentActivity()
        }
        val constructionFailure = AtomicReference<Throwable>()
        val constructorFinished = CountDownLatch(1)

        Thread {
            try {
                PublisherStartupHandler(activity, PublisherManager())
            } catch (failure: Throwable) {
                constructionFailure.set(failure)
            } finally {
                constructorFinished.countDown()
            }
        }.start()

        assertTrue(constructorFinished.await(3, TimeUnit.SECONDS))
        assertNull(constructionFailure.get())
    }

    @Test
    fun successfulStartupInvokesSuccessCallback() {
        val manager = PublisherManager()
        TestPublisher(context, manager)
        val startupSucceeded = CountDownLatch(1)
        val failurePresented = AtomicReference<PublisherManagerStartupResult.Failure>()
        val handler = PublisherStartupHandler(
            manager,
            directExecutor
        ) { failure, _ ->
            failurePresented.set(failure)
        }

        handler.start(startupSucceeded::countDown)

        assertTrue(startupSucceeded.await(3, TimeUnit.SECONDS))
        assertEquals(null, failurePresented.get())
    }

    @Test
    fun failedStartupDoesNotReleaseAppUntilRetrySucceeds() {
        val manager = PublisherManager()
        TestPublisher(context, manager, failuresRemaining = 1)
        val failurePresented = CountDownLatch(1)
        val retry = AtomicReference<() -> Unit>()
        val startupSucceeded = CountDownLatch(1)
        val handler = PublisherStartupHandler(
            manager,
            directExecutor
        ) { _, retryAction ->
            retry.set(retryAction)
            failurePresented.countDown()
        }

        handler.start(startupSucceeded::countDown)
        assertTrue(failurePresented.await(3, TimeUnit.SECONDS))
        assertFalse(startupSucceeded.await(200, TimeUnit.MILLISECONDS))

        retry.get().invoke()

        assertTrue(startupSucceeded.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun rejectedRetryIsPresentedAsFailure() {
        val manager = PublisherManager()
        TestPublisher(context, manager, failuresRemaining = 1)
        val executionCount = AtomicInteger()
        val executor = Executor { command ->
            if (executionCount.getAndIncrement() == 0) {
                command.run()
            } else {
                throw RejectedExecutionException("Test executor rejected retry")
            }
        }
        val presentationCount = AtomicInteger()
        val firstFailurePresented = CountDownLatch(1)
        val retryFailurePresented = CountDownLatch(1)
        val retry = AtomicReference<() -> Unit>()
        val retryFailure = AtomicReference<PublisherManagerStartupResult.Failure>()
        val handler = PublisherStartupHandler(manager, executor) { failure, retryAction ->
            retry.set(retryAction)
            if (presentationCount.incrementAndGet() == 1) {
                firstFailurePresented.countDown()
            } else {
                retryFailure.set(failure)
                retryFailurePresented.countDown()
            }
        }

        handler.start()
        assertTrue(firstFailurePresented.await(3, TimeUnit.SECONDS))

        retry.get().invoke()

        assertTrue(retryFailurePresented.await(3, TimeUnit.SECONDS))
        val cause = retryFailure.get().requiredFailures.single().cause
        assertNotNull(cause)
        assertTrue(cause is RejectedExecutionException)
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val directExecutor = Executor(Runnable::run)

    private class TestPublisher(
        context: Context,
        publisherManager: PublisherManager,
        private var failuresRemaining: Int = 0
    ) : Publisher<Subscriber>(context, publisherManager) {
        override fun getRequiredPermissions() = arrayListOf<String>()

        override fun start() {
            if (failuresRemaining-- > 0) {
                reportInitializationFailed("Test startup failure")
            } else {
                super.start()
                reportInitializationSucceeded()
            }
        }
    }
}

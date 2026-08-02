package jp.oist.abcvlib.core.inputs.publisher

import jp.oist.abcvlib.util.Logger
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class PublisherInitializationRunner(
    private val timeoutMillis: Long,
    private val onInitializationSucceeded: (Publisher<*>) -> Unit,
    private val onInitializationFailed: (PublisherStartupFailure) -> Unit
) {
    private val attempts = LinkedHashMap<Publisher<*>, InitializationAttempt>()
    private val initializingPublisher = ThreadLocal<Publisher<*>>()
    private var executor = Executors.newCachedThreadPool()
    private var timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    private var launchComplete = false

    fun initialize(publisher: Publisher<*>) {
        val attempt = synchronized(this) {
            val attemptId = (attempts[publisher]?.id ?: 0L) + 1
            InitializationAttempt(attemptId).also { attempts[publisher] = it }
        }

        val worker = try {
            executor.submit {
                initializingPublisher.set(publisher)
                try {
                    publisher.runInitialization(attempt.id)
                } catch (failure: Throwable) {
                    failAttempt(publisher, attempt.id, failure.message, failure)
                } finally {
                    initializingPublisher.remove()
                }
            }
        } catch (failure: RejectedExecutionException) {
            failAttempt(
                publisher,
                attempt.id,
                "Could not submit publisher initialization",
                failure
            )
            return
        }
        attempt.worker = worker

        try {
            val timeout = timeoutExecutor.schedule(
                {
                    failAttempt(
                        publisher,
                        attempt.id,
                        "Timed out waiting for publisher initialization"
                    )
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS
            )
            synchronized(this) {
                attempt.timeout = timeout
                if (attempt.completed) timeout.cancel(false)
            }
        } catch (failure: RejectedExecutionException) {
            worker.cancel(true)
            failAttempt(
                publisher,
                attempt.id,
                "Could not schedule publisher initialization timeout",
                failure
            )
        }
    }

    fun reportLegacyInitializationSucceeded() {
        val publisher = initializingPublisher.get()
        if (publisher == null) {
            Logger.e(
                TAG,
                "Ignoring an asynchronous legacy initialization callback. " +
                    "Publishers must use initializationSucceededCallback()."
            )
            return
        }
        val attemptId = synchronized(this) { attempts[publisher]?.id } ?: return
        reportInitializationSucceeded(publisher, attemptId)
    }

    fun reportInitializationSucceeded(publisher: Publisher<*>, attemptId: Long) {
        if (!completeAttempt(publisher, attemptId)) return
        onInitializationSucceeded(publisher)
    }

    fun reportInitializationFailed(
        attemptId: Long,
        failure: PublisherStartupFailure
    ) {
        if (!completeAttempt(failure.publisher, attemptId)) return
        onInitializationFailed(failure)
    }

    fun finishLaunching() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        synchronized(this) {
            launchComplete = true
            shutdownTimeoutExecutorIfComplete()
        }
    }

    @Synchronized
    fun reset() {
        executor = Executors.newCachedThreadPool()
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
        launchComplete = false
    }

    private fun failAttempt(
        publisher: Publisher<*>,
        attemptId: Long,
        message: String?,
        cause: Throwable? = null
    ) {
        val worker = synchronized(this) {
            if (!completeAttemptLocked(publisher, attemptId)) return
            attempts[publisher]?.worker
        }
        worker?.cancel(true)
        onInitializationFailed(PublisherStartupFailure(publisher, message, cause))
    }

    private fun completeAttempt(publisher: Publisher<*>, attemptId: Long): Boolean {
        return synchronized(this) {
            completeAttemptLocked(publisher, attemptId)
        }
    }

    private fun completeAttemptLocked(publisher: Publisher<*>, attemptId: Long): Boolean {
        val attempt = attempts[publisher]
        if (attempt?.id != attemptId || attempt.completed) return false

        attempt.completed = true
        attempt.timeout?.cancel(false)
        shutdownTimeoutExecutorIfComplete()
        return true
    }

    private fun shutdownTimeoutExecutorIfComplete() {
        if (launchComplete && attempts.values.all { it.completed }) {
            timeoutExecutor.shutdown()
        }
    }

    private data class InitializationAttempt(
        val id: Long,
        var worker: Future<*>? = null,
        var timeout: ScheduledFuture<*>? = null,
        var completed: Boolean = false
    )

    private companion object {
        const val TAG = "PublisherInitializationRunner"
    }
}

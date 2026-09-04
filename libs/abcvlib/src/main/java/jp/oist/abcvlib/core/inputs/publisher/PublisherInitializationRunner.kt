package jp.oist.abcvlib.core.inputs.publisher

import jp.oist.abcvlib.core.inputs.Publisher
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
    private val _initializingAttempt = ThreadLocal<InitializationAttempt>()
    private val initializingAttempt get() = checkNotNull(_initializingAttempt.get()) {
        "Publisher is not running an initialization attempt"
    }

    private var executor = Executors.newCachedThreadPool()
    private var timeoutExecutor = Executors.newSingleThreadScheduledExecutor()
    private var launchComplete = false

    fun initialize(publisher: Publisher<*>) {
        val attempt = synchronized(this) {
            InitializationAttempt(publisher).also { attempts[publisher] = it }
        }

        val worker = try {
            executor.submit {
                _initializingAttempt.set(attempt)
                try {
                    publisher.runInitialization()
                } catch (failure: Throwable) {
                    failAttempt(attempt, failure.message, failure)
                } finally {
                    _initializingAttempt.remove()
                }
            }
        } catch (failure: RejectedExecutionException) {
            failAttempt(
                attempt,
                "Could not submit publisher initialization",
                failure
            )
            return
        }
        synchronized(this) {
            attempt.worker = worker
            if (attempt.completed) worker.cancel(true)
        }

        try {
            val timeout = timeoutExecutor.schedule(
                {
                    failAttempt(
                        attempt,
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
                attempt,
                "Could not schedule publisher initialization timeout",
                failure
            )
        }
    }

    fun reportLegacyInitializationSucceeded() {
        val attempt = _initializingAttempt.get()
        if (attempt == null) {
            Logger.e(
                TAG,
                "Ignoring an asynchronous legacy initialization callback. " +
                    "Publishers must use initializationSucceededCallback()."
            )
            return
        }
        succeedAttempt(attempt)
    }

    fun reportInitializationSucceeded() = succeedAttempt(initializingAttempt)
    fun reportInitializationFailed(failure: PublisherStartupFailure) = failAttempt(
        initializingAttempt,
        failure.message,
        failure.cause
    )

    fun initializationSucceededCallback() = initializingAttempt
        .let { { succeedAttempt(it) } }

    fun initializationFailedCallback(): (String?, Throwable?) -> Unit = initializingAttempt
        .let { { message, cause -> failAttempt(it, message, cause) } }

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
        attempt: InitializationAttempt,
        message: String?,
        cause: Throwable? = null
    ) {
        val worker = synchronized(this) {
            if (!completeAttempt(attempt)) return
            attempt.worker
        }
        worker?.cancel(true)
        onInitializationFailed(PublisherStartupFailure(attempt.publisher, message, cause))
    }

    private fun succeedAttempt(attempt: InitializationAttempt) {
        val success = synchronized(this) { completeAttempt(attempt) }
        if (!success) return
        onInitializationSucceeded(attempt.publisher)
    }

    private fun completeAttempt(attempt: InitializationAttempt): Boolean {
        if (attempts[attempt.publisher] !== attempt || attempt.completed)
            return false

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

    private class InitializationAttempt(
        val publisher: Publisher<*>,
        var worker: Future<*>? = null,
        var timeout: ScheduledFuture<*>? = null,
        var completed: Boolean = false
    )

    private companion object {
        const val TAG = "PublisherInitializationRunner"
    }
}

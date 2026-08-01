package jp.oist.abcvlib.core.inputs.publisher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import jp.oist.abcvlib.core.inputs.PublisherManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/** Owns publisher initialization, startup results, retry, and the standard failure UI. */
class PublisherStartupHandler(
    private val publisherManager: PublisherManager,
    private val backgroundExecutor: Executor,
    private val presentFailure: (PublisherManagerStartupResult.Failure, () -> Unit) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    constructor(
        activity: ComponentActivity,
        publisherManager: PublisherManager,
        backgroundExecutor: Executor = Executor { command ->
            activity.lifecycleScope.launch(Dispatchers.Default) { command.run() }
        },
        onFailure: (PublisherManagerStartupResult.Failure) -> Unit = {}
    ) : this(
        publisherManager,
        backgroundExecutor,
        StandardFailurePresenter(activity, publisherManager, onFailure)::show
    )

    fun start(onSuccess: () -> Unit = {}) {
        executeAttempt(retry = { start(onSuccess) }) {
            publisherManager.initializePublishers()
            publisherManager.startPublishers(startupListener(onSuccess))
        }
    }

    private fun startupListener(onSuccess: () -> Unit): PublisherManagerStartupListener {
        return PublisherManagerStartupListener { result ->
            when (result) {
                is PublisherManagerStartupResult.Success -> {
                    onSuccess()
                }

                is PublisherManagerStartupResult.Failure -> {
                    presentFailure(result, retry(result, onSuccess))
                }
            }
        }
    }

    private fun retry(
        previousFailure: PublisherManagerStartupResult.Failure,
        onSuccess: () -> Unit
    ): () -> Unit = {
        executeAttempt(retry(previousFailure, onSuccess)) {
            publisherManager.retryFailedPublishers(startupListener(onSuccess))
        }
    }

    private fun executeAttempt(
        retry: () -> Unit,
        attempt: () -> Unit
    ) {
        try {
            backgroundExecutor.execute {
                try {
                    attempt()
                } catch (exception: Exception) {
                    presentUnexpectedFailure(exception, retry)
                }
            }
        } catch (exception: Exception) {
            presentUnexpectedFailure(exception, retry)
        }
    }

    private fun presentUnexpectedFailure(
        exception: Exception,
        retry: () -> Unit
    ) {
        val result = run {
            val failures = publisherManager.publishers.map { publisher ->
                PublisherStartupFailure(
                    publisher,
                    exception.message ?: "Publisher startup failed",
                    exception
                )
            }

            PublisherManagerStartupResult.Failure(
                requiredFailures = failures.filter {
                    publisherManager.getRequirement(it.publisher) == PublisherRequirement.REQUIRED
                },
                optionalFailures = failures.filter {
                    publisherManager.getRequirement(it.publisher) == PublisherRequirement.OPTIONAL
                }
            )
        }

        mainHandler.post { presentFailure(result, retry) }
    }

    private class StandardFailurePresenter(
        activity: Activity,
        publisherManager: PublisherManager,
        private val onFailure: (PublisherManagerStartupResult.Failure) -> Unit
    ) {
        private val failureHandler by lazy(LazyThreadSafetyMode.NONE) {
            PublisherStartupFailureHandler(activity, publisherManager)
        }

        fun show(failure: PublisherManagerStartupResult.Failure, retry: () -> Unit) {
            onFailure(failure)
            failureHandler.show(failure, retry)
        }
    }
}

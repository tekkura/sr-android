package jp.oist.abcvlib.core.inputs.publisher

import jp.oist.abcvlib.core.inputs.Publisher

/**
 * Defines whether an application can operate without a publisher.
 */
enum class PublisherRequirement {
    REQUIRED,
    OPTIONAL
}

/**
 * Describes why one publisher did not start.
 */
data class PublisherStartupFailure(
    val publisher: Publisher<*>,
    val message: String? = null,
    val cause: Throwable? = null
)

/**
 * Describes the aggregate outcome after all registered publishers attempt startup.
 */
sealed class PublisherManagerStartupResult {
    data class Success(
        val optionalFailures: List<PublisherStartupFailure> = emptyList()
    ) : PublisherManagerStartupResult()

    data class Failure(
        val requiredFailures: List<PublisherStartupFailure>,
        val optionalFailures: List<PublisherStartupFailure>
    ) : PublisherManagerStartupResult()
}

fun interface PublisherManagerStartupListener {
    fun onStartupResult(result: PublisherManagerStartupResult)
}

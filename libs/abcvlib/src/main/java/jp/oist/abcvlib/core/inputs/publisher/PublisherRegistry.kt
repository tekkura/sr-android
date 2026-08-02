package jp.oist.abcvlib.core.inputs.publisher

internal class PublisherRegistry {
    private val registrations = LinkedHashMap<Publisher<*>, PublisherRegistration>()
    private var locked = false

    val publishers: ArrayList<Publisher<*>>
        get() = synchronized(this) { ArrayList(registrations.keys) }

    @Synchronized
    fun add(publisher: Publisher<*>) {
        check(!locked) {
            "Publishers cannot be added after initialization has started"
        }
        registrations[publisher] = PublisherRegistration()
    }

    @Synchronized
    fun lock() {
        locked = true
    }

    @Synchronized
    fun setRequirement(
        publisher: Publisher<*>,
        requirement: PublisherRequirement
    ) {
        check(!locked) {
            "Publisher requirements cannot change after initialization has started"
        }
        registrationFor(publisher).requirement = requirement
    }

    @Synchronized
    fun getRequirement(publisher: Publisher<*>): PublisherRequirement {
        return registrationFor(publisher).requirement
    }

    @Synchronized
    fun recordPermissionGranted(publisher: Publisher<*>): Boolean {
        val registration = registrationFor(publisher)
        if (registration.permissionResolved) return false

        registration.permissionResolved = true
        return true
    }

    @Synchronized
    fun recordPermissionFailure(failure: PublisherStartupFailure): Boolean {
        val registration = registrationFor(failure.publisher)
        if (registration.permissionResolved) return false

        registration.permissionResolved = true
        registration.failure = failure
        return true
    }

    @Synchronized
    fun recordInitializationSucceeded(publisher: Publisher<*>) {
        registrationFor(publisher).completed = true
    }

    @Synchronized
    fun recordInitializationFailure(failure: PublisherStartupFailure) {
        registrationFor(failure.publisher).apply {
            completed = true
            this.failure = failure
        }
    }

    @Synchronized
    fun availablePublishers(): List<Publisher<*>> {
        return registrations.filterValues { it.failure == null }.keys.toList()
    }

    @Synchronized
    fun isAvailable(publisher: Publisher<*>): Boolean {
        return registrationFor(publisher).failure == null
    }

    @Synchronized
    fun pendingPermissionPublishers(): List<Publisher<*>> {
        return registrations.filterValues { !it.permissionResolved }.keys.toList()
    }

    private fun failures(requirement: PublisherRequirement): List<PublisherStartupFailure> {
        return registrations.values
            .filter { it.requirement == requirement }
            .mapNotNull { it.failure }
    }

    @Synchronized
    fun startupSnapshot(): StartupSnapshot {
        return StartupSnapshot(
            availablePublishers = registrations
                .filterValues { it.failure == null }
                .keys
                .toList(),
            requiredFailures = failures(PublisherRequirement.REQUIRED),
            optionalFailures = failures(PublisherRequirement.OPTIONAL)
        )
    }

    @Synchronized
    fun prepareRetry(): List<Publisher<*>> {
        val failedPublishers = registrations
            .filterValues { it.failure != null }
            .keys
            .toList()
        check(failedPublishers.isNotEmpty()) {
            "There are no failed publishers to retry"
        }

        failedPublishers.forEach { publisher ->
            registrationFor(publisher).apply {
                permissionResolved = false
                completed = false
                failure = null
            }
        }
        return failedPublishers
    }

    private fun registrationFor(publisher: Publisher<*>): PublisherRegistration {
        return requireNotNull(registrations[publisher]) {
            "Publisher is not registered with this manager"
        }
    }

    private data class PublisherRegistration(
        var requirement: PublisherRequirement = PublisherRequirement.REQUIRED,
        var permissionResolved: Boolean = false,
        var completed: Boolean = false,
        var failure: PublisherStartupFailure? = null
    )

    data class StartupSnapshot(
        val availablePublishers: List<Publisher<*>>,
        val requiredFailures: List<PublisherStartupFailure>,
        val optionalFailures: List<PublisherStartupFailure>
    )
}

package jp.oist.abcvlib.core.inputs.publisher

enum class PublisherState {
    STOPPED,
    INITIALIZING,
    INITIALIZED,
    STARTED,
    PAUSED,
    FAILED
}

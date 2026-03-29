package jp.oist.abcvlib.comprehensivedemo

internal interface DemoController {
    val currentBehavior: Behavior
    val qrVisible: Boolean
    fun update(state: ControllerState, now: Long)
    fun wheelCommand(state: ControllerState, now: Long): WheelCommand
}

internal object ComprehensiveDemoControllerProvider {
    @Volatile
    var overrideForTesting: (() -> DemoController)? = null

    fun create(): DemoController = overrideForTesting?.invoke() ?: ComprehensiveDemoController()
}

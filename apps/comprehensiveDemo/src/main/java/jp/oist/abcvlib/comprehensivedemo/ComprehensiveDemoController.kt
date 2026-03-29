package jp.oist.abcvlib.comprehensivedemo

import com.google.mediapipe.tasks.components.containers.Detection
import jp.oist.abcvlib.util.Logger
import kotlin.math.abs
import kotlin.random.Random

internal enum class VisibleTarget {
    NONE,
    ROBOT,
    PUCK
}

internal enum class Behavior {
    REST_ON_TAIL,
    GET_UP_AND_BALANCE,
    SEARCH_AROUND,
    GO_CHARGING,
    APPROACH_ANOTHER_ROBOT,
    SEXUAL_DISPLAY,
    SHOW_QR_CODE,
    ACCEPT_QR_CODE
}

internal data class ControllerState(
    val onCharger: Boolean,
    val recentlyUndocked: Boolean,
    val hardwareReady: Boolean,
    val batteryVoltage: Double,
    val imageWidth: Int,
    val imageHeight: Int,
    val robotDetection: Detection? = null,
    val puckDetection: Detection? = null
)

internal data class WheelCommand(
    val left: Float,
    val right: Float,
    val leftBrake: Boolean = false,
    val rightBrake: Boolean = false
)

internal class ComprehensiveDemoController : DemoController {
    override var currentBehavior: Behavior = Behavior.SEARCH_AROUND
        private set

    override val qrVisible: Boolean
        get() = currentBehavior == Behavior.APPROACH_ANOTHER_ROBOT ||
            currentBehavior == Behavior.SHOW_QR_CODE

    private var behaviorStartTimeMs = 0L
    private var searchDirection = 1f
    private var lastSearchDirectionChangeMs = 0L

    override fun update(state: ControllerState, now: Long) {
        update(state, now, forcedBehavior = null)
    }

    internal fun update(
        state: ControllerState,
        now: Long,
        forcedBehavior: Behavior? = null
    ) {
        selectBehavior(state, now, forcedBehavior)
    }

    override fun wheelCommand(state: ControllerState, now: Long): WheelCommand {
        return when (currentBehavior) {
            Behavior.REST_ON_TAIL -> WheelCommand(0f, 0f, true, true)
            Behavior.GET_UP_AND_BALANCE -> getUpCommand(now)
            Behavior.SEARCH_AROUND -> searchCommand(now)
            Behavior.GO_CHARGING -> chargingCommand(state, now)
            Behavior.APPROACH_ANOTHER_ROBOT -> robotApproachCommand(state, now)
            Behavior.SEXUAL_DISPLAY -> danceCommand(now)
            Behavior.SHOW_QR_CODE -> showQrCommand(state)
            Behavior.ACCEPT_QR_CODE -> acceptQrCommand(state)
        }
    }

    private fun selectBehavior(
        state: ControllerState,
        now: Long,
        forcedBehavior: Behavior?
    ) {
        val previousBehavior = currentBehavior
        val nextBehavior = when {
            forcedBehavior != null -> forcedBehavior
            currentBehavior == Behavior.GET_UP_AND_BALANCE && state.recentlyUndocked ->
                currentBehavior

            state.onCharger -> Behavior.REST_ON_TAIL
            state.recentlyUndocked -> Behavior.GET_UP_AND_BALANCE
            state.batteryVoltage in 0.1..BATTERY_LOW_THRESHOLD && state.puckDetection != null ->
                Behavior.GO_CHARGING

            state.batteryVoltage in 0.1..BATTERY_LOW_THRESHOLD ->
                Behavior.SEARCH_AROUND

            state.robotDetection != null -> Behavior.APPROACH_ANOTHER_ROBOT
            else -> Behavior.SEARCH_AROUND
        }

        Logger.v(
            "ComprehensiveDemo",
            "selectBehavior previous=$previousBehavior next=$nextBehavior " +
                "changed=${nextBehavior != previousBehavior} " +
                "forced=${forcedBehavior?.name ?: "none"} " +
                "onCharger=${state.onCharger} " +
                "recentlyUndocked=${state.recentlyUndocked} " +
                "batteryVoltage=${"%.3f".format(state.batteryVoltage)} " +
                "robotVisible=${state.robotDetection != null} " +
                "puckVisible=${state.puckDetection != null}"
        )

        if (nextBehavior == currentBehavior) {
            return
        }
        currentBehavior = nextBehavior
        behaviorStartTimeMs = now
    }

    private fun getUpCommand(now: Long): WheelCommand {
        val elapsed = now - behaviorStartTimeMs
        return when (((elapsed / 500L) % 4).toInt()) {
            0 -> WheelCommand(0.35f, 0.35f)
            1 -> WheelCommand(-0.35f, -0.35f)
            2 -> WheelCommand(0.25f, -0.25f)
            else -> WheelCommand(-0.25f, 0.25f)
        }
    }

    private fun searchCommand(now: Long): WheelCommand {
        if (now - lastSearchDirectionChangeMs > SEARCH_DIRECTION_CHANGE_MS) {
            searchDirection = if (Random.nextBoolean()) 1f else -1f
            lastSearchDirectionChangeMs = now
        }
        return WheelCommand(0.3f * searchDirection, -0.3f * searchDirection)
    }

    private fun chargingCommand(state: ControllerState, now: Long): WheelCommand {
        val puck = state.puckDetection ?: return searchCommand(now)
        return approachCommand(
            detection = puck,
            state = state,
            forwardBias = 0.42f,
            turnGain = 0.28f,
            stopAtBottomError = 0.12f
        )
    }

    private fun robotApproachCommand(state: ControllerState, now: Long): WheelCommand {
        val robot = state.robotDetection ?: return searchCommand(now)
        return approachCommand(
            detection = robot,
            state = state,
            forwardBias = 0.40f,
            turnGain = 0.24f,
            stopAtBottomError = 0.35f
        )
    }

    private fun showQrCommand(state: ControllerState): WheelCommand {
        val robot = state.robotDetection ?: return WheelCommand(0f, 0f)
        val errorX = horizontalError(robot, state)
        return if (abs(errorX) > 0.12f) {
            WheelCommand(-errorX * 0.25f, errorX * 0.25f)
        } else {
            WheelCommand(0f, 0f)
        }
    }

    private fun acceptQrCommand(state: ControllerState): WheelCommand {
        val robot = state.robotDetection ?: return WheelCommand(0f, 0f)
        val errorX = horizontalError(robot, state)
        return if (abs(errorX) > 0.18f) {
            WheelCommand(-errorX * 0.22f, errorX * 0.22f)
        } else {
            WheelCommand(0f, 0f)
        }
    }

    private fun danceCommand(now: Long): WheelCommand {
        val elapsed = now - behaviorStartTimeMs
        return when (((elapsed / 700L) % 4).toInt()) {
            0 -> WheelCommand(0.45f, -0.45f)
            1 -> WheelCommand(-0.45f, 0.45f)
            2 -> WheelCommand(0.30f, 0.30f)
            else -> WheelCommand(-0.30f, -0.30f)
        }
    }

    private fun approachCommand(
        detection: Detection,
        state: ControllerState,
        forwardBias: Float,
        turnGain: Float,
        stopAtBottomError: Float
    ): WheelCommand {
        val errorX = horizontalError(detection, state)
        val verticalError = bottomError(detection, state)
        val forward = if (verticalError > stopAtBottomError) forwardBias else 0f
        val left = (forward - errorX * turnGain).coerceIn(-1f, 1f)
        val right = (forward + errorX * turnGain).coerceIn(-1f, 1f)
        Logger.v(
            "ComprehensiveDemo",
            "approach ${currentBehavior.name} " +
                "errorX=${"%.3f".format(errorX)} " +
                "verticalError=${"%.3f".format(verticalError)} " +
                "forward=${"%.3f".format(forward)} " +
                "left=${"%.3f".format(left)} " +
                "right=${"%.3f".format(right)}"
        )
        return WheelCommand(
            left = left,
            right = right
        )
    }

    private fun horizontalError(detection: Detection, state: ControllerState): Float {
        if (state.imageWidth <= 0 || state.imageHeight <= 0) {
            return 0f
        }
        val box = mapDetectionRectToDisplay(
            detection.boundingBox(),
            state.imageWidth,
            state.imageHeight
        )
        val normalizedScreenError = 2f * ((box.centerX() / state.imageHeight) - 0.5f)
        return SCREEN_X_TO_TURN_SIGN * normalizedScreenError
    }

    private fun bottomError(detection: Detection, state: ControllerState): Float {
        if (state.imageWidth <= 0 || state.imageHeight <= 0) {
            return 1f
        }
        val box = mapDetectionRectToDisplay(
            detection.boundingBox(),
            state.imageWidth,
            state.imageHeight
        )
        return 1f - (box.centerY() / state.imageWidth)
    }

    companion object {
        internal const val BATTERY_LOW_THRESHOLD = 3.65
        private const val SEARCH_DIRECTION_CHANGE_MS = 3_500L
        // Positive screen-X error means the target appears on the right side of the preview.
        // The drive mixing in approachCommand expects positive turn input to steer left, so this
        // sign aligns screen-space error with the robot's turning convention.
        private const val SCREEN_X_TO_TURN_SIGN = -1f
    }
}

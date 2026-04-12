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
    val thetaDeg: Double,
    val angularVelocityDeg: Double,
    val wheelSpeedDeg: Double,
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
    private var searchPhase = SearchPhase.TURN_IN_PLACE
    private var searchPhaseEndsAtMs = 0L
    private var searchCommand = WheelCommand(0f, 0f)
    private var balanceIntegralError = 0.0
    private var lastBalanceUpdateMs = 0L

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
            Behavior.REST_ON_TAIL -> restOnTail()
            Behavior.GET_UP_AND_BALANCE -> getUpAndBalance(state, now)
            Behavior.SEARCH_AROUND -> searchAround(now)
            Behavior.GO_CHARGING -> goCharging(state, now)
            Behavior.APPROACH_ANOTHER_ROBOT -> approachAnotherRobot(state, now)
            Behavior.SEXUAL_DISPLAY -> sexualDisplay(now)
            Behavior.SHOW_QR_CODE -> showQrCode(state)
            Behavior.ACCEPT_QR_CODE -> acceptQrCode(state)
        }
    }

    private fun restOnTail(): WheelCommand {
        return WheelCommand(0f, 0f, true, true)
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
        if (nextBehavior == Behavior.GET_UP_AND_BALANCE) {
            resetBalanceController()
        }
    }

    private fun resetBalanceController() {
        balanceIntegralError = 0.0
        lastBalanceUpdateMs = 0L
    }

    private fun getUpAndBalance(state: ControllerState, now: Long): WheelCommand {
        if (!state.hardwareReady) {
            return WheelCommand(0f, 0f)
        }

        val dtSeconds = if (lastBalanceUpdateMs == 0L) {
            BALANCE_LOOP_DT_FALLBACK_S
        } else {
            ((now - lastBalanceUpdateMs).coerceAtLeast(1L) / 1000.0)
        }
        lastBalanceUpdateMs = now

        if (state.thetaDeg < BALANCE_SET_POINT_DEG - BALANCE_MAX_ABS_TILT_DEG) {
            balanceIntegralError = 0.0
            return WheelCommand(-BALANCE_RECOVERY_SPEED, -BALANCE_RECOVERY_SPEED)
        }
        if (state.thetaDeg > BALANCE_SET_POINT_DEG + BALANCE_MAX_ABS_TILT_DEG) {
            balanceIntegralError = 0.0
            return WheelCommand(BALANCE_RECOVERY_SPEED, BALANCE_RECOVERY_SPEED)
        }

        val tiltErrorDeg = BALANCE_SET_POINT_DEG - state.thetaDeg
        balanceIntegralError = (balanceIntegralError + tiltErrorDeg * dtSeconds)
            .coerceIn(-BALANCE_INTEGRAL_CLAMP, BALANCE_INTEGRAL_CLAMP)

        val output = (
            BALANCE_P_TILT * tiltErrorDeg +
                BALANCE_I_TILT * balanceIntegralError +
                BALANCE_D_TILT * state.angularVelocityDeg +
                BALANCE_P_WHEEL * (0.0 - state.wheelSpeedDeg)
            )
            .coerceIn(-BALANCE_MAX_OUTPUT, BALANCE_MAX_OUTPUT)
            .toFloat()

        Logger.v(
            "ComprehensiveDemo",
            "balance thetaDeg=${"%.2f".format(state.thetaDeg)} " +
                "setPointDeg=${"%.2f".format(BALANCE_SET_POINT_DEG)} " +
                "thetaErrorDeg=${"%.2f".format(tiltErrorDeg)} " +
                "angularVelocityDeg=${"%.2f".format(state.angularVelocityDeg)} " +
                "wheelSpeedDeg=${"%.2f".format(state.wheelSpeedDeg)} " +
                "integral=${"%.2f".format(balanceIntegralError)} " +
                "output=${"%.3f".format(output)}"
        )

        return WheelCommand(output, output)
    }

    private fun searchAround(now: Long): WheelCommand {
        if (now >= searchPhaseEndsAtMs) {
            chooseNextSearchPhase(now)
        }
        return searchCommand
    }

    private fun chooseNextSearchPhase(now: Long) {
        val phaseRoll = Random.nextInt(100)
        val turnDirection = if (Random.nextBoolean()) 1f else -1f
        when {
            phaseRoll < SEARCH_FORWARD_ARC_PERCENT -> {
                searchPhase = SearchPhase.FORWARD_ARC
                val turnBias = Random.nextInt(
                    SEARCH_FORWARD_TURN_MIN_PERCENT,
                    SEARCH_FORWARD_TURN_MAX_PERCENT + 1
                ) / 100f * turnDirection
                searchCommand = WheelCommand(
                    SEARCH_FORWARD_SPEED - turnBias,
                    SEARCH_FORWARD_SPEED + turnBias
                )
                searchPhaseEndsAtMs = now + Random.nextInt(
                    SEARCH_FORWARD_ARC_MIN_MS,
                    SEARCH_FORWARD_ARC_MAX_MS + 1
                )
            }

            phaseRoll < SEARCH_FORWARD_ARC_PERCENT + SEARCH_TURN_PERCENT -> {
                searchPhase = SearchPhase.TURN_IN_PLACE
                searchCommand = WheelCommand(
                    SEARCH_TURN_SPEED * turnDirection,
                    -SEARCH_TURN_SPEED * turnDirection
                )
                searchPhaseEndsAtMs = now + Random.nextInt(
                    SEARCH_TURN_MIN_MS,
                    SEARCH_TURN_MAX_MS + 1
                )
            }

            else -> {
                searchPhase = SearchPhase.REVERSE_ARC
                searchCommand = WheelCommand(
                    -SEARCH_REVERSE_SPEED - (SEARCH_REVERSE_TURN_BIAS * turnDirection),
                    -SEARCH_REVERSE_SPEED + (SEARCH_REVERSE_TURN_BIAS * turnDirection)
                )
                searchPhaseEndsAtMs = now + Random.nextInt(
                    SEARCH_REVERSE_MIN_MS,
                    SEARCH_REVERSE_MAX_MS + 1
                )
            }
        }
        Logger.v(
            "ComprehensiveDemo",
            "searchAround phase=$searchPhase " +
                "durationMs=${searchPhaseEndsAtMs - now} " +
                "left=${"%.3f".format(searchCommand.left)} " +
                "right=${"%.3f".format(searchCommand.right)}"
        )
    }

    private fun goCharging(state: ControllerState, now: Long): WheelCommand {
        val puck = state.puckDetection ?: return searchAround(now)
        return approach(
            detection = puck,
            state = state,
            forwardBias = 0.42f,
            turnGain = 0.28f,
            stopAtBottomError = 0.12f
        )
    }

    private fun approachAnotherRobot(state: ControllerState, now: Long): WheelCommand {
        val robot = state.robotDetection ?: return searchAround(now)
        return approach(
            detection = robot,
            state = state,
            forwardBias = 0.40f,
            turnGain = 0.24f,
            stopAtBottomError = 0.35f
        )
    }

    private fun showQrCode(state: ControllerState): WheelCommand {
        val robot = state.robotDetection ?: return WheelCommand(0f, 0f)
        val errorX = horizontalError(robot, state)
        return if (abs(errorX) > 0.12f) {
            WheelCommand(-errorX * 0.25f, errorX * 0.25f)
        } else {
            WheelCommand(0f, 0f)
        }
    }

    private fun acceptQrCode(state: ControllerState): WheelCommand {
        val robot = state.robotDetection ?: return WheelCommand(0f, 0f)
        val errorX = horizontalError(robot, state)
        return if (abs(errorX) > 0.18f) {
            WheelCommand(-errorX * 0.22f, errorX * 0.22f)
        } else {
            WheelCommand(0f, 0f)
        }
    }

    private fun sexualDisplay(now: Long): WheelCommand {
        val elapsed = now - behaviorStartTimeMs
        return when (((elapsed / 700L) % 4).toInt()) {
            0 -> WheelCommand(0.45f, -0.45f)
            1 -> WheelCommand(-0.45f, 0.45f)
            2 -> WheelCommand(0.30f, 0.30f)
            else -> WheelCommand(-0.30f, -0.30f)
        }
    }

    private fun approach(
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
        private const val SEARCH_FORWARD_ARC_PERCENT = 65
        private const val SEARCH_TURN_PERCENT = 25
        private const val SEARCH_FORWARD_ARC_MIN_MS = 800
        private const val SEARCH_FORWARD_ARC_MAX_MS = 2_200
        private const val SEARCH_TURN_MIN_MS = 250
        private const val SEARCH_TURN_MAX_MS = 900
        private const val SEARCH_REVERSE_MIN_MS = 300
        private const val SEARCH_REVERSE_MAX_MS = 700
        private const val SEARCH_FORWARD_SPEED = 0.32f
        private const val SEARCH_FORWARD_TURN_MIN_PERCENT = 4
        private const val SEARCH_FORWARD_TURN_MAX_PERCENT = 16
        private const val SEARCH_TURN_SPEED = 0.30f
        private const val SEARCH_REVERSE_SPEED = 0.24f
        private const val SEARCH_REVERSE_TURN_BIAS = 0.10f
        private const val BALANCE_SET_POINT_DEG = 0.0
        private const val BALANCE_P_TILT = -0.11
        private const val BALANCE_I_TILT = 0.0
        private const val BALANCE_D_TILT = 0.01
        private const val BALANCE_P_WHEEL = 0.1
        private const val BALANCE_MAX_ABS_TILT_DEG = 10.0
        private const val BALANCE_RECOVERY_SPEED = 0.5f
        private const val BALANCE_MAX_OUTPUT = 0.65
        private const val BALANCE_INTEGRAL_CLAMP = 20.0
        private const val BALANCE_LOOP_DT_FALLBACK_S = 0.02
        // Positive screen-X error means the target appears on the right side of the preview.
        // The drive mixing in approachCommand expects positive turn input to steer left, so this
        // sign aligns screen-space error with the robot's turning convention.
        private const val SCREEN_X_TO_TURN_SIGN = -1f
    }

    private enum class SearchPhase {
        FORWARD_ARC,
        TURN_IN_PLACE,
        REVERSE_ARC
    }
}

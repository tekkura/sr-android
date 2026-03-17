package jp.oist.abcvlib.core.outputs

import ioio.lib.api.exception.ConnectionLostException
import jp.oist.abcvlib.core.Switches
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException
import jp.oist.abcvlib.util.SerialCommManager
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Outputs(
    switches: Switches,
    private val serialCommManager: SerialCommManager
) {
    val motion: Motion

    @get:Synchronized
    val masterController: MasterController
    private val threadPoolExecutor: ScheduledExecutorServiceWithException

    private var lastLeft = 0.0f
    private var lastRight = 0.0f
    private var lastCallTimestamp: Long = 0

    init {
        // Determine number of necessary threads.
        val threadCount = 1 // At least one for the MasterController
        val processPriorityThreadFactory =
            ProcessPriorityThreadFactory(Thread.MAX_PRIORITY, "Outputs")
        threadPoolExecutor =
            ScheduledExecutorServiceWithException(threadCount, processPriorityThreadFactory)

        //BalancePIDController Controller
        motion = Motion(switches)

        masterController = MasterController(switches, serialCommManager)
    }

    fun startMasterController() {
        threadPoolExecutor.scheduleWithFixedDelay(masterController, 0, 1, TimeUnit.MILLISECONDS)
    }

    /**
     * Ideally users are not using this method but instead the default without maxChange.
     * Please only use this if you know the implications surrounding potential motor damage.
     * @param left speed from -1 to 1 (full speed backward vs full speed forward)
     * @param right speed from -1 to 1 (full speed backward vs full speed forward)
     * @param maxChange The maximum change in wheel output. Any faster than this will be clamped.
     */
    fun setWheelOutput(
        left: Float,
        right: Float,
        leftBrake: Boolean,
        rightBrake: Boolean,
        maxChange: Float
    ) {
        var left = left
        var right = right
        if (left < -1.0f) {
            Logger.w("Outputs", "Left wheel output $left is less than -1. Clamping.")
            left = -1.0f
        } else if (left > 1.0f) {
            Logger.w("Outputs", "Left wheel output $left is greater than 1. Clamping.")
            left = 1.0f
        }
        if (right < -1.0f) {
            Logger.w("Outputs", "Right wheel output $right is less than -1. Clamping.")
            right = -1.0f
        } else if (right > 1.0f) {
            Logger.w("Outputs", "Right wheel output $right is greater than 1. Clamping.")
            right = 1.0f
        }

        if (lastCallTimestamp == 0L) {
            lastCallTimestamp = System.currentTimeMillis()
        }
        val now = System.currentTimeMillis()
        val dt = now - lastCallTimestamp
        Logger.v("Outputs", "dt: $dt")

        if (dt == 0L) {
            // To avoid division by zero
            serialCommManager.setMotorLevels(lastLeft, lastRight, leftBrake, rightBrake)
            return
        }

        if (abs(left - lastLeft) > maxChange) {
            Logger.w(
                "Outputs",
                "Controller attempting to change left wheel output too quickly. Change: ${left - lastLeft}, MaxChange: $maxChange"
            )
        }
        if (abs(right - lastRight) > maxChange) {
            Logger.w(
                "Outputs",
                "Controller attempting to change Right wheel output too quickly. Change: ${right - lastRight}, MaxChange: $maxChange"
            )
        }

        var newLeft = lastLeft + max(-maxChange, min(maxChange, left - lastLeft))
        var newRight = lastRight + max(-maxChange, min(maxChange, right - lastRight))

        if (newLeft < -1.0f) {
            Logger.w("Outputs", "New left wheel output $newLeft is less than -1. Clamping.")
            newLeft = -1.0f
        } else if (newLeft > 1.0f) {
            Logger.w("Outputs", "New left wheel output $newLeft is greater than 1. Clamping.")
            newLeft = 1.0f
        }

        if (newRight < -1.0f) {
            Logger.w("Outputs", "New right wheel output $newRight is less than -1. Clamping.")
            newRight = -1.0f
        } else if (newRight > 1.0f) {
            Logger.w("Outputs", "New right wheel output $newRight is greater than 1. Clamping.")
            newRight = 1.0f
        }

        serialCommManager.setMotorLevels(newLeft, newRight, leftBrake, rightBrake)
        lastLeft = newLeft
        lastRight = newRight
        lastCallTimestamp = now
    }

    /**
     * @param left speed from -1 to 1 (full speed backward vs full speed forward)
     * @param right speed from -1 to 1 (full speed backward vs full speed forward)
     */
    fun setWheelOutput(
        left: Float,
        right: Float,
        leftBrake: Boolean,
        rightBrake: Boolean
    ) {
        setWheelOutput(left, right, leftBrake, rightBrake, 0.4f)
    }

    @Throws(ConnectionLostException::class)
    fun turnOffWheels() {
        setWheelOutput(0f, 0f, false, false)
    }
}

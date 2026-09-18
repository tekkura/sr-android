package jp.oist.abcvlib.util.latency

import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand
import jp.oist.abcvlib.util.rp2040.StatusCommand
import java.util.concurrent.atomic.AtomicInteger

internal class LatencyMeasuringSerialCommManager @JvmOverloads constructor(
    private val benchmarkUsbSerial: LatencyMeasuringUsbSerial,
    private val currentIteration: AtomicInteger,
    batteryData: BatteryData? = null,
    wheelData: WheelData? = null
) : SerialCommManager(
    usbSerial = benchmarkUsbSerial,
    batteryData = batteryData,
    wheelData = wheelData
) {

    /**
     * Callback invoked when a command response has been fully processed.
     */
    var onResultProcessed: (() -> Unit)? = null

    override fun sendCommand(command: RP2040OutgoingCommand): Int {
        if (command is RP2040OutgoingCommand.SetMotorLevels) {
            BenchmarkClock.mark(currentIteration.get(), 2)
            benchmarkUsbSerial.setBenchmarkTrafficActive(true)
            try {
                return super.sendCommand(command)
            } finally {
                benchmarkUsbSerial.setBenchmarkTrafficActive(false)
            }
        }

        return super.sendCommand(command)
    }

    override fun receivePacket() {
        val receivedStatus = benchmarkUsbSerial.awaitPacketReceived(10000)

        // T8: Manager Wake-up
        BenchmarkClock.mark(currentIteration.get(), 8)

        if (receivedStatus == 1) {
            //Note this is actually calling the functions like parseLog, parseStatus, etc.
            parseFifoPacket()
        }
    }

    fun setMotorLevelsForBenchmark(
        iteration: Int,
        left: Float,
        right: Float,
        leftBrake: Boolean,
        rightBrake: Boolean,
    ) {
        // T1: Command Creation Start
        currentIteration.set(iteration)
        BenchmarkClock.mark(iteration, 1)
        super.setMotorLevels(left, right, leftBrake, rightBrake)
    }

    override fun parseStatus(command: StatusCommand): Boolean {
        val result = super.parseStatus(command)

        if (result && command is RP2040IncomingCommand.SetMotorLevels) {
            // T9: State Applied
            val iteration = currentIteration.get()
            BenchmarkClock.mark(iteration, 9)
            BenchmarkClock.recordSuccess(iteration)
            currentIteration.compareAndSet(iteration, NO_ITERATION)
            onResultProcessed?.invoke()
        }

        return result
    }

    companion object {
        const val NO_ITERATION = -1
    }
}

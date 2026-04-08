package jp.oist.abcvlib.util.latency

import android.os.SystemClock
import android.util.Log
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.ControlLatencyTrace
import jp.oist.abcvlib.util.HexBinConverters.bytesToHex
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.UsbSerial
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand.Companion.PACKET_SIZE
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand.Companion.PAYLOAD_SIZE
import jp.oist.abcvlib.util.rp2040.StatusCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LatencyMeasuringSerialCommManager @JvmOverloads constructor(
    usbSerial: UsbSerial,
    batteryData: BatteryData? = null,
    wheelData: WheelData? = null
) : SerialCommManager(
    usbSerial = usbSerial,
    batteryData = batteryData,
    wheelData = wheelData
) {

    /**
     * Callback invoked when a command response has been fully processed.
     */
    var onResultProcessed: (() -> Unit)? = null

    private var packet: ByteArray? = null
    private var lastReceivedPacketNumber = 0

    override fun buildAndroid2PiWriter(context: RunContext): Runnable = Runnable {
        startTimeAndroid = System.nanoTime()
        while (!context.stopRequested.get()) {
            try {
                synchronized(commandLock) {
                    // this results in getState commands every 10ms unless another command
                    // (e.g. setMotorLevels) is set, which case wait will return immediately
                    commandLock.wait(10)
                    if (context.stopRequested.get()) {
                        return@synchronized null
                    }

                    packet?.let {
                        if (it.size > 3) {
                            // T2: Writer Loop Wake-up
                            BenchmarkClock.mark(toIteration(it[2], it[3]), 2)
                        } else {
                            Log.e("LatencyBenchmark", "Invalid packet size: ${it.size}")
                        }

                        sendPacket(it)
                    }
                    packet = null
                }
            } catch (e: InterruptedException) {
                continue
            }
        }
    }

    override fun receivePacket() {
        val receivedStatus = usbSerial.awaitPacketReceived(10000)

        // T7: Manager Wake-up
        BenchmarkClock.mark(lastReceivedPacketNumber++, 7)

        if (receivedStatus == 1) {
            //Note this is actually calling the functions like parseLog, parseStatus, etc.
            parseFifoPacket()
        }
    }

    fun setMotorLevels(
        left: Byte,
        right: Byte,
    ) {
        // T1: Command Creation Start
        BenchmarkClock.mark(toIteration(left, right), 1)

        synchronized(commandLock) {
            val buffer = ByteBuffer.allocate(PACKET_SIZE).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                put(AndroidToRP2040Command.START.hexValue)
                put(AndroidToRP2040Command.SET_MOTOR_LEVELS.hexValue)
                put(left)
                put(right)
                put(AndroidToRP2040Command.STOP.hexValue)
            }

            packet = buffer.array()

            commandLock.notify()
        }
    }

    override fun parseStatus(command: StatusCommand): Boolean {
        val result = super.parseStatus(command)

        if (result) {
            // T8: State Applied
            val controlValues = command.motorsState.controlValues
            val iteration = toIteration(controlValues.left, controlValues.right)
            BenchmarkClock.mark(iteration, 8)
            onResultProcessed?.invoke()
        }

        return result
    }
}
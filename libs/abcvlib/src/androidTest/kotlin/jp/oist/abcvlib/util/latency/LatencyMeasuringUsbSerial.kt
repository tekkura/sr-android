package jp.oist.abcvlib.util.latency

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import jp.oist.abcvlib.util.RobotSerialPort
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import java.io.IOException

internal class LatencyMeasuringUsbSerial @Throws(IOException::class) constructor(
    context: Context,
    usbManager: UsbManager,
    serialReadyListener: SerialReadyListener,
    port: RobotSerialPort? = null
) : UsbSerial(
    context = context,
    usbManager = usbManager,
    serialReadyListener = serialReadyListener,
    port = port
) {

    override fun onNewData(data: ByteArray) {
        if (data.size > 5) {
            // T5: Response Receipt at Phone
            BenchmarkClock.mark(toIteration(data[4], data[5]), 5)
        } else {
            Log.e("LatencyBenchmark", "Invalid packet size: ${data.size}")
        }

        super.onNewData(data)
    }

    override fun send(packet: ByteArray, timeout: Int) {
        if (packet.size > 3) {
            // T3: Transport Dispatch
            BenchmarkClock.mark(toIteration(packet[2], packet[3]), 3)
        } else {
            Log.e("LatencyBenchmark", "Invalid packet size: ${packet.size}")
        }

        super.send(packet, timeout)
    }

    override fun onCompletePacketReceived(command: RP2040IncomingCommand) {
        if (command !is RP2040IncomingCommand.SetMotorLevels)
            return

        // Update benchmark)
        val controlValues = command.motorsState
            .controlValues

        val iteration = toIteration(controlValues.left, controlValues.right)

        BenchmarkClock.recordSuccess(iteration)
        super.onCompletePacketReceived(command)

        // T6: Packet Queue Entry
        BenchmarkClock.mark(iteration, 6)
    }
}
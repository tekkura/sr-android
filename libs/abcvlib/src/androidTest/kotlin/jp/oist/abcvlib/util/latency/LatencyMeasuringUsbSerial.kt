package jp.oist.abcvlib.util.latency

import android.content.Context
import android.hardware.usb.UsbManager
import jp.oist.abcvlib.util.RobotSerialPort
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

internal class LatencyMeasuringUsbSerial @Throws(IOException::class) constructor(
    context: Context,
    usbManager: UsbManager,
    serialReadyListener: SerialReadyListener,
    private val currentIteration: AtomicInteger,
    port: RobotSerialPort? = null
) : UsbSerial(
    context = context,
    usbManager = usbManager,
    serialReadyListener = serialReadyListener,
    port = port
) {

    override fun onNewData(data: ByteArray) {
        // T5: Response Receipt at Phone
        BenchmarkClock.mark(currentIteration.get(), 5)

        super.onNewData(data)
    }

    override fun send(packet: ByteArray, timeout: Int) {
        // T3: Transport Dispatch
        BenchmarkClock.mark(currentIteration.get(), 3)

        super.send(packet, timeout)

        // T4: Android Write Complete
        BenchmarkClock.mark(currentIteration.get(), 4)
    }

    override fun onCompletePacketReceived(command: RP2040IncomingCommand) {
        if (command !is RP2040IncomingCommand.SetMotorLevels)
            return

        super.onCompletePacketReceived(command)

        // T6: Packet Queue Entry
        BenchmarkClock.mark(currentIteration.get(), 6)
    }
}

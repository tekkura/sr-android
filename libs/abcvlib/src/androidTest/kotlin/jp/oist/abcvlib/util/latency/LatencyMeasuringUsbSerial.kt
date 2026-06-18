package jp.oist.abcvlib.util.latency

import android.content.Context
import android.hardware.usb.UsbManager
import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.RobotSerialPort
import jp.oist.abcvlib.util.PacketBuffer
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.BatteryDetails
import jp.oist.abcvlib.util.rp2040.ChargeSideUSB
import jp.oist.abcvlib.util.rp2040.MotorsState
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

    private val packetPayloadDecoder: (AndroidToRP2040Command, ByteArray) -> PacketBuffer.PacketPayload = { type, data ->
        val statusPayloadSize =
            MotorsState.BYTE_LENGTH + BatteryDetails.BYTE_LENGTH + ChargeSideUSB.BYTE_LENGTH

        val isTelemetryEligible = when (type) {
            AndroidToRP2040Command.GET_STATE,
            AndroidToRP2040Command.SET_MOTOR_LEVELS,
            AndroidToRP2040Command.RESET_STATE -> true
            else -> false
        }

        if (!isTelemetryEligible || data.size == statusPayloadSize) {
            PacketBuffer.PacketPayload(data)
        } else if (data.size == statusPayloadSize + LatencyTelemetry.BYTE_LENGTH) {
            PacketBuffer.PacketPayload(
                commandData = data.copyOfRange(0, statusPayloadSize),
                additionalData = data.copyOfRange(statusPayloadSize, data.size)
            )
        } else {
            PacketBuffer.PacketPayload(data)
        }
    }

    override val packetBuffer = PacketBuffer(payloadDecoder = packetPayloadDecoder)
    private var transportDispatchTimestampNs: Long? = null
    private var responseReceiptTimestampNs: Long? = null

    override fun onNewData(data: ByteArray) {
        // T6: Response Receipt at Phone
        responseReceiptTimestampNs = BenchmarkClock.mark(currentIteration.get(), 6)

        super.onNewData(data)
    }

    override fun send(packet: ByteArray, timeout: Int) {
        // T3: Transport Dispatch
        transportDispatchTimestampNs = BenchmarkClock.mark(currentIteration.get(), 3)

        super.send(packet, timeout)
    }

    override fun onCompletePacketReceived(
        command: RP2040IncomingCommand,
        additionalData: ByteArray?
    ) {
        if (command is RP2040IncomingCommand.SetMotorLevels) {
            recordLatencyTelemetry(additionalData)
        }

        super.onCompletePacketReceived(command, additionalData)

        if (command !is RP2040IncomingCommand.SetMotorLevels)
            return

        // T7: Packet Queue Entry
        BenchmarkClock.mark(currentIteration.get(), 7)
    }

    private fun recordLatencyTelemetry(additionalData: ByteArray?) {
        val telemetry = additionalData?.let(LatencyTelemetry::from) ?: return
        val transportDispatchNs = transportDispatchTimestampNs ?: return
        val responseReceiptNs = responseReceiptTimestampNs ?: return

        val firmwareReceiptNs = telemetry.t4TimestampUs * 1_000L
        val firmwareProcessingDoneNs = telemetry.t5TimestampUs * 1_000L
        val calculatedT4 = (responseReceiptNs + transportDispatchNs - firmwareProcessingDoneNs + firmwareReceiptNs) / 2
        val calculatedT5 = (responseReceiptNs + transportDispatchNs + firmwareProcessingDoneNs - firmwareReceiptNs) / 2
        val iteration = currentIteration.get()

        BenchmarkClock.markAt(iteration, 4, calculatedT4)
        BenchmarkClock.markAt(iteration, 5, calculatedT5)
        transportDispatchTimestampNs = null
        responseReceiptTimestampNs = null
    }
}

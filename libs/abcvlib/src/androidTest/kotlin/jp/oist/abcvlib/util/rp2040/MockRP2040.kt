package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.latency.LatencyTelemetry
import jp.oist.abcvlib.util.AndroidToRP2040Command
import java.nio.ByteBuffer
import java.nio.ByteOrder
/**
 * A simulator for the RP2040 firmware behavior.
 * This class maintains a simulated state and responds to incoming commands.
 */
internal class MockRP2040 {
    var motorsState = MotorsState()
    var batteryDetails = BatteryDetails().apply {
        voltageMv = 3800
        temperature = 250
        stateOfHealth = 100
    }
    var chargeSideUSB = ChargeSideUSB()
    
    val logEntries = mutableListOf<String>()

    /**
     * Optional callback to notify tests when a command has been processed.
     */
    var onCommandProcessed: ((AndroidToRP2040Command) -> Unit)? = null

    /**
     * Optional hook used by latency benchmarks to tag the firmware processing boundary.
     */
    var benchmarkIterationProvider: (() -> Int)? = null

    /**
     * Processes an incoming raw packet and returns a response packet.
     * Mimics the firmware's request-response cycle.
     */
    fun processPacket(packet: ByteArray): ByteArray? {
        // Simple manual parsing of the header
        if (packet.size < 4) {
            // Simulate firmware processing time.
            // We still want to do this even in case of error
            Thread.sleep(5)

            return null
        }

        // Simulate firmware processing time to prevent race conditions in tests.
        // This ensures the Android side has time to enter its 'await' state.
        val firmwareReceiptUs = System.nanoTime() / 1_000
        Thread.sleep(5)
        val firmwareProcessingCompleteUs = System.nanoTime() / 1_000
        
        val typeByte = packet[1]
        val type = AndroidToRP2040Command.getEnumByValue(typeByte) ?: return null
        val telemetry = if (benchmarkIterationProvider != null && type in benchmarkTelemetryTypes) {
            LatencyTelemetry(
                t4TimestampUs = firmwareReceiptUs,
                t5TimestampUs = firmwareProcessingCompleteUs
            )
        } else {
            null
        }
        
        val response = when (type) {
            AndroidToRP2040Command.GET_STATE -> {
                generateStatusResponse(AndroidToRP2040Command.GET_STATE, telemetry)
            }
            AndroidToRP2040Command.SET_MOTOR_LEVELS -> {
                // Update simulated motor state
                if (packet.size == RP2040OutgoingCommand.PACKET_SIZE) {
                    motorsState.controlValues.left = packet[2]
                    motorsState.controlValues.right = packet[3]
                    
                    logEntries.add("Motors set: L=${motorsState.controlValues.left}, R=${motorsState.controlValues.right}")
                }
                generateStatusResponse(AndroidToRP2040Command.SET_MOTOR_LEVELS, telemetry)
            }
            AndroidToRP2040Command.RESET_STATE -> {
                motorsState = MotorsState()
                logEntries.add("State reset")
                generateStatusResponse(AndroidToRP2040Command.RESET_STATE, telemetry)
            }
            AndroidToRP2040Command.GET_LOG -> {
                val logCmd = RP2040IncomingCommand.GetLog(logEntries.toList())
                logEntries.clear()
                logCmd.toBytes()
            }
            else -> {
                RP2040IncomingCommand.Ack(byteArrayOf()).toBytes()
            }
        }

        onCommandProcessed?.invoke(type)

        return response
    }

    private fun generateStatusResponse(
        type: AndroidToRP2040Command,
        latencyTelemetry: LatencyTelemetry?
    ): ByteArray {
        val command = when (type) {
            AndroidToRP2040Command.GET_STATE -> RP2040IncomingCommand.GetState(motorsState, batteryDetails, chargeSideUSB)
            AndroidToRP2040Command.SET_MOTOR_LEVELS -> RP2040IncomingCommand.SetMotorLevels(motorsState, batteryDetails, chargeSideUSB)
            AndroidToRP2040Command.RESET_STATE -> RP2040IncomingCommand.ResetState(motorsState, batteryDetails, chargeSideUSB)
            else -> throw IllegalArgumentException("Invalid status type")
        }
        return if (latencyTelemetry != null) {
            appendTelemetry(command.toBytes(), latencyTelemetry.toBytes())
        } else {
            command.toBytes()
        }
    }

    private fun appendTelemetry(packet: ByteArray, telemetry: ByteArray): ByteArray {
        val baseDataSize = packet.size - 5
        val extendedPacket = ByteArray(packet.size + telemetry.size)

        extendedPacket[0] = packet[0]
        extendedPacket[1] = packet[1]

        val newSize = (baseDataSize + telemetry.size).toShort()
        val sizeBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(newSize).array()
        extendedPacket[2] = sizeBytes[0]
        extendedPacket[3] = sizeBytes[1]

        System.arraycopy(packet, 4, extendedPacket, 4, baseDataSize)
        System.arraycopy(telemetry, 0, extendedPacket, 4 + baseDataSize, telemetry.size)
        extendedPacket[extendedPacket.lastIndex] = packet.last()

        return extendedPacket
    }

    private companion object {
        val benchmarkTelemetryTypes = setOf(
            AndroidToRP2040Command.GET_STATE,
            AndroidToRP2040Command.SET_MOTOR_LEVELS,
            AndroidToRP2040Command.RESET_STATE
        )
    }
}

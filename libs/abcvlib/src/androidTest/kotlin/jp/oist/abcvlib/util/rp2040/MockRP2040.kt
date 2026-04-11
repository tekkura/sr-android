package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.ByteArrayExtensions.toCrc
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
     * Processes an incoming raw packet and returns a response packet.
     * Mimics the firmware's request-response cycle.
     */
    fun processPacket(packet: ByteArray): ByteArray? {
        if (!isValidRequest(packet)) {
            // Simulate firmware processing time.
            // We still want to do this even in case of error
            Thread.sleep(5)

            return null
        }

        // Simulate firmware processing time to prevent race conditions in tests.
        // This ensures the Android side has time to enter its 'await' state.
        Thread.sleep(5)
        
        val typeByte = packet[3]
        val type = AndroidToRP2040Command.getEnumByValue(typeByte) ?: return null
        
        val response = when (type) {
            AndroidToRP2040Command.GET_STATE -> {
                generateStatusResponse(AndroidToRP2040Command.GET_STATE)
            }
            AndroidToRP2040Command.SET_MOTOR_LEVELS -> {
                // Update simulated motor state
                motorsState.controlValues.left = packet[4]
                motorsState.controlValues.right = packet[5]

                logEntries.add("Motors set: L=${motorsState.controlValues.left}, R=${motorsState.controlValues.right}")
                generateStatusResponse(AndroidToRP2040Command.SET_MOTOR_LEVELS)
            }
            AndroidToRP2040Command.RESET_STATE -> {
                motorsState = MotorsState()
                logEntries.add("State reset")
                generateStatusResponse(AndroidToRP2040Command.RESET_STATE)
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

    private fun isValidRequest(packet: ByteArray): Boolean {
        if (packet.size < MIN_PACKET_SIZE || packet[0] != AndroidToRP2040Command.START.hexValue)
            return false

        val dataLength = ByteBuffer.wrap(packet, 1, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xFFFF

        if (dataLength < 1 || packet.size != 1 + 2 + dataLength + 2)
            return false

        val type = AndroidToRP2040Command.getEnumByValue(packet[3])
            ?: return false

        if (type == AndroidToRP2040Command.START || type == AndroidToRP2040Command.STOP)
            return false

        val expectedPayloadSize = when (type) {
            AndroidToRP2040Command.GET_LOG,
            AndroidToRP2040Command.GET_STATE,
            AndroidToRP2040Command.RESET_STATE,
            AndroidToRP2040Command.GET_VERSION -> 0

            AndroidToRP2040Command.SET_MOTOR_LEVELS -> 2

            AndroidToRP2040Command.ACK,
            AndroidToRP2040Command.NACK -> null

            AndroidToRP2040Command.START,
            AndroidToRP2040Command.STOP -> return false
        }

        if (expectedPayloadSize != null && dataLength != expectedPayloadSize + 1)
            return false

        val crcEnd = 3 + dataLength
        val expectedCrc = ByteBuffer.wrap(packet, crcEnd, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
        val actualCrc = packet.sliceArray(1 until crcEnd).toCrc()
        return expectedCrc == actualCrc
    }

    private fun generateStatusResponse(type: AndroidToRP2040Command): ByteArray {
        val command = when (type) {
            AndroidToRP2040Command.GET_STATE -> RP2040IncomingCommand.GetState(motorsState, batteryDetails, chargeSideUSB)
            AndroidToRP2040Command.SET_MOTOR_LEVELS -> RP2040IncomingCommand.SetMotorLevels(motorsState, batteryDetails, chargeSideUSB)
            AndroidToRP2040Command.RESET_STATE -> RP2040IncomingCommand.ResetState(motorsState, batteryDetails, chargeSideUSB)
            else -> throw IllegalArgumentException("Invalid status type")
        }
        return command.toBytes()
    }

    companion object {
        private const val MIN_PACKET_SIZE: Int = 6
    }
}

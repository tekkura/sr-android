package jp.oist.abcvlib.util.rp2040

import android.util.Log
import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.latency.BenchmarkClock
import jp.oist.abcvlib.util.latency.toIteration
import java.nio.ByteBuffer

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
        // Simple manual parsing of the header
        if (packet.size < 4) {
            // Simulate firmware processing time.
            // We still want to do this even in case of error
            Thread.sleep(5)

            return null
        }

        // T4: Simulator Receipt
        BenchmarkClock.mark(toIteration(packet[2], packet[3]), 4)

        // Simulate firmware processing time to prevent race conditions in tests.
        // This ensures the Android side has time to enter its 'await' state.
        Thread.sleep(5)
        
        val typeByte = packet[1]
        val type = AndroidToRP2040Command.getEnumByValue(typeByte) ?: return null
        
        val response = when (type) {
            AndroidToRP2040Command.GET_STATE -> {
                generateStatusResponse(AndroidToRP2040Command.GET_STATE)
            }
            AndroidToRP2040Command.SET_MOTOR_LEVELS -> {
                // Update simulated motor state
                if (packet.size == RP2040OutgoingCommand.PACKET_SIZE) {
                    motorsState.controlValues.left = packet[2]
                    motorsState.controlValues.right = packet[3]
                    
                    logEntries.add("Motors set: L=${motorsState.controlValues.left}, R=${motorsState.controlValues.right}")
                }
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

    private fun generateStatusResponse(type: AndroidToRP2040Command): ByteArray {
        val command = when (type) {
            AndroidToRP2040Command.GET_STATE -> RP2040IncomingCommand.GetState(motorsState, batteryDetails, chargeSideUSB)
            AndroidToRP2040Command.SET_MOTOR_LEVELS -> RP2040IncomingCommand.SetMotorLevels(motorsState, batteryDetails, chargeSideUSB)
            AndroidToRP2040Command.RESET_STATE -> RP2040IncomingCommand.ResetState(motorsState, batteryDetails, chargeSideUSB)
            else -> throw IllegalArgumentException("Invalid status type")
        }
        return command.toBytes()
    }
}

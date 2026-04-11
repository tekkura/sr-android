package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.AndroidToRP2040Command
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.absoluteValue

sealed class RP2040OutgoingCommand : RP2040Command() {

    class Nack(val data: ByteArray) : RP2040OutgoingCommand() {
        override val type = AndroidToRP2040Command.NACK
        override fun serializeData() = data
    }

    class Ack(val data: ByteArray) : RP2040OutgoingCommand() {
        override val type = AndroidToRP2040Command.ACK
        override fun serializeData() = data
    }

    class GetLog : RP2040OutgoingCommand() {
        override val type = AndroidToRP2040Command.GET_LOG
        override fun serializeData() = ByteArray(0)
    }

    class GetState : RP2040OutgoingCommand() {
        override val type = AndroidToRP2040Command.GET_STATE
        override fun serializeData() = ByteArray(0)
    }

    class SetMotorLevels(
        private val left: Float,
        private val right: Float,
        private val leftBrake: Boolean,
        private val rightBrake: Boolean
    ) : RP2040OutgoingCommand() {

        override val type = AndroidToRP2040Command.SET_MOTOR_LEVELS

        override fun serializeData(): ByteArray {
            return ByteBuffer.allocate(2).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(getControlByte(left, leftBrake))
                put(getControlByte(right, rightBrake))
            }.array()
        }

        private fun getControlByte(input: Float, brake: Boolean): Byte {
            // Normalize [-1,1] to [-5.06,5.06] as this is the range accepted by the chip
            // Note - signs are to invert the direction of the motors as the motors are mounted in a
            // polar opposite direction.
            // Clamp the voltage within the valid range
            // Need to clamp here rather than at byte representation to prevent overflow
            val voltage = (-input * MAX_VOLTAGE).coerceIn(-MAX_VOLTAGE, MAX_VOLTAGE)

            // Exclude or truncate voltages between -0.48V and 0.48V to 0V
            // Changing to 0.49 as the scaling function would result in 0x05h for 0.48V and
            // cause the rp2040 to perform unexpectedly as it is a reserved register value
            if (!brake && voltage.absoluteValue < LOWER_LIMIT)
                return 0

            // Convert voltage to control value (-0x3F to 0x3F)
            val rawControl = (64 * voltage.absoluteValue)
                .div(4 * 1.285)
                .minus(1)
                .toInt() shl 2

            // voltage is defined by bits 2-7. Shift the control value to the correct position
            val result = rawControl

            // Set the IN1 and IN2 bits based on the sign of the voltage
            return when {
                brake -> result or DRV8830_BRAKE
                voltage < 0 -> result or DRV8830_IN1_BIT and DRV8830_IN2_BIT.inv()
                voltage > 0 -> result or DRV8830_IN2_BIT and DRV8830_IN1_BIT.inv()
                else -> 0
            }.toByte()
        }

        companion object {
            private const val DRV8830_IN1_BIT = (1 shl 0)
            private const val DRV8830_IN2_BIT = (1 shl 1)
            private const val DRV8830_BRAKE = DRV8830_IN1_BIT or DRV8830_IN2_BIT

            private const val LOWER_LIMIT = 0.49f
            private const val MAX_VOLTAGE = 5.06f
        }
    }

    class ResetState : RP2040OutgoingCommand() {
        override val type = AndroidToRP2040Command.RESET_STATE
        override fun serializeData() = ByteArray(0)
    }

    class GetVersion : RP2040OutgoingCommand() {
        override val type = AndroidToRP2040Command.GET_VERSION
    }

    companion object {
        // (2) 1 byte for each wheel
        const val PAYLOAD_SIZE: Int = 2

        // Making room for start marker, id, length, type and crc values
        const val PACKET_SIZE: Int = 1 + 1 + 2 + 1 + 2 + PAYLOAD_SIZE + 2
    }
}
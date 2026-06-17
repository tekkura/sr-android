package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.ByteArrayExtensions.toCrc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RP2040OutgoingCommandTest {

    private fun verifyPacketStructure(
        bytes: ByteArray,
        expectedType: AndroidToRP2040Command,
        expectedPayload: ByteArray
    ) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        
        assertEquals("Start marker mismatch", AndroidToRP2040Command.START.hexValue, buffer.get())
        val size = buffer.short
        assertEquals("Size mismatch", (expectedPayload.size + 1).toShort(), size)
        assertEquals("Command type mismatch", expectedType.hexValue, buffer.get())
        
        val actualPayload = ByteArray(size - 1)
        buffer.get(actualPayload)
        assertArrayEquals("Payload mismatch", expectedPayload, actualPayload)

        val dataCrc = bytes.sliceArray(1 until bytes.size - 2).toCrc()
        assertEquals("Data CRC mismatch", dataCrc, buffer.short)
    }

    @Test
    fun `test GetState packet structure`() {
        val command = RP2040OutgoingCommand.GetState()
        val bytes = command.toBytes()
        // No payload populated, so it should be zeros
        verifyPacketStructure(bytes, AndroidToRP2040Command.GET_STATE, byteArrayOf())
    }

    @Test
    fun `test GetLog packet structure`() {
        val command = RP2040OutgoingCommand.GetLog()
        val bytes = command.toBytes()
        verifyPacketStructure(bytes, AndroidToRP2040Command.GET_LOG, byteArrayOf())
    }

    @Test
    fun `test ResetState packet structure`() {
        val command = RP2040OutgoingCommand.ResetState()
        val bytes = command.toBytes()
        verifyPacketStructure(bytes, AndroidToRP2040Command.RESET_STATE, byteArrayOf())
    }

    @Test
    fun `test SetMotorLevels stop`() {
        val command = RP2040OutgoingCommand.SetMotorLevels(0f, 0f, false, false)
        val bytes = command.toBytes()
        // Voltage 0.0 results in 0 control value
        verifyPacketStructure(bytes, AndroidToRP2040Command.SET_MOTOR_LEVELS, byteArrayOf(0, 0))
    }

    @Test
    fun `test SetMotorLevels forward full speed`() {
        val command = RP2040OutgoingCommand.SetMotorLevels(1f, 1f, false, false)
        val bytes = command.toBytes()
        
        // Math for 1.0f:
        // leftNrm = -1.0 * 5.06 = -5.06
        // rightNrm = -1.0 * 5.06 = -5.06
        // abs_voltage = 5.06
        // control_value = (64 * 5.06 / 5.14) - 1 = 63.003 - 1 = 62 (0x3E)
        // shifted = 0x3E << 2 = 0xF8
        // voltage < 0 -> cv | (1 << 0) = 0xF9
        
        val expectedByte = 0xF9.toByte()
        verifyPacketStructure(bytes, AndroidToRP2040Command.SET_MOTOR_LEVELS, byteArrayOf(expectedByte, expectedByte))
    }

    @Test
    fun `test SetMotorLevels backward full speed`() {
        val command = RP2040OutgoingCommand.SetMotorLevels(-1f, -1f, false, false)
        val bytes = command.toBytes()
        
        // Math for -1.0f:
        // leftNrm = -(-1.0) * 5.06 = 5.06
        // voltage > 0 -> cv | (1 << 1) = 0xFA
        
        val expectedByte = 0xFA.toByte()
        verifyPacketStructure(bytes, AndroidToRP2040Command.SET_MOTOR_LEVELS, byteArrayOf(expectedByte, expectedByte))
    }

    @Test
    fun `test SetMotorLevels brake`() {
        val command = RP2040OutgoingCommand.SetMotorLevels(1f, 1f, true, true)
        val bytes = command.toBytes()
        
        // brake -> cv | (1 << 0) | (1 << 1)
        // 0xF8 | 0x01 | 0x02 = 0xFB
        
        val expectedByte = 0xFB.toByte()
        verifyPacketStructure(bytes, AndroidToRP2040Command.SET_MOTOR_LEVELS, byteArrayOf(expectedByte, expectedByte))
    }

    @Test
    fun `test SetMotorLevels clamping`() {
        val command = RP2040OutgoingCommand.SetMotorLevels(2f, -2f, false, false)
        val bytes = command.toBytes()
        
        // 2f results in -10.12V clamped to -5.06V -> 0xF9
        // -2f results in 10.12V clamped to 5.06V -> 0xFA
        
        verifyPacketStructure(bytes, AndroidToRP2040Command.SET_MOTOR_LEVELS, byteArrayOf(0xF9.toByte(), 0xFA.toByte()))
    }

    @Test
    fun `test SetMotorLevels lower limit`() {
        // LOWER_LIMIT is 0.49f. MAX_VOLTAGE is 5.06f.
        // 0.49 / 5.06 = 0.0968f
        val command = RP2040OutgoingCommand.SetMotorLevels(0.09f, 0.09f, false, false)
        val bytes = command.toBytes()
        
        // 0.09 * 5.06 = 0.4554 < 0.49 -> 0V -> 0
        verifyPacketStructure(bytes, AndroidToRP2040Command.SET_MOTOR_LEVELS, byteArrayOf(0, 0))
    }
}

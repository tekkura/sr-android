package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.AndroidToRP2040Command
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RP2040CommandTest {

    private fun createMockMotorsState() = MotorsState().apply {
        controlValues.left = 10
        controlValues.right = 20
        faults.left = 0
        faults.right = 1
        encoderCounts.left = 1000
        encoderCounts.right = 2000
    }

    private fun createMockBatteryDetails() = BatteryDetails().apply {
        voltageMv = 3700
        temperature = 250
        safetyStatus = 0
        stateOfHealth = 95
        flags = 0
    }

    private fun createMockChargeSideUSB() = ChargeSideUSB().apply {
        max77976_chg_details = 1234
        ncp3901_wireless_charger_attached = true
        usb_charger_voltage = 5000
        wireless_charger_vrect = 5100
    }

    private fun extractPayload(bytes: ByteArray): ByteArray {
        // Header is 4 bytes (START, TYPE, SIZE_L, SIZE_H), STOP is 1 byte
        return bytes.sliceArray(4 until bytes.size - 1)
    }

    @Test
    fun testNack() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val command = RP2040Command.Nack(data)
        
        assertEquals(AndroidToRP2040Command.NACK, command.type)
        
        val bytes = command.toBytes()
        // START(1) + TYPE(1) + SIZE(2) + DATA(3) + STOP(1) = 8 bytes
        assertEquals(8, bytes.size)
        assertArrayEquals(data, extractPayload(bytes))
        
        val fromBytes = RP2040Command.from(AndroidToRP2040Command.NACK, data)
        assertTrue(fromBytes is RP2040Command.Nack)
        assertArrayEquals(data, (fromBytes as RP2040Command.Nack).data)
    }

    @Test
    fun testAck() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val command = RP2040Command.Ack(data)
        
        assertEquals(AndroidToRP2040Command.ACK, command.type)
        
        val bytes = command.toBytes()
        assertEquals(7, bytes.size)
        assertArrayEquals(data, extractPayload(bytes))
        
        val fromBytes = RP2040Command.from(AndroidToRP2040Command.ACK, data)
        assertTrue(fromBytes is RP2040Command.Ack)
        assertArrayEquals(data, (fromBytes as RP2040Command.Ack).data)
    }

    @Test
    fun testGetLog() {
        val logs = listOf("Log 1", "Log 2")
        val command = RP2040Command.GetLog(logs)
        
        assertEquals(AndroidToRP2040Command.GET_LOG, command.type)
        val expectedData = "Log 1\nLog 2".toByteArray(charset = Charsets.US_ASCII)
        
        val bytes = command.toBytes()
        assertArrayEquals(expectedData, extractPayload(bytes))
        
        val fromBytes = RP2040Command.from(AndroidToRP2040Command.GET_LOG, expectedData)
        assertTrue(fromBytes is RP2040Command.GetLog)
        assertEquals(logs, (fromBytes as RP2040Command.GetLog).logEntries)
    }

    @Test
    fun testGetState() {
        val motors = createMockMotorsState()
        val battery = createMockBatteryDetails()
        val usb = createMockChargeSideUSB()
        
        val command = RP2040Command.GetState(motors, battery, usb)
        assertEquals(AndroidToRP2040Command.GET_STATE, command.type)
        
        val bytes = command.toBytes()
        val payload = extractPayload(bytes)
        assertEquals(MotorsState.BYTE_LENGTH + BatteryDetails.BYTE_LENGTH + ChargeSideUSB.BYTE_LENGTH, payload.size)
        
        val fromBytes = RP2040Command.from(AndroidToRP2040Command.GET_STATE, payload)
        assertTrue(fromBytes is RP2040Command.GetState)
        val state = fromBytes as RP2040Command.GetState
        
        assertEquals(motors.controlValues.left, state.motorsState.controlValues.left)
        assertEquals(battery.voltageMv, state.batteryDetails.voltageMv)
        assertEquals(usb.max77976_chg_details, state.chargeSideUSB.max77976_chg_details)
    }

    @Test
    fun testSetMotorLevels() {
        val motors = createMockMotorsState()
        val battery = createMockBatteryDetails()
        val usb = createMockChargeSideUSB()
        
        val command = RP2040Command.SetMotorLevels(motors, battery, usb)
        assertEquals(AndroidToRP2040Command.SET_MOTOR_LEVELS, command.type)
        
        val payload = extractPayload(command.toBytes())
        val fromBytes = RP2040Command.from(AndroidToRP2040Command.SET_MOTOR_LEVELS, payload)
        assertTrue(fromBytes is RP2040Command.SetMotorLevels)
    }

    @Test
    fun testResetState() {
        val motors = createMockMotorsState()
        val battery = createMockBatteryDetails()
        val usb = createMockChargeSideUSB()
        
        val command = RP2040Command.ResetState(motors, battery, usb)
        assertEquals(AndroidToRP2040Command.RESET_STATE, command.type)
        
        val payload = extractPayload(command.toBytes())
        val fromBytes = RP2040Command.from(AndroidToRP2040Command.RESET_STATE, payload)
        assertTrue(fromBytes is RP2040Command.ResetState)
    }

    @Test
    fun testHeaderCreation() {
        val data = byteArrayOf(0x01, 0x02)
        val command = RP2040Command.Nack(data)
        val bytes = command.toBytes()
        
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AndroidToRP2040Command.START.hexValue, buffer.get())
        assertEquals(AndroidToRP2040Command.NACK.hexValue, buffer.get())
        assertEquals(data.size.toShort(), buffer.short)
        assertEquals(0x01.toByte(), buffer.get())
        assertEquals(0x02.toByte(), buffer.get())
        assertEquals(AndroidToRP2040Command.STOP.hexValue, buffer.get())
    }

    @Test
    fun testFromInvalidTypes() {
        assertNull(RP2040Command.from(AndroidToRP2040Command.START, byteArrayOf()))
        assertNull(RP2040Command.from(AndroidToRP2040Command.STOP, byteArrayOf()))
    }
}

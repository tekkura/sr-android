package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.ByteArrayExtensions.toCrc
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RP2040IncomingCommandTest {

    private fun createMockMotorsState() = MotorsState().apply {
        controlValues.left = 0x11
        controlValues.right = 0x22
        faults.left = 0x01
        faults.right = 0x00
        encoderCounts.left = 0x12345678
        encoderCounts.right = 0x76543210
    }

    private fun createMockBatteryDetails() = BatteryDetails().apply {
        voltageMv = 3700
        temperature = 250
        safetyStatus = 0x02
        stateOfHealth = 95
        flags = 0x1001
    }

    private fun createMockChargeSideUSB() = ChargeSideUSB().apply {
        max77976_chg_details = 0xABCDEF01.toInt()
        ncp3901_wireless_charger_attached = true
        usb_charger_voltage = 5000
        wireless_charger_vrect = 5100
    }

    private fun extractPayload(bytes: ByteArray): ByteArray {
        // Header is 7 bytes (START, TYPE, SIZE_L, SIZE_H), end CRC is 2 bytes
        return bytes.sliceArray(7 until bytes.size - 2)
    }

    private fun verifyMotorsState(expected: MotorsState, actual: MotorsState) {
        assertEquals(expected.controlValues.left, actual.controlValues.left)
        assertEquals(expected.controlValues.right, actual.controlValues.right)
        assertEquals(expected.faults.left, actual.faults.left)
        assertEquals(expected.faults.right, actual.faults.right)
        assertEquals(expected.encoderCounts.left, actual.encoderCounts.left)
        assertEquals(expected.encoderCounts.right, actual.encoderCounts.right)
    }

    private fun verifyBatteryDetails(expected: BatteryDetails, actual: BatteryDetails) {
        assertEquals(expected.voltageMv, actual.voltageMv)
        assertEquals(expected.temperature, actual.temperature)
        assertEquals(expected.safetyStatus, actual.safetyStatus)
        assertEquals(expected.stateOfHealth, actual.stateOfHealth)
        assertEquals(expected.flags, actual.flags)
    }

    private fun verifyChargeSideUSB(expected: ChargeSideUSB, actual: ChargeSideUSB) {
        assertEquals(expected.max77976_chg_details, actual.max77976_chg_details)
        assertEquals(expected.ncp3901_wireless_charger_attached, actual.ncp3901_wireless_charger_attached)
        assertEquals(expected.usb_charger_voltage, actual.usb_charger_voltage)
        assertEquals(expected.wireless_charger_vrect, actual.wireless_charger_vrect)
    }

    @Test
    fun testNack() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val command = RP2040IncomingCommand.Nack(data)
        
        val bytes = command.toBytes()
        assertArrayEquals(data, extractPayload(bytes))
        
        val fromBytes = RP2040IncomingCommand.from(AndroidToRP2040Command.NACK, data) as RP2040IncomingCommand.Nack
        assertArrayEquals(data, fromBytes.data)
    }

    @Test
    fun testAck() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val command = RP2040IncomingCommand.Ack(data)
        
        val bytes = command.toBytes()
        assertArrayEquals(data, extractPayload(bytes))
        
        val fromBytes = RP2040IncomingCommand.from(AndroidToRP2040Command.ACK, data) as RP2040IncomingCommand.Ack
        assertArrayEquals(data, fromBytes.data)
    }

    @Test
    fun testGetLog() {
        val logs = listOf("Line 1", "Line 2")
        val command = RP2040IncomingCommand.GetLog(logs)
        
        val bytes = command.toBytes()
        val expectedData = "Line 1\nLine 2".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expectedData, extractPayload(bytes))
        
        val fromBytes = RP2040IncomingCommand.from(AndroidToRP2040Command.GET_LOG, expectedData) as RP2040IncomingCommand.GetLog
        assertEquals(logs, fromBytes.logEntries)
    }

    @Test
    fun testGetState() {
        val motors = createMockMotorsState()
        val battery = createMockBatteryDetails()
        val usb = createMockChargeSideUSB()
        val command = RP2040IncomingCommand.GetState(motors, battery, usb)
        
        val bytes = command.toBytes()
        val payload = extractPayload(bytes)
        
        // Verify individual bytes in the serialized array
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        assertEquals(motors.controlValues.left, buffer.get())
        assertEquals(motors.controlValues.right, buffer.get())
        assertEquals(motors.faults.left, buffer.get())
        assertEquals(motors.faults.right, buffer.get())
        assertEquals(motors.encoderCounts.left, buffer.int)
        assertEquals(motors.encoderCounts.right, buffer.int)
        
        assertEquals(battery.voltageMv, buffer.short)
        assertEquals(battery.safetyStatus, buffer.get())
        assertEquals(battery.temperature, buffer.short)
        assertEquals(battery.stateOfHealth, buffer.get())
        assertEquals(battery.flags, buffer.short)
        
        assertEquals(usb.max77976_chg_details, buffer.int)
        assertEquals(1.toByte(), buffer.get()) // attached = true
        assertEquals(usb.usb_charger_voltage, buffer.short)
        assertEquals(usb.wireless_charger_vrect, buffer.short)
        
        // Verify reconstruction
        val fromBytes = RP2040IncomingCommand.from(AndroidToRP2040Command.GET_STATE, payload) as RP2040IncomingCommand.GetState
        verifyMotorsState(motors, fromBytes.motorsState)
        verifyBatteryDetails(battery, fromBytes.batteryDetails)
        verifyChargeSideUSB(usb, fromBytes.chargeSideUSB)
    }

    @Test
    fun testSetMotorLevels() {
        val motors = createMockMotorsState()
        val battery = createMockBatteryDetails()
        val usb = createMockChargeSideUSB()
        val command = RP2040IncomingCommand.SetMotorLevels(motors, battery, usb)
        
        val payload = extractPayload(command.toBytes())
        val fromBytes = RP2040IncomingCommand.from(AndroidToRP2040Command.SET_MOTOR_LEVELS, payload) as RP2040IncomingCommand.SetMotorLevels
        
        verifyMotorsState(motors, fromBytes.motorsState)
        verifyBatteryDetails(battery, fromBytes.batteryDetails)
        verifyChargeSideUSB(usb, fromBytes.chargeSideUSB)
    }

    @Test
    fun testResetState() {
        val motors = createMockMotorsState()
        val battery = createMockBatteryDetails()
        val usb = createMockChargeSideUSB()
        val command = RP2040IncomingCommand.ResetState(motors, battery, usb)
        
        val payload = extractPayload(command.toBytes())
        val fromBytes = RP2040IncomingCommand.from(AndroidToRP2040Command.RESET_STATE, payload) as RP2040IncomingCommand.ResetState
        
        verifyMotorsState(motors, fromBytes.motorsState)
        verifyBatteryDetails(battery, fromBytes.batteryDetails)
        verifyChargeSideUSB(usb, fromBytes.chargeSideUSB)
    }

    @Test
    fun testHeaderCreation() {
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
        val command = RP2040IncomingCommand.Ack(data)
        val bytes = command.toBytes()
        
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(AndroidToRP2040Command.START.hexValue, buffer.get())
        assertEquals(0x80.toByte(), buffer.get())
        assertEquals(data.size.toShort(), buffer.short)
        assertEquals(AndroidToRP2040Command.ACK.hexValue, buffer.get())
        val headerCrc = bytes.sliceArray(0 until 5).toCrc()
        assertEquals(headerCrc, buffer.short)
        assertEquals(0xDE.toByte(), buffer.get())
        assertEquals(0xAD.toByte(), buffer.get())
        val dataCrc = bytes.sliceArray(7 until bytes.size - 2).toCrc()
        assertEquals(dataCrc, buffer.short)
    }

    @Test
    fun testFromInvalidTypes() {
        assertNull(RP2040IncomingCommand.from(AndroidToRP2040Command.START, byteArrayOf()))
        assertNull(RP2040IncomingCommand.from(AndroidToRP2040Command.STOP, byteArrayOf()))
    }
}

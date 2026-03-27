package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.rp2040.BatteryDetails
import jp.oist.abcvlib.util.rp2040.ChargeSideUSB
import jp.oist.abcvlib.util.rp2040.MotorsState
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PacketBufferTest {

    private lateinit var packetBuffer: PacketBuffer
    private val results = mutableListOf<PacketBuffer.ParseResult>()

    @Before
    fun setUp() {
        packetBuffer = PacketBuffer()
        results.clear()
    }

    private val ackCommand = RP2040IncomingCommand.Ack(byteArrayOf(0x01, 0x02))

    private val getLogCommand = RP2040IncomingCommand.GetLog(listOf("Log 1", "Log 2"))

    private val getStateCommand = RP2040IncomingCommand.GetState(
        motorsState = MotorsState().apply {
            controlValues.left = 0x66
            controlValues.right = 0x34
            faults.left = 0x00
            faults.right = 0x01
            encoderCounts.left = 1
            encoderCounts.right = 2
        },
        batteryDetails = BatteryDetails().apply {
            voltageMv = 12345
            temperature = 6789
            safetyStatus = 0x02
            stateOfHealth = 0x6
            flags = 0x2005
        },
        chargeSideUSB = ChargeSideUSB().apply {
            max77976_chg_details = 0x12345678
            ncp3901_wireless_charger_attached = true
            usb_charger_voltage = 0x55
            wireless_charger_vrect = 0x6478
        }
    )

    private val resetStateCommand = RP2040IncomingCommand.ResetState(
        motorsState = MotorsState().apply {
            controlValues.left = 0x66
            controlValues.right = 0x34
            faults.left = 0x00
            faults.right = 0x01
            encoderCounts.left = 1
            encoderCounts.right = 2
        },
        batteryDetails = BatteryDetails().apply {
            voltageMv = 12345
            temperature = 6789
            safetyStatus = 0x02
            stateOfHealth = 0x6
            flags = 0x2005
        },
        chargeSideUSB = ChargeSideUSB().apply {
            max77976_chg_details = 0x12345678
            ncp3901_wireless_charger_attached = true
            usb_charger_voltage = 0x55
            wireless_charger_vrect = 0x6478
        }
    )

    @Test
    fun `test consume single complete valid packet`() {
        val packet = getStateCommand.toBytes()

        packetBuffer.consume(packet) { results.add(it) }

        assertEquals(1, results.size)
        val result = results[0]
        assertTrue(result is PacketBuffer.ParseResult.ReceivedPacket)
        if (result is PacketBuffer.ParseResult.ReceivedPacket) {
            assertEquals(AndroidToRP2040Command.GET_STATE, result.command.type)
            assertArrayEquals(
                getStateCommand.toBytes(),
                result.command.toBytes()
            )
        }
    }

    @Test
    fun `test consume multiple complete packets`() {
        val packet1 = getStateCommand.toBytes()
        val packet2 = ackCommand.toBytes()
        val combined = packet1 + packet2

        packetBuffer.consume(combined) { results.add(it) }

        assertEquals(2, results.size)

        assertTrue(results[0] is PacketBuffer.ParseResult.ReceivedPacket)
        assertEquals(
            AndroidToRP2040Command.GET_STATE,
            (results[0] as PacketBuffer.ParseResult.ReceivedPacket).command.type
        )
        
        assertTrue(results[1] is PacketBuffer.ParseResult.ReceivedPacket)
        assertEquals(
            AndroidToRP2040Command.ACK,
            (results[1] as PacketBuffer.ParseResult.ReceivedPacket).command.type
        )
    }

    @Test
    fun `test consume packet split across multiple calls`() {
        val packet = getLogCommand.toBytes()
        
        val part1 = packet.sliceArray(0 until 3)
        val part2 = packet.sliceArray(3 until packet.size)

        packetBuffer.consume(part1) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is PacketBuffer.ParseResult.NotEnoughData)

        results.clear()
        packetBuffer.consume(part2) { results.add(it) }
        
        assertEquals("Expected 1 result in second call but got ${results.size}: $results", 1, results.size)
        assertTrue("Expected ReceivedPacket but got ${results[0]}", results[0] is PacketBuffer.ParseResult.ReceivedPacket)

        val parsedPacket = (results[0] as PacketBuffer.ParseResult.ReceivedPacket)
        assertEquals(
            AndroidToRP2040Command.GET_LOG,
            parsedPacket.command.type
        )

        assertTrue(parsedPacket.command is RP2040IncomingCommand.GetLog)

        assertArrayEquals(
            getLogCommand.logEntries.toTypedArray(),
            (parsedPacket.command as RP2040IncomingCommand.GetLog)
                .logEntries
                .toTypedArray()
        )
    }

    @Test
    fun `test consume with leading noise`() {
        val noise = byteArrayOf(0x00, 0x11, 0x22)
        val payload = byteArrayOf(0x44)
        val packet = resetStateCommand.toBytes()
        val combined = noise + packet

        packetBuffer.consume(combined) { results.add(it) }

        val packets = results.filterIsInstance<PacketBuffer.ParseResult.ReceivedPacket>()
        assertEquals(1, packets.size)
        assertEquals(AndroidToRP2040Command.RESET_STATE, packets[0].command.type)
        assertArrayEquals(
            resetStateCommand.toBytes(),
            packets[0].command.toBytes()
        )
    }

    @Test
    fun `test consume with invalid packet type`() {
        val size = 2
        val packet = ByteBuffer.allocate(1 + 1 + 2 + size + 1).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(AndroidToRP2040Command.START.hexValue)
            put(0x99.toByte()) // Invalid type
            putShort(size.toShort())
            put(byteArrayOf(0x01, 0x02))
            put(AndroidToRP2040Command.STOP.hexValue)
        }.array()

        packetBuffer.consume(packet) { results.add(it) }

        assertTrue(results.any { it is PacketBuffer.ParseResult.ReceivedErrorPacket })
    }

    @Test
    fun `test consume with reserved framing byte as packet type`() {
        // Test START as packet type
        val packetStart = ByteBuffer.allocate(1 + 1 + 2 + 1 + 1).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(AndroidToRP2040Command.START.hexValue)
            put(AndroidToRP2040Command.START.hexValue) // Reserved framing byte as type
            putShort(1.toShort())
            put(0x00.toByte())
            put(AndroidToRP2040Command.STOP.hexValue)
        }.array()

        packetBuffer.consume(packetStart) { results.add(it) }
        assertTrue("START as packet type should be rejected", results.any { it is PacketBuffer.ParseResult.ReceivedErrorPacket })

        results.clear()
        packetBuffer.clear()

        // Test STOP as packet type
        val packetStop = ByteBuffer.allocate(1 + 1 + 2 + 1 + 1).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(AndroidToRP2040Command.START.hexValue)
            put(AndroidToRP2040Command.STOP.hexValue) // Reserved framing byte as type
            putShort(1.toShort())
            put(0x00.toByte())
            put(AndroidToRP2040Command.STOP.hexValue)
        }.array()

        packetBuffer.consume(packetStop) { results.add(it) }
        assertTrue("STOP as packet type should be rejected", results.any { it is PacketBuffer.ParseResult.ReceivedErrorPacket })
    }

    @Test
    fun `test consume with unreasonable packet size`() {
        val packet = ByteBuffer.allocate(1 + 1 + 2).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(AndroidToRP2040Command.START.hexValue)
            put(AndroidToRP2040Command.GET_STATE.hexValue)
            putShort(3000.toShort()) // > 2048
        }.array()

        packetBuffer.consume(packet) { results.add(it) }

        assertTrue(results.any { it is PacketBuffer.ParseResult.ReceivedErrorPacket })
    }

    @Test
    fun `test consume with missing stop marker`() {
        val payload = byteArrayOf(0x01, 0x02)
        val size = payload.size
        val packet = ByteBuffer.allocate(1 + 1 + 2 + size + 1).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(AndroidToRP2040Command.START.hexValue)
            put(AndroidToRP2040Command.GET_STATE.hexValue)
            putShort(size.toShort())
            put(payload)
            put(0x00.toByte()) // Not STOP marker
        }.array()

        packetBuffer.consume(packet) { results.add(it) }

        assertTrue(results.any { it is PacketBuffer.ParseResult.ReceivedErrorPacket })
    }

    @Test
    fun `test resync after error`() {
        // First packet has bad stop marker
        val badPacket = ByteBuffer.allocate(1 + 1 + 2 + 1 + 1).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(AndroidToRP2040Command.START.hexValue)
            put(AndroidToRP2040Command.GET_STATE.hexValue)
            putShort(1.toShort())
            put(0xAA.toByte())
            put(0x00.toByte()) // Bad STOP
        }.array()

        val goodPacket = ackCommand.toBytes()
        val combined = badPacket + goodPacket

        packetBuffer.consume(combined) { results.add(it) }

        assertTrue(results.any { it is PacketBuffer.ParseResult.ReceivedErrorPacket })
        val packets = results.filterIsInstance<PacketBuffer.ParseResult.ReceivedPacket>()
        assertEquals(1, packets.size)
        assertEquals(AndroidToRP2040Command.ACK, packets[0].command.type)
        assertArrayEquals(goodPacket, packets[0].command.toBytes())
    }

    @Test
    fun `test clear resets full state after overflow`() {
        // 1. Send exactly the START byte to move state to READING_HEADER
        packetBuffer.consume(byteArrayOf(AndroidToRP2040Command.START.hexValue)) { }

        // 2. Trigger overflow
        val hugeArray = ByteArray(1024 * 1024)
        packetBuffer.consume(hugeArray) { results.add(it) }

        assertTrue(results.any { it is PacketBuffer.ParseResult.Overflow })
        results.clear()

        // 3. Send a valid packet.
        val validPacket = ackCommand.toBytes()
        packetBuffer.consume(validPacket) { results.add(it) }

        val packets = results.filterIsInstance<PacketBuffer.ParseResult.ReceivedPacket>()
        assertEquals("Expected 1 valid packet after overflow recovery", 1, packets.size)
        assertEquals(AndroidToRP2040Command.ACK, packets[0].command.type)
        assertArrayEquals(ackCommand.toBytes(), packets[0].command.toBytes())
    }
}

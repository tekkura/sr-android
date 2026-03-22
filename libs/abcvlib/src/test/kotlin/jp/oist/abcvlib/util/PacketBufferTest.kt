package jp.oist.abcvlib.util

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

    private fun createPacket(command: AndroidToRP2040Command, payload: ByteArray): ByteArray {
        val size = payload.size
        val buffer = ByteBuffer.allocate(1 + 1 + 2 + size + 1)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(AndroidToRP2040Command.START.hexValue)
        buffer.put(command.hexValue)
        buffer.putShort(size.toShort())
        buffer.put(payload)
        buffer.put(AndroidToRP2040Command.STOP.hexValue)
        return buffer.array()
    }

    @Test
    fun `test consume single complete valid packet`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val packet = createPacket(AndroidToRP2040Command.GET_STATE, payload)

        packetBuffer.consume(packet) { results.add(it) }

        assertEquals(1, results.size)
        val result = results[0]
        assertTrue(result is PacketBuffer.ParseResult.ReceivedPacket)
        if (result is PacketBuffer.ParseResult.ReceivedPacket) {
            assertEquals(AndroidToRP2040Command.GET_STATE, result.packetType)
            assertArrayEquals(payload, result.packetData)
        }
    }

    @Test
    fun `test consume multiple complete packets`() {
        val payload1 = byteArrayOf(0x01)
        val payload2 = byteArrayOf(0x02, 0x03)
        val packet1 = createPacket(AndroidToRP2040Command.GET_STATE, payload1)
        val packet2 = createPacket(AndroidToRP2040Command.ACK, payload2)
        val combined = packet1 + packet2

        packetBuffer.consume(combined) { results.add(it) }

        assertEquals(2, results.size)
        
        assertTrue(results[0] is PacketBuffer.ParseResult.ReceivedPacket)
        assertEquals(AndroidToRP2040Command.GET_STATE, (results[0] as PacketBuffer.ParseResult.ReceivedPacket).packetType)
        
        assertTrue(results[1] is PacketBuffer.ParseResult.ReceivedPacket)
        assertEquals(AndroidToRP2040Command.ACK, (results[1] as PacketBuffer.ParseResult.ReceivedPacket).packetType)
    }

    @Test
    fun `test consume packet split across multiple calls`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val packet = createPacket(AndroidToRP2040Command.GET_LOG, payload)
        
        val part1 = packet.sliceArray(0 until 3)
        val part2 = packet.sliceArray(3 until packet.size)

        packetBuffer.consume(part1) { results.add(it) }
        assertEquals(1, results.size)
        assertTrue(results[0] is PacketBuffer.ParseResult.NotEnoughData)

        results.clear()
        packetBuffer.consume(part2) { results.add(it) }
        
        assertEquals("Expected 1 result in second call but got ${results.size}: $results", 1, results.size)
        assertTrue("Expected ReceivedPacket but got ${results[0]}", results[0] is PacketBuffer.ParseResult.ReceivedPacket)
        assertEquals(AndroidToRP2040Command.GET_LOG, (results[0] as PacketBuffer.ParseResult.ReceivedPacket).packetType)
        assertArrayEquals(payload, (results[0] as PacketBuffer.ParseResult.ReceivedPacket).packetData)
    }

    @Test
    fun `test consume with leading noise`() {
        val noise = byteArrayOf(0x00, 0x11, 0x22)
        val payload = byteArrayOf(0x44)
        val packet = createPacket(AndroidToRP2040Command.RESET_STATE, payload)
        val combined = noise + packet

        packetBuffer.consume(combined) { results.add(it) }

        val packets = results.filterIsInstance<PacketBuffer.ParseResult.ReceivedPacket>()
        assertEquals(1, packets.size)
        assertEquals(AndroidToRP2040Command.RESET_STATE, packets[0].packetType)
        assertArrayEquals(payload, packets[0].packetData)
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
        
        val goodPayload = byteArrayOf(0xBB.toByte())
        val goodPacket = createPacket(AndroidToRP2040Command.ACK, goodPayload)
        
        val combined = badPacket + goodPacket
        
        packetBuffer.consume(combined) { results.add(it) }
        
        assertTrue(results.any { it is PacketBuffer.ParseResult.ReceivedErrorPacket })
        val packets = results.filterIsInstance<PacketBuffer.ParseResult.ReceivedPacket>()
        assertEquals(1, packets.size)
        assertEquals(AndroidToRP2040Command.ACK, packets[0].packetType)
        assertArrayEquals(goodPayload, packets[0].packetData)
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
        val payload = byteArrayOf(0x05)
        val validPacket = createPacket(AndroidToRP2040Command.ACK, payload)
        packetBuffer.consume(validPacket) { results.add(it) }

        val packets = results.filterIsInstance<PacketBuffer.ParseResult.ReceivedPacket>()
        assertEquals("Expected 1 valid packet after overflow recovery", 1, packets.size)
        assertEquals(AndroidToRP2040Command.ACK, packets[0].packetType)
        assertArrayEquals(payload, packets[0].packetData)
    }
}

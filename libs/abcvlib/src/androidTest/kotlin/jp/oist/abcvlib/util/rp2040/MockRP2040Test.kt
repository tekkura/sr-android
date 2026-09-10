package jp.oist.abcvlib.util.rp2040

import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.PacketBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockRP2040Test {

    @Test
    fun `rejects request with mismatched declared length`() {
        val packet = RP2040OutgoingCommand.GetState().toBytes().copyOf()
        packet[1] = 2
        packet[2] = 0

        assertNull(MockRP2040().processPacket(packet))
    }

    @Test
    fun `rejects request with invalid crc`() {
        val packet = RP2040OutgoingCommand.GetState().toBytes().copyOf()
        packet[packet.lastIndex] = (packet[packet.lastIndex].toInt() xor 0x01).toByte()

        assertNull(MockRP2040().processPacket(packet))
    }

    @Test
    fun `accepts valid request`() {
        assertNotNull(MockRP2040().processPacket(RP2040OutgoingCommand.GetState().toBytes()))
    }

    @Test
    fun `reset state request returns ack`() {
        val response = MockRP2040().processPacket(RP2040OutgoingCommand.ResetState().toBytes())

        assertNotNull(response)

        val results = mutableListOf<PacketBuffer.ParseResult>()
        PacketBuffer().consume(response!!) { results.add(it) }

        assertEquals(1, results.size)
        assertTrue(results[0] is PacketBuffer.ParseResult.ReceivedPacket)
        val command = (results[0] as PacketBuffer.ParseResult.ReceivedPacket).command
        assertEquals(AndroidToRP2040Command.ACK, command.type)
    }
}

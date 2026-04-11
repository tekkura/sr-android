package jp.oist.abcvlib.util.rp2040

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}

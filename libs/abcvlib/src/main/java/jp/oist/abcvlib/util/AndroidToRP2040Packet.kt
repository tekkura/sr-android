package jp.oist.abcvlib.util

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
packet[0] = START marker
packet[1] = AndroidToRP2040Command
packet[2..9] = payload
packet[10] = STOP marker
 */
internal class AndroidToRP2040Packet {
    companion object {
        // (2) 1 byte for each wheel, and + 1 for command
        const val AndroidToRP2040PayloadSize: Int = 2 + 1

        // Making room for start and stop marks
        const val packetSize: Int = AndroidToRP2040PayloadSize + 2
    }

    private lateinit var command: AndroidToRP2040Command

    // make room for packet_type, and start and stop marks
    val payload: ByteBuffer = ByteBuffer.allocate(AndroidToRP2040PayloadSize)
    private val packet: ByteBuffer = ByteBuffer.allocate(packetSize)

    init {
        // rp2040 is little endian whereas Java is big endian. This is to ensure that the bytes are
        // written in the correct order for parsing on the rp2040
        payload.order(ByteOrder.LITTLE_ENDIAN)
        packet.order(ByteOrder.LITTLE_ENDIAN)
        packet.put(AndroidToRP2040Command.START.hexValue)
    }

    fun setCommand(command: AndroidToRP2040Command) {
        this.command = command
        payload.put(command.hexValue)
    }

    // Add data to packet then the end mark
    fun packetToBytes(): ByteArray {
        payload.rewind()
        packet.put(payload)
        packet.put(AndroidToRP2040Command.STOP.hexValue)
        return packet.array()
    }

    fun clear() {
        packet.clear()
        packet.put(AndroidToRP2040Command.START.hexValue)
        payload.clear()
    }
}

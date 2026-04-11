package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.AndroidToRP2040Command.Companion.getEnumByValue
import jp.oist.abcvlib.util.ByteArrayExtensions.toCrc
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040ToAndroidPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class PacketBuffer(capacity: Int = (512 * 128) + 8) {

    private var packetDataSize = RP2020_PACKET_SIZE_STATE
    private var packetType: AndroidToRP2040Command = AndroidToRP2040Command.NACK

    private val _buffer = ByteBuffer.allocate(capacity).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    private var state = PacketBufferState.FINDING_START
    private var startIdx = -1
    private var readIndex = 0

    @Synchronized
    fun consume(
        bytes: ByteArray,
        onResult: (ParseResult) -> Unit
    ) {
        if (bytes.isEmpty()) {
            onResult(ParseResult.NotEnoughData)
            return
        }

        if (!put(bytes)) {
            Logger.e("verifyPacket", "Buffer overflow risk: clearing buffer.")
            clear()
            onResult(ParseResult.Overflow)
            return
        }

        val dataEnd = _buffer.position()

        while (readIndex < dataEnd) {
            when(state) {
                PacketBufferState.FINDING_START -> {
                    if (findStartIndex(dataEnd) != null) {
                        state = PacketBufferState.READING_HEADER
                    } else {
                        readIndex = dataEnd
                        onResult(ParseResult.NotEnoughData)
                        return
                    }
                }

                PacketBufferState.READING_HEADER -> {
                    if (dataEnd - startIdx < RP2040ToAndroidPacket.Offsets.DATA) {
                        onResult(ParseResult.NotEnoughData)
                        return
                    }

                    packetDataSize = _buffer.getShort(startIdx + RP2040ToAndroidPacket.Offsets.DATA_SIZE)
                        .toInt() and 0xFFFF

                    if (packetDataSize > 2048) {
                        Logger.e("verifyPacket", "Unreasonable packet size: ${packetDataSize}")
                        onResult(ParseResult.ReceivedErrorPacket)
                        resync()
                        continue
                    }

                    state = PacketBufferState.AWAITING_DATA
                }

                PacketBufferState.AWAITING_DATA -> {
                    val totalExpectedSize = RP2040ToAndroidPacket.Offsets.DATA + packetDataSize + 2
                    if (dataEnd - startIdx < totalExpectedSize) {
                        onResult(ParseResult.NotEnoughData)
                        return
                    }

                    val data = ByteArray(packetDataSize)
                    System.arraycopy(
                        _buffer.array(),
                        _buffer.arrayOffset() + startIdx + RP2040ToAndroidPacket.Offsets.DATA,
                        data,
                        0,
                        packetDataSize
                    )

                    val typeByte = data[0]
                    val parsedPacketType = getEnumByValue(typeByte)

                    if (parsedPacketType == null || parsedPacketType == AndroidToRP2040Command.START) {
                        Logger.e("verifyPacket", "Invalid or reserved packetType: $typeByte")
                        onResult(ParseResult.ReceivedErrorPacket)
                        resync()
                        continue
                    }

                    packetType = parsedPacketType

                    val endPos = startIdx + totalExpectedSize - 2
                    val crc = ByteBuffer.allocate(packetDataSize + 2).apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                        putShort(packetDataSize.toShort())
                        put(data)
                    }.array().toCrc()

                    if (_buffer.getShort(endPos) != crc) {
                        Logger.e("verifyPacket", "Data CRC mismatch: ${_buffer.getShort(endPos)} != ${data.toCrc()}")
                        onResult(ParseResult.ReceivedErrorPacket)
                        resync()
                        continue
                    }

                    val command = RP2040IncomingCommand.from(
                        packetType,
                        data.sliceArray(1 until data.size)
                    )

                    onResult(
                        command?.let {
                            ParseResult.ReceivedPacket(it)
                        } ?: ParseResult.ReceivedErrorPacket
                    )

                    readIndex = startIdx + totalExpectedSize
                    resetState()
                }
            }
        }

        if (readIndex > _buffer.capacity() / 2) {
            compact()
        }
    }

    private fun findStartIndex(limit: Int) : Int? {
        for (i in readIndex until limit) {
            if (_buffer.get(i) == AndroidToRP2040Command.START.hexValue) {
                startIdx = i
                readIndex = i
                return i
            }
        }
        return null
    }

    private fun resync() {
        readIndex = startIdx + 1
        resetState()
    }

    private fun resetState() {
        packetType = AndroidToRP2040Command.NACK
        packetDataSize = RP2020_PACKET_SIZE_STATE
        state = PacketBufferState.FINDING_START
        startIdx = -1
    }

    private fun compact() {
        if (readIndex == 0)
            return

        val dataEnd = _buffer.position()

        _buffer.position(readIndex)
        _buffer.limit(dataEnd)
        _buffer.compact()

        if (startIdx != -1)
            startIdx -= readIndex

        readIndex = 0
    }

    fun clear() {
        _buffer.clear()
        readIndex = 0
        resetState()
    }

    private fun put(bytes: ByteArray) : Boolean {
        if (_buffer.remaining() < bytes.size) {
            compact()
        }

        if (_buffer.remaining() < bytes.size) {
            return false
        }

        _buffer.put(bytes)
        return true
    }

    sealed class ParseResult {
        object NotEnoughData : ParseResult()
        object Overflow : ParseResult()
        object ReceivedErrorPacket : ParseResult()
        data class ReceivedPacket(
            val command: RP2040IncomingCommand
        ) : ParseResult()
    }

    private enum class PacketBufferState {
        FINDING_START,
        READING_HEADER,
        AWAITING_DATA;
    }

    companion object {
        private const val RP2020_PACKET_SIZE_STATE = 64
    }
}

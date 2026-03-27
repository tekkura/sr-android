package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.AndroidToRP2040Command.Companion.getEnumByValue
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040ToAndroidPacket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class PacketBuffer(capacity: Int = (512 * 128) + 8) {

    // Maybe make them private
    private var packetDataSize = RP2020_PACKET_SIZE_STATE
    private var packetType: AndroidToRP2040Command = AndroidToRP2040Command.NACK

    private val _buffer = ByteBuffer.allocate(capacity).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    private var state = PacketBufferState.FINDING_START
    private var startIdx = -1

    // Implementation of consume that can handle both case in which several commands are sent at once and in which one command is split between several packages
    @Synchronized
    fun consume(
        bytes: ByteArray,
        onResult: (ParseResult) -> Unit
    ) {
        if (bytes.isEmpty()) {
            Logger.v("verifyPacket", "Zero bytes array received. Waiting for more data")
            onResult(ParseResult.NotEnoughData)

            return
        }

        if (!put(bytes)) {
            Logger.e("verifyPacket", "Buffer overflow risk: clearing buffer and resynchronizing.")
            clear()

            onResult(ParseResult.Overflow)
            return
        }

        _buffer.flip()

        while (_buffer.hasRemaining()) {
            when(state) {
                PacketBufferState.FINDING_START -> {
                    if (findStartIndex() != null) {
                        Logger.v("verifyPacket", "startIdx found at ${_buffer.position() - 1}")
                    } else {
                        Logger.v("verifyPacket", "StartIdx not yet found. Waiting for more data")
                        onResult(ParseResult.NotEnoughData)

                        break
                    }

                    state = PacketBufferState.READING_HEADER
                }

                PacketBufferState.READING_HEADER -> {
                    // If position is 0, nothing received yet. If position is 1 only the start mark has been received.
                    // position 2 is the packetType, and positions 3 and 4 are a short for packetSize
                    if (_buffer.remaining() < RP2040ToAndroidPacket.Offsets.DATA - 1) {
                        Logger.v("verifyPacket", "Incomplete header. Waiting for more data")
                        _buffer.position(startIdx)
                        resetState()
                        onResult(ParseResult.NotEnoughData)

                        break
                    }

                    val headerStartPosition = _buffer.position()
                    val packetTypeByte = _buffer.get()
                    val parsedPacketType = getEnumByValue(packetTypeByte)

                    if (parsedPacketType == null ||
                        parsedPacketType == AndroidToRP2040Command.START ||
                        parsedPacketType == AndroidToRP2040Command.STOP
                    ) {
                        Logger.e("verifyPacket", "Invalid or reserved packetType: $packetTypeByte")
                        onResult(ParseResult.ReceivedErrorPacket)
                        resync()

                        continue
                    }

                    packetType = parsedPacketType

                    // get the packetDataSize. It is stored at index 3 and 4 as a short
                    packetDataSize = _buffer.getShort().toInt() and 0xFFFF

                    if (packetDataSize > 2048) {
                        Logger.e(
                            "verifyPacket",
                            "Unreasonable packet size: ${packetDataSize}. Resynchronizing."
                        )

                        onResult(ParseResult.ReceivedErrorPacket)
                        resync()

                        continue
                    }

                    Logger.v(
                        "verifyPacket",
                        "$packetType packetType of size $packetDataSize found at $headerStartPosition"
                    )

                    state = PacketBufferState.AWAITING_DATA
                }

                PacketBufferState.AWAITING_DATA -> {
                    if (_buffer.remaining() < (packetDataSize + 1)) {
                        Logger.d(
                            "verifyPacket", "Data received not yet enough to fill " +
                                    packetType + " packetType. Waiting for more data"
                        )

                        Logger.d(
                            "verifyPacket",
                            _buffer.remaining().toString() +
                                    " bytes recvd thus far. Require " +
                                    packetDataSize + 1
                        )

                        _buffer.position(startIdx)
                        resetState()
                        onResult(ParseResult.NotEnoughData)

                        break
                    }

                    state = PacketBufferState.CHECKING_STOP
                }

                PacketBufferState.CHECKING_STOP -> {
                    val stopIdxPosition = _buffer.position() + packetDataSize
                    if (_buffer.get(stopIdxPosition) != AndroidToRP2040Command.STOP.hexValue) {
                        onResult(ParseResult.ReceivedErrorPacket)
                        resync()

                        continue
                    }

                    Logger.i("verifyPacket", "${packetType.name} command received")
                    state = PacketBufferState.PROCESSING_COMMAND
                }

                PacketBufferState.PROCESSING_COMMAND -> {
                    val data = ByteArray(packetDataSize)
                    _buffer.get(data) // Position moves past data

                    _buffer.get() // Position moves past STOP byte

                    val command = RP2040IncomingCommand.from(packetType, data)

                    onResult(
                        if (command != null)
                            ParseResult.ReceivedPacket(command)
                        else ParseResult.ReceivedErrorPacket
                    )

                    resetState()
                }
            }
        }

        _buffer.compact()
    }

    private fun findStartIndex() : Int? = with(_buffer) {
        while (hasRemaining()) {
            val i = position()
            if (get() == AndroidToRP2040Command.START.hexValue) {
                Logger.v("verifyPacket", "StartIdx found at $i")
                startIdx = i

                return i
            }
        }

        return null
    }

    private fun resync() {
        _buffer.position(startIdx + 1)
        resetState()
    }

    private fun resetState() {
        packetType = AndroidToRP2040Command.NACK
        packetDataSize = RP2020_PACKET_SIZE_STATE
        state = PacketBufferState.FINDING_START
        startIdx = -1
    }

    fun clear() {
        with(_buffer) {
            // High performance for buffers created with ByteBuffer.allocate()
            if (hasArray()) {
                Arrays.fill(
                    array(),
                    arrayOffset(),
                    arrayOffset() + capacity(),
                    0.toByte()
                )
            }

            clear()
        }

        resetState()
    }

    private fun put(bytes: ByteArray) : Boolean {
        if (_buffer.remaining() < bytes.size) {
            return false
        }

        _buffer.put(bytes)
        return true
    }

    sealed class ParseResult() {
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
        AWAITING_DATA,
        CHECKING_STOP,
        PROCESSING_COMMAND;
    }

    companion object {
        private const val RP2020_PACKET_SIZE_STATE = 64
    }
}

package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.AndroidToRP2040Command.Companion.getEnumByValue
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class PacketBuffer(capacity: Int = (512 * 128) + 8) {

    // Maybe make them private
    private var packetDataSize = RP2020_PACKET_SIZE_STATE
    private var packetType: AndroidToRP2040Command = AndroidToRP2040Command.NACK

    //Ensure a proper start and stop mark present before adding anything to the fifoQueue
    private var startStopIdx = StartStopIndex(-1, -1)

    private val _buffer = ByteBuffer.allocate(capacity).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    @Synchronized
    fun consume(bytes: ByteArray) : ParseResult {
        if (!put(bytes)) {
            Logger.e("verifyPacket", "Buffer overflow risk: clearing buffer and resynchronizing.")
            clear()

            return ParseResult.Overflow
        }

        // If startIdx not yet found, find it
        if (startStopIdx.startIdx < 0)
            startStopIndexSearch()

        // If startIdx still not found, return false
        if (startStopIdx.startIdx < 0) {
            Logger.v("verifyPacket", "StartIdx not yet found. Waiting for more data")
            return ParseResult.NotEnoughData
        }

        Logger.v("verifyPacket", "StartIdx found at " + startStopIdx.startIdx)
        // if packetType not yet found, find it
        if (packetType == AndroidToRP2040Command.NACK) {
            // If position is 0, nothing received yet. If position is 1 only the start mark has been received.
            // position 2 is the packetType, and positions 3 and 4 are a short for packetSize
            if (_buffer.position() < RP2040ToAndroidPacket.Offsets.DATA) {
                Logger.v("verifyPacket", "Nothing other than startIdx found. Waiting for more data")
                return ParseResult.NotEnoughData
            }

            val packetTypeByte = _buffer.get(startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.PACKET_TYPE)
            val parsedPacketType = getEnumByValue(packetTypeByte)
            if (parsedPacketType == null) {
                Logger.e("verifyPacket", "Unknown packetType: $packetTypeByte")
                return ParseResult.InvalidType(packetTypeByte)
            }

            packetType = parsedPacketType

            // get the packetDataSize. It is stored at index 3 and 4 as a short
            packetDataSize = _buffer.getShort(
                startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.DATA_SIZE
            ).toInt() and 0xFFFF

            if (packetDataSize > 2048) {
                Logger.e(
                    "verifyPacket",
                    "Unreasonable packet size: ${packetDataSize}. Resynchronizing."
                )

                return ParseResult.InvalidSize(packetDataSize)
            }

            Logger.v(
                "verifyPacket",
                packetType.toString() + " packetType of size " + packetDataSize + " found at "
                        + (startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.PACKET_TYPE)
            )

            findStopIndex()
        }

        if (_buffer.position() < (startStopIdx.stopIdx + 1)) {
            Logger.d(
                "verifyPacket", "Data received not yet enough to fill " +
                        packetType + " packetType. Waiting for more data"
            )

            Logger.d(
                "verifyPacket",
                _buffer.position().toString() +
                        " bytes recvd thus far. Require " +
                        startStopIdx.stopIdx + 1
            )

            return ParseResult.NotEnoughData
        }

        if (_buffer.get(startStopIdx.stopIdx) != AndroidToRP2040Command.STOP.hexValue)
            return ParseResult.StopMarkerNotFound

        Logger.i("verifyPacket", "${packetType.name} command received")

        return ParseResult.Success(
            packetType = packetType,
            packetData = getCurrentCommandByteArray()
        )
    }

    fun clear() {
        with(_buffer) {
            // High performance for buffers created with ByteBuffer.allocate()
            Arrays.fill(
                array(),
                arrayOffset(),
                arrayOffset() + capacity(),
                0.toByte()
            )

            clear()
        }

        packetType = AndroidToRP2040Command.NACK
        startStopIdx = StartStopIndex(-1, -1)
    }

    private fun put(bytes: ByteArray) : Boolean {
        if (_buffer.remaining() < bytes.size) {
            return false
        }

        _buffer.put(bytes)
        return true
    }

    @Throws(IOException::class)
    private fun startStopIndexSearch() {
        val startStopIdx = StartStopIndex(-1, -1)
        var i = 0
        _buffer.flip()

        while (i < _buffer.limit()) {
            // get value at position and increment position by 1 (so don't call it multiple times)
            val value = _buffer.get()

            // Always overwrite the value of stopIdx as you want the last instance
            if (value == AndroidToRP2040Command.STOP.hexValue) {
                startStopIdx.stopIdx = i
                Logger.v("serial", "stopIdx found at $i")
            } else if (value == AndroidToRP2040Command.START.hexValue && startStopIdx.startIdx < 0) {
                startStopIdx.startIdx = i
                Logger.v("serial", "startIdx found at $i")
            }

            i++
        }

        // flip will set limit to position and position to 0
        // need to set limit back to capacity after reading
        _buffer.limit(_buffer.capacity())
        this.startStopIdx = startStopIdx
    }

    private fun findStopIndex() {
        startStopIdx.stopIdx =
            startStopIdx.startIdx + packetDataSize + (RP2040ToAndroidPacket.Offsets.DATA)
    }

    private fun getCurrentCommandByteArray() : ByteArray {
        _buffer.position(startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.DATA)
        _buffer.limit(startStopIdx.stopIdx)
        val partialArray = ByteArray(_buffer.remaining())
        _buffer.get(partialArray)

        return partialArray
    }

    fun resyncToNextStartMarker() {
        _buffer.flip()
        var startIdx = -1
        for (i in 0..<_buffer.limit()) {
            if (_buffer.get(i) == AndroidToRP2040Command.START.hexValue) {
                startIdx = i
                break
            }
        }

        if (startIdx >= 0) {
            _buffer.position(startIdx)
            _buffer.compact()
        } else {
            _buffer.clear()
        }
    }

    private class StartStopIndex(var startIdx: Int, var stopIdx: Int)

    sealed class ParseResult() {
        data class InvalidSize(val packetSize: Int) : ParseResult()
        data class InvalidType(val packetTypeByte: Byte) : ParseResult()
        object NotEnoughData : ParseResult()
        object Overflow : ParseResult()
        object StopMarkerNotFound : ParseResult()
        data class Success(
            val packetType: AndroidToRP2040Command,
            val packetData: ByteArray
        ) : ParseResult()
    }

    companion object {
        private const val RP2020_PACKET_SIZE_STATE = 64
    }
}
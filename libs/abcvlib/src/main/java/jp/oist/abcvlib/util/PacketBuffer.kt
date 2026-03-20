package jp.oist.abcvlib.util

import jp.oist.abcvlib.util.AndroidToRP2040Command.Companion.getEnumByValue
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

class PacketBuffer(capacity: Int = (512 * 128) + 8) {

    // Maybe make them private
    var packetDataSize = RP2020_PACKET_SIZE_STATE
    var packetType: AndroidToRP2040Command = AndroidToRP2040Command.NACK

    //Ensure a proper start and stop mark present before adding anything to the fifoQueue
    var startStopIdx = StartStopIndex(-1, -1)

    private val _buffer = ByteBuffer.allocate(capacity).apply {
        order(ByteOrder.LITTLE_ENDIAN)
    }

    fun put(bytes: ByteArray) : Boolean {
        if (_buffer.remaining() < bytes.size) {
            return false
        }

        _buffer.put(bytes)
        return true
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
    }

    @Throws(IOException::class)
    fun startStopIndexSearch() {
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

    @Throws(
        NoDataException::class,
        InvalidPacketTypeException::class,
        InvalidPacketSizeException::class
    )
    fun initializePacket() {
        // If position is 0, nothing received yet. If position is 1 only the start mark has been received.
        // position 2 is the packetType, and positions 3 and 4 are a short for packetSize
        if (_buffer.position() >= RP2040ToAndroidPacket.Offsets.DATA) {
            val packetTypeByte = _buffer.get(startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.PACKET_TYPE)

            getEnumByValue(packetTypeByte)?.let {
                packetType = it
            } ?: throw InvalidPacketTypeException(packetTypeByte)

            // get the packetDataSize. It is stored at index 3 and 4 as a short
            packetDataSize = _buffer.getShort(
                startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.DATA_SIZE
            ).toInt() and 0xFFFF

            if (packetDataSize > 2048) // sanity check for max packet size
                throw InvalidPacketSizeException(packetDataSize)

            Logger.v(
                "serial",
                packetType.toString() + " packetType of size " + packetDataSize + " found at "
                        + (startStopIdx.startIdx + RP2040ToAndroidPacket.Offsets.PACKET_TYPE)
            )
        } else throw NoDataException()
    }

    fun findStopIndex() {
        startStopIdx.stopIdx =
            startStopIdx.startIdx + packetDataSize + (RP2040ToAndroidPacket.Offsets.DATA)
    }

    @Throws(
        NotEnoughDataForTypeException::class,
        StopMarkerNotFoundException::class
    )
    fun checkPacketDataAvailability() : Boolean {
        // +1 for packetBuffer.position() needing to be 1 after endIdx byte to indicate that you have the stopIdx byte
        if (_buffer.position() < (startStopIdx.stopIdx + 1))
            throw NotEnoughDataForTypeException(
                packetType,
                _buffer.position(),
                startStopIdx.stopIdx + 1
            )

        if (_buffer.get(startStopIdx.stopIdx) != AndroidToRP2040Command.STOP.hexValue)
            throw StopMarkerNotFoundException()

        return true
    }

    fun getCurrentCommandByteArray() : ByteArray {
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

    class StartStopIndex(var startIdx: Int, var stopIdx: Int)

    class InvalidPacketSizeException(val packetSize: Int) : Exception()
    class InvalidPacketTypeException(val packetTypeByte: Byte) : Exception()
    class NoDataException : Exception()
    class NotEnoughDataForTypeException(
        val packetType: AndroidToRP2040Command,
        val bytesGiven: Int,
        val bytesExpected: Int
    ) : Exception()

    class StopMarkerNotFoundException : Exception()

    companion object {
        private const val RP2020_PACKET_SIZE_STATE = 64
    }
}
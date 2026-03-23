package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class MotorsState {
    class ControlValues {
        var left: Byte = 0
        var right: Byte = 0
    }

    class Faults {
        var left: Byte = 0
        var right: Byte = 0
    }

    class EncoderCounts {
        var left: Int = 0
        var right: Int = 0
    }

    var controlValues: ControlValues = ControlValues()
    var encoderCounts: EncoderCounts = EncoderCounts()
    var faults: Faults = Faults()

    fun toBytes() : ByteArray {
        val buffer = ByteBuffer.allocate(12).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(controlValues.left)
            put(controlValues.right)
            put(faults.left)
            put(faults.right)
            putInt(encoderCounts.left)
            putInt(encoderCounts.right)
        }

        return buffer.array()
    }

    companion object {

        const val BYTE_LENGTH = 12
        private const val TAG = "RP2040State"

        fun from(buffer: ByteBuffer): MotorsState? {
            if (buffer.remaining() < BYTE_LENGTH) {
                Logger.w(TAG, "Buffer size too small to parse MotorsState")
                return null
            }

            return MotorsState().apply {
                controlValues.left = buffer.get()
                controlValues.right = buffer.get()
                faults.left = buffer.get()
                faults.right = buffer.get()
                encoderCounts.left = buffer.int
                encoderCounts.right = buffer.int
            }
        }
    }
}
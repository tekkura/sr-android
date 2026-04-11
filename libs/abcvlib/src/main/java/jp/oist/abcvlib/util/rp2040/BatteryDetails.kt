package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BatteryDetails {
    // Raw byte data
    var voltageMv: Short = 0
    var temperature: Short = 0
    var safetyStatus: Byte = 0
    var stateOfHealth: Byte = 0
    var flags: Short = 0

    /* convert mV to V
        See bq27441-G1 Technical Reference Manual, Section 4.1.5
    */
    fun getVoltage(): Double {
        return voltageMv / 1000.0
    }

    fun getTemperatureV(): Double {
        return temperature / 10.0
    }

    fun toBytes() : ByteArray {
        val buffer = ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(voltageMv)
            put(safetyStatus)
            putShort(temperature)
            put(stateOfHealth)
            putShort(flags)
        }

        return buffer.array()
    }

    companion object {

        const val BYTE_LENGTH = 8
        private const val TAG = "BatteryDetails"

        fun from(buffer: ByteBuffer): BatteryDetails? {
            if (buffer.remaining() < BYTE_LENGTH) {
                Logger.w(TAG, "Buffer size too small to parse BatteryDetails")
                return null
            }

            return BatteryDetails().apply {
                voltageMv = buffer.short
                safetyStatus = buffer.get()
                temperature = buffer.short
                stateOfHealth = buffer.get()
                flags = buffer.short
            }
        }
    }
}
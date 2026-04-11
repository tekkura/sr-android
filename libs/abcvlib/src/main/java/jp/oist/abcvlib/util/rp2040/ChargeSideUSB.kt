package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChargeSideUSB {
    var max77976_chg_details: Int = 0
    var ncp3901_wireless_charger_attached: Boolean = false
    var usb_charger_voltage: Short = 0
    var wireless_charger_vrect: Short = 0

    fun getMax77976ChgDetails(): Int {
        return max77976_chg_details
    }

    fun isWirelessChargerAttached(): Boolean {
        return ncp3901_wireless_charger_attached
    }

    fun getUsbChargerVoltage(): Double {
        return usb_charger_voltage / 1000.0
    }

    fun getWirelessChargerVrect(): Double {
        return wireless_charger_vrect / 1000.0
    }

    fun toBytes() : ByteArray {
        val buffer = ByteBuffer.allocate(9).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(max77976_chg_details)
            put(if (ncp3901_wireless_charger_attached) 1 else 0)
            putShort(usb_charger_voltage)
            putShort(wireless_charger_vrect)
        }

        return buffer.array()
    }

    companion object {
        const val BYTE_LENGTH = 9
        private const val TAG = "ChargeSideUSB"

        fun from(buffer: ByteBuffer): ChargeSideUSB? {
            if (buffer.remaining() < BYTE_LENGTH) {
                Logger.w(TAG, "Buffer size too small to parse ChargeSideUSB")
                return null
            }

            return ChargeSideUSB().apply {
                max77976_chg_details = buffer.int
                ncp3901_wireless_charger_attached = (buffer.get() == 1.toByte())
                usb_charger_voltage = buffer.short
                wireless_charger_vrect = buffer.short
            }
        }
    }
}
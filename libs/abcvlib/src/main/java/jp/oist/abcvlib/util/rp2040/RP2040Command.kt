package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.ByteArrayExtensions.toCrc
import jp.oist.abcvlib.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface StatusCommand {
    val motorsState: MotorsState
    val batteryDetails: BatteryDetails
    val chargeSideUSB: ChargeSideUSB
}

abstract class RP2040Command {

    protected val id: Int = 0
    protected abstract fun serializeData(): ByteArray
    abstract val type: AndroidToRP2040Command

    fun toBytes(): ByteArray {
        val data = serializeData()
        val header = createHeader(data)
        val crc = data.toCrc()
        val crcBuffer = ByteBuffer.allocate(2).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(crc)
        }

        return header + data + crcBuffer.array()
    }

    private fun createHeader(data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(5).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(AndroidToRP2040Command.START.hexValue)
            put((id and 0x7F or MASTER_BIT).toByte())
            putShort(data.size.toShort())
            put(type.hexValue)
        }

        val crc = header.array().toCrc()

        val crcBuffer = ByteBuffer.allocate(2).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(crc)
        }

        return header.array() + crcBuffer.array()
    }

    companion object {
        private const val MASTER_BIT = 0x80
    }
}
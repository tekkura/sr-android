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

    protected abstract fun serializeData(): ByteArray
    abstract val type: AndroidToRP2040Command

    fun toBytes(): ByteArray {
        val data = serializeData()
        val header = createHeader(data)
        val crc = (header + data).toCrc()

        return ByteBuffer.allocate(data.size + 6).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(AndroidToRP2040Command.START.hexValue)
            put(header)
            put(data)
            putShort(crc)
        }.array()
    }

    private fun createHeader(data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(3).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putShort((data.size + 1).toShort())
            put(type.hexValue)
        }

        return header.array()
    }
}
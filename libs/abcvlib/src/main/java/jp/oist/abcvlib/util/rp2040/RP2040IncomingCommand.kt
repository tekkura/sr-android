package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.util.AndroidToRP2040Command
import jp.oist.abcvlib.util.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface StatusCommand {
    val motorsState: MotorsState
    val batteryDetails: BatteryDetails
    val chargeSideUSB: ChargeSideUSB
}

sealed class RP2040IncomingCommand {

    protected abstract fun serializeData(): ByteArray
    abstract val type: AndroidToRP2040Command

    fun toBytes(): ByteArray {
        val data = serializeData()
        val header = createHeader(data)
        return header + data + AndroidToRP2040Command.STOP.hexValue
    }

    private fun createHeader(data: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(4).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }

        buffer.put(AndroidToRP2040Command.START.hexValue)
        buffer.put(type.hexValue)
        buffer.putShort(data.size.toShort())

        return buffer.array()
    }

    class Nack(val data: ByteArray) : RP2040IncomingCommand() {
        override val type = AndroidToRP2040Command.NACK

        override fun serializeData(): ByteArray {
            return data
        }
    }

    class Ack(val data: ByteArray) : RP2040IncomingCommand() {
        override val type = AndroidToRP2040Command.ACK

        override fun serializeData(): ByteArray {
            return data
        }
    }

    class GetLog(val logEntries: List<String>) : RP2040IncomingCommand() {
        override val type = AndroidToRP2040Command.GET_LOG

        override fun serializeData(): ByteArray {
            val logString = logEntries.joinToString("\n")
            return logString.toByteArray(charset = Charsets.US_ASCII)
        }
    }

    class GetState(
        override val motorsState: MotorsState,
        override val batteryDetails: BatteryDetails,
        override val chargeSideUSB: ChargeSideUSB
    ) : RP2040IncomingCommand(), StatusCommand {
        override val type = AndroidToRP2040Command.GET_STATE

        override fun serializeData() = motorsState.toBytes() +
                batteryDetails.toBytes() +
                chargeSideUSB.toBytes()
    }

    class SetMotorLevels(
        override val motorsState: MotorsState,
        override val batteryDetails: BatteryDetails,
        override val chargeSideUSB: ChargeSideUSB
    ) : RP2040IncomingCommand(), StatusCommand {
        override val type = AndroidToRP2040Command.SET_MOTOR_LEVELS

        override fun serializeData() = motorsState.toBytes() +
                batteryDetails.toBytes() +
                chargeSideUSB.toBytes()
    }

    class ResetState(
        override val motorsState: MotorsState,
        override val batteryDetails: BatteryDetails,
        override val chargeSideUSB: ChargeSideUSB
    ) : RP2040IncomingCommand(), StatusCommand {
        override val type = AndroidToRP2040Command.RESET_STATE

        override fun serializeData() = motorsState.toBytes() +
                batteryDetails.toBytes() +
                chargeSideUSB.toBytes()
    }

    class GetVersion(
        val major: Int,
        val minor: Int,
        val patch: Int
    ) : RP2040IncomingCommand() {
        override val type = AndroidToRP2040Command.GET_VERSION

        override fun serializeData(): ByteArray = ByteBuffer.allocate(3)
            .apply {
                put(major.toByte())
                put(minor.toByte())
                put(patch.toByte())
            }
            .array()
    }

    companion object {
        private const val TAG = "RP2040Command"

        fun from(
            type: AndroidToRP2040Command,
            data: ByteArray
        ): RP2040IncomingCommand? {
            when (type) {
                AndroidToRP2040Command.NACK -> {
                    return Nack(data)
                }

                AndroidToRP2040Command.ACK -> {
                    return Ack(data)
                }

                AndroidToRP2040Command.GET_LOG -> {
                    val logEntries = data.toString(charset = Charsets.US_ASCII)
                        .lines()

                    return GetLog(logEntries)
                }

                AndroidToRP2040Command.GET_STATE -> {
                    val buffer = ByteBuffer.wrap(data).apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                    }

                    return GetState(
                        motorsState = MotorsState.from(buffer) ?: return null,
                        batteryDetails = BatteryDetails.from(buffer) ?: return null,
                        chargeSideUSB = ChargeSideUSB.from(buffer) ?: return null
                    )
                }

                AndroidToRP2040Command.SET_MOTOR_LEVELS -> {
                    val buffer = ByteBuffer.wrap(data).apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                    }

                    return SetMotorLevels(
                        motorsState = MotorsState.from(buffer) ?: return null,
                        batteryDetails = BatteryDetails.from(buffer) ?: return null,
                        chargeSideUSB = ChargeSideUSB.from(buffer) ?: return null
                    )
                }

                AndroidToRP2040Command.RESET_STATE -> {
                    val buffer = ByteBuffer.wrap(data).apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                    }

                    return ResetState(
                        motorsState = MotorsState.from(buffer) ?: return null,
                        batteryDetails = BatteryDetails.from(buffer) ?: return null,
                        chargeSideUSB = ChargeSideUSB.from(buffer) ?: return null
                    )
                }

                AndroidToRP2040Command.GET_VERSION -> {
                    return GetVersion(
                        major = data[0].toInt(),
                        minor = data[1].toInt(),
                        patch = data[2].toInt()
                    )
                }

                AndroidToRP2040Command.START -> {
                    Logger.e(TAG, "parseStart. Start should never be a command")

                    return null
                }

                AndroidToRP2040Command.STOP -> {
                    Logger.e(TAG, "parseStop. Stop should never be a command")

                    return null
                }
            }
        }
    }
}
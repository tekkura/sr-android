package jp.oist.abcvlib.util

enum class AndroidToRP2040Command(val hexValue: Byte) {
    GET_LOG(0x00.toByte()),
    SET_MOTOR_LEVELS(0x01.toByte()),
    RESET_STATE(0x02.toByte()),
    GET_STATE(0x03.toByte()),
    NACK(0xFC.toByte()),
    ACK(0xFD.toByte()),
    START(0xFE.toByte()),
    STOP(0xFF.toByte());

    companion object {
        private val map: Map<Byte, AndroidToRP2040Command> = entries.associateBy { it.hexValue }

        fun getEnumByValue(value: Byte): AndroidToRP2040Command? {
            return map[value]
        }
    }
}

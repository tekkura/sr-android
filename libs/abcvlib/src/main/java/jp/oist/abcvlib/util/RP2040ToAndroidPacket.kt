package jp.oist.abcvlib.util

class RP2040ToAndroidPacket {
    object Offsets {
        const val START_MARKER: Int = 0
        const val PACKET_TYPE: Int = 1
        const val DATA_SIZE: Int = 2
        const val DATA: Int = 4
        const val END_MARKER: Int = 0 // Adjust this as needed
    }

    object Sizes {
        // Define sizes for fields
        const val START_MARKER: Int = 1
        const val PACKET_TYPE: Int = 1
        const val DATA_SIZE: Int = 2
        const val END_MARKER: Int = 1
    }
}

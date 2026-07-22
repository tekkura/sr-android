package jp.oist.abcvlib.util.latency

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LatencyTelemetry(
    val t4TimestampUs: Long,
    val t5TimestampUs: Long
) {
    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(BYTE_LENGTH).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putLong(t4TimestampUs)
            putLong(t5TimestampUs)
        }

        return buffer.array()
    }

    companion object {
        const val BYTE_LENGTH = 16

        fun from(bytes: ByteArray): LatencyTelemetry? {
            if (bytes.size != BYTE_LENGTH) return null

            val buffer = ByteBuffer.wrap(bytes).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }

            return LatencyTelemetry(
                t4TimestampUs = buffer.long,
                t5TimestampUs = buffer.long
            )
        }
    }
}

package jp.oist.abcvlib.util.latency

import java.util.concurrent.ConcurrentHashMap

/**
 * Utility to capture high-resolution timestamps for performance benchmarking.
 * This is intended for use during development and profiling.
 */
object BenchmarkClock {
    private var enabled = false
    private val iterations = ConcurrentHashMap<Int, LongArray>()
    private val successStates = ConcurrentHashMap<Int, Boolean>()

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
        if (!isEnabled)
            clear()
    }

    fun startIteration(iteration: Int) {
        if (!enabled) return
        iterations[iteration] = LongArray(9) // T1 to T9
        successStates[iteration] = false
    }

    fun recordSuccess(iteration: Int) {
        if (!enabled) return

        successStates[iteration] = true
    }

    fun mark(iteration: Int, junction: Int): Long? {
        return markAt(iteration, junction, System.nanoTime())
    }

    fun markAt(iteration: Int, junction: Int, timestampNs: Long): Long? {
        if (!enabled || iteration == -1) return null
        val timestamps = iterations[iteration] ?: return null
        if (junction !in 1..9) return null

        val index = junction - 1

        // Prevent overwriting a junction that was already marked for this iteration.
        if (timestamps[index] == 0L) {
            timestamps[index] = timestampNs
            return timestampNs
        }

        return timestamps[index]
    }

    fun getResults(): Map<Int, LongArray> = iterations.toMap()

    fun getSuccessStates(): Map<Int, Boolean> = successStates.toMap()

    fun clear() {
        iterations.clear()
        successStates.clear()
    }

}

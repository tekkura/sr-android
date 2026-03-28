package jp.oist.abcvlib.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Utility to capture high-resolution timestamps for performance benchmarking.
 * This is intended for use during development and profiling.
 */
object BenchmarkClock {
    private var enabled = false
    private val iterations = ConcurrentHashMap<Int, LongArray>()
    private val successStates = ConcurrentHashMap<Int, Boolean>()
    private var currentIteration = -1

    fun setEnabled(isEnabled: Boolean) {
        enabled = isEnabled
        if (!isEnabled) {
            iterations.clear()
            successStates.clear()
            currentIteration = -1
        }
    }

    fun startIteration(iteration: Int) {
        if (!enabled) return
        currentIteration = iteration
        iterations[iteration] = LongArray(8) // T1 to T8
    }

    fun recordSuccess(success: Boolean) {
        if (!enabled || currentIteration == -1) return
        // We only care about the first definitive result (Success or Error) per iteration
        successStates.putIfAbsent(currentIteration, success)
    }

    fun mark(junction: Int) {
        if (!enabled || currentIteration == -1) return
        val timestamps = iterations[currentIteration] ?: return
        if (junction !in 1..8) return
        
        val index = junction - 1
        
        // Ensure that junctions are marked in order for a single command-response cycle.
        // This prevents a late T7 from a previous iteration from being recorded in a new one,
        // and ensures we only track the first valid sequence.
        if (junction > 1 && timestamps[index - 1] == 0L) return

        // Prevent overwriting a junction that was already marked for this iteration.
        if (timestamps[index] == 0L) {
            timestamps[index] = System.nanoTime()
        }
    }

    fun getResults(): Map<Int, LongArray> = iterations.toMap()
    
    fun getSuccessStates(): Map<Int, Boolean> = successStates.toMap()

    fun clear() {
        iterations.clear()
        successStates.clear()
        currentIteration = -1
    }
}

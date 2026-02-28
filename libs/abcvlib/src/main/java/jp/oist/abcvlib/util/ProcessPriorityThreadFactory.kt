package jp.oist.abcvlib.util

import java.util.concurrent.ThreadFactory

class ProcessPriorityThreadFactory(
    private val threadPriority: Int,
    private val threadName: String
) : ThreadFactory {
    private var threadCount = 0

    override fun newThread(r: Runnable): Thread {
        val thread = Thread(r)
        // Logger.v("Threading", "Current Thread Priority: " + thread.getPriority());
        // Logger.v("Threading", "Current ThreadGroup Max Priority: " + thread.getThreadGroup().getMaxPriority());
        thread.priority = threadPriority
        // Logger.v("Threading", "Newly set Thread Priority: " + thread.getPriority());
        thread.name = "${threadName}_$threadCount"
        threadCount++
        return thread
    }
}

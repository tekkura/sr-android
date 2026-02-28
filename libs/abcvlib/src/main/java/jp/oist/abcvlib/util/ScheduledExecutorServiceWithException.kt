package jp.oist.abcvlib.util

import android.annotation.SuppressLint
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

class ScheduledExecutorServiceWithException(corePoolSize: Int, threadFactory: ThreadFactory) {
    private val executor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(corePoolSize, threadFactory)
    private val TAG: String = javaClass.simpleName

    @SuppressLint("DiscouragedApi")
    fun scheduleAtFixedRate(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
        val scheduledFuture = executor.scheduleAtFixedRate(
            command,
            initialDelay,
            delay,
            unit
        )
        catchErrors(scheduledFuture)
        return scheduledFuture
    }

    fun scheduleWithFixedDelay(
        command: Runnable,
        initialDelay: Long,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
        val scheduledFuture = executor.scheduleWithFixedDelay(
            command,
            initialDelay,
            delay,
            unit
        )
        catchErrors(scheduledFuture)
        return scheduledFuture
    }

    fun execute(command: Runnable) {
        val scheduledFuture = executor.schedule(command, 0, TimeUnit.MILLISECONDS)
        catchErrors(scheduledFuture)
    }

    fun schedule(
        command: Runnable,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*> {
        val scheduledFuture = executor.schedule(command, delay, unit)
        catchErrors(scheduledFuture)
        return scheduledFuture
    }

    private fun catchErrors(scheduledFuture: ScheduledFuture<*>) {
        Executors.newSingleThreadExecutor().execute {
            try {
                // System.out.println("before get()");
                scheduledFuture.get() // will return only if canceled
                // System.out.println("after get()");
            } catch (e: ExecutionException) {
                executor.shutdown()
                throw RuntimeException(e)
            } catch (e: InterruptedException) {
                // Logger.d(TAG, "Executor Interrupted or Cancelled", e);
            } catch (e: CancellationException) {
                // Logger.d(TAG, "Executor Interrupted or Cancelled", e);
            }
        }
    }

    fun shutdownNow() {
        executor.shutdownNow()
    }

    fun shutdown() {
        executor.shutdown()
    }
}

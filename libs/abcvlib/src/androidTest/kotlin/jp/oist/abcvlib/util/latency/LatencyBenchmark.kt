package jp.oist.abcvlib.util.latency

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.util.RealRobotSerialPort
import jp.oist.abcvlib.util.latency.BenchmarkClock
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import jp.oist.abcvlib.util.VirtualRobotPort
import jp.oist.abcvlib.util.rp2040.MockRP2040
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand
import junit.framework.TestCase.assertEquals
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue

@RunWith(AndroidJUnit4::class)
class LatencyBenchmark {

    private lateinit var simulator: MockRP2040
    private lateinit var usbSerial: UsbSerial
    private lateinit var commManager: LatencyMeasuringSerialCommManager
    private lateinit var publisherManager: PublisherManager
    private lateinit var wheelData: WheelData
    private lateinit var batteryData: BatteryData

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        val args = InstrumentationRegistry.getArguments()
        val useHardware = args.getString("useHardware")?.toBoolean() ?: false

        // Grant "All Files Access" to the test app specifically for this run
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val packageName = context.packageName
            InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .executeShellCommand("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
        }

        val listener = object : SerialReadyListener {
            override fun onSerialReady(usbSerial: UsbSerial) {}
        }

        publisherManager = PublisherManager()

        if (useHardware) {
            usbSerial = LatencyMeasuringUsbSerial(
                context = context,
                usbManager = usbManager,
                serialReadyListener = listener
            )
        } else {
            simulator = MockRP2040()
            val virtualPort = VirtualRobotPort(simulator)
            usbSerial = LatencyMeasuringUsbSerial(
                context = context,
                usbManager = usbManager,
                serialReadyListener = listener,
                port = virtualPort
            )
        }

        batteryData = BatteryData.Builder(context, publisherManager).build()
        wheelData = WheelData.Builder(context, publisherManager).build()

        commManager = LatencyMeasuringSerialCommManager(usbSerial, batteryData, wheelData)

        publisherManager.initializePublishers()
        publisherManager.startPublishers()
    }

    @After
    fun tearDown() {
        if (::commManager.isInitialized) {
            commManager.stop()
            commManager.onResultProcessed = null
        }
    }

    @Test
    fun runLatencyBenchmark() {
        val warmUpIterations = 100
        val measuredIterations = 10000
        val totalIterations = warmUpIterations + measuredIterations

        BenchmarkClock.setEnabled(true)
        commManager.start()

        Log.i("LatencyBenchmark", "Starting benchmark: $warmUpIterations warm-up, $measuredIterations measured.")

        val latchRef = AtomicReference<CountDownLatch>()

        // Use the new callback instead of WheelDataSubscriber
        commManager.onResultProcessed = {
            latchRef.get()?.countDown()
        }

        try {
            for (i in 0 until totalIterations) {
                val iterationLatch = CountDownLatch(1)
                latchRef.set(iterationLatch)

                BenchmarkClock.startIteration(i)

                // T1: Start
                val (left, right) = fromIteration(i)
                commManager.setMotorLevels(left, right)

                val success = iterationLatch.await(100, TimeUnit.MILLISECONDS)

                if (!success) {
                    Log.e("LatencyBenchmark", "Iteration $i timed out!")
                }
            }
        } finally {
            commManager.stop()
            commManager.onResultProcessed = null
        }

        val results = reportAndVerifyResults(warmUpIterations, measuredIterations)

        // Assertions for Automated Validation
        Assert.assertTrue(
            "Success rate too low: ${results.successRate}%",
            results.successRate > 95.0
        )
        Assert.assertTrue(
            "Mean RTT exceeds 20ms target: ${results.meanRtt}ms",
            results.meanRtt < 20.0
        )
        Assert.assertTrue("P95 RTT exceeds 30ms limit: ${results.p95Rtt}ms", results.p95Rtt < 30.0)
    }

    private data class BenchmarkSummary(val successRate: Double, val meanRtt: Double, val p95Rtt: Double)

    private fun reportAndVerifyResults(warmUp: Int, measured: Int): BenchmarkSummary {
        val results = BenchmarkClock.getResults()
        val successStates = BenchmarkClock.getSuccessStates()

        val metrics = mutableMapOf<String, MutableList<Double>>()
        val metricNames = listOf(
            "M1: Outbound Queueing",
            "M2: Handling/Serialization",
            "M3: Transport Out",
            "M4: Robot + Transit In",
            "M5: Buffer Processing",
            "M6: Wake-up Lag",
            "M7: App Logic",
            "Total RTT"
        )

        metricNames.forEach { metrics[it] = mutableListOf() }

        var successfulMeasuredCount = 0

        for (i in warmUp until (warmUp + measured)) {
            val isSuccess = successStates[i] ?: false
            if (!isSuccess) continue

            val ts = results[i] ?: continue
            // Discard results where any timestamp is missing (0L)
            if (ts.any { it == 0L }) continue

            successfulMeasuredCount++

            // Convert to milliseconds
            val m1 = (ts[1] - ts[0]) / 1_000_000.0
            val m2 = (ts[2] - ts[1]) / 1_000_000.0
            val m3 = (ts[3] - ts[2]) / 1_000_000.0
            val m4 = (ts[4] - ts[3]) / 1_000_000.0
            val m5 = (ts[5] - ts[4]) / 1_000_000.0
            val m6 = (ts[6] - ts[5]) / 1_000_000.0
            val m7 = (ts[7] - ts[6]) / 1_000_000.0
            val total = (ts[7] - ts[0]) / 1_000_000.0

            metrics[metricNames[0]]!!.add(m1)
            metrics[metricNames[1]]!!.add(m2)
            metrics[metricNames[2]]!!.add(m3)
            metrics[metricNames[3]]!!.add(m4)
            metrics[metricNames[4]]!!.add(m5)
            metrics[metricNames[5]]!!.add(m6)
            metrics[metricNames[6]]!!.add(m7)
            metrics["Total RTT"]!!.add(total)
        }

        val successRate = (successfulMeasuredCount.toDouble() / measured) * 100.0
        val rttValues = metrics["Total RTT"]!!.sorted()
        val meanRtt = if (rttValues.isNotEmpty()) rttValues.average() else Double.MAX_VALUE
        val p95Rtt = if (rttValues.isNotEmpty()) rttValues[(rttValues.size * 0.95).toInt()] else Double.MAX_VALUE

        val sb = StringBuilder()
        sb.append("### Benchmark Results ($measured iterations)\n\n")
        sb.append(String.format("Success Rate: %.2f%% (%d/%d)\n\n", successRate, successfulMeasuredCount, measured))
        sb.append("| Metric                     | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |\n")
        sb.append("|:---------------------------|:----------|:---------|:---------|:---------|\n")

        metricNames.forEach { name ->
            val values = metrics[name]!!.sorted()
            if (values.isNotEmpty()) {
                val mean = values.average()
                val min = values.first()
                val max = values.last()
                val p95 = values[(values.size * 0.95).toInt()]
                sb.append(
                    String.format(
                        "| %-26s | %-9.3f | %-8.3f | %-8.3f | %-8.3f |\n",
                        name,
                        mean,
                        min,
                        max,
                        p95
                    )
                )
            }
        }

        val output = sb.toString()
        Log.i("LatencyBenchmark", "\n$output")

        // Write to Public Download folder for easier access
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val file = File(downloadDir, "benchmark_results.md")
            file.writeText(output)
            Log.i("LatencyBenchmark", "Results saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("LatencyBenchmark", "Failed to write results to public storage", e)
        }

        return BenchmarkSummary(successRate, meanRtt, p95Rtt)
    }
}

package jp.oist.abcvlib.util

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber
import jp.oist.abcvlib.util.rp2040.MockRP2040
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LatencyBenchmark {

    private lateinit var simulator: MockRP2040
    private lateinit var usbSerial: UsbSerial
    private lateinit var commManager: SerialCommManager
    private lateinit var publisherManager: PublisherManager
    private lateinit var wheelData: WheelData
    private lateinit var batteryData: BatteryData

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        
        simulator = MockRP2040()
        val virtualPort = VirtualRobotPort(simulator)
        
        val listener = object : SerialReadyListener {
            override fun onSerialReady(usbSerial: UsbSerial) {}
        }
        
        usbSerial = UsbSerial(context, usbManager, listener, virtualPort)
        publisherManager = PublisherManager()
        
        batteryData = BatteryData.Builder(context, publisherManager).build()
        wheelData = WheelData.Builder(context, publisherManager).build()
        
        commManager = SerialCommManager(usbSerial, batteryData, wheelData)
        
        publisherManager.initializePublishers()
        publisherManager.startPublishers()
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
        
        // Register subscriber once outside the loop to minimize overhead
        val subscriber = object : WheelDataSubscriber {
            override fun onWheelDataUpdate(
                timestamp: Long, wheelCountL: Int, wheelCountR: Int,
                wheelDistanceL: Double, wheelDistanceR: Double,
                wheelSpeedInstantL: Double, wheelSpeedInstantR: Double,
                wheelSpeedBufferedL: Double, wheelSpeedBufferedR: Double,
                wheelSpeedExpAvgL: Double, wheelSpeedExpAvgR: Double
            ) {
                latchRef.get()?.countDown()
            }
        }
        wheelData.addSubscriber(subscriber)

        try {
            for (i in 0 until totalIterations) {
                val iterationLatch = CountDownLatch(1)
                latchRef.set(iterationLatch)

                BenchmarkClock.startIteration(i)
                
                // T1: Start
                commManager.setMotorLevels(0.1f, 0.1f, false, false)

                val success = iterationLatch.await(100, TimeUnit.MILLISECONDS)

                if (!success) {
                    Log.e("LatencyBenchmark", "Iteration $i timed out!")
                    // Even if it times out, we continue to the next iteration
                    // To avoid blocking the whole test if a few packets are lost.
                }
            }
        } finally {
            commManager.stop()
        }

        reportResults(warmUpIterations, measuredIterations)
    }

    private fun reportResults(warmUp: Int, measured: Int) {
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

        val sb = StringBuilder()
        sb.append("\n### Benchmark Results ($measured iterations)\n\n")
        sb.append(String.format("Success Rate: %.2f%% (%d/%d)\n\n", successRate, successfulMeasuredCount, measured))
        sb.append("| Metric | Mean (ms) | Min (ms) | Max (ms) | P95 (ms) |\n")
        sb.append("| :--- | :--- | :--- | :--- | :--- |\n")

        metricNames.forEach { name ->
            val values = metrics[name]!!.sorted()
            if (values.isNotEmpty()) {
                val mean = values.average()
                val min = values.first()
                val max = values.last()
                val p95 = values[(values.size * 0.95).toInt()]
                sb.append(String.format("| %s | %.3f | %.3f | %.3f | %.3f |\n", name, mean, min, max, p95))
            }
        }

        Log.i("LatencyBenchmark", sb.toString())
        
        // Print the table to stdout for easy capture by scripts
        println(sb.toString())
    }
}

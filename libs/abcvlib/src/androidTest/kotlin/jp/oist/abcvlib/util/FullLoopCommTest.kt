package jp.oist.abcvlib.util

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryDataSubscriber
import jp.oist.abcvlib.util.rp2040.MockRP2040
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FullLoopCommTest {

    private lateinit var simulator: MockRP2040
    private lateinit var usbSerial: UsbSerial
    private lateinit var commManager: SerialCommManager
    private lateinit var publisherManager: PublisherManager
    
    private lateinit var batteryData: BatteryData
    private lateinit var wheelData: WheelData

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        
        simulator = MockRP2040()
        val virtualPort = VirtualRobotPort(simulator)
        
        // Use a dummy listener as we don't need the callback in these specific tests
        val dummyListener = object : SerialReadyListener {
            override fun onSerialReady(usbSerial: UsbSerial) {}
        }
        
        usbSerial = UsbSerial(context, usbManager, dummyListener, virtualPort)
        publisherManager = PublisherManager()
        
        batteryData = BatteryData.Builder(context, publisherManager).build()
        wheelData = WheelData.Builder(context, publisherManager).build()
        
        commManager = SerialCommManager(usbSerial, batteryData, wheelData)
    }

    @After
    fun tearDown() {
        if (::commManager.isInitialized) {
            commManager.stop()
        }
    }

    @Test
    fun testSetMotorLevelsRoundTrip() {
        // 1. Prepare expectations in simulator
        simulator.batteryDetails.voltageMv = 4200
        
        val latch = CountDownLatch(1)
        var receivedVoltage = 0.0

        // 2. Subscribe to updates
        batteryData.addSubscriber(object : BatteryDataSubscriber {
            override fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
                receivedVoltage = voltage
                latch.countDown()
            }
            override fun onChargerVoltageUpdate(timestamp: Long, chargerVoltage: Double, coilVoltage: Double) {}
        })

        // Start publishers so updates are processed
        publisherManager.initializePublishers()
        publisherManager.startPublishers()

        // 3. Start the communication loop
        commManager.start()

        // 4. Trigger a command
        commManager.setMotorLevels(1.0f, -1.0f, false, false)
        
        // Wait for the update to propagate (the round trip includes: send -> simulate -> receive -> parse -> publish)
        // Increased timeout slightly for reliable test execution on slower devices/emulators
        assertTrue("Timed out waiting for battery update", latch.await(200, TimeUnit.MILLISECONDS))
        
        // 5. Verify results
        assertEquals(4.2, receivedVoltage, 0.01)
        
        // Check that the simulator received the command
        // Note: the conversion logic results in specific bytes.
        // For 1.0f -> -5.06V -> 0xF9
        // For -1.0f -> 5.06V -> 0xFA
        assertEquals(0xF9.toByte(), simulator.motorsState.controlValues.left)
        assertEquals(0xFA.toByte(), simulator.motorsState.controlValues.right)
    }

    @Test
    fun testGetLogRoundTrip() {
        publisherManager.initializePublishers()

        val testLog = "Hello from RP2040"
        simulator.logEntries.add(testLog)
        
        val latch = CountDownLatch(1)
        simulator.onCommandProcessed = { type ->
            if (type == AndroidToRP2040Command.GET_LOG) {
                latch.countDown()
            }
        }

        commManager.start()
        commManager.getLog()
        
        // Wait for the simulator to process the log request
        assertTrue("Timed out waiting for log request to be processed", latch.await(200000, TimeUnit.MILLISECONDS))
        
        assertTrue("Simulator logs should be cleared after being read", simulator.logEntries.isEmpty())
    }
}

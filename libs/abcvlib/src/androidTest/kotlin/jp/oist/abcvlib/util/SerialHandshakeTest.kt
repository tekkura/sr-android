package jp.oist.abcvlib.util

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hoho.android.usbserial.util.SerialInputOutputManager
import jp.oist.abcvlib.util.rp2040.RP2040Command
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.versioning.FirmwareCompatibilityException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SerialHandshakeTest {
    private val managers = mutableListOf<TestManager>()
    private val releases = mutableListOf<CountDownLatch>()

    @After
    fun stopManagers() {
        releases.forEach { it.countDown() }
        managers.forEach { it.stop() }
    }

    @Test
    fun responseReceivedBeforeWaitIsConsumedImmediately() {
        val serial = serial(TestPort())
        serial.onNewData(version())
        assertEquals(1, serial.awaitPacketReceived(0))
        assertTrue(serial.fifoQueue.poll() is RP2040IncomingCommand.GetVersion)
        assertEquals(-1, serial.awaitPacketReceived(0))
    }

    @Test
    fun malformedVersionBeforeWaitIsReportedAndCleared() {
        val serial = serial(TestPort())
        val malformed = object : RP2040Command() {
            override val type = AndroidToRP2040Command.GET_VERSION
            override fun serializeData() = byteArrayOf(1, 2)
        }
        serial.onNewData(malformed.toBytes())
        assertThrows(FirmwareCompatibilityException::class.java) {
            serial.awaitPacketReceived(0)
        }
        assertEquals(-1, serial.awaitPacketReceived(0))
    }

    @Test
    fun readinessAndWriterWaitForSupportedVersionAndRunOnce() {
        val requested = CountDownLatch(1)
        val port = TestPort { requested.countDown(); null }
        val manager = manager(serial(port))
        val ready = AtomicInteger()
        manager.start(onReady = { ready.incrementAndGet() })
        await(requested)
        assertEquals(0, ready.get())
        assertEquals(0, manager.writerStarts.get())

        port.reply(version())
        await(manager.writerStarted)
        assertEquals(1, ready.get())
        manager.start(onReady = { ready.incrementAndGet() })
        assertEquals(1, ready.get())
        assertEquals(1, manager.writerStarts.get())
    }

    @Test
    fun immediateVersionResponseStartsTheWriter() {
        val manager = manager(serial(TestPort { version() }))
        val ready = CountDownLatch(1)
        manager.start(onReady = { ready.countDown() })
        await(ready)
        await(manager.writerStarted)
    }

    @Test
    fun unsupportedVersionDoesNotStartOutputsOrWriter() {
        assertFailedHandshake(TestPort { RP2040IncomingCommand.GetVersion(9, 0, 0).toBytes() })
    }

    @Test
    fun timeoutDoesNotStartOutputsOrWriter() {
        assertFailedHandshake(TestPort())
    }

    @Test
    fun virtualPortStillStartsWithoutVersionRequest() {
        val writes = AtomicInteger()
        val manager = manager(serial(TestPort { writes.incrementAndGet(); null }, real = false))
        val ready = CountDownLatch(1)
        manager.start(onReady = { ready.countDown() })
        await(ready)
        await(manager.writerStarted)
        assertEquals(0, writes.get())
    }

    @Test
    fun stoppedHandshakeCannotStartOutputsFromLateResponse() {
        val entered = CountDownLatch(1)
        val release = releaseLatch()
        val returned = CountDownLatch(1)
        val manager = manager(serial(TestPort {
            entered.countDown()
            awaitUninterruptibly(release)
            returned.countDown()
            version()
        }))
        val ready = CountDownLatch(1)
        manager.start(onReady = { ready.countDown() })
        await(entered)
        manager.stop()
        release.countDown()
        await(returned)
        assertFalse(ready.await(200, TimeUnit.MILLISECONDS))
        assertEquals(0, manager.writerStarts.get())
    }

    @Test
    fun oldSendFailureCannotStopRestartedHandshake() {
        val entered = CountDownLatch(1)
        val release = releaseLatch()
        val oldReturned = CountDownLatch(1)
        val writes = AtomicInteger()
        val manager = manager(serial(TestPort {
            if (writes.incrementAndGet() == 1) {
                entered.countDown()
                awaitUninterruptibly(release)
                oldReturned.countDown()
                throw IOException("late failure from stopped run")
            }
            version()
        }))
        val failures = CountDownLatch(1)
        val oldReady = AtomicInteger()
        manager.setFirmwareCompatibilityFailureListener { failures.countDown() }
        manager.start(onReady = { oldReady.incrementAndGet() })
        await(entered)
        manager.stop()

        val ready = CountDownLatch(1)
        manager.start(onReady = { ready.countDown() })
        await(ready)
        await(manager.writerStarted)
        release.countDown()
        await(oldReturned)
        assertFalse(failures.await(200, TimeUnit.MILLISECONDS))
        assertEquals(0, oldReady.get())
        assertEquals(1, manager.writerStarts.get())
    }

    private fun assertFailedHandshake(port: TestPort) {
        val manager = manager(serial(port))
        val ready = AtomicInteger()
        val failed = CountDownLatch(1)
        manager.setFirmwareCompatibilityFailureListener { failed.countDown() }
        manager.start(onReady = { ready.incrementAndGet() })
        await(failed)
        assertEquals(0, ready.get())
        assertEquals(0, manager.writerStarts.get())
    }

    private fun serial(port: TestPort, real: Boolean = true): UsbSerial {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return object : UsbSerial(context, usbManager, object : SerialReadyListener {
            override fun onSerialReady(usbSerial: UsbSerial) = Unit
        }, port) {
            override val isPortReal get() = real
        }
    }

    private fun manager(serial: UsbSerial) = TestManager(serial).also { managers.add(it) }
    private fun version() = RP2040IncomingCommand.GetVersion(1, 2, 0).toBytes()
    private fun releaseLatch() = CountDownLatch(1).also { releases.add(it) }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        // Emulate an in-flight USB operation which completes after cancellation.
        while (true) {
            try {
                check(latch.await(5, TimeUnit.SECONDS)) { "Test did not release the blocked write" }
                return
            } catch (_: InterruptedException) {
                // The test deliberately completes the old operation after stop().
            }
        }
    }

    private class TestManager(serial: UsbSerial) : SerialCommManager(serial) {
        val writerStarts = AtomicInteger()
        val writerStarted = CountDownLatch(1)
        override fun buildAndroid2PiWriter(context: RunContext) = Runnable {
            writerStarts.incrementAndGet()
            writerStarted.countDown()
        }
    }

    private class TestPort(private val response: () -> ByteArray? = { null }) : RobotSerialPort {
        private lateinit var listener: SerialInputOutputManager.Listener
        fun reply(bytes: ByteArray) = listener.onNewData(bytes)
        override fun write(data: ByteArray, timeout: Int) { response()?.let { reply(it) } }
        override fun startReading(listener: SerialInputOutputManager.Listener) { this.listener = listener }
        override fun open(connection: UsbDeviceConnection?) = Unit
        override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) = Unit
        override fun setDtr(value: Boolean) = Unit
        override fun close() = Unit
        override fun stopReading() = Unit
    }
}

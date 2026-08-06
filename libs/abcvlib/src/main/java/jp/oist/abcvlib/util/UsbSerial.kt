package jp.oist.abcvlib.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import jp.oist.abcvlib.util.ByteArrayExtensions.toHexString
import jp.oist.abcvlib.util.ErrorHandler.eLog
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.apache.commons.collections4.queue.CircularFifoQueue
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

open class UsbSerial @Throws(IOException::class) constructor(
    private val context: Context,
    private val usbManager: UsbManager,
    private val serialReadyListener: SerialReadyListener,
    port: RobotSerialPort? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val initialConnectionErrorListener: ((IOException) -> Unit)? = null
) : SerialInputOutputManager.Listener {

    private var _port: RobotSerialPort? = null
    private val stateLock = Any()
    private var isOpening = false
    private var usbReceiver: BroadcastReceiver? = null
    private var connectJob: Job? = null
    @Volatile
    private var isClosed = false

    private val timeout: Int = 1000 //1s
    private var badPacketCount = 0

    internal val fifoQueue: CircularFifoQueue<RP2040IncomingCommand> = CircularFifoQueue<RP2040IncomingCommand>(256)
    private val packetBuffer = PacketBuffer()

    companion object {
        private const val TAG = "UsbSerial"

        private val lock = ReentrantLock()
        private val packetReceived: Condition = lock.newCondition()

        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
        private const val BAD_PACKET_THRESHOLD = 5
    }

    init {
        if (port != null) {
            // If a port is provided (e.g. for testing), we assume it's already "found"
            // and we notify the listener immediately to ensure it's ready for the test.
            synchronized(stateLock) {
                _port = port
            }
            port.startReading(this)
            serialReadyListener.onSerialReady(this)
        } else {
            // Registration is UI-safe. Preserve the constructor's automatic startup behavior,
            // but perform enumeration and connection on the IO dispatcher.
            val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            }
            val receiver = MyBroadcastReceiver()
            usbReceiver = receiver
            ContextCompat.registerReceiver(context, receiver, filter, RECEIVER_NOT_EXPORTED)
            connectJob = coroutineScope.launch(Dispatchers.IO) {
                try {
                    connect()
                } catch (e: IOException) {
                    Logger.e(TAG, "Initial USB connection failed: ${e.localizedMessage}")
                    initialConnectionErrorListener?.invoke(e)
                }
            }
        }
    }

    /** Enumerates devices and opens the selected port. Call from a background dispatcher. */
    @Throws(IOException::class)
    suspend fun connect() = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (isClosed) return@withContext

        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            throw IOException("No USB devices found")
        }

        for (device in deviceList.values) {
            if (isSupportedDevice(device)) {
                Logger.i(Thread.currentThread().name, "Found a supported USB device. Connecting...")
                connect(device)
                return@withContext
            }
        }
        throw IOException("No supported USB devices found")
    }

    private fun isSupportedDevice(device: UsbDevice): Boolean =
        device.manufacturerName == "Seeed" && device.productName == "Seeeduino XIAO" ||
            device.manufacturerName == "Raspberry Pi" &&
            (device.productName == "Pico Test Device" || device.productName == "Pico")

    @Throws(IOException::class)
    private suspend fun connect(device: UsbDevice) {
        currentCoroutineContext().ensureActive()
        if (usbManager.hasPermission(device)) {
            Logger.i(Thread.currentThread().name, "Has permission to connect to device")
            val connection = usbManager.openDevice(device)
                ?: throw IOException("Failed to open USB device connection")
            openPort(connection)
        } else {
            Logger.i(Thread.currentThread().name, "Requesting permission to connect to device")
            // Make the permission intent mutable for Android 12 and above
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                0
            }
            val usbPermissionIntent: Intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val permissionIntent =
                PendingIntent.getBroadcast(context, 0, usbPermissionIntent, flags)

            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private suspend fun openPort(connection: UsbDeviceConnection) {
        synchronized(stateLock) {
            if (isClosed || _port != null || isOpening) {
                connection.close()
                return
            }
            isOpening = true
        }

        try {
            openPortInternal(connection)
        } finally {
            synchronized(stateLock) {
                isOpening = false
            }
        }
    }

    private suspend fun openPortInternal(connection: UsbDeviceConnection) {
        Logger.i(Thread.currentThread().name, "Opening port")
        val driver = getDriver()
        val usbSerialPort = driver.ports[0] // Most devices have just one port (port 0)
        val realPort = RealRobotSerialPort(usbSerialPort)
        try {
            realPort.open(connection)
            realPort.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE)
            realPort.setDtr(true)
            
            synchronized(stateLock) {
                if (isClosed) {
                    realPort.close()
                    return
                }
                _port = realPort
            }
            
            // Adding this as there doesn't appear to be any call back in the usbIoManager that
            // will call onSerialReady after initialization. As it stands, there were things occurring
            // in onSerialReady that were being executed before the usbIoManager was initialized.
            delay(100)
            synchronized(stateLock) {
                if (isClosed || _port !== realPort) {
                    if (_port === realPort) _port = null
                    realPort.close()
                    return
                }
                realPort.startReading(this)
            }
            serialReadyListener.onSerialReady(this)
        } catch (e: IOException) {
            synchronized(stateLock) {
                if (_port === realPort) _port = null
            }
            try {
                realPort.close()
            } catch (_: IOException) {
                // Preserve the original connection error.
            }
            e.printStackTrace()
        } catch (e: CancellationException) {
            synchronized(stateLock) {
                if (_port === realPort) _port = null
            }
            try {
                realPort.close()
            } catch (_: IOException) {
                // Preserve cancellation while still attempting cleanup.
            }
            throw e
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    private fun getDriver(): UsbSerialDriver {
        val customTable = ProbeTable().apply {
            addProduct(0x2886, 0x802F, CdcAcmSerialDriver::class.java) // Seeeduino XIAO
            addProduct(11914, 10, CdcAcmSerialDriver::class.java) // Raspberry Pi Pico
            addProduct(0x0000, 0x0001, CdcAcmSerialDriver::class.java) // Custom Raspberry Pi Pico
        }
        val prober = UsbSerialProber(customTable)
        val availableDrivers = prober.findAllDrivers(usbManager)
        if (availableDrivers.isEmpty()) {
            eLog("Serial", "No USB Serial drivers found", Exception(), true)
        }
        return availableDrivers[0]
    }

    override fun onNewData(data: ByteArray) {
        // TODO I feel this should be executed in a separate thread otherwise the
        // SerialInputOutputManager thread may be delayed and miss data

        // print the byte[] as an array of hex values
        Logger.d(TAG, "onNewData Received: ${data.toHexString()}")

        // Run the packet verification in a separate thread
        try {
            lock.lock()
            packetBuffer.consume(
                bytes = data,
                onResult = { result ->
                    when (result) {
                        is PacketBuffer.ParseResult.NotEnoughData -> {
                            Logger.d(TAG, "Incomplete Packet. Waiting for more data")
                        }

                        is PacketBuffer.ParseResult.Overflow -> {
                            onBadPacket()
                            Logger.d(TAG, "Buffer overflow.")
                        }

                        is PacketBuffer.ParseResult.ReceivedErrorPacket -> {
                            onBadPacket()
                            Logger.d(TAG, "Error packet received")
                            Logger.d(TAG, "packetReceived.signal()")
                            packetReceived.signal()
                        }

                        is PacketBuffer.ParseResult.ReceivedPacket -> {
                            onCompletePacketReceived(result.command)

                            Logger.d(TAG, "Packet verified")
                            Logger.d(TAG, "packetReceived.signal()")
                            packetReceived.signal()
                        }
                    }
                }
            )
        } catch (e: IOException) {
            throw RuntimeException(e)
        } finally {
            lock.unlock()
        }
    }

    @Throws(IOException::class)
    internal fun send(command: RP2040OutgoingCommand, timeout: Int) =
        send(command.toBytes(), timeout)

    @Throws(IOException::class)
    internal open fun send(packet: ByteArray, timeout: Int) {
        val port = synchronized(stateLock) { _port }
            ?: throw IOException("Port is not open")
        port.write(packet, timeout)
        Logger.i(Thread.currentThread().name, "send()")
    }

    /**
     * Blocks until a response is received
     */
    internal fun awaitPacketReceived(timeout: Int): Int {
        var returnVal = -1
        // Wait until packet is available
        lock.lock()
        try {
            if (!packetReceived.await(timeout.toLong(), TimeUnit.MILLISECONDS)) {
                // throw new RuntimeException("SerialTimeoutException on send. The serial connection " +
                // "with the rp2040 is not working as expected and timed out");
            } else {
                Logger.d(
                    Thread.currentThread().name,
                    "packetReceived.await() completed. Packet received from rp2040"
                )
                returnVal = 1
            }
        } catch (e: InterruptedException) {
            e.printStackTrace()
        } finally {
            lock.unlock()
        }
        return returnVal
    }

    /**
     * @throws IOException if fifoQueue is full
     */
    @Throws(IOException::class)
    protected open fun onCompletePacketReceived(command: RP2040IncomingCommand) {
        synchronized(fifoQueue) {
            if (fifoQueue.isAtFullCapacity) {
                Logger.e("serial", "fifoQueue is full")
                throw RuntimeException("fifoQueue is full")
            } else {
                Logger.d("verifyPacket", "Adding Packet: ${command.toBytes().toHexString()} to fifoQueue")
                fifoQueue.add(command)
            }
        }

        badPacketCount = 0 // Reset on successful packet
    }

    protected open fun onBadPacket() {
        Logger.e("serial", "Bad packet received. Clearing buffer and sending next command.")
        // Ignore this packet as it is corrupted by some other data being sent between.
        badPacketCount++
        if (badPacketCount >= BAD_PACKET_THRESHOLD) {
            Logger.e(
                "serial",
                "Too many consecutive bad packets. Consider resetting connection or notifying user."
            )
            // Optionally: trigger recovery, e.g., re-initialize serial connection or notify user
            // recoverSerialConnection();
            // For now, just reset the counter
            badPacketCount = 0
        }
    }

    override fun onRunError(e: Exception) {
        Logger.e("serial", "error: " + e.localizedMessage)
        e.printStackTrace()
    }

    internal fun close() {
        val port = synchronized(stateLock) {
            isClosed = true
            _port.also { _port = null }
        }
        connectJob?.cancel()
        try {
            port?.close()
        } catch (e: IOException) {
            Logger.e(TAG, "Failed to close USB port: ${e.localizedMessage}")
        }
        usbReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Receiver was already unregistered.
            }
        }
        usbReceiver = null
    }

    private inner class MyBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(
                intent,
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java
            )
            val action = intent.action
            if (device != null && isSupportedDevice(device) &&
                (ACTION_USB_PERMISSION == action || UsbManager.ACTION_USB_DEVICE_ATTACHED == action)
            ) {
                if (ACTION_USB_PERMISSION != action ||
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                ) {
                    connectJob?.cancel()
                    connectJob = coroutineScope.launch(Dispatchers.IO) {
                        try {
                            connect(device)
                        } catch (e: IOException) {
                            Logger.e(TAG, "USB connection failed: ${e.localizedMessage}")
                        }
                    }
                } else {
                    Logger.d("serial", "permission denied for device $device")
                }
            } else {
                Logger.d("serial", "permission denied for device $device")
            }
        }
    }
}

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
import jp.oist.abcvlib.util.AndroidToRP2040Command.Companion.getEnumByValue
import jp.oist.abcvlib.util.ErrorHandler.eLog
import org.apache.commons.collections4.queue.CircularFifoQueue
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.text.clear

class UsbSerial @Throws(IOException::class) constructor(
    private val context: Context,
    private val usbManager: UsbManager,
    private val serialReadyListener: SerialReadyListener
) : SerialInputOutputManager.Listener {
    private lateinit var port: UsbSerialPort

    private val timeout: Int = 1000 //1s
    private val totalBytesRead: Int = 0 // Track total bytes read
    private val pwm = floatArrayOf(1.0f, 0.5f, 0.0f, -0.5f, -1.0f)
    private val cnt = 0
    private var badPacketCount = 0

    internal val fifoQueue: CircularFifoQueue<FifoQueuePair> = CircularFifoQueue<FifoQueuePair>(256)
    private val packetBuffer = PacketBuffer()

    companion object {
        private const val TAG = "UsbSerial"

        private val lock = ReentrantLock()
        private val packetReceived: Condition = lock.newCondition()

        // 1024 + 3 for start, command, and stop markers sent as putchar. (i.e. they won't be optimized anyway).
        private const val RP2020_PACKET_SIZE_LOG = 1027
        private const val ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION"
        private const val BAD_PACKET_THRESHOLD = 5

    }

    init {
        // Find all available drivers from attached devices.
        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            throw IOException("No USB devices found")
        }

        for (d in deviceList.values) {
            if (d.manufacturerName == "Seeed" && d.productName == "Seeeduino XIAO") {
                Logger.i(Thread.currentThread().name, "Found a XIAO. Connecting...")
                connect(d)
            } else if (d.manufacturerName.equals("Raspberry Pi") && d.productName.equals("Pico Test Device")) {
                Logger.i(Thread.currentThread().name, "Found a Pico Test Device. Connecting...")
                connect(d)
            } else if (d.manufacturerName == "Raspberry Pi" && d.productName == "Pico") {
                Logger.i(Thread.currentThread().name, "Found a Pi. Connecting...")
                connect(d)
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        val usbReceiver: BroadcastReceiver = MyBroadcastReceiver()
        ContextCompat.registerReceiver(context, usbReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    @Throws(IOException::class)
    private fun connect(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            Logger.i(Thread.currentThread().name, "Has permission to connect to device")
            val connection: UsbDeviceConnection = usbManager.openDevice(device)
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

    private fun openPort(connection: UsbDeviceConnection) {
        Logger.i(Thread.currentThread().name, "Opening port")
        val driver = getDriver()
        val port = driver.ports[0] // Most devices have just one port (port 0)
        try {
            port.open(connection)
            port.setParameters(115200, 8, 1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            this.port = port
            val usbIoManager = SerialInputOutputManager(port, this)
            // Adding this as there doesn't appear to be any call back in the usbIoManager that
            // will call onSerialReady after initialization. As it stands, there were things occurring
            // in onSerialReady that were being executed before the usbIoManager was initialized.
            Thread.sleep(100)
            usbIoManager.start()
            serialReadyListener.onSerialReady(this)
        } catch (e: IOException) {
            e.printStackTrace()
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

        val sb = StringBuilder()
        for (b in data) {
            sb.append(String.format("%02X ", b))
        }
        Logger.d(TAG, "onNewData Received: $sb")

        // Run the packet verification in a separate thread
        try {
            lock.lock()
            if (verifyPacket(data)) {
                Logger.d(TAG, "Packet verified")
                Logger.d(TAG, "packetReceived.signal()")
                packetReceived.signal()
            } else {
                Logger.d(TAG, "Incomplete Packet. Waiting for more data")
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        } finally {
            lock.unlock()
        }
    }

    @Throws(IOException::class)
    internal fun send(packet: ByteArray, timeout: Int) {
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
     * synchronized as newData might be called again while this method is running
     * @param bytes to be verified to contain a valid packet. If so, the packet is added to the fifoQueue
     * @return 0 if successfully found a complete packet or found an erroneous packet,
     * -1 if not enough data to determine
     * @throws IOException if fifoQueue is full
     */
    @Synchronized
    @Throws(IOException::class)
    internal fun verifyPacket(bytes: ByteArray): Boolean {
        // This function is a mess
        if (!packetBuffer.put(bytes)) {
            Logger.e("verifyPacket", "Buffer overflow risk: clearing buffer and resynchronizing.")
            packetBuffer.clear()
            onBadPacket()
            return false
        }

        // If startIdx not yet found, find it
        if (packetBuffer.startStopIdx.startIdx < 0)
            packetBuffer.startStopIndexSearch()

        // If startIdx still not found, return false
        if (packetBuffer.startStopIdx.startIdx < 0) {
            Logger.v("serial", "StartIdx not yet found. Waiting for more data")
            return false
        } else {
            Logger.v("serial", "StartIdx found at " + packetBuffer.startStopIdx.startIdx)
            // if packetType not yet found, find it
            if (packetBuffer.packetType == AndroidToRP2040Command.NACK) {
                try {
                    packetBuffer.initializePacket()
                } catch (e: PacketBuffer.NoDataException) {
                    Logger.v("serial", "Nothing other than startIdx found. Waiting for more data")
                    return false
                } catch (e: PacketBuffer.InvalidPacketTypeException) {
                    Logger.e("serial", "IndexOutOfBoundsException: " + e.message)
                    Logger.e("serial", "Unknown packetType: ${e.packetTypeByte}")
                    // Ignore this packet as it is corrupted by some other data being sent between.
                    packetBuffer.clear()
                    return true
                } catch (e: PacketBuffer.InvalidPacketSizeException) {
                    Logger.e(
                        "verifyPacket",
                        "Unreasonable packet size: ${e.packetSize}. Resynchronizing."
                    )

                    onBadPacket()
                    return true
                }
            }

            // Check if you have enough data for a full packet before processing anything
            // +1 for moving 1 past packetBuffer.position() to endIdx byte
            packetBuffer.findStopIndex()
            // +1 for packetBuffer.position() needing to be 1 after endIdx byte to indicate that you have the stopIdx byte
            try {
                packetBuffer.checkPacketDataAvailability()
            } catch (e: PacketBuffer.NotEnoughDataForTypeException) {
                Logger.d(
                    "verifyPacket", "Data received not yet enough to fill " +
                            e.packetType + " packetType. Waiting for more data"
                )
                Logger.d(
                    "verifyPacket",
                    e.bytesGiven.toString() +
                            " bytes recvd thus far. Require " +
                            e.bytesExpected.toString()
                )

                return false
            } catch (e: PacketBuffer.StopMarkerNotFoundException) {
                onBadPacket()
                return true
            }

            when (packetBuffer.packetType) {
                AndroidToRP2040Command.GET_LOG -> {
                    Logger.i("verifyPacket", "GET_LOG command received")
                }

                AndroidToRP2040Command.SET_MOTOR_LEVELS -> {
                    Logger.i("verifyPacket", "SET_MOTOR_LEVELS command received")
                }

                AndroidToRP2040Command.GET_STATE -> {
                    Logger.i("verifyPacket", "GET_STATE command received")
                }

                else -> {
                    Logger.e("verifyPacket", "Unknown packetType: ${packetBuffer.packetType}")
                    onBadPacket()
                    return true
                }
            }
            onCompletePacketReceived()
            return true
        }
    }

    private fun onCompletePacketReceived() {
        val currentCommand = packetBuffer.getCurrentCommandByteArray()
        packetBuffer.clear()
        synchronized(fifoQueue) {
            if (fifoQueue.isAtFullCapacity) {
                Logger.e("serial", "fifoQueue is full")
                throw RuntimeException("fifoQueue is full")
            } else {
                // Log partialArray as array of hex values
                val sb = StringBuilder()
                for (b in currentCommand) {
                    sb.append(String.format("%02X ", b))
                }
                Logger.d("verifyPacket", "Adding Packet: $sb to fifoQueue")
                val fifoQueuePair = FifoQueuePair(
                    packetBuffer.packetType,
                    currentCommand
                )

                fifoQueue.add(fifoQueuePair) // Add the partialArray to the queue
            }
        }
        // reset to default value;
        packetBuffer.packetType = AndroidToRP2040Command.NACK
        badPacketCount = 0 // Reset on successful packet
    }

    private fun onBadPacket() {
        Logger.e("serial", "Bad packet received. Clearing buffer and sending next command.")
        // Ignore this packet as it is corrupted by some other data being sent between.
        packetBuffer.clear()
        packetBuffer.resyncToNextStartMarker()
        // reset to default value;
        packetBuffer.packetType = AndroidToRP2040Command.NACK
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

    private inner class MyBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(
                intent,
                UsbManager.EXTRA_DEVICE,
                UsbDevice::class.java
            )
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                val action = intent.action
                if (ACTION_USB_PERMISSION == action) {
                    synchronized(this) {
                        try {
                            connect(device!!)
                        } catch (e: IOException) {
                            e.printStackTrace()
                        }
                    }
                }
            } else {
                Logger.d("serial", "permission denied for device $device")
            }
        }
    }
}

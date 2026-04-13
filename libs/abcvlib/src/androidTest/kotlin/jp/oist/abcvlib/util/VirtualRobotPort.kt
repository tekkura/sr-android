package jp.oist.abcvlib.util

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.util.SerialInputOutputManager
import jp.oist.abcvlib.util.rp2040.MockRP2040
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * A virtual implementation of RobotSerialPort for testing.
 * It connects production code directly to the MockRP2040 firmware simulator.
 */
internal class VirtualRobotPort(private val simulator: MockRP2040) : RobotSerialPort {

    private var listener: SerialInputOutputManager.Listener? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override fun open(connection: UsbDeviceConnection?) {
        // No-op for virtual port
    }

    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        // No-op for virtual port
    }

    override fun setDtr(value: Boolean) {
        // No-op for virtual port
    }

    @Throws(IOException::class)
    override fun write(data: ByteArray, timeout: Int) {
        coroutineScope.launch {
            // 1. Production code writes bytes to the "port"
            // 2. The virtual port passes them to the firmware simulator
            val response = simulator.processPacket(data)

            // 3. If the simulator generates a response, we feed it back via the listener
            response?.let {
                listener?.onNewData(it)
            }
        }
    }

    override fun close() {
        stopReading()
    }

    override fun startReading(listener: SerialInputOutputManager.Listener) {
        this.listener = listener
    }

    override fun stopReading() {
        this.listener = null
    }
}

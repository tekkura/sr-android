package jp.oist.abcvlib.util

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

/**
 * Production implementation of RobotSerialPort that wraps a physical UsbSerialPort
 * from the hoho.android.usbserial library.
 */
class RealRobotSerialPort(private val usbSerialPort: UsbSerialPort) : RobotSerialPort {
    
    private var ioManager: SerialInputOutputManager? = null

    @Throws(IOException::class)
    override fun open(connection: UsbDeviceConnection?) {
        usbSerialPort.open(connection)
    }

    @Throws(IOException::class)
    override fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int) {
        usbSerialPort.setParameters(baudRate, dataBits, stopBits, parity)
    }

    @Throws(IOException::class)
    override fun setDtr(value: Boolean) {
        usbSerialPort.dtr = value
    }

    @Throws(IOException::class)
    override fun write(data: ByteArray, timeout: Int) {
        usbSerialPort.write(data, timeout)
    }

    @Throws(IOException::class)
    override fun close() {
        stopReading()
        usbSerialPort.close()
    }

    override fun startReading(listener: SerialInputOutputManager.Listener) {
        ioManager = SerialInputOutputManager(usbSerialPort, listener)
        ioManager?.start()
    }

    override fun stopReading() {
        ioManager?.stop()
        ioManager = null
    }
}

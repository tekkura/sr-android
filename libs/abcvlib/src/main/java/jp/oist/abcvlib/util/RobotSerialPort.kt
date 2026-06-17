package jp.oist.abcvlib.util

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

/**
 * Interface representing a serial port for robot communication.
 * This abstraction allows swapping physical USB hardware with a virtual implementation for testing.
 */
interface RobotSerialPort {
    /**
     * Opens the serial port.
     */
    @Throws(IOException::class)
    fun open(connection: UsbDeviceConnection?)

    /**
     * Sets the serial port parameters.
     */
    @Throws(IOException::class)
    fun setParameters(baudRate: Int, dataBits: Int, stopBits: Int, parity: Int)

    /**
     * Sets the DTR (Data Terminal Ready) signal.
     */
    @Throws(IOException::class)
    fun setDtr(value: Boolean)

    /**
     * Writes data to the serial port.
     */
    @Throws(IOException::class)
    fun write(data: ByteArray, timeout: Int)

    /**
     * Closes the serial port.
     */
    @Throws(IOException::class)
    fun close()

    /**
     * Starts the background reading process.
     */
    fun startReading(listener: SerialInputOutputManager.Listener)

    /**
     * Stops the background reading process.
     */
    fun stopReading()
}

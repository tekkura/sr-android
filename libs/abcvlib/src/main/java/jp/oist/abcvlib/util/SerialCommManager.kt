package jp.oist.abcvlib.util

import com.hoho.android.usbserial.driver.SerialTimeoutException
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.util.HexBinConverters.bytesToHex
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand
import jp.oist.abcvlib.util.rp2040.RP2040State
import jp.oist.abcvlib.util.rp2040.StatusCommand
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Class to manage request-response patter between Android phone and USB Serial connection
 * over a separate thread.
 */
class SerialCommManager @JvmOverloads constructor(
    private val usbSerial: UsbSerial,
    batteryData: BatteryData? = null,
    wheelData: WheelData? = null
) {
    private val rp2040State: RP2040State?

    private var shutdown = false
    private var command: RP2040OutgoingCommand? = null
    private val commandLock = Object()

    private var startTimeAndroid: Long = 0
    private var cnt: Int = 0
    private var durationAndroid: Long = 0

    // Constructor to initialize SerialCommManager
    init {
        if (batteryData == null || wheelData == null) {
            Logger.w(
                "serial", "batteryData or wheelData was null. " +
                        "Ignoring all rp2040 state values. You must initialize both to use rp2040 state"
            )
            rp2040State = null
        } else {
            rp2040State = RP2040State(batteryData, wheelData)
        }
    }


    private val android2PiWriter: Runnable = Runnable {
        startTimeAndroid = System.nanoTime()
        while (!shutdown) {
            synchronized(commandLock) {
                try {
                    // this results in getState commands every 10ms unless another command
                    // (e.g. setMotorLevels) is set, which case wait will return immediately
                    commandLock.wait(10)
                    
                    // T2: Writer Loop Wake-up
                    BenchmarkClock.mark(2)

                    if (command == null) {
                        command = RP2040OutgoingCommand.GetState()
                    }
                    sendCommand(command!!)
                    command = null
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
            cnt++
            if (cnt == 100) {
                durationAndroid = (System.nanoTime() - startTimeAndroid) / 100
                cnt = 0
                startTimeAndroid = System.nanoTime()
                // convert from nanoseconds to microseconds
                Logger.e(
                    "AndroidSide",
                    "Average time per command: " + durationAndroid / 1000 + "us"
                )
            }
        }
    }


    // Start method to start the thread
    fun start() {
        shutdown = false
        val thread = Thread(android2PiWriter, "SerialCommManager_Android2Pi")
        thread.priority = Thread.MAX_PRIORITY
        thread.start()
    }

    fun stop() {
        shutdown = true
        synchronized(commandLock) {
            commandLock.notifyAll()
        }
    }

    //TODO paseFifoPacket() should call the various SerialResponseListener methods.
    internal fun parseFifoPacket() {
        var result = 0
        val command: RP2040IncomingCommand?
        var packet: ByteArray?
        // Run the command on a thread handler to allow the queue to keep being added to
        synchronized(usbSerial.fifoQueue) {
            command = usbSerial.fifoQueue.poll()
        }
        // Check if there is a packet in the queue (fifoQueue
        if (command != null) {
            // Log packet as an array of hex bytes
            Logger.i(Thread.currentThread().name, "Received packet: ${bytesToHex(command.toBytes())}")

            // The first byte after the start mark is the command
            Logger.i(Thread.currentThread().name, "Received ${command.type} from pi")

            when (command) {
                is RP2040IncomingCommand.GetLog -> {
                    parseLog(command)
                    result = 1
                }

                // Covers SetMotorState, ResetState and GetState commands
                is StatusCommand -> {
                    parseStatus(command)
                    result = 1
                }

                is RP2040IncomingCommand.Nack -> {
                    onNack(command.data)
                    Logger.w("Pi2AndroidReader", "Nack issued from device")
                    result = -1
                }

                is RP2040IncomingCommand.Ack -> {
                    onAck(command.data)
                    result = 1
                    Logger.d("Pi2AndroidReader", "parseAck")
                }

                else -> {
                    result = -1
                    Logger.e("Pi2AndroidReader", "Unknown command received")
                }
            }
        } else {
            Logger.i(Thread.currentThread().name, "No packet in queue")
            result = 0
        }
    }

    private fun sendCommand(command: RP2040OutgoingCommand): Int {
        try {
            this.usbSerial.send(command, 10000)
        } catch (e: SerialTimeoutException) {
            throw RuntimeException(
                "SerialTimeoutException on send. The serial connection " +
                        "with the rp2040 is not working as expected and timed out"
            )
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        receivePacket()
        return 0
    }

    /**
     * Do not use this method unless you are very familiar with the protocol on both the rp2040 and
     * Android side. This method is used to send raw bytes to the rp2040. It is recommended to use
     * the wrapped methods for doing higher level commands such as setMotorLevels. Sending the wrong
     * bytes to the rp2040 can cause it to crash and require a reset or worse.
     * @param bytes The raw bytes to be sent to the rp2040
     * @return 0 if successful, -1 if mResponse is not large enough to hold all response and the stop mark,
     * -2 if SerialTimeoutException on send
     */
    private fun sendPacket(bytes: ByteArray): Int {
        require(bytes.size == RP2040OutgoingCommand.PACKET_SIZE) {
            "Input byte array must have a length of " + RP2040OutgoingCommand.PACKET_SIZE
        }
        try {
            this.usbSerial.send(bytes, 10000)
        } catch (e: SerialTimeoutException) {
            throw RuntimeException(
                "SerialTimeoutException on send. The serial connection " +
                        "with the rp2040 is not working as expected and timed out"
            )
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
        receivePacket()
        return 0
    }

    private fun receivePacket() {
        val receivedStatus = usbSerial.awaitPacketReceived(10000)
        
        // T7: Manager Wake-up
        BenchmarkClock.mark(7)

        if (receivedStatus == 1) {
            //Note this is actually calling the functions like parseLog, parseStatus, etc.
            parseFifoPacket()
        }
    }



    //-------------------------------------------------------------------///
    // ---- API function calls for requesting something from the mcu ----///
    //-------------------------------------------------------------------///
    //    private void sendAck() throws IOException {
    //        byte[] ack = new byte[]{AndroidToRP2040Command.ACK.getHexValue()};
    //        sendPacket(ack);
    //    }
    /*
    parameters:
    float: left [-1,1] representing full speed backward to full speed forward
    float: right (same as left)
    */
    fun setMotorLevels(left: Float, right: Float, leftBrake: Boolean, rightBrake: Boolean) {
        // T1: Command Creation Start
        BenchmarkClock.mark(1)

        synchronized(commandLock) {
            command = RP2040OutgoingCommand.SetMotorLevels(
                left = left,
                right = right,
                leftBrake = leftBrake,
                rightBrake = rightBrake
            )

            commandLock.notify()
        }
    }

    fun getLog() {
        synchronized(commandLock) {
            command = RP2040OutgoingCommand.GetLog()
            commandLock.notify()
        }
    }

    //----------------------------------------------------------///
    // ---- Handlers for when data is returned from the mcu ----///
    // ---- Override these defaults with your own handlers -----///
    //----------------------------------------------------------///
    private fun parseLog(command: RP2040IncomingCommand.GetLog) {
        Logger.d("serial", "parseLogs")
        command.logEntries.forEach {
            Logger.i("rp2040Log", it)
        }
    }

    private fun parseStatus(command: StatusCommand) {
        Logger.d("serial", "parseStatus")
        if (rp2040State != null) {
            if (rp2040State.motorsState.controlValues.left != command.motorsState.controlValues.left) {
                //Logger.e("serial", "Left control value mismatch");
            }
            if (rp2040State.motorsState.controlValues.right != command.motorsState.controlValues.right) {
                //Logger.e("serial", "Right control value mismatch");
            }
            rp2040State.motorsState.faults = command.motorsState.faults
            Logger.v("serial", "left motor fault: " + rp2040State.motorsState.faults.left)
            Logger.v("serial", "right motor fault: " + rp2040State.motorsState.faults.right)

            rp2040State.motorsState.encoderCounts = command.motorsState.encoderCounts
            Logger.v("serial", "Left encoder count: " + rp2040State.motorsState.encoderCounts.left)
            Logger.v(
                "serial",
                "Right encoder count: " + rp2040State.motorsState.encoderCounts.right
            )

            rp2040State.batteryDetails = command.batteryDetails
            Logger.v("serial", "Battery voltage: " + rp2040State.batteryDetails.voltageMv)
            Logger.v("serial", "Battery voltage in V: " + rp2040State.batteryDetails.getVoltage())

            rp2040State.chargeSideUSB = command.chargeSideUSB
            Logger.v(
                "serial",
                "ncp3901_wireless_charger_attached: " + rp2040State.chargeSideUSB.isWirelessChargerAttached()
            )
            // Logger.v("serial", "usb_charger_voltage: " + rp2040State.chargeSideUSB.usb_charger_voltage);
            rp2040State.updatePublishers()
            
            // T8: State Applied
            BenchmarkClock.mark(8)
        }
    }

    private fun onNack(bytes: ByteArray) {
        Logger.d("serial", "parseNack")
    }

    private fun onAck(bytes: ByteArray) {
        Logger.d("serial", "parseAck")
    }
}

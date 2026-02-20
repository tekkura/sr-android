package jp.oist.abcvlib.util

import com.hoho.android.usbserial.driver.SerialTimeoutException
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.util.HexBinConverters.bytesToHex
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Class to manage request-response patter between Android phone and USB Serial connection
 * over a separate thread.
 * send()
 * onReceive()
 */
class SerialCommManager @JvmOverloads constructor(
    private val usbSerial: UsbSerial,
    batteryData: BatteryData? = null,
    wheelData: WheelData? = null
) {
    private val androidToRP2040Packet = AndroidToRP2040Packet()
    private val rp2040State: RP2040State?

    private var shutdown = false
    private var command: ByteArray? = null
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
                    if (command == null) {
                        command = generateGetStateCmd()
                    }
                    sendPacket(command!!)
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
    @JvmOverloads
    fun start(initialDelay: Long = 0, delay: Long = 10) {
        val priorityFactory = ProcessPriorityThreadFactory(
            Thread.MAX_PRIORITY,
            "SerialCommManager_Android2Pi"
        )
        val scheduledExecutorService = ScheduledExecutorServiceWithException(1, priorityFactory)
        scheduledExecutorService.scheduleWithFixedDelay(
            android2PiWriter,
            initialDelay,
            delay,
            TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        shutdown = true
    }

    //TODO paseFifoPacket() should call the various SerialResponseListener methods.
    internal fun parseFifoPacket() {
        var result = 0
        val fifoQueuePair: FifoQueuePair?
        var packet: ByteArray?
        // Run the command on a thread handler to allow the queue to keep being added to
        synchronized(usbSerial.fifoQueue) {
            fifoQueuePair = usbSerial.fifoQueue.poll()
        }
        // Check if there is a packet in the queue (fifoQueue
        if (fifoQueuePair != null) {
            packet = fifoQueuePair.byteArray

            // Log packet as an array of hex bytes
            Logger.i(Thread.currentThread().name, "Received packet: ${bytesToHex(packet)}")

            // The first byte after the start mark is the command
            val command = fifoQueuePair.androidToRP2040Command
            Logger.i(Thread.currentThread().name, "Received $command from pi")
            if (command == null) {
                Logger.e("Pi2AndroidReader", "Command not found")
                return
            }
            when (command) {
                AndroidToRP2040Command.GET_LOG -> {
                    parseLog(packet)
                    result = 1
                }

                AndroidToRP2040Command.SET_MOTOR_LEVELS,
                AndroidToRP2040Command.GET_STATE,
                AndroidToRP2040Command.RESET_STATE -> {
                    parseStatus(packet)
                    result = 1
                }

                AndroidToRP2040Command.NACK -> {
                    onNack(packet)
                    Logger.w("Pi2AndroidReader", "Nack issued from device")
                    result = -1
                }

                AndroidToRP2040Command.ACK -> {
                    onAck(packet)
                    result = 1
                    Logger.d("Pi2AndroidReader", "parseAck")
                }

                AndroidToRP2040Command.START -> {
                    Logger.e("Pi2AndroidReader", "parseStart. Start should never be a command")
                    result = -1
                }

                AndroidToRP2040Command.STOP -> {
                    Logger.e("Pi2AndroidReader", "parseStop. Stop should never be a command")
                    result = -1
                }

                else -> {
                    Logger.e("Pi2AndroidReader", "parsePacket. Command not found")
                    result = -1
                }
            }
        } else {
            Logger.i(Thread.currentThread().name, "No packet in queue")
            result = 0
        }
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
        require(bytes.size == AndroidToRP2040Packet.packetSize) {
            "Input byte array must have a length of " + AndroidToRP2040Packet.packetSize
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
        if (receivedStatus == 1) {
            //Note this is actually calling the functions like parseLog, parseStatus, etc.
            parseFifoPacket()
        }
    }

    private fun generateSetMotorLevels(
        androidToRP2040Packet: AndroidToRP2040Packet,
        left: Float,
        right: Float,
        leftBrake: Boolean,
        rightBrake: Boolean
    ): ByteArray {

        androidToRP2040Packet.clear()
        androidToRP2040Packet.setCommand(AndroidToRP2040Command.SET_MOTOR_LEVELS)

        val LOWER_LIMIT = 0.49f

        // Normalize [-1,1] to [-5.06,5.06] as this is the range accepted by the chip
        // Note - signs are to invert the direction of the motors as the motors are mounted in a
        // polar opposite direction.
        val leftNrm = -left * 5.06f
        val rightNrm = -right * 5.06f

        val DRV8830_IN1_BIT: Byte = 0
        val DRV8830_IN2_BIT: Byte = 1

        val voltages = floatArrayOf(leftNrm, rightNrm)
        val abs_voltages = FloatArray(2)
        val control_values = ByteArray(2)
        val brakes = booleanArrayOf(leftBrake, rightBrake)

        for (i in voltages.indices) {
            val voltage = voltages[i] // Get the current voltage
            control_values[i] = 0 // Reset the control value
            // Exclude or truncate voltages between -0.48V and 0.48V to 0V
            // Changing to 0.49 as the scaling function would result in 0x05h for 0.48V and
            // cause the rp2040 to perform unexpectedly as it is a reserved register value
            if (voltage >= -LOWER_LIMIT && voltage <= LOWER_LIMIT) {
                voltages[i] = 0.0f // Update the value in the array
            } else {
                // Clamp the voltage within the valid range
                // Need to clamp here rather than at byte representation to prevent overflow
                if (voltages[i] < -5.06) {
                    voltages[i] = -5.06f // Update the value in the array
                } else if (voltages[i] > 5.06) {
                    voltages[i] = 5.06f // Update the value in the array
                }

                abs_voltages[i] = abs(voltages[i])
                // Convert voltage to control value (-0x3F to 0x3F)
                control_values[i] = (((64 * abs_voltages[i]) / (4 * 1.285)) - 1).toInt().toByte()
                // voltage is defined by bits 2-7. Shift the control value to the correct position
                control_values[i] = (control_values[i].toInt() shl 2).toByte()
            }


            // Set the IN1 and IN2 bits based on the sign of the voltage
            var cv = control_values[i].toInt()
            if (brakes[i]) {
                cv = cv or (1 shl DRV8830_IN1_BIT.toInt())
                cv = cv or (1 shl DRV8830_IN2_BIT.toInt())
            } else {
                if (voltage < 0) {
                    cv = cv or (1 shl DRV8830_IN1_BIT.toInt())
                    cv = cv and (1 shl DRV8830_IN2_BIT.toInt()).inv()
                } else if (voltage > 0) {
                    cv = cv or (1 shl DRV8830_IN2_BIT.toInt())
                    cv = cv and (1 shl DRV8830_IN1_BIT.toInt()).inv()
                } else {
                    // Standby/Coast: Both IN1 and IN2 set to 0
                    cv = 0
                }
            }
            control_values[i] = cv.toByte()
            androidToRP2040Packet.payload.put(control_values[i])
        }
        return androidToRP2040Packet.packetToBytes()
    }

    private fun generateGetLogCmd(): ByteArray {
        androidToRP2040Packet.clear()
        androidToRP2040Packet.setCommand(AndroidToRP2040Command.GET_LOG)
        return androidToRP2040Packet.packetToBytes()
    }

    private fun generateGetStateCmd(): ByteArray {
        androidToRP2040Packet.clear()
        androidToRP2040Packet.setCommand(AndroidToRP2040Command.GET_STATE)
        return androidToRP2040Packet.packetToBytes()
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
        synchronized(commandLock) {
            command =
                generateSetMotorLevels(androidToRP2040Packet, left, right, leftBrake, rightBrake)
            commandLock.notify()
        }
    }

    fun getLog() {
        synchronized(commandLock) {
            command = generateGetLogCmd()
            commandLock.notify()
        }
    }

    //----------------------------------------------------------///
    // ---- Handlers for when data is returned from the mcu ----///
    // ---- Override these defaults with your own handlers -----///
    //----------------------------------------------------------///
    private fun parseLog(bytes: ByteArray) {
        Logger.d("serial", "parseLogs")
        val string = String(bytes, StandardCharsets.US_ASCII)
        val lines = string.lines()
        for (line in lines) {
            Logger.i("rp2040Log", line)
        }
    }

    private fun parseStatus(bytes: ByteArray) {
        Logger.d("serial", "parseStatus")
        if (rp2040State != null) {
            val byteBuffer = ByteBuffer.wrap(bytes)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            if (rp2040State.motorsState.controlValues.left != byteBuffer.get()) {
                //Logger.e("serial", "Left control value mismatch");
            }
            if (rp2040State.motorsState.controlValues.right != byteBuffer.get()) {
                //Logger.e("serial", "Right control value mismatch");
            }
            rp2040State.motorsState.faults.left = byteBuffer.get()
            rp2040State.motorsState.faults.right = byteBuffer.get()
            Logger.v("serial", "left motor fault: " + rp2040State.motorsState.faults.left)
            Logger.v("serial", "right motor fault: " + rp2040State.motorsState.faults.right)
            rp2040State.motorsState.encoderCounts.left = byteBuffer.getInt()
            rp2040State.motorsState.encoderCounts.right = byteBuffer.getInt()
            Logger.v("serial", "Left encoder count: " + rp2040State.motorsState.encoderCounts.left)
            Logger.v(
                "serial",
                "Right encoder count: " + rp2040State.motorsState.encoderCounts.right
            )
            rp2040State.batteryDetails.voltageMv = byteBuffer.getShort()
            rp2040State.batteryDetails.safetyStatus = byteBuffer.get()
            rp2040State.batteryDetails.temperature = byteBuffer.getShort()
            rp2040State.batteryDetails.stateOfHealth = byteBuffer.get()
            rp2040State.batteryDetails.flags = byteBuffer.getShort()
            Logger.v("serial", "Battery voltage: " + rp2040State.batteryDetails.voltageMv)
            Logger.v("serial", "Battery voltage in V: " + rp2040State.batteryDetails.getVoltage())
            rp2040State.chargeSideUSB.max77976_chg_details = byteBuffer.getInt()
            rp2040State.chargeSideUSB.ncp3901_wireless_charger_attached =
                byteBuffer.get().toInt() == 1
            Logger.v(
                "serial",
                "ncp3901_wireless_charger_attached: " + rp2040State.chargeSideUSB.isWirelessChargerAttached()
            )
            rp2040State.chargeSideUSB.usb_charger_voltage = byteBuffer.getShort()
            rp2040State.chargeSideUSB.wireless_charger_vrect = byteBuffer.getShort()
            // Logger.v("serial", "usb_charger_voltage: " + rp2040State.chargeSideUSB.usb_charger_voltage);
            rp2040State.updatePublishers()
        }
    }

    private fun onNack(bytes: ByteArray) {
        Logger.d("serial", "parseNack")
    }

    private fun onAck(bytes: ByteArray) {
        Logger.d("serial", "parseAck")
    }
}

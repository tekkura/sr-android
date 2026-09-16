package jp.oist.abcvlib.util

import android.os.SystemClock
import com.hoho.android.usbserial.driver.SerialTimeoutException
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.util.HexBinConverters.bytesToHex
import jp.oist.abcvlib.util.rp2040.RP2040IncomingCommand
import jp.oist.abcvlib.util.rp2040.RP2040OutgoingCommand
import jp.oist.abcvlib.util.rp2040.RP2040State
import jp.oist.abcvlib.util.rp2040.StatusCommand
import jp.oist.abcvlib.util.versioning.FirmwareCompatibilityException
import jp.oist.abcvlib.util.versioning.Version
import jp.oist.abcvlib.util.versioning.checkVersionSupport
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Class to manage request-response patter between Android phone and USB Serial connection
 * over a separate thread.
 */
open class SerialCommManager @JvmOverloads constructor(
    protected val usbSerial: UsbSerial,
    batteryData: BatteryData? = null,
    wheelData: WheelData? = null,
    private var firmwareCompatibilityFailureListener: FirmwareCompatibilityFailureListener? = null
) {
    private val rp2040State: RP2040State?

    private var command: RP2040OutgoingCommand? = null
    private var queuedMotorLevelsAtMs: Long = 0L
    protected val commandLock = Object()
    private val lifecycleLock = Any()
    private var runContext: RunContext? = null
    private val executingContext = ThreadLocal<RunContext>()

    protected var startTimeAndroid: Long = 0
    private var cnt: Int = 0
    private var durationAndroid: Long = 0

    private val VERSION_TIMEOUT_MS = 2000L

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


    protected class RunContext(
        val stopRequested: AtomicBoolean = AtomicBoolean(false),
        var writerExecutor: ScheduledExecutorServiceWithException? = null,
        var versionTimeoutExecutor: ScheduledExecutorService? = null,
        var delay: AtomicLong = AtomicLong(10),
        var initialDelay: AtomicLong = AtomicLong(0),
        var versionTimeoutFuture: ScheduledFuture<*>? = null,
        var handshakeComplete: Boolean = false,
        val onReady: (() -> Unit)? = null,
        val onFailure: FirmwareCompatibilityFailureListener? = null
    )

    protected open fun buildAndroid2PiWriter(context: RunContext): Runnable = Runnable {
        startTimeAndroid = System.nanoTime()
        while (!context.stopRequested.get()) {
            val nextCommand = try {
                synchronized(commandLock) {
                    while (command == null && !context.stopRequested.get()) {
                        commandLock.wait()
                    }

                    if (context.stopRequested.get()) {
                        return@synchronized null
                    }

                    val localCommand = command ?: return@synchronized null
                    command = null
                    localCommand
                }
            } catch (e: InterruptedException) {
                continue
            }

            if (nextCommand == null) {
                continue
            }

            if (queuedMotorLevelsAtMs > 0L) {
                ControlLatencyTrace.queueToSendMs = SystemClock.uptimeMillis() - queuedMotorLevelsAtMs
                queuedMotorLevelsAtMs = 0L
            }
            sendCommand(nextCommand)
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
    fun start(initialDelay: Long = 0, delay: Long = 10, onReady: (() -> Unit)? = null) {
        synchronized(lifecycleLock) {
            val existing = runContext
            if (existing != null && !existing.stopRequested.get()) {
                Logger.w("serial", "SerialCommManager already started; ignoring duplicate start()")
                return
            }

            val context = RunContext(
                onReady = onReady,
                onFailure = firmwareCompatibilityFailureListener
            ).apply {
                this.delay.set(delay)
                this.initialDelay.set(initialDelay)
            }

            val priorityFactory = ProcessPriorityThreadFactory(
                Thread.MAX_PRIORITY,
                "SerialCommManager_Android2Pi"
            )

            context.writerExecutor = ScheduledExecutorServiceWithException(2, priorityFactory)
            context.versionTimeoutExecutor = Executors.newSingleThreadScheduledExecutor(
                ProcessPriorityThreadFactory(
                    Thread.MAX_PRIORITY,
                    "SerialCommManager_FirmwareVersionTimeout"
                )
            )
            runContext = context
            usbSerial.resetReceiveState()

            // Only check version support for real firmware
            if (usbSerial.isPortReal)
                requestFirmwareVersion(context)
            else context.writerExecutor?.execute { startPolling(context) }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            runContext?.let { stopContext(it) }
        }
    }

    // Called with lifecycleLock held, so an old run cannot stop a replacement run.
    private fun stopContext(context: RunContext) {
        context.stopRequested.set(true)
        context.versionTimeoutFuture?.cancel(false)
        context.versionTimeoutFuture = null
        context.writerExecutor?.shutdownNow()
        context.writerExecutor = null
        context.versionTimeoutExecutor?.shutdownNow()
        context.versionTimeoutExecutor = null
        if (runContext === context) runContext = null
        synchronized(commandLock) {
            command = null
            queuedMotorLevelsAtMs = 0L
            commandLock.notifyAll()
        }
    }

    fun setFirmwareCompatibilityFailureListener(
        listener: FirmwareCompatibilityFailureListener?
    ) {
        firmwareCompatibilityFailureListener = listener
    }

    //TODO paseFifoPacket() should call the various SerialResponseListener methods.
    internal fun parseFifoPacket() {
        val context = executingContext.get()
        if (context != null && synchronized(lifecycleLock) {
                runContext !== context || context.stopRequested.get()
            }) return
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

                is RP2040IncomingCommand.GetVersion -> {
                    if (context == null) {
                        Logger.w("serial", "Ignoring version response outside an active request")
                        return
                    }
                    if (checkVersionSupport(
                        command.major,
                        command.minor,
                        command.patch
                    )) {
                        startPolling(context)
                    } else {
                        handleFirmwareCompatibilityFailure(
                            context,
                            FirmwareCompatibilityException.unsupportedVersion(
                                Version(command.major, command.minor, command.patch)
                            )
                        )
                    }

                    result = 1
                    Logger.d("Pi2AndroidReader", "parseGetVersion")
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

    protected open fun sendCommand(command: RP2040OutgoingCommand): Int {
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
    protected fun sendPacket(bytes: ByteArray): Int {
        require(bytes.size >= 1 + 2 + 1 + 2) {
            "Input byte array must contain at least a header and CRC"
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

    protected open fun receivePacket() {
        val receivedStatus = usbSerial.awaitPacketReceived(10000)

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
        val activeContext: RunContext
        synchronized(lifecycleLock) {
            activeContext = runContext ?: return
            if (activeContext.stopRequested.get()) {
                return
            }
        }
        synchronized(commandLock) {
            if (activeContext.stopRequested.get()) {
                return
            }
            queuedMotorLevelsAtMs = SystemClock.uptimeMillis()
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
        val activeContext: RunContext
        synchronized(lifecycleLock) {
            activeContext = runContext ?: return
            if (activeContext.stopRequested.get()) {
                return
            }
        }
        synchronized(commandLock) {
            if (activeContext.stopRequested.get()) {
                return
            }
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

    protected open fun parseStatus(command: StatusCommand) : Boolean {
        Logger.d("serial", "parseStatus")
        if (rp2040State == null)
            return false

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

        return true
    }

    private fun onNack(bytes: ByteArray) {
        Logger.d("serial", "parseNack")
    }

    private fun onAck(bytes: ByteArray) {
        Logger.d("serial", "parseAck")
    }

    private fun startPolling(context: RunContext) {
        val onReady: (() -> Unit)?
        synchronized(lifecycleLock) {
            if (runContext !== context || context.stopRequested.get() || context.handshakeComplete)
                return
            context.handshakeComplete = true
            context.versionTimeoutFuture?.cancel(false)
            context.versionTimeoutFuture = null
            onReady = context.onReady
        }

        onReady?.invoke()

        synchronized(lifecycleLock) {
            if (runContext !== context || context.stopRequested.get()) return
            context.writerExecutor?.schedule(
                { inRun(context) { buildAndroid2PiWriter(context).run() } },
                context.initialDelay.get(),
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun requestFirmwareVersion(context: RunContext) {
        with(context) {
            versionTimeoutFuture = versionTimeoutExecutor?.schedule({
                handleFirmwareCompatibilityFailure(
                    context,
                    FirmwareCompatibilityException.versionRequestTimedOut(VERSION_TIMEOUT_MS)
                )
            }, VERSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)


            writerExecutor?.execute {
                inRun(context) {
                    try {
                        usbSerial.send(RP2040OutgoingCommand.GetVersion(), 10000)
                        if (!awaitFirmwareVersionResponse(context, VERSION_TIMEOUT_MS)) {
                            handleFirmwareCompatibilityFailure(
                                context,
                                FirmwareCompatibilityException.versionRequestTimedOut(VERSION_TIMEOUT_MS)
                            )
                        }
                    } catch (e: FirmwareCompatibilityException) {
                        handleFirmwareCompatibilityFailure(context, e)
                    } catch (e: IOException) {
                        handleFirmwareCompatibilityFailure(
                            context,
                            FirmwareCompatibilityException.versionRequestFailed(e)
                        )
                    } catch (e: RuntimeException) {
                        handleFirmwareCompatibilityFailure(
                            context,
                            FirmwareCompatibilityException.versionRequestFailed(e)
                        )
                    }
                }
            }
        }
    }

    private fun awaitFirmwareVersionResponse(context: RunContext, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!context.stopRequested.get()) {
            val remainingMs = deadline - SystemClock.uptimeMillis()
            if (remainingMs <= 0L) return false

            val receivedStatus = usbSerial.awaitPacketReceived(
                remainingMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            if (receivedStatus != 1) {
                continue
            }

            val receivedCommand = synchronized(usbSerial.fifoQueue) {
                usbSerial.fifoQueue.poll()
            } ?: continue

            if (receivedCommand is RP2040IncomingCommand.GetVersion) {
                if (checkVersionSupport(
                    receivedCommand.major,
                    receivedCommand.minor,
                    receivedCommand.patch
                )) {
                    startPolling(context)
                } else {
                    handleFirmwareCompatibilityFailure(
                        context,
                        FirmwareCompatibilityException.unsupportedVersion(
                            Version(
                                receivedCommand.major,
                                receivedCommand.minor,
                                receivedCommand.patch
                            )
                        )
                    )
                }
                return true
            }

            Logger.w(
                "serial",
                "Ignoring ${receivedCommand.type} received while waiting for firmware version"
            )
        }

        return true
    }

    private fun inRun(context: RunContext, action: () -> Unit) {
        if (context.stopRequested.get()) return
        executingContext.set(context)
        try {
            action()
        } finally {
            executingContext.remove()
        }
    }

    private fun handleFirmwareCompatibilityFailure(
        context: RunContext,
        exception: FirmwareCompatibilityException
    ) {
        synchronized(lifecycleLock) {
            if (runContext !== context || context.stopRequested.get() || context.handshakeComplete)
                return
            Logger.e("serial", exception.message ?: "Firmware compatibility failure", exception)
            stopContext(context)
            context.onFailure?.onFirmwareCompatibilityFailure(exception.userFacingMessage)
        }
    }
}

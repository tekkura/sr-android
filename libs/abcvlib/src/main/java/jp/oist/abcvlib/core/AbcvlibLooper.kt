package jp.oist.abcvlib.core

import ioio.lib.api.AnalogInput
import ioio.lib.api.Closeable
import ioio.lib.api.DigitalInput
import ioio.lib.api.DigitalOutput
import ioio.lib.api.PwmOutput
import ioio.lib.api.exception.ConnectionLostException
import ioio.lib.util.BaseIOIOLooper
import ioio.lib.util.IOIOConnectionManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.util.Logger
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * AbcvlibLooper provides the connection with the IOIOBoard by allowing access to the loop
 * function being called by the software on the IOIOBoard itself. All class variables and
 * contents of the setup() and loop() methods are passed upward to the respective parent classes
 * related to the core IOIOBoard operation by extending BaseIOIOLooper.
 * 
 * AbcvlibLooper represents the "control" thread mentioned in the git wiki. It sets up the IOIO
 * Board pin connections, reads the encoder values, and writes out the total encoder counts for
 * wheel speed calculations elsewhere.
 * 
 * @author Jiexin Wang https://github.com/ha5ha6
 * @author Christopher Buckley https://github.com/topherbuckley
 */
class AbcvlibLooper(private val ioReadyListener: IOReadyListener) : BaseIOIOLooper() {

    companion object {
        private val TAG: String = AbcvlibLooper::class.java.simpleName

        const val INPUT1_RIGHT_WHEEL_PIN = 2
        const val INPUT2_RIGHT_WHEEL_PIN = 3
        const val PWM_RIGHT_WHEEL_PIN = 4
        const val ENCODER_A_RIGHT_WHEEL_PIN = 6
        const val ENCODER_B_RIGHT_WHEEL_PIN = 7

        const val INPUT1_LEFT_WHEEL_PIN = 11
        const val INPUT2_LEFT_WHEEL_PIN = 12
        const val PWM_LEFT_WHEEL_PIN = 13
        const val ENCODER_A_LEFT_WHEEL_PIN = 15
        const val ENCODER_B_LEFT_WHEEL_PIN = 16

        const val CHARGER_VOLTAGE = 33
        const val BATTERY_VOLTAGE = 34
        const val COIL_VOLTAGE = 35

        const val PWM_FREQ = 1000

        const val MAX_DUTY_CYCLE = 1

    }

    //      --------------Quadrature Encoders----------------
    /**
     * Creates IOIO Board object that read the quadrature encoders of the Hubee Wheels.
     * Using the encoderARightWheel.read() returns a boolean of either high or low telling whether the
     * quadrature encoder is on a black or white mark at the rime of read.<br></br><br></br>
     * 
     * The encoderARightWheel.waitForValue(true) can also be used to wait for the value read by the quadrature
     * encoder to change to a given value (true in this case).<br></br><br></br>
     * 
     * encoderARightWheelStatePrevious is just the previous reading of encoderARightWheel<br></br><br></br>
     * 
     * For more on quadrature encoders see
     * [here](http://www.creative-robotics.com/bmdsresources) OR
     * [here](https://en.wikipedia.org/wiki/Rotary_encoder#Incremental_rotary_encoder)<br></br><br></br>
     * 
     * For more on IOIO Board DigitalInput objects see:
     * [here](https://github.com/ytai/ioio/wiki/Digital-IO)<br></br><br></br>
     */
    private lateinit var encoderARightWheel: DigitalInput

    /**
     * @see .encoderARightWheel
     */
    private lateinit var encoderBRightWheel: DigitalInput

    /**
     * @see .encoderARightWheel
     */
    private lateinit var encoderALeftWheel: DigitalInput

    /**
     * @see .encoderARightWheel
     */
    private lateinit var encoderBLeftWheel: DigitalInput

    //     --------------Wheel Direction Controllers----------------
    /**
     * The values set by input1RightWheelController.write() control the direction of the Hubee wheels.
     * See [here](http://www.creative-robotics.com/bmdsresources) for the source of the
     * control value table copied below:
     *
     * Table below refers to a single wheel
     * (e.g. setting input1RightWheelController to H and input2RightWheelController to L
     * with PWM H and Standby H would result in the right wheel turning backwards)
     *
     * Setting input1RightWheelController to H is done via input1RightWheelController.write(true)
     *
     * | IN1 | IN2 | PWM | Standby | Result       |
     * |-----|-----|-----|---------|--------------|
     * | H   | H   | H/L | H       | Stop-Brake   |
     * | L   | H   | H   | H       | Turn Forwards|
     * | L   | H   | L   | H       | Stop-Brake   |
     * | H   | L   | H   | H       | Turn Backwards|
     * | H   | L   | L   | H       | Stop-Brake   |
     * | L   | L   | H/L | H       | Stop-NoBrake |
     * | H/L | H/L | H/L | L       | Standby      |
     */
    private lateinit var input1RightWheelController: DigitalOutput

    /**
     * @see .input1RightWheelController
     */
    private lateinit var input2RightWheelController: DigitalOutput

    /**
     * @see .input1RightWheelController
     */
    private lateinit var input1LeftWheelController: DigitalOutput

    /**
     * @see .input1RightWheelController
     */
    private lateinit var input2LeftWheelController: DigitalOutput

    /**
     * Monitors onboard battery voltage (note this is not the smartphone battery voltage. The
     * smartphone battery should always be fully charged as it will draw current from the onboard
     * battery until the onboard battery dies)
     */
    private lateinit var batteryVoltageMonitor: AnalogInput

    /**
     * Monitors external charger (usb or wireless coil) voltage. Use this to detect on charge puck or not. (H/L)
     */
    private lateinit var chargerVoltageMonitor: AnalogInput

    /**
     * Monitors wireless receiver coil voltage at coil (not after Qi regulator). Use this to detect on charge puck or not. (H/L)
     */
    private lateinit var coilVoltageMonitor: AnalogInput

    //     --------------Pulse Width Modulation (PWM)----------------
    /**
     * PwmOutput objects like pwmControllerRightWheel have methods like
     * 
     *  * openPwmOutput(pinNum, freq) to start the PWM on pinNum at freq
     * 
     *  * pwm.setDutyCycle(duty-cycle) to change the freq directly by modifying the pulse width
     * 
     * 
     * More info [here](https://github.com/ytai/ioio/wiki/PWM-Output)
     */
    private lateinit var pwmControllerRightWheel: PwmOutput

    /**
     * @see .pwmControllerRightWheel
     */
    private lateinit var pwmControllerLeftWheel: PwmOutput

    /**
     * The IN1 and IN2 IO determining Hubee Wheel direction. See input1RightWheelController doc for
     * control table
     * 
     * @see .input1RightWheelController
     */
    private var input1RightWheelState = false

    /**
     * @see .input1RightWheelState
     */
    private var input2RightWheelState = false

    /**
     * @see .input1RightWheelState
     */
    private var input1LeftWheelState = false

    /**
     * @see .input1RightWheelState
     */
    private var input2LeftWheelState = false

    /**
     * Duty cycle of PWM pulse width tracking variable. Values range from 0 to 100
     */
    private var dutyCycleRightWheel = 0f

    /**
     * @see .dutyCycleRightWheel
     */
    private var dutyCycleLeftWheel = 0f

    @Volatile
    private var batteryData: BatteryData? = null

    @Volatile
    private var wheelData: WheelData? = null

    private val ioioPins: MutableList<Closeable> = ArrayList()

    /**
     * Called every time a connection with IOIO has been established.
     * Typically used to open pins.
     * 
     * @throws ConnectionLostException
     * When IOIO connection is lost.
     * 
     * @see ioio.lib.util.IOIOLooper.setup
     */
    @Throws(ConnectionLostException::class)
    public override fun setup() {
        /*
                 --------------IOIO Board PIN References----------------
                 Although several other pins would work, there are restrictions on which pins can be used to
                 PWM and which pins can be used for analog/digital purposes. See back of IOIO Board for pin
                 mapping.
        
                 Note the INPUTX_XXXX pins were all placed on 5V tolerant pins, but Hubee wheel inputs
                 operate from 3.3 to 5V, so any other pins would work just as well.
        
                 Although the encoder pins were chosen to be on the IOIO board analog in pins, this is not
                 necessary as the encoder objects only read digital high and low values.
        
                 PWM pins are currently on pins with P (peripheral) and 5V tolerant pins. The P capability is
                 necessary in order to properly use the PWM based methods (though not sure if these are even
                 used). The 5V tolerant pins are not necessary as the IOIO Board PWM is a 3.3V peak signal.
                 */

        Logger.d(TAG, "AbcvlibLooper setup() started")

        Logger.v(TAG, "ioio_ state = ${ioio_.state}")

        /*
        Initializing all wheel controller values to low would result in both wheels being in
        the "Stop-NoBrake" mode according to the Hubee control table. Not sure if this state
        is required for some reason or just what was defaulted to.
        */
        input1RightWheelController = ioio_.openDigitalOutput(INPUT1_RIGHT_WHEEL_PIN, false)
        ioioPins.add(input1RightWheelController)
        input2RightWheelController = ioio_.openDigitalOutput(INPUT2_RIGHT_WHEEL_PIN, false)
        ioioPins.add(input2RightWheelController)
        input1LeftWheelController = ioio_.openDigitalOutput(INPUT1_LEFT_WHEEL_PIN, false)
        ioioPins.add(input1LeftWheelController)
        input2LeftWheelController = ioio_.openDigitalOutput(INPUT2_LEFT_WHEEL_PIN, false)
        ioioPins.add(input2LeftWheelController)

        batteryVoltageMonitor = ioio_.openAnalogInput(BATTERY_VOLTAGE)
        ioioPins.add(batteryVoltageMonitor)
        chargerVoltageMonitor = ioio_.openAnalogInput(CHARGER_VOLTAGE)
        ioioPins.add(chargerVoltageMonitor)
        coilVoltageMonitor = ioio_.openAnalogInput(COIL_VOLTAGE)
        ioioPins.add(coilVoltageMonitor)

        // This try-catch statement should likely be refined to handle common errors/exceptions
        try {
            /*
             * PWM frequency. Do not modify locally. Modify at AbcvlibActivity level if necessary
             *  Not sure why initial PWM_FREQ is 1000, but assume this can be modified as necessary.
             *  This may depend on the motor or microcontroller requirements/specs. <br><br>
             *
             *  If motor is just a DC motor, I guess this does not matter much, but for servos, this would
             *  be the control function, so would have to match the baud rate of the microcontroller. Note
             *  this library is not set up to control servos at this time. <br><br>
             *
             *  The microcontroller likely has a maximum frequency which it can turn ON/OFF the IO, so
             *  setting PWM_FREQ too high may cause issues for certain microcontrollers.
             */
            pwmControllerRightWheel = ioio_.openPwmOutput(PWM_RIGHT_WHEEL_PIN, PWM_FREQ)
            ioioPins.add(pwmControllerRightWheel)
            pwmControllerLeftWheel = ioio_.openPwmOutput(PWM_LEFT_WHEEL_PIN, PWM_FREQ)
            ioioPins.add(pwmControllerLeftWheel)

            /*
            Note openDigitalInput() can also accept DigitalInput.Spec.Mode.OPEN_DRAIN if motor
            circuit requires
            */
            encoderARightWheel = ioio_.openDigitalInput(
                ENCODER_A_RIGHT_WHEEL_PIN,
                DigitalInput.Spec.Mode.PULL_UP
            )
            ioioPins.add(encoderARightWheel)
            encoderBRightWheel = ioio_.openDigitalInput(
                ENCODER_B_RIGHT_WHEEL_PIN,
                DigitalInput.Spec.Mode.PULL_UP
            )
            ioioPins.add(encoderBRightWheel)
            encoderALeftWheel = ioio_.openDigitalInput(
                ENCODER_A_LEFT_WHEEL_PIN,
                DigitalInput.Spec.Mode.PULL_UP
            )
            ioioPins.add(encoderALeftWheel)
            encoderBLeftWheel = ioio_.openDigitalInput(
                ENCODER_B_LEFT_WHEEL_PIN,
                DigitalInput.Spec.Mode.PULL_UP
            )
            ioioPins.add(encoderBLeftWheel)
        } catch (e: ConnectionLostException) {
            Logger.e(TAG, "ConnectionLostException at AbcvlibLooper.setup()")
            throw e
        }
        Logger.d(TAG, "AbcvlibLooper setup() finished")
        ioReadyListener.onIOReady()
    }

    /**
     * Called repetitively while the IOIO is connected.
     * 
     * @see ioio.lib.util.IOIOLooper.loop
     */
    override fun loop() {
        try {
            val timeStamp = System.nanoTime()

            // Read IOIO Input pins
            val chargerVoltage = chargerVoltageMonitor.voltage
            val coilVoltage = coilVoltageMonitor.voltage
            val batteryVoltage = batteryVoltageMonitor.voltage
            val encoderARightWheelState = encoderARightWheel.read()
            val encoderBRightWheelState = encoderBRightWheel.read()
            val encoderALeftWheelState = encoderALeftWheel.read()
            val encoderBLeftWheelState = encoderBLeftWheel.read()

            //            // Update any subscribers/listeners
//            if (wheelData != null){
//                wheelData.onWheelDataUpdate(timeStamp, encoderARightWheelState, encoderBRightWheelState,
//                        encoderALeftWheelState, encoderBLeftWheelState);
//            }
//            if (batteryData != null){
//                batteryData.onChargerVoltageUpdate(chargerVoltage, coilVoltage, timeStamp);
//                batteryData.onBatteryVoltageUpdate(batteryVoltage, timeStamp);
//            }

            // Write all calculated values to the IOIO Board pins
            input1RightWheelController.write(input1RightWheelState)
            input2RightWheelController.write(input2RightWheelState)
            pwmControllerRightWheel.setDutyCycle(dutyCycleRightWheel) //converting from duty cycle to pulse width
            input1LeftWheelController.write(input1LeftWheelState)
            input2LeftWheelController.write(input2LeftWheelState)
            pwmControllerLeftWheel.setDutyCycle(dutyCycleLeftWheel) //converting from duty cycle to pulse width
        } catch (e: ConnectionLostException) {
            Logger.e(TAG, "connection lost in AbcvlibLooper.loop")
        } catch (e: InterruptedException) {
            Logger.e(TAG, "connection lost in AbcvlibLooper.loop")
        }
        IOIOConnectionManager.Thread.yield()
    }

    /**
     * Called when the IOIO is disconnected.
     * 
     * @see ioio.lib.util.IOIOLooper.disconnected
     */
    override fun disconnected() {
        Logger.d(TAG, "AbcvlibLooper disconnected")
    }

    /**
     * Called when the IOIO is connected, but has an incompatible firmware version.
     * 
     * @see ioio.lib.util.IOIOLooper.incompatible
     */
    override fun incompatible() {
        Logger.e(TAG, "Incompatible IOIO firmware version!")
    }

    /**
     * Returns a hard limited value for the dutyCycle to be within the inclusive range of [0,1].
     * @param dutyCycleOld un-limited duty cycle
     * @return limited duty cycle
     */
    private fun dutyCycleLimiter(dutyCycleOld: Float): Float {
        val dutyCycleNew: Float = if (abs(dutyCycleOld) < MAX_DUTY_CYCLE) {
            abs(dutyCycleOld)
        } else {
            MAX_DUTY_CYCLE.toFloat()
        }

        return dutyCycleNew
    }

    fun setDutyCycle(left: Float, right: Float) {
        // Determine how to set the ioio input pins which in turn set the direction of the wheel
        if (right >= 0) {
            input1RightWheelState = false
            input2RightWheelState = true
        } else {
            input1RightWheelState = true
            input2RightWheelState = false
        }

        if (left <= 0) {
            input1LeftWheelState = false
            input2LeftWheelState = true
        } else {
            input1LeftWheelState = true
            input2LeftWheelState = false
        }

        // Limit the duty cycle to be from 0 to 1
        dutyCycleRightWheel = dutyCycleLimiter(right)
        dutyCycleLeftWheel = dutyCycleLimiter(left)
    }

    fun setBatteryData(batteryData: BatteryData?) {
        this.batteryData = batteryData
    }

    fun setWheelData(wheelData: WheelData?) {
        this.wheelData = wheelData
    }

    @Throws(ConnectionLostException::class)
    fun turnOffWheels() {
        pwmControllerRightWheel.setDutyCycle(0f)
        pwmControllerLeftWheel.setDutyCycle(0f)
        Logger.d(TAG, "Turning off wheels")
    }

    fun shutDown() {
        for (pin in ioioPins) {
            pin.close()
        }
    }
}

package jp.oist.abcvlib.tests

import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.OrientationData
import jp.oist.abcvlib.core.inputs.phone.OrientationDataSubscriber
import jp.oist.abcvlib.core.outputs.AbcvlibController
import jp.oist.abcvlib.util.Logger

class BalancePIDController : AbcvlibController(), WheelDataSubscriber, OrientationDataSubscriber {
    private val TAG: String = this.javaClass.name

    // Initialize all sensor reading variables
    private var pTilt = -24.0
    private var iTilt = 0.0
    private var dTilt = 1.0
    private var setPoint = 2.8
    private var pWheel = 0.0
    private var expWeight = 0.25
    private var e_t = 0.0 // e(t) of wikipedia
    private var int_e_t = 0.0 // integral of e(t) from wikipedia. Discrete, so just a sum here.

    private var maxAbsTilt = 6.5 // in Degrees

    private var speedL = 0.0
    private var thetaDeg = 0.0
    private var angularVelocityDeg = 0.0

    private var bounceLoopCount = 0

    override fun run() {
        // If current tilt angle is over maxAbsTilt or under -maxAbsTilt --> Bounce Up
        if ((setPoint - maxAbsTilt) > thetaDeg) {
            bounce(false) // Bounce backward first
        } else if ((setPoint + maxAbsTilt) < thetaDeg) {
            bounce(true) // Bounce forward first
        } else {
            bounceLoopCount = 0
            linearController()
        }
    }

    /**
     * A means of changing the PID values while this controller is running
     * @param pTilt proportional controller relative to the tilt angle of the phone
     * @param iTilt integral controller relative to the tilt angle of the phone
     * @param dTilt derivative controller relative to the tilt angle of the phone
     * @param setPoint the assumed angle where the robot would be balanced (ideally zero but realistically nearer to 3 or 4 deg)
     * @param pWheel proportional controller relative to the wheel distance
     * @param expWeight exponential filter coefficient //todo implement this more clearly
     * @param maxAbsTilt max tilt angle (deg) at which the controller will switch between a linear and non-linear bounce controller.
     * @throws InterruptedException thrown if shutdown while trying to read/write to the IOIO board.
     */
    @Synchronized
    @Throws(InterruptedException::class)
    fun setPID(
        pTilt: Double, iTilt: Double, dTilt: Double, setPoint: Double,
        pWheel: Double, expWeight: Double, maxAbsTilt: Double
    ) {
        try {
            this.setPoint = setPoint
            this.pTilt = pTilt
            this.iTilt = iTilt
            this.dTilt = dTilt
            this.pWheel = pWheel
            this.expWeight = expWeight
            this.maxAbsTilt = maxAbsTilt
        } catch (e: NullPointerException) {
            Logger.e(TAG, "Error", e)
            Thread.sleep(1000)
        }
    }

    // -------------- Actual Controllers ----------------------------
    private fun bounce(forward: Boolean) {
        val speed = 0.5f
        // loop steps between turning on and off wheels.
        val bouncePulseWidth = 100
        if (bounceLoopCount < bouncePulseWidth * 0.1) {
            setOutput(0f, 0f)
        } else if (bounceLoopCount < bouncePulseWidth * 1.1) {
            if (forward) {
                setOutput(speed, speed)
            } else {
                setOutput(-speed, -speed)
            }
        } else if (bounceLoopCount < bouncePulseWidth * 1.2) {
            setOutput(0f, 0f)
        } else if (bounceLoopCount < bouncePulseWidth * 2.2) {
            if (forward) {
                setOutput(-speed, -speed)
            } else {
                setOutput(speed, speed)
            }
        } else {
            bounceLoopCount = 0
        }
        bounceLoopCount++
    }

    private fun linearController() {
        // TODO this needs to account for length of time on each interval, or overall time length. Here this just assumes a width of 1 for all intervals.

        int_e_t += e_t
        e_t = setPoint - thetaDeg
        // error between actual and desired wheel speed (default 0)
        val e_w = 0.0 - speedL
        Logger.v(TAG, "speedL:$speedL")

        val pOut = (pTilt * e_t) + (pWheel * e_w)
        val iOut = iTilt * int_e_t
        val dOut = dTilt * angularVelocityDeg

        setOutput((pOut + iOut + dOut).toFloat(), (pOut + iOut + dOut).toFloat())
    }

    // -------------- Input Data Listeners ----------------------------
    override fun onWheelDataUpdate(
        timestamp: Long, wheelCountL: Int, wheelCountR: Int,
        wheelDistanceL: Double, wheelDistanceR: Double,
        wheelSpeedInstantL: Double, wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double, wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double, wheelSpeedExpAvgR: Double
    ) {
        speedL = wheelSpeedExpAvgL
        // wheelData.setExpWeight(expWeight); // todo enable access to this in GUI somehow
    }

    override fun onOrientationUpdate(
        timestamp: Long,
        thetaRad: Double,
        angularVelocityRad: Double
    ) {
        thetaDeg = OrientationData.getThetaDeg(thetaRad)
        angularVelocityDeg = OrientationData.getAngularVelocityDeg(angularVelocityRad)
    }
}

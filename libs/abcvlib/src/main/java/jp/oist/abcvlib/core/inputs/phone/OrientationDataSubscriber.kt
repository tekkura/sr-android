package jp.oist.abcvlib.core.inputs.phone

import jp.oist.abcvlib.core.inputs.Subscriber

interface OrientationDataSubscriber : Subscriber {
    /**
     * See [BatteryDataSubscriber.onBatteryVoltageUpdate] ()}
     * @param timestamp in nanoseconds see [System.nanoTime]
     * @param thetaRad tilt angle in radians. See [OrientationData.getThetaDeg] for angle in degrees
     * @param angularVelocityRad
     */
    fun onOrientationUpdate(
        timestamp: Long,
        thetaRad: Double,
        angularVelocityRad: Double
    )
}

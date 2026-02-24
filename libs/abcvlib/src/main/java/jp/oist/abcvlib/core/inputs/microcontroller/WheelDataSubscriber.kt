package jp.oist.abcvlib.core.inputs.microcontroller

import jp.oist.abcvlib.core.inputs.Subscriber

interface WheelDataSubscriber : Subscriber {
    /**
     * Looping call from IOIOboard with quadrature encoder updates.
     * See [BatteryDataSubscriber.onBatteryVoltageUpdate] for details on looper.
     *
     * @param timestamp in nanoseconds, see [System.nanoTime]
     * @param wheelCountL left wheel encoder count
     * @param wheelCountR right wheel encoder count
     * @param wheelDistanceL left wheel distance traveled
     * @param wheelDistanceR right wheel distance traveled
     * @param wheelSpeedInstantL left wheel instantaneous speed
     * @param wheelSpeedInstantR right wheel instantaneous speed
     * @param wheelSpeedBufferedL left wheel buffered speed
     * @param wheelSpeedBufferedR right wheel buffered speed
     * @param wheelSpeedExpAvgL left wheel exponential average speed
     * @param wheelSpeedExpAvgR right wheel exponential average speed
     */
    fun onWheelDataUpdate(
        timestamp: Long, wheelCountL: Int, wheelCountR: Int,
        wheelDistanceL: Double, wheelDistanceR: Double,
        wheelSpeedInstantL: Double, wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double, wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double, wheelSpeedExpAvgR: Double
    )
}

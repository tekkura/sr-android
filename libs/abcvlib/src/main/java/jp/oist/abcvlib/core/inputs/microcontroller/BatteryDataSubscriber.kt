package jp.oist.abcvlib.core.inputs.microcontroller

import jp.oist.abcvlib.core.inputs.Subscriber

interface BatteryDataSubscriber : Subscriber {
    /**
     * Called every time the IOIOLooper runs once. Note this will happen at a variable time length
     * each call, but should be on the order of 2 milliseconds. You may want to ignore every 10
     * calls, filter results, or use the more robust TimeStepDataBuffer as a pipeline to access
     * this data.
     *
     * @param timestamp in nanoseconds, see [System.nanoTime]
     * @param voltage battery voltage reading
     */
    fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double)

    /**
     * See [onBatteryVoltageUpdate]
     *
     * @param timestamp in nanoseconds, see [System.nanoTime]
     * @param chargerVoltage charger voltage reading
     * @param coilVoltage coil voltage reading
     */
    fun onChargerVoltageUpdate(timestamp: Long, chargerVoltage: Double, coilVoltage: Double)
}

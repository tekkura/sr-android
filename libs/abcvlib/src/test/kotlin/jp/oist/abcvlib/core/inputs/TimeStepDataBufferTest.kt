package jp.oist.abcvlib.core.inputs

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class TimeStepDataBufferTest {
    @Test
    fun publicSnapshotArraysCannotMutateFrozenWheelData() {
        val buffer = TimeStepDataBuffer(bufferLength = 3)

        buffer.onWheelDataUpdate(
            timestamp = 100L,
            wheelCountL = 1,
            wheelCountR = 2,
            wheelDistanceL = 3.0,
            wheelDistanceR = 4.0,
            wheelSpeedInstantL = 5.0,
            wheelSpeedInstantR = 6.0,
            wheelSpeedBufferedL = 7.0,
            wheelSpeedBufferedR = 8.0,
            wheelSpeedExpAvgL = 9.0,
            wheelSpeedExpAvgR = 10.0
        )
        buffer.nextTimeStep()

        val leftWheelData = buffer.getReadData().wheelData.getLeft()
        leftWheelData.getTimeStamps()[0] = 999L
        leftWheelData.getCounts()[0] = 999
        leftWheelData.getDistances()[0] = 999.0
        leftWheelData.getSpeedsInstantaneous()[0] = 999.0
        leftWheelData.getSpeedsBuffered()[0] = 999.0
        leftWheelData.getSpeedsExpAvg()[0] = 999.0

        assertArrayEquals(longArrayOf(100L), leftWheelData.getTimeStamps())
        assertArrayEquals(intArrayOf(1), leftWheelData.getCounts())
        assertArrayEquals(doubleArrayOf(3.0), leftWheelData.getDistances(), 0.0)
        assertArrayEquals(doubleArrayOf(5.0), leftWheelData.getSpeedsInstantaneous(), 0.0)
        assertArrayEquals(doubleArrayOf(7.0), leftWheelData.getSpeedsBuffered(), 0.0)
        assertArrayEquals(doubleArrayOf(9.0), leftWheelData.getSpeedsExpAvg(), 0.0)
    }

    @Test
    fun publicSnapshotArraysCannotMutateFrozenBatteryAndOrientationData() {
        val buffer = TimeStepDataBuffer(bufferLength = 3)

        buffer.onBatteryVoltageUpdate(timestamp = 100L, voltage = 4.2)
        buffer.onOrientationUpdate(timestamp = 200L, thetaRad = 1.5, angularVelocityRad = 2.5)
        buffer.nextTimeStep()

        val readData = buffer.getReadData()
        readData.batteryData.getTimeStamps()[0] = 999L
        readData.batteryData.getVoltage()[0] = 999.0
        readData.orientationData.getTimeStamps()[0] = 999L
        readData.orientationData.getTiltAngle()[0] = 999.0
        readData.orientationData.getAngularVelocity()[0] = 999.0

        assertArrayEquals(longArrayOf(100L), readData.batteryData.getTimeStamps())
        assertArrayEquals(doubleArrayOf(4.2), readData.batteryData.getVoltage(), 0.0)
        assertArrayEquals(longArrayOf(200L), readData.orientationData.getTimeStamps())
        assertArrayEquals(doubleArrayOf(1.5), readData.orientationData.getTiltAngle(), 0.0)
        assertArrayEquals(
            doubleArrayOf(2.5),
            readData.orientationData.getAngularVelocity(),
            0.0
        )
    }
}

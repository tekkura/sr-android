package jp.oist.abcvlib.util.rp2040

import jp.oist.abcvlib.core.inputs.PublisherState
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class RP2040State(
    private val batteryData: BatteryData,
    private val wheelData: WheelData
) {
    var motorsState: MotorsState = MotorsState()
    var batteryDetails: BatteryDetails = BatteryDetails()
    var chargeSideUSB: ChargeSideUSB = ChargeSideUSB()

    fun updatePublishers() {
        if (batteryData.getState() != PublisherState.STARTED || wheelData.getState() != PublisherState.STARTED) {
            return
        }
        val ts = System.nanoTime()
        batteryData.onBatteryVoltageUpdate(ts, batteryDetails.getVoltage())
        //Todo need to implement coilVoltage get from rp2040
        batteryData.onChargerVoltageUpdate(
            ts,
            chargeSideUSB.getUsbChargerVoltage(),
            chargeSideUSB.getWirelessChargerVrect()
        )
        wheelData.onWheelDataUpdate(
            ts,
            motorsState.encoderCounts.left,
            motorsState.encoderCounts.right
        )
    }
}

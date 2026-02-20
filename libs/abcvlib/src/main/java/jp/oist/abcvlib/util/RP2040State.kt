package jp.oist.abcvlib.util

import jp.oist.abcvlib.core.inputs.PublisherState
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData

internal class RP2040State(
    private val batteryData: BatteryData,
    private val wheelData: WheelData
) {
    var motorsState: MotorsState = MotorsState()
    var batteryDetails: BatteryDetails = BatteryDetails()
    var chargeSideUSB: ChargeSideUSB = ChargeSideUSB()


    internal class MotorsState {
        class ControlValues {
            var left: Byte = 0
            var right: Byte = 0
        }

        class Faults {
            var left: Byte = 0
            var right: Byte = 0
        }

        class EncoderCounts {
            var left: Int = 0
            var right: Int = 0
        }

        var controlValues: ControlValues = ControlValues()
        var encoderCounts: EncoderCounts = EncoderCounts()
        var faults: Faults = Faults()
    }

    internal class BatteryDetails {
        // Raw byte data
        var voltageMv: Short = 0
        var temperature: Short = 0
        var safetyStatus: Byte = 0
        var stateOfHealth: Byte = 0
        var flags: Short = 0

        /* convert mV to V
          See bq27441-G1 Technical Reference Manual, Section 4.1.5
        */
        fun getVoltage(): Double {
            return voltageMv / 1000.0
        }

        fun getTemperatureV(): Double {
            return temperature / 10.0
        }
    }

    internal class ChargeSideUSB {
        var max77976_chg_details: Int = 0
        var ncp3901_wireless_charger_attached: Boolean = false
        var usb_charger_voltage: Short = 0
        var wireless_charger_vrect: Short = 0

        fun getMax77976ChgDetails(): Int {
            return max77976_chg_details
        }

        fun isWirelessChargerAttached(): Boolean {
            return ncp3901_wireless_charger_attached
        }

        fun getUsbChargerVoltage(): Double {
            return usb_charger_voltage / 1000.0
        }

        fun getWirelessChargerVrect(): Double {
            return wireless_charger_vrect / 1000.0
        }
    }

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

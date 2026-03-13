package jp.oist.abcvlib.backandforthPython

import android.os.Bundle
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.util.UsbSerial


class MainActivity : AbcvlibActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        super.onSerialReady(usbSerial)
    }
}
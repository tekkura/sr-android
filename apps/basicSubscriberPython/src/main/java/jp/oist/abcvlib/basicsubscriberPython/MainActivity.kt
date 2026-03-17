package jp.oist.abcvlib.basicsubscriberPython

import android.os.Bundle
import jp.oist.abcvlib.basicsubscriberPython.databinding.ActivityMainBinding
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial

class MainActivity : AbcvlibActivity(), SerialReadyListener {
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        super.onCreate(savedInstanceState)
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        super.onSerialReady(usbSerial)
    }
}


package jp.oist.abcvlib.backandforthPython

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.UsbSerial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class MainActivity : AbcvlibActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableMainLoop(false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        super.onSerialReady(usbSerial)
        initPython()
    }

    private fun initPython() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        val py = Python.getInstance()
        val setupModule = py.getModule("abcvlib")
        // inject variables to python
        setupModule.put("outputs", outputs)
        setupModule.put("loop_delay", 0.5) // 500 ms

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                setupModule.callAttr("run")
            } catch (e: PyException) {
                Logger.e("MainActivity", "Python error: ${e.message}")
            }
        }
    }
}
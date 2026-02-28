package jp.oist.abcvlib.basicqrreceiver

import android.os.Bundle
import android.widget.TextView
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.phone.QRCodeData
import jp.oist.abcvlib.core.inputs.phone.QRCodeDataSubscriber
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial

/**
 * Android application showing connection to IOIOBoard, Hubee Wheels, and Android Sensors
 * Initializes socket connection with external python server
 * Runs PID controller locally on Android, but takes PID parameters from python GUI
 * @author Christopher Buckley https://github.com/topherbuckley
 */
class MainActivity : AbcvlibActivity(), SerialReadyListener, QRCodeDataSubscriber {
    private lateinit var publisherManager: PublisherManager
    private lateinit var letterTextView: TextView

    private var speedL = 0f
    private var speedR = 0f
    private val speed = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        letterTextView = findViewById(R.id.letterTextView)
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        publisherManager = PublisherManager()

        val qrCodeData = QRCodeData.Builder(this, publisherManager, this).build()
        qrCodeData.addSubscriber(this)

        publisherManager.initializePublishers()
        publisherManager.startPublishers()

        setSerialCommManager(SerialCommManager(usbSerial))
        super.onSerialReady(usbSerial)
    }

    public override fun onOutputsReady() {
        publisherManager.initializePublishers()
        publisherManager.startPublishers()
    }

    override fun onQRCodeDetected(qrDataDecoded: String) {
        when (qrDataDecoded) {
            "L" -> turnLeft()
            "R" -> turnRight()
        }
    }

    // Main loop for any application extending AbcvlibActivity. This is where you will put your main code
    override fun abcvlibMainLoop() {
        outputs.setWheelOutput(speedL, speedR, false, false)
    }

    private fun turnRight() {
        speedL = -speed
        speedR = speed
        runOnUiThread { letterTextView.text = "R" }
    }

    private fun turnLeft() {
        speedL = speed
        speedR = -speed
        runOnUiThread { letterTextView.text = "L" }
    }
}

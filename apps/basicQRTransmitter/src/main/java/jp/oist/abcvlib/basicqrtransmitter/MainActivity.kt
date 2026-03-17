package jp.oist.abcvlib.basicqrtransmitter

import android.os.Bundle
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.phone.QRCodeData
import jp.oist.abcvlib.core.inputs.phone.QRCodeDataSubscriber
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.QRCode
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import java.util.concurrent.TimeUnit

/**
 * Android application showing connection to IOIOBoard, Hubee Wheels, and Android Sensors
 * Initializes socket connection with external python server
 * Runs PID controller locally on Android, but takes PID parameters from python GUI
 * @author Christopher Buckley https://github.com/topherbuckley
 */
class MainActivity : AbcvlibActivity(), SerialReadyListener, QRCodeDataSubscriber {
    private lateinit var qrCode: QRCode
    private lateinit var publisherManager: PublisherManager
    private var speedL = 0f
    private var speedR = 0f
    private val speed = 0.6f
    private enum class ACTIONS { TURN_LEFT, TURN_RIGHT }
    private var action = ACTIONS.TURN_RIGHT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        qrCode = QRCode(supportFragmentManager, R.id.qrFragmentView)
    }

    private val swapAction: Runnable = Runnable {
        if (action == ACTIONS.TURN_RIGHT) {
            action = ACTIONS.TURN_LEFT
            turnRight()
        } else {
            action = ACTIONS.TURN_RIGHT
            turnLeft()
        }
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
        val executor = ScheduledExecutorServiceWithException(1, ProcessPriorityThreadFactory(Thread.MIN_PRIORITY, "ActionSelector"))
        executor.scheduleAtFixedRate(swapAction, 0, 10, TimeUnit.SECONDS)
    }

    override fun onQRCodeDetected(qrDataDecoded: String) {
        if (qrDataDecoded.isNotEmpty()) {
            Logger.i("qrcode", "QR Code Found and decoded: $qrDataDecoded")
        }
    }

    // Main loop for any application extending AbcvlibActivity. This is where you will put your main code
    override fun abcvlibMainLoop() {
        outputs.setWheelOutput(speedL, speedR, false, false)
    }

    private fun turnRight() {
        speedL = -speed
        speedR = speed
        qrCodeRegenerate("R")
    }

    private fun turnLeft() {
        speedL = speed
        speedR = -speed
        qrCodeRegenerate("L")
    }

    private fun qrCodeRegenerate(qrcodeData: String) {
        qrCode.close()
        qrCode.generate(qrcodeData)
    }
}
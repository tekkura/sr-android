package jp.oist.abcvlib.pidbalancer

import android.os.Bundle
import android.view.View
import android.widget.Button
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.core.inputs.phone.OrientationData
import jp.oist.abcvlib.fragments.PidGuiFragament
import jp.oist.abcvlib.tests.BalancePIDController
import jp.oist.abcvlib.util.UsbSerial
import java.util.concurrent.TimeUnit

/**
 * Android application showing connection to IOIOBoard, Hubee Wheels, and Android Sensors
 * Initializes socket connection with external python server
 * Runs PID controller locally on Android, but takes PID parameters from python GUI
 * 
 * @author Christopher Buckley https://github.com/topherbuckley
 */
class MainActivity : AbcvlibActivity() {
    private val TAG: String = javaClass.name
    private lateinit var balancePIDController: BalancePIDController
    private lateinit var pidGuiFragment: PidGuiFragament

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        super.onCreate(savedInstanceState)
    }

    private fun displayPidUi() {
        pidGuiFragment = PidGuiFragament.newInstance(balancePIDController)
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.main_fragment, pidGuiFragment)
            .commit()
    }

    fun buttonClick(view: View) {
        val button = view as Button
        if (button.text == "Start") {
            // Sets initial values rather than wait for slider change
            pidGuiFragment.updatePID()
            button.text = "Stop"
            balancePIDController.startController()
        } else {
            button.text = "Start"
            balancePIDController.stopController()
        }
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        // Create your data publisher objects
        val publisherManager = PublisherManager()
        val orientationData = OrientationData.Builder(this, publisherManager).build()
        val wheelData = WheelData.Builder(this, publisherManager).build()
        // Initialize all publishers (i.e. start their threads and data streams)
        publisherManager.initializePublishers()

        // Create your controller/subscriber
        balancePIDController = BalancePIDController().apply {
            setInitDelay(0)
            setName("BalancePIDController")
            setThreadCount(1)
            setThreadPriority(Thread.NORM_PRIORITY)
            setTimestep(5)
            setTimeUnit(TimeUnit.MILLISECONDS)
        }

        // Attach the controller/subscriber to the publishers
        orientationData.addSubscriber(balancePIDController)
        wheelData.addSubscriber(balancePIDController)

        // Start your publishers
        publisherManager.startPublishers()

        super.onSerialReady(usbSerial)
    }

    override fun onOutputsReady() {
        // Adds your custom controller to the compounding master controller.
        getOutputs().masterController.addController(balancePIDController)
        // Start the master controller after adding and starting any customer controllers.
        getOutputs().startMasterController()

        runOnUiThread { displayPidUi() }
    }
}

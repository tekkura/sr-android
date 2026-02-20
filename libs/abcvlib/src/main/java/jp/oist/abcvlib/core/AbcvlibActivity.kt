package jp.oist.abcvlib.core

import android.app.AlertDialog
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import jp.oist.abcvlib.core.outputs.Outputs
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * AbcvlibActivity is where all the other classes are initialized into objects. The objects
 * are then passed to one another in order to coordinate the various shared values between them.
 * 
 * Android app MainActivity can start Motion by extending AbcvlibActivity and then running
 * any of the methods within the object instance Motion within an infinite threaded loop
 * e.g:
 * 
 * @author Christopher Buckley https://github.com/topherbuckley
 */
abstract class AbcvlibActivity : AppCompatActivity(), SerialReadyListener {
    var switches = Switches()
    protected lateinit var usbSerial: UsbSerial
    protected lateinit var outputs: Outputs
    private var serialCommManager: SerialCommManager? = null
    private var android2PiWriter: Runnable? = null
    private var pi2AndroidReader: Runnable? = null
    private var alertDialog: AlertDialog? = null
    private var initialDelay: Long = 0

    // Note anything less than 10ms will result in no GET_STATE commands being called and all
    // being overrides by whatever commands are sent in the main loop
    private var delay: Long = 5
    private var isCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        isCreated = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usbInitialize()
        super.onCreate(savedInstanceState)
    }

    private fun usbInitialize() {
        try {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            this.usbSerial = UsbSerial(this, usbManager, this)
        } catch (e: IOException) {
            e.printStackTrace()
            showCustomDialog()
        }
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        if (serialCommManager == null) {
            Logger.w(
                TAG, "Default SerialCommManager being used. If you intended to create your " +
                        "own, make sure you initialize it in onCreate prior to calling super.onCreate()."
            )
            serialCommManager = SerialCommManager(usbSerial)
        }
        serialCommManager!!.start()

        initializeOutputs()
        onOutputsReady()

        // Needs to be > 5 in order for object detector not to overwhelm cpu
        val priority = ProcessPriorityThreadFactory(Thread.MAX_PRIORITY, "AbcvlibActivityMainLoop")
        Executors.newSingleThreadScheduledExecutor(priority).scheduleWithFixedDelay(
            AbcvlibActivityRunnable(), this.initialDelay, this.delay, TimeUnit.MILLISECONDS
        )
    }

    private inner class AbcvlibActivityRunnable : Runnable {
        override fun run() {
            abcvlibMainLoop()
        }
    }

    protected open fun abcvlibMainLoop() {
        // Throw runtime error if this is called and indicate to user that this needs to be overridden
        throw RuntimeException("runAbcvlibActivityMainLoop must be overridden")
    }

    protected open fun onOutputsReady() {
        // Override this method in your MainActivity to do anything that requires the outputs
        Logger.w(
            TAG, "onOutputsReady not overridden. Override this method in your MainActivity to " +
                    "do anything that requires the outputs."
        )
    }

    private fun initializeOutputs() {
        outputs = Outputs(switches, serialCommManager!!)
    }

    protected fun setSerialCommManager(serialCommManager: SerialCommManager) {
        this.serialCommManager = serialCommManager
    }

    protected fun setInitialDelay(initialDelay: Long) {
        if (isCreated) {
            throw RuntimeException("setInitialDelay must be called before onCreate")
        }
        this.initialDelay = initialDelay
    }

    protected fun setDelay(delay: Long) {
        if (isCreated) {
            throw RuntimeException("setDelay must be called before onCreate")
        }
        this.delay = delay
    }


    fun onEncoderCountsRec(left: Int, right: Int) {
        Logger.d("serial", "Left encoder count: $left")
        Logger.d("serial", "Right encoder count: $right")
    }

    protected fun setAndroid2PiWriter(android2PiWriter: Runnable) {
        this.android2PiWriter = android2PiWriter
    }

    protected fun setPi2AndroidReader(pi2AndroidReader: Runnable) {
        this.pi2AndroidReader = pi2AndroidReader
    }

    private fun showCustomDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.missing_robot, null)
        builder.setView(dialogView)

        // Find the TextView and Button in the dialog layout
        val confirmButton = dialogView.findViewById<Button>(R.id.confirmButton)

        // Set a click listener for the Confirm button
        confirmButton.setOnClickListener { // Dismiss the dialog
            alertDialog?.dismiss()
            usbInitialize()
        }

        // Create the AlertDialog
        alertDialog = builder.create()
        // Show the dialog
        alertDialog?.show()
    }

    override fun onStop() {
        super.onStop()
        Logger.v(TAG, "End of AbcvlibActivity.onStop")
    }

    public override fun onPause() {
        super.onPause()
        serialCommManager?.let {
            it.setMotorLevels(0f, 0f, true, true)
            it.stop()
        }
        Logger.i(TAG, "End of AbcvlibActivity.onPause")
    }


    companion object {
        private const val TAG = "abcvlib"
    }
}

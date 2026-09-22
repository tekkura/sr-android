package jp.oist.abcvlib.core

import android.app.AlertDialog
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.outputs.Outputs
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
    @Volatile
    private var serialCommManager: SerialCommManager? = null
    private var android2PiWriter: Runnable? = null
    private var pi2AndroidReader: Runnable? = null
    private var alertDialog: AlertDialog? = null
    private var initialDelay: Long = 0
    private var serialReadyJob: Job? = null
    private val serialLifecycleLock = Any()
    private val publisherManagers = mutableListOf<PublisherManager>()
    private val publisherManagersLock = Any()
    private var mainLoopExecutor: ScheduledExecutorService? = null
    private val outputsInitializationLock = Any()
    private var outputsInitializedFor: SerialCommManager? = null
    private var startedSerialManager: SerialCommManager? = null
    private val startupGeneration = AtomicLong()

    @Volatile
    private var startupAllowed = true

    // Note anything less than 10ms will result in no GET_STATE commands being called and all
    // being overrides by whatever commands are sent in the main loop
    private var delay: Long = 5
    private var isCreated = false
    private var mainLoopEnabled = true

    @Volatile
    protected var isActivityResumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        isCreated = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usbInitialize()
        super.onCreate(savedInstanceState)

        val contentView = findViewById<android.view.View>(android.R.id.content)
        contentView?.let { view ->
            val initialPaddingLeft = view.paddingLeft
            val initialPaddingTop = view.paddingTop
            val initialPaddingRight = view.paddingRight
            val initialPaddingBottom = view.paddingBottom

            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(
                    initialPaddingLeft + systemBars.left,
                    initialPaddingTop + systemBars.top,
                    initialPaddingRight + systemBars.right,
                    initialPaddingBottom + systemBars.bottom
                )
                insets
            }
        }
    }

    private fun usbInitialize() {
        try {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            this.usbSerial = UsbSerial(
                context = this,
                usbManager = usbManager,
                serialReadyListener = object : SerialReadyListener {
                    override fun onSerialReady(usbSerial: UsbSerial) {
                        lifecycleScope.launch {
                            // Cancel the previous serialReadyJob if it exists to avoid multiple executions in parallel.
                            serialReadyJob?.cancelAndJoin()
                            // Call Activity's onSerialReady inside (Dispatchers.Default) to avoid blocking the main thread
                            serialReadyJob = launch(Dispatchers.Default) {
                                this@AbcvlibActivity.onSerialReady(usbSerial)
                            }
                        }
                    }
                }
            )
        } catch (e: IOException) {
            e.printStackTrace()
            showCustomDialog()
        }
    }

    @WorkerThread
    override fun onSerialReady(usbSerial: UsbSerial) {
        synchronized(serialLifecycleLock) {
            if (serialCommManager == null) {
                Logger.w(
                    TAG, "Default SerialCommManager being used. If you intended to create your " +
                            "own, make sure you initialize it in onCreate prior to calling super.onCreate()."
                )
                serialCommManager = SerialCommManager(usbSerial)
            }
            if (isActivityResumed) {
                startSerialCommManager(serialCommManager!!)
            }
        }
    }

    // Must be called with serialLifecycleLock held.
    private fun startSerialCommManager(manager: SerialCommManager) {
        if (startedSerialManager === manager) return

        val generation = startupGeneration.incrementAndGet()
        startedSerialManager = manager
        manager.setFirmwareCompatibilityFailureListener { message ->
            mainLoopExecutor?.shutdownNow()
            mainLoopExecutor = null
            runOnUiThread {
                if (startupAllowed && startupGeneration.get() == generation &&
                    serialCommManager === manager
                ) {
                    showCustomDialog(message)
                }
            }
        }
        manager.start(onReady = {
            if (isStartupCurrent(manager, generation)) {
                synchronized(outputsInitializationLock) {
                    if (isStartupCurrent(manager, generation) &&
                        outputsInitializedFor !== manager
                    ) {
                        initializeOutputs(manager)
                        onOutputsReady()
                        outputsInitializedFor = manager
                    }
                }

                synchronized(serialLifecycleLock) {
                    if (mainLoopEnabled && isStartupCurrent(manager, generation)) {
                        val priority = ProcessPriorityThreadFactory(
                            Thread.MAX_PRIORITY,
                            "AbcvlibActivityMainLoop"
                        )
                        mainLoopExecutor?.shutdownNow()
                        mainLoopExecutor = Executors.newSingleThreadScheduledExecutor(priority)
                        mainLoopExecutor!!.scheduleWithFixedDelay(
                            AbcvlibActivityRunnable(), this.initialDelay, this.delay,
                            TimeUnit.MILLISECONDS
                        )
                    }
                }
            }
        })
    }

    private fun isStartupCurrent(manager: SerialCommManager, generation: Long): Boolean {
        return startupAllowed && startupGeneration.get() == generation &&
                serialCommManager === manager
    }

    private inner class AbcvlibActivityRunnable : Runnable {
        override fun run() {
            if (isActivityResumed) {
                abcvlibMainLoop()
            }
        }
    }

    @WorkerThread
    protected open fun abcvlibMainLoop() {
        // Throw runtime error if this is called and indicate to user that this needs to be overridden
        throw RuntimeException("runAbcvlibActivityMainLoop must be overridden")
    }

    @WorkerThread
    protected open fun onOutputsReady() {
        // Override this method in your MainActivity to do anything that requires the outputs
        Logger.w(
            TAG, "onOutputsReady not overridden. Override this method in your MainActivity to " +
                    "do anything that requires the outputs."
        )
    }


    private fun initializeOutputs(manager: SerialCommManager) {
        outputs = Outputs(switches, manager)
    }

    protected fun setSerialCommManager(serialCommManager: SerialCommManager) {
        synchronized(serialLifecycleLock) {
            if (this.serialCommManager !== serialCommManager) {
                startedSerialManager?.stop()
                startedSerialManager = null
                startupGeneration.incrementAndGet()
                this.serialCommManager = serialCommManager
            }
        }
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

    protected fun enableMainLoop(enable: Boolean) {
        if (isCreated) {
            throw RuntimeException("enableMainLoop must be called before onCreate")
        }
        this.mainLoopEnabled = enable
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

    /**
     * Registers publishers that should only process data while this Activity is visible.
     * Applications create their managers when hardware becomes available, so registration is
     * intentionally explicit and also handles managers created after onResume. Registration is
     * replacement-based: each Activity owns one current PublisherManager for its active serial
     * setup, and registering a different manager stops any manager from an older setup.
     */
    protected fun registerPublisherManager(publisherManager: PublisherManager) {
        val (staleManagers, pauseForLifecycle) = synchronized(publisherManagersLock) {
            if (publisherManagers.contains(publisherManager)) {
                emptyList<PublisherManager>() to !isActivityResumed
            } else {
                publisherManagers.toList().also {
                    publisherManagers.clear()
                    publisherManagers.add(publisherManager)
                } to !isActivityResumed
            }
        }

        for (staleManager in staleManagers) {
            staleManager.stopPublishers()
        }

        if (pauseForLifecycle) {
            publisherManager.pauseForLifecycle()
        }
    }

    fun isAbcvlibActivityResumed(): Boolean {
        return isActivityResumed
    }

    private fun pausePublisherManagers() {
        synchronized(publisherManagersLock) {
            publisherManagers.toList()
        }.forEach { it.pauseForLifecycle() }
    }

    private fun resumePublisherManagers() {
        synchronized(publisherManagersLock) {
            publisherManagers.toList()
        }.forEach { it.resumeAfterLifecycle() }
    }

    private fun clearPublisherManagers() {
        val managersToStop = synchronized(publisherManagersLock) {
            publisherManagers.toList().also {
                publisherManagers.clear()
            }
        }
        for (publisherManager in managersToStop) {
            publisherManager.stopPublishers()
        }
    }

    private fun showCustomDialog(
        message: String = getString(R.string.robot_not_properly_attached_please_reattach_and_press_confirm)
    ) {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.missing_robot, null)
        builder.setView(dialogView)

        // Find the TextView and Button in the dialog layout
        val messageTextView = dialogView.findViewById<TextView>(R.id.messageTextView)
        val confirmButton = dialogView.findViewById<Button>(R.id.confirmButton)
        messageTextView.text = message

        // Set a click listener for the Confirm button
        confirmButton.setOnClickListener { // Dismiss the dialog
            alertDialog?.dismiss()
            synchronized(serialLifecycleLock) {
                startupGeneration.incrementAndGet()
                startedSerialManager = null
                serialCommManager?.stop()
                serialCommManager = null
            }
            clearPublisherManagers()
            mainLoopExecutor?.shutdownNow()
            mainLoopExecutor = null

            if (::usbSerial.isInitialized)
                usbSerial.close()

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

    override fun onResume() {
        super.onResume()
        startupAllowed = true
        isActivityResumed = true
        resumePublisherManagers()
        synchronized(serialLifecycleLock) {
            serialCommManager?.let { startSerialCommManager(it) }
        }
        Logger.i(TAG, "AbcvlibActivity resumed: publishers and main loop enabled")
    }

    public override fun onPause() {
        isActivityResumed = false
        startupAllowed = false
        startupGeneration.incrementAndGet()
        serialReadyJob?.cancel()
        pausePublisherManagers()
        super.onPause()
        synchronized(serialLifecycleLock) {
            startedSerialManager = null
            serialCommManager?.let {
                it.setMotorLevels(0f, 0f, true, true)
                it.stop()
            }
        }
        mainLoopExecutor?.shutdownNow()
        mainLoopExecutor = null
        Logger.i(TAG, "End of AbcvlibActivity.onPause")
    }

    override fun onDestroy() {
        mainLoopExecutor?.shutdownNow()
        mainLoopExecutor = null
        clearPublisherManagers()
        super.onDestroy()
    }


    companion object {
        private const val TAG = "abcvlib"
    }
}

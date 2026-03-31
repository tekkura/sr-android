package jp.oist.abcvlib.rosbridge

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import jp.oist.abcvlib.rosbridge.databinding.ActivityMainBinding
import jp.oist.abcvlib.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : AppCompatActivity(), RosBridgeClientListener {
    private val rosBridgeClient: RosBridgeClient = RosBridgeClient(this)
    private lateinit var binding: ActivityMainBinding

    private enum class StepState {
        NOT_STARTED,
        RUNNING,
        PASS,
        FAIL
    }

    private var connectState = StepState.NOT_STARTED
    private var subscribeState = StepState.NOT_STARTED
    private var publishState = StepState.NOT_STARTED
    private var smokeTestActive = false
    private var smokeSummaryLogged = false
    private var stepTimeoutJob: Job? = null
    private var pendingPublishAck: String? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val SMOKE_TEST_TAG = "SmokeTest"
        private const val EXTRA_ROS_PC_IP = "ROS_PC_IP"
        private const val EXTRA_AUTO_RUN_SMOKE_TEST = "AUTO_RUN_SMOKE_TEST"
        private const val EXTRA_SMOKE_TEST_MESSAGE = "SMOKE_TEST_MESSAGE"
        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
        private const val PUBLISH_TIMEOUT_MS = 5_000L
        private const val SUBSCRIBE_TOPIC = "/test_from_ros"
        private const val PUBLISH_TOPIC = "/test_from_android"
        private const val PUBLISH_ACK_TOPIC = "/test_from_android_ack"
        private const val DEFAULT_PUBLISH_MESSAGE = "hello from android"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonConnect.setOnClickListener {
            val ip = binding.rosPcIp.text.toString().trim()
            if (ip.isNotBlank()) {
                binding.connectionIndicator.show()
                rosBridgeClient.connect(ip)
            } else {
                binding.rosPcIp.error = getString(R.string.ros_ip_required)
                binding.rosPcIp.requestFocus();
            }
        }

        binding.buttonPublish.setOnClickListener {
            val message = currentPublishMessage()
            lifecycleScope.launch(Dispatchers.IO) {
                rosBridgeClient.advertise(PUBLISH_TOPIC)
                rosBridgeClient.publish(PUBLISH_TOPIC, message)
            }
            binding.textPublishTo.text = getString(R.string.ros_publish_to, PUBLISH_TOPIC)

            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.rosMessage.windowToken, 0)
        }

        binding.textStatus.setOnClickListener {
            rosBridgeClient.disconnect()
        }

        binding.test.setOnClickListener {
            startSmokeTest()
        }

        binding.layoutConnected.hide()
        binding.connectionIndicator.hide()
        binding.textReceived.movementMethod = ScrollingMovementMethod()
        renderSmokeTestStatus()
        handleLaunchExtras()
    }


    override fun onConnected(url: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.textStatus.text = getString(R.string.ros_status, url)
            binding.layoutConnected.show()
            binding.layoutDisconnected.hide()
            binding.connectionIndicator.hide()
            binding.textSubscribeTo.text = getString(R.string.ros_subscribe_to, SUBSCRIBE_TOPIC)
            binding.textPublishTo.text = getString(R.string.ros_publish_to, PUBLISH_TOPIC)
            if (smokeTestActive) {
                connectState = StepState.PASS
                logStepState("connect", connectState)
                renderSmokeTestStatus()
                startSubscribeStep()
            } else {
                withContext(Dispatchers.IO) {
                    rosBridgeClient.subscribe(SUBSCRIBE_TOPIC)
                }
            }
        }
    }

    override fun onMessage(topic: String, message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.textReceived.append("$message\n")
            if (smokeTestActive && topic == SUBSCRIBE_TOPIC && subscribeState == StepState.RUNNING) {
                stepTimeoutJob?.cancel()
                subscribeState = StepState.PASS
                logStepState("subscribe", subscribeState)
                renderSmokeTestStatus()
                startPublishStep()
            } else if (
                smokeTestActive &&
                topic == PUBLISH_ACK_TOPIC &&
                publishState == StepState.RUNNING &&
                message == pendingPublishAck
            ) {
                stepTimeoutJob?.cancel()
                pendingPublishAck = null
                publishState = StepState.PASS
                logStepState("publish", publishState)
                renderSmokeTestStatus()
                emitSmokeSummary()
            }
        }
    }

    override fun onDisconnected() {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.layoutConnected.hide()
            binding.layoutDisconnected.show()
            binding.textReceived.text = ""
            binding.rosMessage.setText("")
        }
        stepTimeoutJob?.cancel()
        if (smokeTestActive && !smokeSummaryLogged) {
            failRunningStep("Disconnected")
        } else if (!smokeTestActive) {
            resetSmokeTestState()
            renderSmokeTestStatus()
        }
    }

    override fun onError(error: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.connectionIndicator.hide()
            Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
        }
        stepTimeoutJob?.cancel()
        if (smokeTestActive && !smokeSummaryLogged) {
            failRunningStep(error)
        } else {
            resetSmokeTestState()
            renderSmokeTestStatus()
        }
    }

    override fun onDestroy() {
        rosBridgeClient.disconnect()
        super.onDestroy()
    }

    private fun View.show() {
        this.visibility = View.VISIBLE
    }

    private fun View.hide() {
        this.visibility = View.GONE
    }

    private fun handleLaunchExtras() {
        intent.getStringExtra(EXTRA_ROS_PC_IP)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            binding.rosPcIp.setText(it)
        }
        intent.getStringExtra(EXTRA_SMOKE_TEST_MESSAGE)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            binding.rosMessage.setText(it)
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_RUN_SMOKE_TEST, false)) {
            binding.root.post { startSmokeTest() }
        }
    }

    private fun startSmokeTest() {
        val ip = binding.rosPcIp.text.toString().trim()
        if (ip.isBlank()) {
            binding.rosPcIp.error = getString(R.string.ros_ip_required)
            binding.rosPcIp.requestFocus()
            return
        }

        binding.connectionIndicator.show()
        binding.textReceived.text = ""
        smokeTestActive = true
        smokeSummaryLogged = false
        pendingPublishAck = null
        stepTimeoutJob?.cancel()
        connectState = StepState.RUNNING
        subscribeState = StepState.NOT_STARTED
        publishState = StepState.NOT_STARTED
        renderSmokeTestStatus()

        if (rosBridgeClient.isConnected()) {
            lifecycleScope.launch(Dispatchers.Main) {
                binding.layoutConnected.show()
                binding.layoutDisconnected.hide()
                binding.connectionIndicator.hide()
                connectState = StepState.PASS
                logStepState("connect", connectState)
                renderSmokeTestStatus()
                startSubscribeStep()
            }
        } else {
            rosBridgeClient.connect(ip)
        }
    }

    private fun startSubscribeStep() {
        if (!smokeTestActive) return
        subscribeState = StepState.RUNNING
        publishState = StepState.NOT_STARTED
        renderSmokeTestStatus()
        lifecycleScope.launch(Dispatchers.IO) {
            val subscribedOk = rosBridgeClient.subscribe(SUBSCRIBE_TOPIC)
            withContext(Dispatchers.Main) {
                if (!smokeTestActive) return@withContext
                if (!subscribedOk) {
                    subscribeState = StepState.FAIL
                    logStepState("subscribe", subscribeState)
                    renderSmokeTestStatus()
                    emitSmokeSummary()
                    return@withContext
                }
                stepTimeoutJob?.cancel()
                stepTimeoutJob = lifecycleScope.launch {
                    delay(SUBSCRIBE_TIMEOUT_MS)
                    if (smokeTestActive && subscribeState == StepState.RUNNING) {
                        subscribeState = StepState.FAIL
                        logStepState("subscribe", subscribeState)
                        renderSmokeTestStatus()
                        emitSmokeSummary()
                    }
                }
            }
        }
    }

    private fun startPublishStep() {
        if (!smokeTestActive) return
        publishState = StepState.RUNNING
        renderSmokeTestStatus()
        val message = currentPublishMessage()
        pendingPublishAck = message
        binding.rosMessage.setText(message)
        lifecycleScope.launch(Dispatchers.IO) {
            val ackSubscribed = rosBridgeClient.subscribe(PUBLISH_ACK_TOPIC)
            val advertised = ackSubscribed && rosBridgeClient.advertise(PUBLISH_TOPIC)
            if (advertised) {
                delay(100)
            }
            val publishedOk = advertised && rosBridgeClient.publish(PUBLISH_TOPIC, message)
            withContext(Dispatchers.Main) {
                if (!smokeTestActive) return@withContext
                if (!publishedOk) {
                    publishState = StepState.FAIL
                    pendingPublishAck = null
                    logStepState("publish", publishState)
                    renderSmokeTestStatus()
                    emitSmokeSummary()
                    return@withContext
                }
                stepTimeoutJob?.cancel()
                stepTimeoutJob = lifecycleScope.launch {
                    delay(PUBLISH_TIMEOUT_MS)
                    if (smokeTestActive && publishState == StepState.RUNNING) {
                        publishState = StepState.FAIL
                        pendingPublishAck = null
                        logStepState("publish", publishState, "No ack received on $PUBLISH_ACK_TOPIC")
                        renderSmokeTestStatus()
                        emitSmokeSummary()
                    }
                }
            }
        }
    }

    private fun currentPublishMessage(): String {
        return binding.rosMessage.text.toString().trim().ifBlank { DEFAULT_PUBLISH_MESSAGE }
    }

    private fun failRunningStep(reason: String) {
        when {
            connectState == StepState.RUNNING -> {
                connectState = StepState.FAIL
                logStepState("connect", connectState, reason)
            }
            subscribeState == StepState.RUNNING -> {
                subscribeState = StepState.FAIL
                logStepState("subscribe", subscribeState, reason)
            }
            publishState == StepState.RUNNING -> {
                publishState = StepState.FAIL
                logStepState("publish", publishState, reason)
            }
        }
        renderSmokeTestStatus()
        emitSmokeSummary()
    }

    private fun emitSmokeSummary() {
        if (smokeSummaryLogged) return
        val done = connectState == StepState.FAIL ||
            subscribeState == StepState.FAIL ||
            publishState == StepState.FAIL ||
            (connectState == StepState.PASS &&
                subscribeState == StepState.PASS &&
                publishState == StepState.PASS)
        if (!done) return

        smokeSummaryLogged = true
        smokeTestActive = false
        binding.connectionIndicator.hide()
        val overall = if (
            connectState == StepState.PASS &&
            subscribeState == StepState.PASS &&
            publishState == StepState.PASS
        ) {
            "PASS"
        } else {
            "FAIL"
        }
        val summary = "$overall connect=$connectState subscribe=$subscribeState publish=$publishState"
        binding.testResult.text = summary
        if (overall == "PASS") {
            Logger.i(SMOKE_TEST_TAG, summary)
        } else {
            Logger.e(SMOKE_TEST_TAG, summary)
        }
    }

    private fun renderSmokeTestStatus() {
        binding.testResult.text =
            "connect=$connectState subscribe=$subscribeState publish=$publishState"
    }

    private fun logStepState(step: String, state: StepState, reason: String? = null) {
        val suffix = reason?.let { " reason=$it" } ?: ""
        val message = "$step=$state$suffix"
        if (state == StepState.FAIL) {
            Logger.e(SMOKE_TEST_TAG, message)
        } else {
            Logger.i(SMOKE_TEST_TAG, message)
        }
    }

    private fun resetSmokeTestState() {
        smokeTestActive = false
        smokeSummaryLogged = false
        pendingPublishAck = null
        connectState = StepState.NOT_STARTED
        subscribeState = StepState.NOT_STARTED
        publishState = StepState.NOT_STARTED
    }
}

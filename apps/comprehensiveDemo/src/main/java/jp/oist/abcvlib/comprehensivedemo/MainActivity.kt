package jp.oist.abcvlib.comprehensivedemo

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Detection
import jp.oist.abcvlib.comprehensivedemo.databinding.ActivityMainBinding
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryData
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryDataSubscriber
import jp.oist.abcvlib.core.inputs.microcontroller.WheelData
import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorData
import jp.oist.abcvlib.core.inputs.phone.ObjectDetectorDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.OrientationData
import jp.oist.abcvlib.core.inputs.phone.OrientationDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.QRCodeData
import jp.oist.abcvlib.core.inputs.phone.QRCodeDataSubscriber
import jp.oist.abcvlib.util.ControlLatencyTrace
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.QRCode
import jp.oist.abcvlib.util.SerialCommManager
import jp.oist.abcvlib.util.SerialReadyListener
import jp.oist.abcvlib.util.UsbSerial
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * First integrated app scaffold for the comprehensiveDemo milestone.
 *
 * This app intentionally focuses on wiring publishers, exposing explicit state, and driving a
 * periodic behavior-selection loop. The individual behavior handlers are deliberately simple and
 * act as placeholders for later milestone PRs.
 */
class MainActivity : AbcvlibActivity(), SerialReadyListener, BatteryDataSubscriber,
    WheelDataSubscriber, OrientationDataSubscriber, ObjectDetectorDataSubscriber,
    QRCodeDataSubscriber {

    private lateinit var binding: ActivityMainBinding
    private lateinit var publisherManager: PublisherManager
    private lateinit var boundingBoxView: BoundingBoxView
    private lateinit var previewView: PreviewView
    private lateinit var qrCode: QRCode
    private val controller: DemoController = ComprehensiveDemoControllerProvider.create()
    private val robotSinger = RobotSinger()
    private lateinit var behaviorOverrideOptions: List<BehaviorOverrideOption>

    private var batteryVoltage = 0.0
    private var chargerVoltage = 0.0
    private var coilVoltage = 0.0
    private var latestImageWidth = 0
    private var latestImageHeight = 0
    private var latestRobotDetection: Detection? = null
    private var latestPuckDetection: Detection? = null
    private var latestRobotDetectionReceivedAtMs = 0L
    private var latestRobotDetectionInferenceMs = 0L
    private var lastApproachLatencyLogAtMs = 0L
    private var latestRobotFrameCapturedAtNs = 0L
    private var latestRobotDetectCompletedAtNs = 0L
    private var latestThetaDeg = 0.0
    private var latestAngularVelocityDeg = 0.0
    private var latestWheelSpeedDeg = 0.0
    private var qrVisibleApplied = false
    private var sexualDisplayAudioApplied = false
    private var wasOnCharger = false
    private var recentlyUndockedUntilMs = 0L
    private val localQrId = generateQrId()
    private var currentQrColor = generateInitialQrColor()
    private var lastProcessedRemoteQrPayload: String? = null
    private var lastQrBlendAtMs = 0L
    private var forcedBehavior: Behavior? = null
    @Volatile
    private var outputsReadyCompleted = false
    @Volatile
    private var activityResumed = false
    @Volatile
    private var hardwareReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        setDelay(20)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        previewView = binding.cameraXPreview
        boundingBoxView = binding.boundingBoxView
        qrCode = QRCode(supportFragmentManager, R.id.qrFragmentView)
        binding.qrFragmentView.visibility = View.GONE
        setupBehaviorOverrideSelector()
        updateCurrentBehaviorStatus()
        Logger.i(
            "ComprehensiveDemo",
            "Initial QR payload ${currentQrPayload()} id=$localQrId color=${currentQrColorHex()}"
        )

        lifecycleScope.launch {
            delay(100)
            updateBehavior()
            while (isActive) {
                updateBehavior()
                delay(2.seconds)
            }
        }

        super.onCreate(savedInstanceState)

        previewView.post {
            boundingBoxView.setPreviewDimensions(previewView.width, previewView.height)
        }
        previewView.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            boundingBoxView.setPreviewDimensions(right - left, bottom - top)
        }
    }

    override fun onSerialReady(usbSerial: UsbSerial) {
        publisherManager = PublisherManager()

        val batteryData = BatteryData.Builder(this, publisherManager).build()
        batteryData.addSubscriber(this)
        val wheelData = WheelData.Builder(this, publisherManager).build()
        wheelData.addSubscriber(this)
        val orientationData = OrientationData.Builder(this, publisherManager).build()
        orientationData.addSubscriber(this)

        ObjectDetectorData.Builder(this, publisherManager, this)
            .setDelegate(ObjectDetectorData.delegates.DELEGATE_GPU)
            .setModel("model.tflite")
            .setPreviewView(previewView)
            .build()
            .addSubscriber(this)

        QRCodeData.Builder(this, publisherManager, this).build().addSubscriber(this)

        setSerialCommManager(SerialCommManager(usbSerial, batteryData, wheelData))
        super.onSerialReady(usbSerial)
    }

    public override fun onOutputsReady() {
        publisherManager.initializePublishers()
        publisherManager.startPublishers()
        outputsReadyCompleted = true
        updateHardwareReady()
    }

    override fun abcvlibMainLoop() {
        val now = System.currentTimeMillis()
        val nowUptime = SystemClock.uptimeMillis()
        val nowNs = SystemClock.elapsedRealtimeNanos()
        updateRecentlyUndocked(now)
        val state = getState(now)
        setQrVisible(controller.qrVisible)
        setSexualDisplayAudio(activityResumed && controller.currentBehavior == Behavior.SEXUAL_DISPLAY)
        val command = controller.wheelCommand(state, now)
        if (
            controller.currentBehavior == Behavior.APPROACH_ANOTHER_ROBOT &&
            latestRobotDetection != null &&
            latestRobotDetectionReceivedAtMs > 0L &&
            nowUptime - lastApproachLatencyLogAtMs >= APPROACH_LATENCY_LOG_INTERVAL_MS
        ) {
            lastApproachLatencyLogAtMs = nowUptime
            Logger.v(
                "ComprehensiveDemo",
                "e2eLatency detectionAgeMs=${nowUptime - latestRobotDetectionReceivedAtMs} " +
                    "inferenceMs=$latestRobotDetectionInferenceMs " +
                    "captureToDetectMs=${nsToMs(latestRobotDetectCompletedAtNs - latestRobotFrameCapturedAtNs)} " +
                    "detectToCommandMs=${nsToMs(nowNs - latestRobotDetectCompletedAtNs)} " +
                    "captureToCommandMs=${nsToMs(nowNs - latestRobotFrameCapturedAtNs)} " +
                    "requestedL=${"%.3f".format(ControlLatencyTrace.requestedLeft)} " +
                    "requestedR=${"%.3f".format(ControlLatencyTrace.requestedRight)} " +
                    "sentL=${"%.3f".format(ControlLatencyTrace.sentLeft)} " +
                    "sentR=${"%.3f".format(ControlLatencyTrace.sentRight)} " +
                    "outputsDtMs=${ControlLatencyTrace.outputsDtMs} " +
                    "queueToSendMs=${ControlLatencyTrace.queueToSendMs}"
            )
        }
        outputs.setWheelOutput(command.left, command.right, command.leftBrake, command.rightBrake)
    }

    override fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
        batteryVoltage = voltage
    }

    override fun onChargerVoltageUpdate(
        timestamp: Long,
        chargerVoltage: Double,
        coilVoltage: Double
    ) {
        this.chargerVoltage = chargerVoltage
        this.coilVoltage = coilVoltage
    }

    override fun onWheelDataUpdate(
        timestamp: Long,
        wheelCountL: Int,
        wheelCountR: Int,
        wheelDistanceL: Double,
        wheelDistanceR: Double,
        wheelSpeedInstantL: Double,
        wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double,
        wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double,
        wheelSpeedExpAvgR: Double
    ) {
        latestWheelSpeedDeg = (wheelSpeedExpAvgL + wheelSpeedExpAvgR) / 2.0
    }

    override fun onOrientationUpdate(
        timestamp: Long,
        thetaRad: Double,
        angularVelocityRad: Double
    ) {
        latestThetaDeg = OrientationData.getThetaDeg(thetaRad)
        latestAngularVelocityDeg = OrientationData.getAngularVelocityDeg(angularVelocityRad)
    }

    override fun onQRCodeDetected(qrDataDecoded: String) {
        val trimmedPayload = qrDataDecoded.trim()
        if (trimmedPayload.isEmpty()) {
            return
        }
        Logger.i("ComprehensiveDemo", "QR detected: $trimmedPayload")
        val remoteQr = parseQrPayload(trimmedPayload) ?: return
        if (remoteQr.id == localQrId) {
            Logger.v("ComprehensiveDemo", "Ignoring self QR payload $trimmedPayload")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (
            trimmedPayload.equals(lastProcessedRemoteQrPayload, ignoreCase = true) &&
            now - lastQrBlendAtMs < QR_BLEND_COOLDOWN_MS
        ) {
            return
        }
        lastProcessedRemoteQrPayload = trimmedPayload.uppercase(Locale.US)
        lastQrBlendAtMs = now
        val previousQrPayload = currentQrPayload()
        currentQrColor = combineQrColors(currentQrColor, remoteQr.color)
        Logger.i(
            "ComprehensiveDemo",
            "QR offspring parentA=$previousQrPayload parentB=$trimmedPayload " +
                "child=${currentQrPayload()}"
        )
        if (qrVisibleApplied) {
            runOnUiThread {
                binding.qrFragmentView.visibility = View.VISIBLE
                qrCode.generate(currentQrPayload(), currentQrColor)
            }
        }
    }

    override fun onObjectsDetected(
        bitmap: Bitmap,
        mpImage: MPImage,
        results: MutableList<Detection>,
        inferenceTime: Long,
        frameCapturedAtNs: Long,
        detectStartedAtNs: Long,
        detectCompletedAtNs: Long,
        height: Int,
        width: Int
    ) {
        val detectionReceivedAtMs = SystemClock.uptimeMillis()
        var bestRobot: Detection? = null
        var bestPuck: Detection? = null
        val labels = mutableListOf<String>()

        for (result in results) {
            val category = result.categories().firstOrNull() ?: continue
            val rawLabel = category.categoryName().lowercase(Locale.getDefault())
            val target = rawLabel.toVisibleTarget() ?: continue
            labels.add(
                String.format(
                    Locale.getDefault(),
                    "%s %.2f",
                    rawLabel,
                    category.score()
                )
            )
            when (target) {
                VisibleTarget.ROBOT -> if (
                    bestRobot == null ||
                    category.score() > bestRobot.categories().first().score()
                ) {
                    bestRobot = result
                }

                VisibleTarget.PUCK -> if (
                    bestPuck == null ||
                    category.score() > bestPuck.categories().first().score()
                ) {
                    bestPuck = result
                }

                VisibleTarget.NONE -> {}
            }
        }

        latestRobotDetection = bestRobot
        latestPuckDetection = bestPuck
        if (bestRobot != null) {
            latestRobotDetectionReceivedAtMs = detectionReceivedAtMs
            latestRobotDetectionInferenceMs = inferenceTime
            latestRobotFrameCapturedAtNs = frameCapturedAtNs
            latestRobotDetectCompletedAtNs = detectCompletedAtNs
        } else {
            latestRobotDetectionReceivedAtMs = 0L
            latestRobotDetectionInferenceMs = 0L
            latestRobotFrameCapturedAtNs = 0L
            latestRobotDetectCompletedAtNs = 0L
        }

        latestImageWidth = width
        latestImageHeight = height

        boundingBoxView.setImageDimensions(latestImageWidth, latestImageHeight)
        showCurrentTarget()?.let {
            val box = it.boundingBox()
            Logger.v(
                "ComprehensiveDemo",
                "targetBox behavior=${controller.currentBehavior} " +
                    "raw=[${"%.1f".format(box.left)},${"%.1f".format(box.top)}," +
                    "${"%.1f".format(box.right)},${"%.1f".format(box.bottom)}] " +
                    "size=${latestImageWidth}x${latestImageHeight}"
            )
            boundingBoxView.setRect(box)
        } ?: boundingBoxView.clearRect()

        if (labels.isNotEmpty()) {
            Logger.v(
                "ComprehensiveDemo",
                labels.joinToString(separator = " | ") +
                        String.format(Locale.getDefault(), " (%d ms)", inferenceTime)
            )
        }
    }

    private fun String.toVisibleTarget(): VisibleTarget? {
        return when (this) {
            "robot",
            "robot-front",
            "robot-back" -> VisibleTarget.ROBOT
            "puck" -> VisibleTarget.PUCK
            else -> null
        }
    }

    private fun updateBehavior() {
        val now = System.currentTimeMillis()
        updateRecentlyUndocked(now)
        controller.update(getState(now), now, forcedBehavior)
        updateCurrentBehaviorStatus()
    }

    private fun updateCurrentBehaviorStatus() {
        runOnUiThread {
            binding.currentBehaviorText.text = getString(
                R.string.current_behavior_format,
                controller.currentBehavior.name
            )
        }
    }

    private fun setupBehaviorOverrideSelector() {
        behaviorOverrideOptions = listOf(
            BehaviorOverrideOption(
                getString(R.string.behavior_override_normal),
                behavior = null
            )
        ) + Behavior.entries.map { behavior ->
            BehaviorOverrideOption(behavior.name, behavior)
        }
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            behaviorOverrideOptions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.behaviorOverrideSpinner.adapter = adapter
        binding.behaviorOverrideSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selectedBehavior = behaviorOverrideOptions[position].behavior
                    if (selectedBehavior == forcedBehavior) {
                        return
                    }
                    forcedBehavior = selectedBehavior
                    Logger.i(
                        "ComprehensiveDemo",
                        "Behavior override selected ${selectedBehavior?.name ?: "normal"}"
                    )
                    updateBehavior()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    forcedBehavior = null
                }
            }
    }

    private fun getState(now: Long): ControllerState {
        return ControllerState(
            onCharger = chargerVoltage > 1.0 || coilVoltage > 1.0,
            recentlyUndocked = now < recentlyUndockedUntilMs,
            hardwareReady = hardwareReady,
            batteryVoltage = batteryVoltage,
            thetaDeg = latestThetaDeg,
            angularVelocityDeg = latestAngularVelocityDeg,
            wheelSpeedDeg = latestWheelSpeedDeg,
            imageWidth = latestImageWidth,
            imageHeight = latestImageHeight,
            robotDetection = latestRobotDetection,
            puckDetection = latestPuckDetection
        )
    }

    private fun updateRecentlyUndocked(now: Long) {
        val onCharger = chargerVoltage > 1.0 || coilVoltage > 1.0
        if (wasOnCharger && !onCharger) {
            recentlyUndockedUntilMs = now + GET_UP_DURATION_MS
        }
        wasOnCharger = onCharger
    }

    private fun showCurrentTarget(): Detection? {
        return when (controller.currentBehavior) {
            Behavior.GO_CHARGING -> latestPuckDetection
            Behavior.APPROACH_ANOTHER_ROBOT,
            Behavior.SHOW_QR_CODE,
            Behavior.ACCEPT_QR_CODE -> latestRobotDetection
            else -> null
        }
    }

    private fun setQrVisible(visible: Boolean) {
        if (visible == qrVisibleApplied) {
            return
        }
        qrVisibleApplied = visible
        if (!visible) {
            Logger.v(
                "ComprehensiveDemo",
                "setQrVisible true->false behavior=${controller.currentBehavior}"
            )
            runOnUiThread {
                qrCode.close()
                binding.qrFragmentView.visibility = View.GONE
            }
            return
        }
        Logger.v(
            "ComprehensiveDemo",
            "setQrVisible false->true behavior=${controller.currentBehavior}"
        )
        runOnUiThread {
            binding.qrFragmentView.visibility = View.VISIBLE
            qrCode.generate(currentQrPayload(), currentQrColor)
        }
    }

    private fun setSexualDisplayAudio(enabled: Boolean) {
        if (enabled == sexualDisplayAudioApplied) {
            return
        }
        sexualDisplayAudioApplied = enabled
        if (enabled) {
            Logger.i(
                "ComprehensiveDemo",
                "setSexualDisplayAudio false->true behavior=${controller.currentBehavior}"
            )
            robotSinger.start()
        } else {
            Logger.i(
                "ComprehensiveDemo",
                "setSexualDisplayAudio true->false behavior=${controller.currentBehavior}"
            )
            robotSinger.stop()
        }
    }

    private fun updateHardwareReady() {
        if (hardwareReady || !outputsReadyCompleted || !activityResumed) {
            return
        }
        hardwareReady = true
        Logger.i("ComprehensiveDemo", "Hardware smoke readiness reached")
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        updateHardwareReady()
    }

    override fun onPause() {
        activityResumed = false
        if (sexualDisplayAudioApplied) {
            sexualDisplayAudioApplied = false
            robotSinger.stop()
        }
        if (qrVisibleApplied) {
            qrVisibleApplied = false
            binding.qrFragmentView.visibility = View.GONE
            qrCode.close()
        }
        super.onPause()
    }

    override fun onDestroy() {
        robotSinger.stop()
        super.onDestroy()
    }

    companion object {
        private const val GET_UP_DURATION_MS = 4_000L
        private const val APPROACH_LATENCY_LOG_INTERVAL_MS = 250L
        private const val MIN_QR_CONTRAST = 1.75
        private const val QR_BLEND_COOLDOWN_MS = 1_500L
        private const val QR_MUTATION_RANGE = 16
        private val HEX_COLOR_REGEX = Regex("^[0-9A-Fa-f]{6}$")
        private val QR_ID_REGEX = Regex("^[0-9A-F]{8}$")

        private fun nsToMs(durationNs: Long): Long = durationNs / 1_000_000L
    }

    private fun currentQrPayload(): String {
        return "$localQrId:${currentQrColorHex()}"
    }

    private fun currentQrColorHex(): String {
        return String.format(
            Locale.US,
            "%02X%02X%02X",
            Color.red(currentQrColor),
            Color.green(currentQrColor),
            Color.blue(currentQrColor)
        )
    }

    private fun parseQrPayload(payload: String): ParsedQrPayload? {
        val normalizedPayload = payload.trim()
        val parts = normalizedPayload.split(':', limit = 2)
        if (parts.size != 2) {
            return null
        }
        val id = parts[0].uppercase(Locale.US)
        val colorHex = parts[1].uppercase(Locale.US)
        if (!QR_ID_REGEX.matches(id) || !HEX_COLOR_REGEX.matches(colorHex)) {
            return null
        }
        val rgb = colorHex.toInt(16)
        val color = Color.rgb(
            (rgb shr 16) and 0xFF,
            (rgb shr 8) and 0xFF,
            rgb and 0xFF
        )
        return ParsedQrPayload(id, color)
    }

    private fun combineQrColors(localColor: Int, remoteColor: Int): Int {
        val red = mutateChannel((Color.red(localColor) + Color.red(remoteColor)) / 2)
        val green = mutateChannel((Color.green(localColor) + Color.green(remoteColor)) / 2)
        val blue = mutateChannel((Color.blue(localColor) + Color.blue(remoteColor)) / 2)
        return ensureContrastWithWhite(Color.rgb(red, green, blue))
    }

    private fun mutateChannel(value: Int): Int {
        return (value + Random.nextInt(-QR_MUTATION_RANGE, QR_MUTATION_RANGE + 1))
            .coerceIn(0, 255)
    }

    private fun generateInitialQrColor(): Int {
        return ensureContrastWithWhite(
            Color.rgb(
                Random.nextInt(0, 256),
                Random.nextInt(0, 256),
                Random.nextInt(0, 256)
            )
        )
    }

    private fun generateQrId(): String {
        return UUID.randomUUID().toString().replace("-", "").take(8).uppercase(Locale.US)
    }

    private fun ensureContrastWithWhite(color: Int): Int {
        var red = Color.red(color).toDouble()
        var green = Color.green(color).toDouble()
        var blue = Color.blue(color).toDouble()
        var adjustedColor = color
        var iterations = 0
        while (contrastRatioWithWhite(adjustedColor) < MIN_QR_CONTRAST && iterations < 24) {
            red *= 0.92
            green *= 0.92
            blue *= 0.92
            adjustedColor = Color.rgb(red.toInt(), green.toInt(), blue.toInt())
            iterations += 1
        }
        return adjustedColor
    }

    private fun contrastRatioWithWhite(color: Int): Double {
        val luminance = relativeLuminance(color)
        return 1.05 / (luminance + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun linearize(channel: Int): Double {
            val srgb = channel / 255.0
            return if (srgb <= 0.04045) {
                srgb / 12.92
            } else {
                ((srgb + 0.055) / 1.055).pow(2.4)
            }
        }

        return 0.2126 * linearize(Color.red(color)) +
            0.7152 * linearize(Color.green(color)) +
            0.0722 * linearize(Color.blue(color))
    }

    private data class ParsedQrPayload(
        val id: String,
        val color: Int
    )
}

private data class BehaviorOverrideOption(
    val label: String,
    val behavior: Behavior?
) {
    override fun toString(): String = label
}

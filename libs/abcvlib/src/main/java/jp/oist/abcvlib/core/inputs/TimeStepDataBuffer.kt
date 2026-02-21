package jp.oist.abcvlib.core.inputs

import android.graphics.Bitmap
import android.media.AudioTimestamp
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer.TimeStepData.ImageData.SingleImage
import jp.oist.abcvlib.core.inputs.microcontroller.BatteryDataSubscriber
import jp.oist.abcvlib.core.inputs.microcontroller.WheelDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.ImageDataRawSubscriber
import jp.oist.abcvlib.core.inputs.phone.MicrophoneDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.OrientationDataSubscriber
import jp.oist.abcvlib.core.inputs.phone.QRCodeDataSubscriber
import jp.oist.abcvlib.core.learning.CommAction
import jp.oist.abcvlib.core.learning.MotionAction
import jp.oist.abcvlib.util.ImageOps
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import java.util.Collections
import java.util.Hashtable
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class TimeStepDataBuffer(private val bufferLength: Int) : BatteryDataSubscriber,
    WheelDataSubscriber,
    ImageDataRawSubscriber, MicrophoneDataSubscriber,
    OrientationDataSubscriber, QRCodeDataSubscriber {

    init {
        require(bufferLength > 1) {
            "bufferLength must be larger than 1. bufferLength of $bufferLength provided."
        }
    }

    private var writeIndex: Int = 1

    @get:Synchronized
    var readIndex: Int = 0
        private set
    private val buffer: Array<TimeStepData> = Array(bufferLength) { TimeStepData() }

    @get:Synchronized
    var writeData: TimeStepData = buffer[writeIndex]
        private set

    @get:Synchronized
    var readData: TimeStepData = buffer[readIndex]
        private set

    @JvmField
    val imgCompFuturesTimeStep: MutableList<Future<*>> = Collections.synchronizedList(LinkedList())

    @JvmField
    val imgCompFuturesEpisode: MutableCollection<MutableCollection<Future<*>>> =
        Collections.synchronizedList(LinkedList())

    private val imageCompressionExecutor: ExecutorService = Executors.newCachedThreadPool(
        ProcessPriorityThreadFactory(Thread.MAX_PRIORITY, "ImageCompression")
    )

    @Synchronized
    fun nextTimeStep() {
        // Keeps track of how many image compression threads have yet to finish per timestep
        imgCompFuturesEpisode.add(imgCompFuturesTimeStep)
        imgCompFuturesTimeStep.clear()

        // Update index for read and write pointer
        writeIndex = ((writeIndex + 1) % bufferLength)
        readIndex = ((readIndex + 1) % bufferLength)

        // Clear the next TimeStepData object for new writing
        buffer[writeIndex].clear()

        // Move pointer for reading and writing objects one index forward;
        writeData = buffer[writeIndex]
        readData = buffer[readIndex]
    }

    @Synchronized
    fun getTimeStepData(timestep: Int): TimeStepData {
        return buffer[timestep]
    }

    @Synchronized
    override fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
        this.writeData.batteryData.put(voltage, timestamp)
    }

    @Synchronized
    override fun onChargerVoltageUpdate(
        timestamp: Long,
        chargerVoltage: Double,
        coilVoltage: Double
    ) {
        this.writeData.chargerData.put(timestamp, chargerVoltage, coilVoltage)
    }

    override fun onWheelDataUpdate(
        timestamp: Long, wheelCountL: Int, wheelCountR: Int,
        wheelDistanceL: Double, wheelDistanceR: Double,
        wheelSpeedInstantL: Double, wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double, wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double, wheelSpeedExpAvgR: Double
    ) {
        this.writeData.wheelData.getLeft().put(
            timestamp, wheelCountL, wheelDistanceL,
            wheelSpeedInstantL, wheelSpeedBufferedL,
            wheelSpeedExpAvgL
        )
        this.writeData.wheelData.getRight().put(
            timestamp, wheelCountR, wheelDistanceR,
            wheelSpeedInstantR, wheelSpeedBufferedR, wheelSpeedExpAvgR
        )
    }

    override fun onImageDataRawUpdate(timestamp: Long, width: Int, height: Int, bitmap: Bitmap) {
        this.writeData.imageData.add(timestamp, width, height, bitmap, null)
        // Handler to compress and put images into buffer
        synchronized(imgCompFuturesTimeStep) {
            imgCompFuturesTimeStep.add(
                imageCompressionExecutor.submit {
                    ImageOps.addCompressedImage2Buffer(
                        writeIndex,
                        timestamp,
                        bitmap,
                        buffer
                    )
                }
            )
        }
    }

    override fun onMicrophoneDataUpdate(
        audioData: FloatArray,
        numSamples: Int,
        sampleRate: Int,
        startTime: AudioTimestamp,
        endTime: AudioTimestamp
    ) {
        this.writeData.soundData.setMetaData(sampleRate, startTime, endTime)
        this.writeData.soundData.add(audioData, numSamples)
    }

    override fun onOrientationUpdate(
        timestamp: Long,
        thetaRad: Double,
        angularVelocityRad: Double
    ) {
        this.writeData.orientationData.put(timestamp, thetaRad, angularVelocityRad)
    }

    override fun onQRCodeDetected(qrDataDecoded: String) {
        Logger.i("qrcode", "Qrcode detected: $qrDataDecoded")
    }

    class TimeStepData {
        @get:Synchronized
        var wheelData: WheelData = WheelData()
            private set

        @get:Synchronized
        var chargerData: ChargerData = ChargerData()
            private set

        @get:Synchronized
        var batteryData: BatteryData = BatteryData()
            private set

        @get:Synchronized
        var imageData: ImageData = ImageData()
            private set

        @get:Synchronized
        var soundData: SoundData = SoundData()
            private set

        @get:Synchronized
        var actions: RobotAction = RobotAction()
            private set

        @get:Synchronized
        var orientationData: OrientationData = OrientationData()
            private set

        fun clear() {
            wheelData = WheelData()
            chargerData = ChargerData()
            batteryData = BatteryData()
            imageData = ImageData()
            soundData = SoundData()
            actions = RobotAction()
            orientationData = OrientationData()
        }

        class WheelData {
            private var _left: IndividualWheelData = IndividualWheelData()
            private var _right: IndividualWheelData = IndividualWheelData()

            fun getLeft(): IndividualWheelData = _left
            fun getRight(): IndividualWheelData = _right

            class IndividualWheelData {
                private val _timestamps = arrayListOf<Long>()
                private val _counts = arrayListOf<Int>()
                private val _distances = arrayListOf<Double>()
                private val _speedsInstantaneous = arrayListOf<Double>()
                private val _speedsBuffered = arrayListOf<Double>()
                private val _speedsExpAvg = arrayListOf<Double>()

                fun put(
                    timestamp: Long, count: Int, distance: Double, speedInstantaneous: Double,
                    speedBuffered: Double, speedExpAvg: Double
                ) {
                    _timestamps.add(timestamp)
                    _counts.add(count)
                    _distances.add(distance)
                    _speedsInstantaneous.add(speedInstantaneous)
                    _speedsBuffered.add(speedBuffered)
                    _speedsExpAvg.add(speedExpAvg)
                }

                fun getTimeStamps() = _timestamps.toLongArray()
                fun getCounts() = _counts.toIntArray()
                fun getDistances() = _distances.toDoubleArray()
                fun getSpeedsInstantaneous() = _speedsInstantaneous.toDoubleArray()
                fun getSpeedsBuffered() = _speedsBuffered.toDoubleArray()
                fun getSpeedsExpAvg() = _speedsExpAvg.toDoubleArray()
            }

        }

        class ChargerData {
            private val _timestamps = arrayListOf<Long>()
            private val _chargerVoltage = arrayListOf<Double>()
            private val _coilVoltage = arrayListOf<Double>()

            fun put(timestamp: Long, chargerVoltage: Double, coilVoltage: Double) {
                _timestamps.add(timestamp)
                _chargerVoltage.add(chargerVoltage)
                _coilVoltage.add(coilVoltage)
            }

            fun getTimeStamps() = _timestamps.toLongArray()
            fun getChargerVoltage() = _chargerVoltage.toDoubleArray()
            fun getCoilVoltage() = _coilVoltage.toDoubleArray()
        }

        class BatteryData {
            private val _timestamps = arrayListOf<Long>()
            private val _voltage = arrayListOf<Double>()

            fun put(voltage: Double, timestamp: Long) {
                _timestamps.add(timestamp)
                _voltage.add(voltage)
            }

            fun getTimeStamps() = _timestamps.toLongArray()
            fun getVoltage() = _voltage.toDoubleArray()
        }

        class SoundData {
            var startTime: AudioTimestamp = AudioTimestamp()
                private set
            var endTime: AudioTimestamp = AudioTimestamp()
                private set

            var totalTime: Double = 0.0
                private set
            var sampleRate: Int = 0
                private set
            var totalSamples: Long = 0
                private set
            var totalSamplesCalculatedViaTime: Long = 0
                private set

            private val _levels = arrayListOf<Float>()
            fun getLevels() = _levels.toFloatArray()

            fun add(levels: FloatArray, numSamples: Int) {
                for (level in levels) {
                    _levels.add(level)
                }
                totalSamples += numSamples.toLong()
            }

            fun setMetaData(sampleRate: Int, startTime: AudioTimestamp, endTime: AudioTimestamp) {
                //Logger.i("audioFrame", (this.endTime.nanoTime - startTime.nanoTime) + " missing nanoseconds between last frames");

                if (startTime.framePosition != 0L) {
                    this.startTime = startTime
                }
                //todo add logic to test if timestamps overlap or have gaps.
                this.endTime = endTime
                this.totalTime = (endTime.nanoTime - startTime.nanoTime) * 10e-10
                this.sampleRate = sampleRate
                this.totalSamplesCalculatedViaTime = endTime.framePosition - startTime.framePosition
            }
        }

        class ImageData : Hashtable<Long, SingleImage>() {
            fun add(
                timestamp: Long,
                width: Int,
                height: Int,
                bitmap: Bitmap,
                webpImage: ByteArray?
            ) {
                val singleImage = SingleImage(timestamp, width, height, bitmap, webpImage)
                put(timestamp, singleImage)
            }

            @Synchronized
            override fun put(key: Long, value: SingleImage): SingleImage? {
                return super.put(key, value)
            }

            val images: ArrayList<SingleImage> get() = ArrayList(this.values)

            class SingleImage(
                val timestamp: Long,
                val width: Int,
                val height: Int,
                val bitmap: Bitmap,
                @set:Synchronized var webpImage: ByteArray?
            )
        }

        class RobotAction {
            var motionAction: MotionAction? = null
            var commAction: CommAction? = null

            fun add(motionAction: MotionAction, commAction: CommAction) {
                this.motionAction = motionAction
                this.commAction = commAction
            }
        }

        class OrientationData {
            private val _timestamps = arrayListOf<Long>()
            private val _tiltAngle = arrayListOf<Double>()
            private val _angularVelocity = arrayListOf<Double>()

            /**
             * @param timestamp long nano-time
             * @param _tiltAngle in radians
             * @param _angularVelocity in radians per second
             */
            fun put(timestamp: Long, tiltAngle: Double, angularVelocity: Double) {
                _timestamps.add(timestamp)
                _tiltAngle.add(tiltAngle)
                _angularVelocity.add(angularVelocity)
            }

            fun getTimeStamps() = _timestamps.toLongArray()
            fun getTiltAngle() = _tiltAngle.toDoubleArray()
            fun getAngularVelocity() = _angularVelocity.toDoubleArray()
        }
    }
}

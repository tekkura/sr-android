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

    private val buffer: Array<TimeStepData> = Array(bufferLength) { TimeStepData() }
    private var writeIndex: Int = 1
    private var readIndex: Int = 0
    private var writeData: TimeStepData = buffer[writeIndex]
    private var readData: TimeStepData = buffer[readIndex]

    val imgCompFuturesTimeStep: MutableCollection<Future<*>> =
        Collections.synchronizedList(LinkedList())

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
    fun getWriteData(): TimeStepData {
        return writeData
    }

    @Synchronized
    fun getReadData(): TimeStepData {
        return readData
    }

    @Synchronized
    fun getTimeStepData(timestep: Int): TimeStepData {
        return buffer[timestep]
    }

    @Synchronized
    fun getReadIndex(): Int {
        return readIndex
    }

    override fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
        getWriteData().batteryData.put(voltage, timestamp)
    }

    override fun onChargerVoltageUpdate(
        timestamp: Long,
        chargerVoltage: Double,
        coilVoltage: Double
    ) {
        getWriteData().chargerData.put(timestamp, chargerVoltage, coilVoltage)
    }

    override fun onWheelDataUpdate(
        timestamp: Long, wheelCountL: Int, wheelCountR: Int,
        wheelDistanceL: Double, wheelDistanceR: Double,
        wheelSpeedInstantL: Double, wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double, wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double, wheelSpeedExpAvgR: Double
    ) {
        getWriteData().wheelData.getLeft().put(
            timestamp, wheelCountL, wheelDistanceL,
            wheelSpeedInstantL, wheelSpeedBufferedL,
            wheelSpeedExpAvgL
        )
        getWriteData().wheelData.getRight().put(
            timestamp, wheelCountR, wheelDistanceR,
            wheelSpeedInstantR, wheelSpeedBufferedR, wheelSpeedExpAvgR
        )
    }

    override fun onImageDataRawUpdate(timestamp: Long, width: Int, height: Int, bitmap: Bitmap) {
        getWriteData().imageData.add(timestamp, width, height, bitmap, null)
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
        with(getWriteData().soundData) {
            add(TimeStepData.SoundData.AudioFrame(
                levels = audioData,
                sampleCount = numSamples,
                sampleRate = sampleRate,
                startTime = startTime,
                endTime = endTime
            ))
        }
    }

    override fun onOrientationUpdate(
        timestamp: Long,
        thetaRad: Double,
        angularVelocityRad: Double
    ) {
        getWriteData().orientationData.put(timestamp, thetaRad, angularVelocityRad)
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

        @Synchronized
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
            private val left: IndividualWheelData = IndividualWheelData()
            private val right: IndividualWheelData = IndividualWheelData()

            fun getLeft() = left
            fun getRight() = right

            class IndividualWheelData {
                private val timestamps = arrayListOf<Long>()
                private val counts = arrayListOf<Int>()
                private val distances = arrayListOf<Double>()
                private val speedsInstantaneous = arrayListOf<Double>()
                private val speedsBuffered = arrayListOf<Double>()
                private val speedsExpAvg = arrayListOf<Double>()

                fun put(
                    timestamp: Long, count: Int, distance: Double, speedInstantaneous: Double,
                    speedBuffered: Double, speedExpAvg: Double
                ) {
                    this.timestamps.add(timestamp)
                    this.counts.add(count)
                    this.distances.add(distance)
                    this.speedsInstantaneous.add(speedInstantaneous)
                    this.speedsBuffered.add(speedBuffered)
                    this.speedsExpAvg.add(speedExpAvg)
                }

                fun getTimeStamps() = timestamps.toLongArray()
                fun getCounts() = counts.toIntArray()
                fun getDistances() = distances.toDoubleArray()
                fun getSpeedsInstantaneous() = speedsInstantaneous.toDoubleArray()
                fun getSpeedsBuffered() = speedsBuffered.toDoubleArray()
                fun getSpeedsExpAvg() = speedsExpAvg.toDoubleArray()
            }

        }

        class ChargerData {
            private val timestamps = arrayListOf<Long>()
            private val chargerVoltage = arrayListOf<Double>()
            private val coilVoltage = arrayListOf<Double>()

            fun put(timestamp: Long, chargerVoltage: Double, coilVoltage: Double) {
                this.timestamps.add(timestamp)
                this.chargerVoltage.add(chargerVoltage)
                this.coilVoltage.add(coilVoltage)
            }

            fun getTimeStamps() = timestamps.toLongArray()
            fun getChargerVoltage() = chargerVoltage.toDoubleArray()
            fun getCoilVoltage() = coilVoltage.toDoubleArray()
        }

        class BatteryData {
            private val timestamps = arrayListOf<Long>()
            private val voltage = arrayListOf<Double>()

            fun put(voltage: Double, timestamp: Long) {
                this.timestamps.add(timestamp)
                this.voltage.add(voltage)
            }

            fun getTimeStamps() = timestamps.toLongArray()
            fun getVoltage() = voltage.toDoubleArray()
        }

        /**
         * Container for audio data recorded during a single timestep.
         *
         * This class manages a collection of [AudioFrame] objects and provides metadata regarding
         * the audio stream, including start and end timestamps, total duration, and sample rates.
         */
        class SoundData {
            /**
             * The timestamp of the start of the first audio frame in this buffer.
             * If no frames are present, it returns a default [AudioTimestamp].
             */
            val startTime: AudioTimestamp get() = frames.firstOrNull()
                ?.startTime
                ?: AudioTimestamp()

            /**
             * The timestamp of the end of the audio sequence within this time step.
             * Returns the [AudioTimestamp] of the last recorded [AudioFrame],
             * or a default instance if no frames are present.
             */
            val endTime: AudioTimestamp get() = frames.lastOrNull()
                ?.endTime
                ?: AudioTimestamp()

            /**
             * The total duration of the audio sequence in seconds.
             *
             * This value is calculated as the difference between [endTime] and [startTime].
             */
            val totalTime: Double
                get() = (endTime.nanoTime - startTime.nanoTime) * 10e-10

            /**
             * The sampling rate (samples per second) of the audio data.
             *
             * This value corresponds to the sampling frequency of the most recently recorded
             * [AudioFrame]. Returns 0 if no frames have been collected for this timestep.
             */
            val sampleRate: Int
                get() = frames.lastOrNull()
                    ?.sampleRate
                    ?: 0

            /**
             * The total number of audio samples accumulated across all [AudioFrame] objects
             * within this time step.
             */
            val totalSamples: Long get() = frames
                .sumOf { it.sampleCount.toLong() }

            /**
             * The total number of audio samples within this time step, calculated as the difference
             * between the hardware frame positions at the [endTime] and [startTime].
             *
             * This provides a hardware-clock-referenced count of samples that elapsed during the
             * recording interval, which can be used to verify the consistency of the received
             * audio data chunks.
             */
            val totalSamplesCalculatedViaTime: Long
                get() = endTime.framePosition - startTime.framePosition

            /**
             * The raw audio amplitude samples (PCM levels) recorded during this frame.
             */
            private val levels get() = frames
                .flatMap { it.levels.asIterable() }

            /**
             * Returns all audio samples collected during this timestep as a single [FloatArray].
             *
             * This method flattens the audio data from all recorded [AudioFrame] objects into
             * a continuous array of amplitude levels.
             *
             * @return A [FloatArray] containing the concatenated audio samples.
             */
            fun getLevels() = levels.toFloatArray()

            /**
             * A mutable list of [AudioFrame] objects captured during this specific timestep.
             * This serves as the primary storage for raw audio samples and their associated
             * metadata, used to derive cumulative properties like total duration and sample levels.
             */
            private val frames = mutableListOf<AudioFrame>()

            /**
             * Adds a new [AudioFrame] to the collection of audio data for this timestep.
             *
             * @param frame The audio frame containing raw sample data and metadata to be stored.
             * @return `true` if the frame was successfully added to the internal list.
             */
            fun add(frame: AudioFrame) = frames.add(frame)

            data class AudioFrame(
                val levels: FloatArray,
                val sampleCount: Int,
                val sampleRate: Int,
                val startTime: AudioTimestamp,
                val endTime: AudioTimestamp
            )
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
            lateinit var motionAction: MotionAction
                private set
            lateinit var commAction: CommAction
                private set

            fun add(motionAction: MotionAction, commAction: CommAction) {
                this.motionAction = motionAction
                this.commAction = commAction
            }
        }

        class OrientationData {
            private val timestamps = arrayListOf<Long>()
            private val tiltAngle = arrayListOf<Double>()
            private val angularVelocity = arrayListOf<Double>()

            /**
             * @param timestamp long nano-time
             * @param tiltAngle in radians
             * @param angularVelocity in radians per second
             */
            fun put(timestamp: Long, tiltAngle: Double, angularVelocity: Double) {
                this.timestamps.add(timestamp)
                this.tiltAngle.add(tiltAngle)
                this.angularVelocity.add(angularVelocity)
            }

            fun getTimeStamps() = timestamps.toLongArray()
            fun getTiltAngle() = tiltAngle.toDoubleArray()
            fun getAngularVelocity() = angularVelocity.toDoubleArray()
        }
    }
}
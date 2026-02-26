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
        getWriteData().soundData.setMetaData(sampleRate, startTime, endTime)
        getWriteData().soundData.add(audioData, numSamples)
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

            private val levels = arrayListOf<Float>()
            fun getLevels() = levels.toFloatArray()

            fun add(levels: FloatArray, numSamples: Int) {
                for (level in levels) {
                    this.levels.add(level)
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
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

    private val imgCompFuturesByIndex: Array<MutableList<Future<*>>> =
        Array(bufferLength) { Collections.synchronizedList(LinkedList()) }

    private val imageCompressionExecutor: ExecutorService = Executors.newCachedThreadPool(
        ProcessPriorityThreadFactory(Thread.MAX_PRIORITY, "ImageCompression")
    )

    @Synchronized
    fun nextTimeStep() {
        writeData.freeze()

        // Update index for read and write pointer
        writeIndex = ((writeIndex + 1) % bufferLength)
        readIndex = ((readIndex + 1) % bufferLength)

        // Replace the next write slot so async readers can keep using the old frozen object.
        buffer[writeIndex] = TimeStepData()
        synchronized(imgCompFuturesByIndex[writeIndex]) {
            imgCompFuturesByIndex[writeIndex].clear()
        }
        synchronized(imgCompFuturesTimeStep) {
            imgCompFuturesTimeStep.clear()
        }

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

    fun getImageCompressionFutures(timestep: Int): List<Future<*>> {
        val futures = imgCompFuturesByIndex[timestep]
        synchronized(futures) {
            return futures.toList()
        }
    }

    @Synchronized
    override fun onBatteryVoltageUpdate(timestamp: Long, voltage: Double) {
        writeData.batteryData.put(voltage, timestamp)
    }

    @Synchronized
    override fun onChargerVoltageUpdate(
        timestamp: Long,
        chargerVoltage: Double,
        coilVoltage: Double
    ) {
        writeData.chargerData.put(timestamp, chargerVoltage, coilVoltage)
    }

    @Synchronized
    override fun onWheelDataUpdate(
        timestamp: Long, wheelCountL: Int, wheelCountR: Int,
        wheelDistanceL: Double, wheelDistanceR: Double,
        wheelSpeedInstantL: Double, wheelSpeedInstantR: Double,
        wheelSpeedBufferedL: Double, wheelSpeedBufferedR: Double,
        wheelSpeedExpAvgL: Double, wheelSpeedExpAvgR: Double
    ) {
        writeData.wheelData.getLeft().put(
            timestamp, wheelCountL, wheelDistanceL,
            wheelSpeedInstantL, wheelSpeedBufferedL,
            wheelSpeedExpAvgL
        )
        writeData.wheelData.getRight().put(
            timestamp, wheelCountR, wheelDistanceR,
            wheelSpeedInstantR, wheelSpeedBufferedR, wheelSpeedExpAvgR
        )
    }

    @Synchronized
    override fun onImageDataRawUpdate(timestamp: Long, width: Int, height: Int, bitmap: Bitmap) {
        val targetIndex = writeIndex
        writeData.imageData.add(timestamp, width, height, bitmap, null)
        // Handler to compress and put images into buffer
        synchronized(imgCompFuturesTimeStep) {
            val future = imageCompressionExecutor.submit {
                ImageOps.addCompressedImage2Buffer(
                    targetIndex,
                    timestamp,
                    bitmap,
                    buffer
                )
            }
            imgCompFuturesTimeStep.add(future)
            synchronized(imgCompFuturesByIndex[targetIndex]) {
                imgCompFuturesByIndex[targetIndex].add(future)
            }
        }
    }

    override fun onMicrophoneDataUpdate(
        audioData: FloatArray,
        numSamples: Int,
        sampleRate: Int,
        startTime: AudioTimestamp,
        endTime: AudioTimestamp
    ) {
        synchronized(this) {
            writeData.soundData.setMetaData(sampleRate, startTime, endTime)
            writeData.soundData.add(audioData, numSamples)
        }
    }

    @Synchronized
    override fun onOrientationUpdate(
        timestamp: Long,
        thetaRad: Double,
        angularVelocityRad: Double
    ) {
        writeData.orientationData.put(timestamp, thetaRad, angularVelocityRad)
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

        fun freeze() {
            wheelData.freeze()
            chargerData.freeze()
            batteryData.freeze()
            imageData.freeze()
            soundData.freeze()
            orientationData.freeze()
        }

        class WheelData {
            private val left: IndividualWheelData = IndividualWheelData()
            private val right: IndividualWheelData = IndividualWheelData()

            fun getLeft() = left
            fun getRight() = right

            fun freeze() {
                left.freeze()
                right.freeze()
            }

            class IndividualWheelData {
                private val timestamps = arrayListOf<Long>()
                private val counts = arrayListOf<Int>()
                private val distances = arrayListOf<Double>()
                private val speedsInstantaneous = arrayListOf<Double>()
                private val speedsBuffered = arrayListOf<Double>()
                private val speedsExpAvg = arrayListOf<Double>()
                private var timestampSnapshot = LongArray(0)
                private var countSnapshot = IntArray(0)
                private var distanceSnapshot = DoubleArray(0)
                private var speedInstantaneousSnapshot = DoubleArray(0)
                private var speedBufferedSnapshot = DoubleArray(0)
                private var speedExpAvgSnapshot = DoubleArray(0)

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

                fun freeze() {
                    timestampSnapshot = timestamps.toLongArray()
                    countSnapshot = counts.toIntArray()
                    distanceSnapshot = distances.toDoubleArray()
                    speedInstantaneousSnapshot = speedsInstantaneous.toDoubleArray()
                    speedBufferedSnapshot = speedsBuffered.toDoubleArray()
                    speedExpAvgSnapshot = speedsExpAvg.toDoubleArray()
                }

                fun getTimeStamps() = timestampSnapshot.copyOf()
                fun getCounts() = countSnapshot.copyOf()
                fun getDistances() = distanceSnapshot.copyOf()
                fun getSpeedsInstantaneous() = speedInstantaneousSnapshot.copyOf()
                fun getSpeedsBuffered() = speedBufferedSnapshot.copyOf()
                fun getSpeedsExpAvg() = speedExpAvgSnapshot.copyOf()
            }

        }

        class ChargerData {
            private val timestamps = arrayListOf<Long>()
            private val chargerVoltage = arrayListOf<Double>()
            private val coilVoltage = arrayListOf<Double>()
            private var timestampSnapshot = LongArray(0)
            private var chargerVoltageSnapshot = DoubleArray(0)
            private var coilVoltageSnapshot = DoubleArray(0)

            fun put(timestamp: Long, chargerVoltage: Double, coilVoltage: Double) {
                this.timestamps.add(timestamp)
                this.chargerVoltage.add(chargerVoltage)
                this.coilVoltage.add(coilVoltage)
            }

            fun freeze() {
                timestampSnapshot = timestamps.toLongArray()
                chargerVoltageSnapshot = chargerVoltage.toDoubleArray()
                coilVoltageSnapshot = coilVoltage.toDoubleArray()
            }

            fun getTimeStamps() = timestampSnapshot.copyOf()
            fun getChargerVoltage() = chargerVoltageSnapshot.copyOf()
            fun getCoilVoltage() = coilVoltageSnapshot.copyOf()
        }

        class BatteryData {
            private val timestamps = arrayListOf<Long>()
            private val voltage = arrayListOf<Double>()
            private var timestampSnapshot = LongArray(0)
            private var voltageSnapshot = DoubleArray(0)

            fun put(voltage: Double, timestamp: Long) {
                this.timestamps.add(timestamp)
                this.voltage.add(voltage)
            }

            fun freeze() {
                timestampSnapshot = timestamps.toLongArray()
                voltageSnapshot = voltage.toDoubleArray()
            }

            fun getTimeStamps() = timestampSnapshot.copyOf()
            fun getVoltage() = voltageSnapshot.copyOf()
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
            private var levelSnapshot = FloatArray(0)

            fun getLevels() = levelSnapshot.copyOf()

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

            fun freeze() {
                levelSnapshot = levels.toFloatArray()
            }
        }

        class ImageData : Hashtable<Long, SingleImage>() {
            private var imageSnapshot = ArrayList<SingleImage>()

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

            val images: ArrayList<SingleImage>
                get() = ArrayList(imageSnapshot)

            @Synchronized
            fun freeze() {
                imageSnapshot = ArrayList(this.values)
            }

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
            private var timestampSnapshot = LongArray(0)
            private var tiltAngleSnapshot = DoubleArray(0)
            private var angularVelocitySnapshot = DoubleArray(0)

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

            fun freeze() {
                timestampSnapshot = timestamps.toLongArray()
                tiltAngleSnapshot = tiltAngle.toDoubleArray()
                angularVelocitySnapshot = angularVelocity.toDoubleArray()
            }

            fun getTimeStamps() = timestampSnapshot.copyOf()
            fun getTiltAngle() = tiltAngleSnapshot.copyOf()
            fun getAngularVelocity() = angularVelocitySnapshot.copyOf()
        }
    }
}

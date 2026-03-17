package jp.oist.abcvlib.core.learning

import com.google.flatbuffers.FlatBufferBuilder
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer.TimeStepData
import jp.oist.abcvlib.core.learning.fbclasses.AudioTimestamp
import jp.oist.abcvlib.core.learning.fbclasses.BatteryData
import jp.oist.abcvlib.core.learning.fbclasses.ChargerData
import jp.oist.abcvlib.core.learning.fbclasses.CommAction
import jp.oist.abcvlib.core.learning.fbclasses.Episode
import jp.oist.abcvlib.core.learning.fbclasses.Image
import jp.oist.abcvlib.core.learning.fbclasses.ImageData
import jp.oist.abcvlib.core.learning.fbclasses.IndividualWheelData
import jp.oist.abcvlib.core.learning.fbclasses.MotionAction
import jp.oist.abcvlib.core.learning.fbclasses.OrientationData
import jp.oist.abcvlib.core.learning.fbclasses.RobotAction
import jp.oist.abcvlib.core.learning.fbclasses.SoundData
import jp.oist.abcvlib.core.learning.fbclasses.TimeStep
import jp.oist.abcvlib.core.learning.fbclasses.WheelData
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException
import jp.oist.abcvlib.util.SocketConnectionManager
import jp.oist.abcvlib.util.SocketListener
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Enters data from TimeStepDataBuffer into a flatbuffer
 */
class FlatbufferAssembler(
    private val myTrial: Trial,
    private val inetSocketAddress: InetSocketAddress?,
    private val socketListener: SocketListener?,
    val timeStepDataBuffer: TimeStepDataBuffer,
    private val robotID: Int
) {
    private lateinit var builder: FlatBufferBuilder

    private val timeStepVector: IntArray
    private val executor: ScheduledExecutorServiceWithException
    private lateinit var episode: ByteBuffer
    private val flatBufferWriter: ExecutorService = Executors.newSingleThreadExecutor(
        ProcessPriorityThreadFactory(
            Thread.NORM_PRIORITY, "flatBufferWriter"
        )
    )
    val flatbufferWriteFutures: MutableList<Future<*>> = Collections.synchronizedList(LinkedList())

    companion object {
        private const val TAG = "flatbuff"
    }

    init {
        val threads = 5
        executor = ScheduledExecutorServiceWithException(
            threads,
            ProcessPriorityThreadFactory(1, "flatbufferAssembler")
        )

        timeStepVector = IntArray(myTrial.maxTimeStepCount + 1)
    }

    fun startEpisode() {
        builder = FlatBufferBuilder(1024)
        Logger.v(TAG, "starting New Episode")
    }

    @Throws(ExecutionException::class, InterruptedException::class)
    fun addTimeStep(timestep: Int) {
        // Wait for image compression to finish before trying to write to flatbuffer
        synchronized(timeStepDataBuffer.imgCompFuturesTimeStep) {
            for (future in timeStepDataBuffer.imgCompFuturesTimeStep) {
                future.get()
            }
        }
        flatbufferWriteFutures.add(flatBufferWriter.submit {
            val timeStepData = timeStepDataBuffer.getTimeStepData(timestep)

            val wheelData = addWheelData(timeStepData)
            val orientationData = addOrientationData(timeStepData)
            val chargerData = addChargerData(timeStepData)
            val batteryData = addBatteryData(timeStepData)
            val soundData = addSoundData(timeStepData)
            val imageData = addImageData(timeStepData)
            val actionData = addActionData(timeStepData)

            TimeStep.startTimeStep(builder)
            TimeStep.addWheelData(builder, wheelData)
            TimeStep.addOrientationData(builder, orientationData)
            TimeStep.addChargerData(builder, chargerData)
            TimeStep.addBatteryData(builder, batteryData)
            TimeStep.addSoundData(builder, soundData)
            TimeStep.addImageData(builder, imageData)
            TimeStep.addActions(builder, actionData)
            timeStepVector[timestep] = TimeStep.endTimeStep(builder)
        })
    }

    private fun addWheelData(timeStepData: TimeStepData): Int {
        val leftData = timeStepData.wheelData.getLeft()
        Logger.v(
            TAG, "STEP wheelCount TimeStamps Length: " +
                    leftData.getTimeStamps().size
        )
        val timeStampsLeft = IndividualWheelData.createTimestampsVector(
            builder,
            leftData.getTimeStamps()
        )
        val countsLeft = IndividualWheelData.createCountsVector(
            builder,
            leftData.getCounts()
        )
        val distancesLeft = IndividualWheelData.createDistancesVector(
            builder,
            leftData.getDistances()
        )
        val speedsLeftInstant = IndividualWheelData.createSpeedsInstantaneousVector(
            builder,
            leftData.getSpeedsInstantaneous()
        )
        val speedsLeftBuffered = IndividualWheelData.createSpeedsBufferedVector(
            builder,
            leftData.getSpeedsBuffered()
        )
        val speedsLeftExpAvg = IndividualWheelData.createSpeedsExpavgVector(
            builder,
            leftData.getSpeedsExpAvg()
        )
        val leftOffset = IndividualWheelData.createIndividualWheelData(
            builder, timeStampsLeft,
            countsLeft, distancesLeft, speedsLeftInstant, speedsLeftBuffered, speedsLeftExpAvg
        )

        val rightData = timeStepData.wheelData.getRight()
        Logger.v(
            TAG, "STEP wheelCount TimeStamps Length: " +
                    rightData.getTimeStamps().size
        )
        val timeStampsRight = IndividualWheelData.createTimestampsVector(
            builder,
            rightData.getTimeStamps()
        )
        val countsRight = IndividualWheelData.createCountsVector(
            builder,
            rightData.getCounts()
        )
        val distancesRight = IndividualWheelData.createDistancesVector(
            builder,
            rightData.getDistances()
        )
        val speedsRightInstant = IndividualWheelData.createSpeedsInstantaneousVector(
            builder,
            rightData.getSpeedsInstantaneous()
        )
        val speedsRightBuffered = IndividualWheelData.createSpeedsBufferedVector(
            builder,
            rightData.getSpeedsBuffered()
        )
        val speedsRightExpAvg = IndividualWheelData.createSpeedsExpavgVector(
            builder,
            rightData.getSpeedsExpAvg()
        )
        val rightOffset = IndividualWheelData.createIndividualWheelData(
            builder,
            timeStampsRight,
            countsRight,
            distancesRight,
            speedsRightInstant,
            speedsRightBuffered,
            speedsRightExpAvg
        )

        return WheelData.createWheelData(builder, leftOffset, rightOffset)
    }

    private fun addOrientationData(timeStepData: TimeStepData): Int {
        Logger.v(
            TAG, "STEP orientationData TimeStamps Length: " +
                    timeStepData.orientationData.getTimeStamps().size
        )
        val ts = OrientationData.createTimestampsVector(
            builder,
            timeStepData.orientationData.getTimeStamps()
        )
        val tiltAngles = OrientationData.createTiltangleVector(
            builder,
            timeStepData.orientationData.getTiltAngle()
        )
        val tiltVelocityAngles = OrientationData.createTiltvelocityVector(
            builder,
            timeStepData.orientationData.getAngularVelocity()
        )
        return OrientationData.createOrientationData(builder, ts, tiltAngles, tiltVelocityAngles)
    }

    private fun addChargerData(timeStepData: TimeStepData): Int {
        Logger.v(
            TAG, "STEP chargerData TimeStamps Length: " +
                    timeStepData.chargerData.getTimeStamps().size
        )
        val ts = ChargerData.createTimestampsVector(
            builder,
            timeStepData.chargerData.getTimeStamps()
        )
        val voltage = ChargerData.createVoltageVector(
            builder,
            timeStepData.chargerData.getChargerVoltage()
        )
        return ChargerData.createChargerData(builder, ts, voltage)
    }

    private fun addBatteryData(timeStepData: TimeStepData): Int {
        Logger.v(
            TAG, "STEP batteryData TimeStamps Length: " +
                    timeStepData.batteryData.getTimeStamps().size
        )
        val ts = BatteryData.createTimestampsVector(
            builder,
            timeStepData.batteryData.getTimeStamps()
        )
        val voltage = BatteryData.createVoltageVector(
            builder,
            timeStepData.batteryData.getVoltage()
        )
        return ChargerData.createChargerData(builder, ts, voltage)
    }

    private fun addSoundData(timeStepData: TimeStepData): Int {
        val soundData = timeStepData.soundData

        Logger.v(TAG, "Sound Data TotalSamples: " + soundData.totalSamples)
        Logger.v(
            TAG, "Sound Data totalSamplesCalculatedViaTime: " +
                    soundData.totalSamplesCalculatedViaTime
        )

        val startTime = AudioTimestamp.createAudioTimestamp(
            builder,
            soundData.startTime.framePosition,
            soundData.startTime.nanoTime
        )
        val endTime = AudioTimestamp.createAudioTimestamp(
            builder,
            soundData.startTime.framePosition,
            soundData.startTime.nanoTime
        )
        val levels = SoundData.createLevelsVector(
            builder,
            timeStepData.soundData.getLevels()
        )

        SoundData.startSoundData(builder)
        SoundData.addStartTime(builder, startTime)
        SoundData.addEndTime(builder, endTime)
        SoundData.addTotalTime(builder, soundData.totalTime)
        SoundData.addSampleRate(builder, soundData.sampleRate)
        SoundData.addTotalSamples(builder, soundData.totalSamples)
        SoundData.addLevels(builder, levels)

        return SoundData.endSoundData(builder)
    }

    private fun addImageData(timeStepData: TimeStepData): Int {
        val imageData = timeStepData.imageData
        val numOfImages = imageData.images.size

        Logger.v(TAG, "$numOfImages images gathered")
        Logger.v(TAG, "Step:" + myTrial.timeStep)

        val images = IntArray(numOfImages)

        for (i in 0..<numOfImages) {
            val image = imageData.images[i]
            try {
                val webpImage = Image.createWebpImageVector(builder, image.webpImage)
                Image.startImage(builder)
                Image.addWebpImage(builder, webpImage)
                Image.addTimestamp(builder, image.timestamp)
                Image.addHeight(builder, image.height)
                Image.addWidth(builder, image.width)
                images[i] = Image.endImage(builder)
            } catch (e: NullPointerException) {
                Logger.i("FlatbufferImages", "No images recorded")
                e.printStackTrace()
            }
        }

        val imagesOffset = ImageData.createImagesVector(builder, images)
        ImageData.startImageData(builder)
        ImageData.addImages(builder, imagesOffset)
        // Offset for all image data to be returned from this method
        val imageDataOffset = ImageData.endImageData(builder)
        return imageDataOffset
    }

    private fun addActionData(timeStepData: TimeStepData): Int {
        val ca = timeStepData.actions.commAction
        val ma = timeStepData.actions.motionAction
        Logger.v(TAG, "CommAction : " + ca.actionByte)
        Logger.v(TAG, "MotionAction : " + ma.actionName)
        val caOffset = CommAction.createCommAction(
            builder, ca.actionByte, builder.createString(ca.actionName)
        )
        val maOffset = MotionAction.createMotionAction(
            builder, ma.actionByte, builder.createString(ma.actionName),
            ma.leftWheelPWM, ma.rightWheelPWM,
            ma.leftWheelBrake, ma.rightWheelBrake
        )
        return RobotAction.createRobotAction(builder, maOffset, caOffset)
    }

    // End episode after some reward has been achieved or max-timesteps has been reached
    fun endEpisode() {
        //todo I think I need to add each timestep when it is generated rather than all at once? Is this the leak?
        val ts = Episode.createTimestepsVector(builder, timeStepVector)
        Episode.startEpisode(builder)
        Episode.addRobotid(builder, robotID)
        Episode.addTimesteps(builder, ts)
        val ep = Episode.endEpisode(builder)
        builder.finish(ep)
        episode = builder.dataBuffer()

        // The following is just to check the contents of the flatbuffer prior to sending to the server.
        // You should comment this out if not using it as it doubles the required memory.
        // Also, it seems the getRootAsEpisode modifies the episode buffer itself, thus messing up later processing.
        // Therefore, I propose only using this as an inline debugging step or if you don't want
        // To evaluate anything past this point for a given run.

        // Episode episodeTest = Episode.getRootAsEpisode(episode);
        // Logger.d(TAG, "TimeSteps Length: "  + String.valueOf(episodeTest.timestepsLength()));
        // Logger.d(TAG, "WheelCounts TimeStep 0 Length: "  + String.valueOf(episodeTest.timesteps(0).wheelCounts().timestampsLength()));
        // Logger.d(TAG, "WheelCounts TimeStep 1 Length: "  + String.valueOf(episodeTest.timesteps(1).wheelCounts().timestampsLength()));
        // Logger.d(TAG, "WheelCounts TimeStep 2 Length: "  + String.valueOf(episodeTest.timesteps(2).wheelCounts().timestampsLength()));
        // Logger.d(TAG, "WheelCounts TimeStep 3 Length: "  + String.valueOf(episodeTest.timesteps(3).wheelCounts().timestampsLength()));
        // Logger.d(TAG, "WheelCounts TimeStep 3 idx 0: "  + String.valueOf(episodeTest.timesteps(3).wheelCounts().timestamps(0)));
        // Logger.d(TAG, "Levels Length TimeStep 100: "  + String.valueOf(episodeTest.timesteps(100).soundData().levelsLength()));
        // Logger.d(TAG, "SoundData ByteBuffer Length TimeStep 100: "  + String.valueOf(episodeTest.timesteps(100).soundData().getByteBuffer().capacity()));
        // Logger.d(TAG, "ImageData ByteBuffer Length TimeStep 100: "  + String.valueOf(episodeTest.timesteps(100).imageData().getByteBuffer().capacity()));


        // float[] soundFloats = new float[10];
        // episodeTest.timesteps(1).soundData().levelsAsByteBuffer().asFloatBuffer().get(soundFloats);
        // Logger.d(TAG, "Sound TimeStep 1 as numpy: "  + Arrays.toString(soundFloats));
    }

    private inner class CyclicBarrierHandler : Runnable {
        override fun run() {
            builder.clear()
            //builder = null
            startEpisode()
        }
    }

    @Throws(BrokenBarrierException::class, InterruptedException::class)
    fun sendToServer() {
        val doneSignal = CyclicBarrier(2, CyclicBarrierHandler())
        Logger.d("SocketConnection", "New executor deployed creating new SocketConnectionManager")
        if (inetSocketAddress != null && socketListener != null) {
            executor.execute(
                SocketConnectionManager(
                    socketListener,
                    inetSocketAddress,
                    episode,
                    doneSignal
                )
            )
            doneSignal.await()
        } else {
            executor.execute {
                try {
                    doneSignal.await()
                } catch (e: BrokenBarrierException) {
                    e.printStackTrace()
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }
        }
    }
}

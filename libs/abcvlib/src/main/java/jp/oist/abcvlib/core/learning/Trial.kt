package jp.oist.abcvlib.core.learning

import android.content.Context
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer
import jp.oist.abcvlib.core.outputs.ActionSelector
import jp.oist.abcvlib.core.outputs.Outputs
import jp.oist.abcvlib.util.ErrorHandler
import jp.oist.abcvlib.util.FileOps
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.RecordingWithoutTimeStepBufferException
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException
import jp.oist.abcvlib.util.SocketListener
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

open class Trial(
    metaParameters: MetaParameters,
    actionSpace: ActionSpace,
    stateSpace: StateSpace
) : Runnable, ActionSelector, SocketListener {

    private val context: Context = metaParameters.context
    private val timeStepDataBuffer: TimeStepDataBuffer = metaParameters.timeStepDataBuffer

    @JvmField
    protected val outputs: Outputs = metaParameters.outputs

    @JvmField
    protected val robotID: Int = metaParameters.robotID

    private var timeStepLength = metaParameters.timeStepLength

    @JvmField
    var maxTimeStepCount = metaParameters.maxTimeStepCount
    private var maxReward = metaParameters.maxReward

    @JvmField
    protected var maxEpisodeCount = metaParameters.maxEpisodeCount

    val commActionSet: CommActionSpace = actionSpace.commActionSpace
    val motionActionSet: MotionActionSpace = actionSpace.motionActionSpace
    private val publisherManager: PublisherManager = stateSpace.publisherManager

    @JvmField
    var timeStep = 0

    // Use to trigger MainActivity to stop generating episodes
    private var lastEpisode = false

    // Use to trigger MainActivity to stop generating timesteps for a single episode
    private var lastTimestep = false

    private var reward = 0

    @JvmField
    protected var episodeCount = 0


    private lateinit var timeStepDataAssemblerFuture: ScheduledFuture<*>
    private val executor: ScheduledExecutorServiceWithException
    private var flatBufferAssembler: FlatbufferAssembler

    private val TAG: String = javaClass.toString()

    init {
        flatBufferAssembler = FlatbufferAssembler(
            this,
            metaParameters.inetSocketAddress,
            this,
            timeStepDataBuffer,
            robotID
        )
        val threads = 1
        executor = ScheduledExecutorServiceWithException(
            threads,
            ProcessPriorityThreadFactory(1, "trail")
        )
    }

    fun setFlatBufferAssembler(flatBufferAssembler: FlatbufferAssembler) {
        this.flatBufferAssembler = flatBufferAssembler
    }

    protected open fun startTrail() {
        publisherManager.initializePublishers()
        publisherManager.startPublishers()
        startEpisode()
        startPublishers()
    }

    protected open fun startPublishers() {
        timeStepDataAssemblerFuture = executor.scheduleAtFixedRate(
            this,
            getTimeStepLength().toLong(),
            getTimeStepLength().toLong(),
            TimeUnit.MILLISECONDS
        )
    }

    protected open fun startEpisode() {
        flatBufferAssembler.startEpisode()
    }

    override fun run() {
        incrementTimeStep()
        // Moves timeStepDataBuffer.writeData to readData and nulls out the writeData for new data
        timeStepDataBuffer.nextTimeStep()

        // Choose action wte based on current timestep data
        forward(timeStepDataBuffer.readData)

        // Add timestep and return int representing offset in flatbuffer
        try {
            flatBufferAssembler.addTimeStep(timeStepDataBuffer.readIndex)
        } catch (e: ExecutionException) {
            e.printStackTrace()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        // If some criteria met, end episode.
        if (isLastTimestep()) {
            try {
                endEpisode()
                if (isLastEpisode()) {
                    endTrial()
                } else {
                    startEpisode()
                    resumePublishers()
                }
            } catch (e: BrokenBarrierException) {
                ErrorHandler.eLog(TAG, "Error when trying to end episode or trail", e, true)
            } catch (e: InterruptedException) {
                ErrorHandler.eLog(TAG, "Error when trying to end episode or trail", e, true)
            } catch (e: IOException) {
                ErrorHandler.eLog(TAG, "Error when trying to end episode or trail", e, true)
            } catch (e: RecordingWithoutTimeStepBufferException) {
                ErrorHandler.eLog(TAG, "Error when trying to end episode or trail", e, true)
            } catch (e: ExecutionException) {
                ErrorHandler.eLog(TAG, "Error when trying to end episode or trail", e, true)
            }
        }
    }

    @Throws(RecordingWithoutTimeStepBufferException::class, InterruptedException::class)
    protected open fun pausePublishers() {
        publisherManager.pausePublishers()
        timeStepDataAssemblerFuture.cancel(false)
    }

    protected open fun resumePublishers() {
        publisherManager.resumePublishers()
        timeStepDataAssemblerFuture = executor.scheduleAtFixedRate(
            this,
            getTimeStepLength().toLong(),
            getTimeStepLength().toLong(),
            TimeUnit.MILLISECONDS
        )
    }

    // End episode after some reward has been achieved or max-timesteps has been reached
    @Throws(
        BrokenBarrierException::class,
        InterruptedException::class,
        IOException::class,
        RecordingWithoutTimeStepBufferException::class,
        ExecutionException::class
    )
    protected open fun endEpisode() {
        Logger.d("Episode", "End of episode: $episodeCount")
        // Waits for all image compression to finish prior to finishing flatbuffer
        val start = System.nanoTime()
        synchronized(timeStepDataBuffer.imgCompFuturesEpisode) {
            for (timestepFutures in timeStepDataBuffer.imgCompFuturesEpisode) {
                synchronized(timestepFutures) {
                    for (future in timestepFutures) {
                        future.get()
                    }
                }
            }
        }
        synchronized(flatBufferAssembler.flatbufferWriteFutures) {
            // Waits for all timestep flatbuffer writes to finish prior to finishing flatbuffer
            for (future in flatBufferAssembler.flatbufferWriteFutures) {
                future.get()
            }
        }
        flatBufferAssembler.endEpisode()
        pausePublishers()
        setTimeStep(0)
        setLastTimestep(false)
        incrementEpisodeCount()
        timeStepDataBuffer.nextTimeStep()
        flatBufferAssembler.sendToServer()
    }

    @Throws(RecordingWithoutTimeStepBufferException::class, InterruptedException::class)
    protected open fun endTrial() {
        Logger.i(TAG, "Need to handle end of trail here")
        pausePublishers()
        publisherManager.stopPublishers()
        timeStepDataAssemblerFuture.cancel(false)
    }

    /**
     * This method is called at the end of each timestep within your class extending Trail and implementing ActionSelector
     * @param data All the data collected from the most recent timestep
     */
    override fun forward(data: TimeStepDataBuffer.TimeStepData) {
    }

    override fun onServerReadSuccess(jsonHeader: JSONObject, msgFromServer: ByteBuffer) {
        // Parse whatever you sent from python here
        //loadMappedFile...
        try {
            when (jsonHeader.get("content-encoding")) {
                "utf-8" -> {
                    Logger.d(TAG, "Received text message from server")
                    msgFromServer.flip()
                    val bytes = ByteArray(jsonHeader.get("content-length") as Int)
                    msgFromServer.get(bytes)
                    val msg = String(bytes, StandardCharsets.UTF_8)
                    Logger.d(TAG, "Server says, \"$msg\"")
                }

                "binary" -> {
                    when (jsonHeader.get("content-type")) {
                        "files" -> {
                            Logger.d(TAG, "Writing files to disk")
                            val fileNames = jsonHeader.getJSONArray("file-names")
                            val fileLengths = jsonHeader.getJSONArray("file-lengths")

                            msgFromServer.flip()

                            for (i in 0 until fileNames.length()) {
                                val bytes = ByteArray(fileLengths.getInt(i))
                                msgFromServer.get(bytes)
                                FileOps.savedata(context, bytes, "models", fileNames.getString(i))
                            }
                        }

                        "flatbuffer" -> {
                            //todo
                        }

                        "json" -> {
                            //todo
                        }
                    }
                }

                else -> {
                    Logger.d(
                        TAG,
                        "Data from server does not contain modelVector content. Be sure to set content-encoding to \"modelVector\" in the python jsonHeader"
                    )
                }
            }
        } catch (e: JSONException) {
            ErrorHandler.eLog(
                TAG,
                "Something wrong with parsing the JSON-header from python",
                e,
                true
            )
        }
    }

    fun getTimeStepLength(): Int = timeStepLength

    fun getTimeStepDataBuffer(): TimeStepDataBuffer = timeStepDataBuffer

    fun getEpisodeCount(): Int = episodeCount

    fun getTimeStep(): Int = timeStep

    open fun isLastEpisode(): Boolean = (episodeCount >= maxEpisodeCount) || lastEpisode

    open fun isLastTimestep(): Boolean = (timeStep >= maxTimeStepCount) || lastTimestep

    fun getMaxEpisodecount(): Int = maxEpisodeCount

    fun getMaxTimeStepCount(): Int = maxTimeStepCount

    fun getReward(): Int = reward

    fun getMaxReward(): Int = maxReward

    fun incrementEpisodeCount() {
        episodeCount++
    }

    fun incrementTimeStep() {
        timeStep++
    }

    fun setTimeStep(timeStep: Int) {
        this.timeStep = timeStep
    }

    fun setLastEpisode(lastEpisode: Boolean) {
        this.lastEpisode = lastEpisode
    }

    fun setLastTimestep(lastTimestep: Boolean) {
        this.lastTimestep = lastTimestep
    }
}
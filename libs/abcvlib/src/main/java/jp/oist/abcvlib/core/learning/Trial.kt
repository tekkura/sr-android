package jp.oist.abcvlib.core.learning

import android.app.Activity
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.core.inputs.publisher.PublisherManagerStartupResult
import jp.oist.abcvlib.core.inputs.publisher.PublisherStartupFailureHandler
import jp.oist.abcvlib.core.inputs.publisher.PublisherStartupHandler
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

open class Trial(
    metaParameters: MetaParameters,
    actionSpace: ActionSpace,
    stateSpace: StateSpace
) : Runnable, ActionSelector, SocketListener {

    private val context: Context = metaParameters.context
    val timeStepDataBuffer: TimeStepDataBuffer = metaParameters.timeStepDataBuffer

    protected val outputs: Outputs = metaParameters.outputs

    protected val robotID: Int = metaParameters.robotID

    var timeStepLength: Int = metaParameters.timeStepLength
        private set

    var maxTimeStepCount: Int = metaParameters.maxTimeStepCount

    var maxReward: Int = metaParameters.maxReward
        private set

    protected var maxEpisodeCount: Int = metaParameters.maxEpisodeCount

    val commActionSet: CommActionSpace = actionSpace.commActionSpace
    val motionActionSet: MotionActionSpace = actionSpace.motionActionSpace
    private val publisherManager: PublisherManager = stateSpace.publisherManager

    var timeStep: Int = 0

    // Use to trigger MainActivity to stop generating episodes
    var lastEpisode: Boolean = false

    // Use to trigger MainActivity to stop generating timesteps for a single episode
    var lastTimestep: Boolean = false

    var reward: Int = 0
        private set

    var episodeCount: Int = 0
        protected set

    private lateinit var timeStepDataAssemblerFuture: ScheduledFuture<*>
    private val executor: ScheduledExecutorServiceWithException
    private var flatBufferAssembler: FlatbufferAssembler
    private var trialStarted = false
    private var publisherFailureHandler: PublisherStartupFailureHandler? = null
    private var retryPublisherStartupAction: (() -> Unit)? = null
    private var onPublisherStartupSucceeded: () -> Unit = {}
    private val publisherStartupHandler: PublisherStartupHandler

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

        val lifecycleOwner = context as? LifecycleOwner
        val publisherStartupExecutor = if (lifecycleOwner != null) {
            Executor { command ->
                lifecycleOwner.lifecycleScope.launch(Dispatchers.Default) { command.run() }
            }
        } else {
            Executor { command ->
                ProcessPriorityThreadFactory(1, "trialPublisherStartup")
                    .newThread(command)
                    .start()
            }
        }

        publisherStartupHandler = PublisherStartupHandler(
            publisherManager,
            backgroundExecutor = publisherStartupExecutor,
            presentFailure = failurePresenter@{ failure, retry ->
                if (!isPublisherStartupActive()) return@failurePresenter
                retryPublisherStartupAction = retry
                onPublisherStartupFailed(failure)
            }
        )
    }

    fun setFlatBufferAssembler(flatBufferAssembler: FlatbufferAssembler) {
        this.flatBufferAssembler = flatBufferAssembler
    }

    protected open fun startTrail() {
        publisherStartupHandler.start startupSucceeded@{
            if (!isPublisherStartupActive()) return@startupSucceeded
            publisherFailureHandler?.dismiss()
            onPublisherStartupSucceeded()
            startTrialOnce()
        }
    }

    private fun isPublisherStartupActive(): Boolean {
        val lifecycleOwner = context as? LifecycleOwner
        if (lifecycleOwner?.lifecycle?.currentState == Lifecycle.State.DESTROYED) return false

        val activity = context as? Activity
        return activity == null || (!activity.isDestroyed && !activity.isFinishing)
    }

    fun startTrail(onStartupSucceeded: () -> Unit) {
        onPublisherStartupSucceeded = onStartupSucceeded
        startTrail()
    }

    protected fun retryPublisherStartup() {
        checkNotNull(retryPublisherStartupAction) {
            "Publisher retry is unavailable before a startup failure"
        }.invoke()
    }

    protected open fun onPublisherStartupFailed(
        result: PublisherManagerStartupResult.Failure
    ) {
        outputs.turnOffWheels()
        Logger.e(
            TAG,
            "Trial cannot start because ${result.requiredFailures.size} required publishers failed"
        )
        val activity = context as? Activity
        if (activity == null) {
            Logger.e(
                TAG,
                "Cannot show the publisher startup failure dialog without an Activity context. " +
                    "Override onPublisherStartupFailed to provide custom failure handling."
            )
            return
        }

        val failureHandler = publisherFailureHandler
            ?: PublisherStartupFailureHandler(activity, publisherManager).also {
                publisherFailureHandler = it
            }
        failureHandler.show(result, ::retryPublisherStartup)
    }

    @Synchronized
    private fun startTrialOnce() {
        if (trialStarted) return
        trialStarted = true
        publisherFailureHandler?.dismiss()
        startEpisode()
        startPublishers()
    }

    protected open fun startPublishers() {
        timeStepDataAssemblerFuture = executor.scheduleAtFixedRate(
            this,
            timeStepLength.toLong(),
            timeStepLength.toLong(),
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
        forward(timeStepDataBuffer.getReadData())

        // Add timestep and return int representing offset in flatbuffer
        try {
            flatBufferAssembler.addTimeStep(timeStepDataBuffer.getReadIndex())
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
            timeStepLength.toLong(),
            timeStepLength.toLong(),
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
        synchronized(flatBufferAssembler.flatbufferWriteFutures) {
            // Waits for all timestep flatbuffer writes to finish prior to finishing flatbuffer
            for (future in flatBufferAssembler.flatbufferWriteFutures) {
                future.get()
            }
            flatBufferAssembler.flatbufferWriteFutures.clear()
        }
        flatBufferAssembler.endEpisode()
        pausePublishers()
        timeStep = 0
        lastTimestep = false
        episodeCount++
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

    open fun isLastEpisode(): Boolean = (episodeCount >= maxEpisodeCount) || lastEpisode

    open fun isLastTimestep(): Boolean = (timeStep >= maxTimeStepCount) || lastTimestep

    fun incrementEpisodeCount() {
        episodeCount++
    }

    fun incrementTimeStep() {
        timeStep++
    }
}

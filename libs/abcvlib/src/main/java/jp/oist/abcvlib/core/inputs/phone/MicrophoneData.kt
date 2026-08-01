package jp.oist.abcvlib.core.inputs.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRecord.OnRecordPositionUpdateListener
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import jp.oist.abcvlib.core.inputs.publisher.Publisher
import jp.oist.abcvlib.core.inputs.publisher.PublisherManager
import jp.oist.abcvlib.util.ErrorHandler
import jp.oist.abcvlib.util.Logger
import jp.oist.abcvlib.util.ProcessPriorityThreadFactory
import jp.oist.abcvlib.util.ScheduledExecutorServiceWithException

open class MicrophoneData(
    context: Context,
    publisherManager: PublisherManager
) : Publisher<MicrophoneDataSubscriber>(context, publisherManager),
    OnRecordPositionUpdateListener {

    private val _startTime: AudioTimestamp = AudioTimestamp()
    private val _endTime: AudioTimestamp = AudioTimestamp()

    @Volatile
    private var audioResources: AudioResources? = null

    private var audioHandlerThread: HandlerThread? = null

    class Builder(
        private val context: Context,
        private val publisherManager: PublisherManager
    ) {
        fun build(): MicrophoneData {
            return MicrophoneData(context, publisherManager)
        }
    }

    override fun start() {
        var attemptResources: AudioResources? = null
        try {
            val resources = createAudioResources()
            attemptResources = resources
            synchronized(this) {
                audioResources = resources
            }
            val activeRecorder = resources.recorder
            activeRecorder.startRecording()
            check(activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Unable to start audio recording"
            }

            val timestampResult = waitForUsableTimestamp {
                activeRecorder.getTimestamp(
                    _startTime,
                    AudioTimestamp.TIMEBASE_MONOTONIC
                )
            }
            check(timestampResult == AudioRecord.SUCCESS) {
                "Unable to read microphone timestamp"
            }
            Logger.i(
                "microphone_start",
                "StartFrame:" + _startTime.framePosition + " NanoTime: " + _startTime.nanoTime
            )
            super.start()
            reportInitializationSucceeded()
        } catch (failure: InterruptedException) {
            releaseAudioResources(attemptResources)
            releaseAudioHandlerThread()
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: Exception) {
            releaseAudioResources(attemptResources)
            releaseAudioHandlerThread()
            throw failure
        }
    }

    override fun stop() {
        releaseAudioResources()
        releaseAudioHandlerThread()
        super.stop()
    }

    override fun getRequiredPermissions(): ArrayList<String> {
        val permissions = ArrayList<String>()
        permissions.add(Manifest.permission.RECORD_AUDIO)
        return permissions
    }

    fun getStartTime(): AudioTimestamp {
        return _startTime
    }

    fun setStartTime() {
        audioResources?.recorder?.getTimestamp(_startTime, AudioTimestamp.TIMEBASE_MONOTONIC)
    }


    fun getEndTime(): AudioTimestamp {
        audioResources?.recorder?.getTimestamp(_endTime, AudioTimestamp.TIMEBASE_MONOTONIC)
        return _endTime
    }

    fun getSampleRate(): Int {
        return synchronized(this) {
            audioResources?.sampleRate ?: AUDIO_SAMPLE_RATE
        }
    }

    override fun onMarkerReached(recorder: AudioRecord) {
    }

    /**
     * This method fires 2 times during each loop of the audio record buffer.
     * audioRecord.read(audioData) writes the buffer values (stored in the audioRecord) to a local
     * float array called audioData. It is set to read in non_blocking mode
     * (https://developer.android.com/reference/android/media/AudioRecord?hl=ja#READ_NON_BLOCKING)
     * You can verify it is not blocking by checking the log for "Missed some audio samples"
     * You can verify if the buffer writer is overflowing by checking the log for:
     * "W/AudioFlinger: RecordThread: buffer overflow"
     * @param audioRecord
     */
    override fun onPeriodicNotification(audioRecord: AudioRecord) {
        val activeExecutor = synchronized(this) {
            audioResources
                ?.takeIf { it.recorder === audioRecord }
                ?.executor
        } ?: return
        try {
            activeExecutor.execute {
                val readBufferSize = audioRecord.positionNotificationPeriod
                val audioData = FloatArray(readBufferSize)
                @SuppressLint("WrongConstant") val numSamples = audioRecord.read(
                    audioData, 0,
                    readBufferSize, AudioRecord.READ_NON_BLOCKING
                )
                if (numSamples < readBufferSize) {
                    Logger.w("microphone", "Missed some audio samples")
                }
                onNewAudioData(audioData, numSamples)
            }
        } catch (e: Exception) {
            ErrorHandler.eLog("onPeriodicNotification", "sadfkjsdhf", e, true)
        }
    }

    protected fun onNewAudioData(audioData: FloatArray, numSamples: Int) {
        val activeResources = synchronized(this) { audioResources } ?: return
        if (subscribers.isNotEmpty() && !paused) {
            for (subscriber in subscribers) {
                subscriber.onMicrophoneDataUpdate(
                    audioData,
                    numSamples,
                    sampleRate = activeResources.sampleRate,
                    startTime = getStartTime(),
                    endTime = getEndTime()
                )
            }
            setStartTime()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    internal open fun createRecorder(bufferSize: Int): AudioRecord {
        val audioSource = MediaRecorder.AudioSource.UNPROCESSED
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

        return AudioRecord(
            audioSource,
            AUDIO_SAMPLE_RATE,
            channelConfig,
            audioFormat,
            bufferSize
        )
    }

    internal fun waitForUsableTimestamp(readTimestamp: () -> Int): Int {
        val timestampWaitStarted = SystemClock.elapsedRealtime()
        var timestampResult = readTimestamp()
        while (timestampResult == AudioRecord.ERROR_INVALID_OPERATION) {
            check(
                SystemClock.elapsedRealtime() - timestampWaitStarted <
                    TIMESTAMP_TIMEOUT_MILLIS
            ) {
                "Timed out waiting for a usable microphone timestamp"
            }
            Thread.sleep(TIMESTAMP_POLL_INTERVAL_MILLIS)
            timestampResult = readTimestamp()
        }
        return timestampResult
    }

    private fun createAudioResources(): AudioResources {
        val priority = ProcessPriorityThreadFactory(10, "dataGatherer")
        val executor = ScheduledExecutorServiceWithException(1, priority)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_FLOAT
        val bufferSize = 3 * AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            channelConfig,
            audioFormat
        )

        val activeRecorder = try {
            createRecorder(bufferSize)
        } catch (failure: SecurityException) {
            executor.shutdownNow()
            throw failure
        } catch (failure: Exception) {
            executor.shutdownNow()
            throw failure
        }
        val resources = AudioResources(activeRecorder, executor, activeRecorder.sampleRate)

        try {
            val bytesPerSample = 32 / 8
            val bytesPerFrame = bytesPerSample * activeRecorder.channelCount
            val framesPerBuffer = bufferSize / bytesPerFrame
            val framePeriod = framesPerBuffer / 2
            activeRecorder.positionNotificationPeriod = framePeriod
            val handler = createAudioHandler()
            activeRecorder.setRecordPositionUpdateListener(this, handler)
            return resources
        } catch (failure: Exception) {
            releaseAudioResources(resources)
            releaseAudioHandlerThread()
            throw failure
        }
    }

    @Synchronized
    private fun createAudioHandler(): Handler {
        val handlerThread = audioHandlerThread
            ?.takeIf { it.isAlive }
            ?: HandlerThread("audioHandlerThread").also {
                it.start()
                audioHandlerThread = it
            }
        return Handler(handlerThread.looper)
    }

    private fun releaseAudioHandlerThread() {
        val handlerThread = synchronized(this) {
            audioHandlerThread.also { audioHandlerThread = null }
        }
        handlerThread?.quitSafely()
    }

    private fun releaseAudioResources(resources: AudioResources? = audioResources) {
        if (resources == null) return
        synchronized(resources) {
            if (resources.released) return
            resources.released = true
        }
        synchronized(this) {
            if (audioResources === resources) {
                audioResources = null
            }
        }
        val activeRecorder = resources.recorder
        runCatching { activeRecorder.setRecordPositionUpdateListener(null) }
        if (activeRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { activeRecorder.stop() }
        }

        resources.executor.shutdownNow()
        runCatching { activeRecorder.release() }
    }
    /*        public void processAudioFrame(short[] audioFrame) {
                final double bufferLength = 20; //milliseconds
                final double bufferSampleCount = mSampleRate / bufferLength;
                // The Google ASR input requirements state that audio input sensitivity
                // should be set such that 90 dB SPL at 1000 Hz yields RMS of 2500 for
                // 16-bit samples, i.e. 20 * log_10(2500 / mGain) = 90.
                final double mGain = 2500.0 / Math.pow(10.0, 90.0 / 20.0);
                double mRmsSmoothed = 0;  // Temporally filtered version of RMS.

                // Leq Calcs
                double leqLength = 5; // seconds
                double leqArrayLength = (mSampleRate / bufferSampleCount) * leqLength;
                double[] leqBuffer = new double[(int) leqArrayLength];
                // Compute the RMS value. (Note that this does not remove DC).
                rms = 0;
                for (short value : audioFrame) {
                    rms += value * value;
                }
                rms = Math.sqrt(rms / audioFrame.length);

                // Compute a smoothed version for less flickering of the display.
                // Coefficient of IIR smoothing filter for RMS.
                double mAlpha = 0.9;
                mRmsSmoothed = (mRmsSmoothed * mAlpha) + (1 - mAlpha) * rms;
                rmsdB = 20 + (20.0 * Math.log10(mGain * mRmsSmoothed));

            }

            public int getTotalSamples() {
                return mTotalSamples;
            }

            public void setTotalSamples(int totalSamples) {
                mTotalSamples = totalSamples;
            }

            public double getRms() {
                return rms;
            }

            public double getRmsdB() {
                return rmsdB;
            }
    */
    private companion object {
        const val AUDIO_SAMPLE_RATE = 8_000
        const val TIMESTAMP_POLL_INTERVAL_MILLIS = 20L
        const val TIMESTAMP_TIMEOUT_MILLIS = 2_000L
    }

    private data class AudioResources(
        val recorder: AudioRecord,
        val executor: ScheduledExecutorServiceWithException,
        val sampleRate: Int,
        var released: Boolean = false
    )
}

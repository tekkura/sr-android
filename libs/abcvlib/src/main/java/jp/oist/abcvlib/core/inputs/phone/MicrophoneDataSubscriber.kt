package jp.oist.abcvlib.core.inputs.phone

import android.media.AudioTimestamp
import jp.oist.abcvlib.core.inputs.Subscriber

interface MicrophoneDataSubscriber : Subscriber {
    /**
     * Likely easier to pull data from a TimeStepDataBuffer as it handles concatenating all samples
     * to one object.
     * @param audioData most recent sampling from buffer
     * @param numSamples number of samples copied from buffer
     */
    fun onMicrophoneDataUpdate(
        audioData: FloatArray,
        numSamples: Int,
        sampleRate: Int,
        startTime: AudioTimestamp,
        endTime: AudioTimestamp
    )
}

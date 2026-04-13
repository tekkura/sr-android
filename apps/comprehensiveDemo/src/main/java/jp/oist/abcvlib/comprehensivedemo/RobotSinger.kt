package jp.oist.abcvlib.comprehensivedemo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import jp.oist.abcvlib.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

internal class RobotSinger {
    private val playing = AtomicBoolean(false)
    private var audioThread: Thread? = null

    @Synchronized
    fun start() {
        if (playing.get()) {
            return
        }
        audioThread?.takeIf { it.isAlive }?.join(AUDIO_THREAD_JOIN_TIMEOUT_MS)
        if (!playing.compareAndSet(false, true)) {
            return
        }
        audioThread = Thread(::playSongLoop, "ComprehensiveDemoRobotSinger").apply {
            isDaemon = true
            start()
        }
        Logger.i(TAG, "RobotSinger started")
    }

    @Synchronized
    fun stop() {
        if (!playing.compareAndSet(true, false)) {
            return
        }
        val thread = audioThread
        thread?.interrupt()
        if (thread != null && thread != Thread.currentThread()) {
            thread.join(AUDIO_THREAD_JOIN_TIMEOUT_MS)
        }
        audioThread = null
        Logger.i(TAG, "RobotSinger stopped")
    }

    private fun playSongLoop() {
        val minBufferBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            Logger.e(TAG, "Invalid AudioTrack buffer size: $minBufferBytes")
            playing.set(false)
            return
        }

        val audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBufferBytes * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )

        try {
            audioTrack.setVolume(AudioTrack.getMaxVolume())
            audioTrack.play()
            while (playing.get()) {
                for (repeatIndex in 0 until LIGHT_VENTED_BULBUL_GENOME.repeatsBeforeLongRest) {
                    for (event in LIGHT_VENTED_BULBUL_GENOME.phrase) {
                        if (!playing.get()) {
                            break
                        }
                        playEvent(audioTrack, event)
                    }
                    if (!playing.get()) {
                        break
                    }
                    playRest(audioTrack, LIGHT_VENTED_BULBUL_GENOME.phraseRestMs)
                }
                playRest(audioTrack, LIGHT_VENTED_BULBUL_GENOME.longRestMs)
            }
        } catch (e: IllegalStateException) {
            Logger.e(TAG, "AudioTrack playback failed", e)
        } finally {
            playing.set(false)
            runCatching { audioTrack.pause() }
            runCatching { audioTrack.flush() }
            runCatching { audioTrack.release() }
            Logger.i(TAG, "RobotSinger released")
        }
    }

    private fun playEvent(audioTrack: AudioTrack, event: BirdSongEvent) {
        when (event) {
            is BirdSongEvent.Chirp -> playChirp(audioTrack, event)
            is BirdSongEvent.Rest -> playRest(audioTrack, event.durationMs)
        }
    }

    private fun playChirp(audioTrack: AudioTrack, chirp: BirdSongEvent.Chirp) {
        val totalSamples = samplesForDuration(chirp.durationMs)
        val buffer = ShortArray(totalSamples)
        val startFrequency = chirp.startFrequencyHz
        val endFrequency = chirp.endFrequencyHz
        var phase = 0.0

        for (sampleIndex in buffer.indices) {
            val progress = sampleIndex.toDouble() / totalSamples
            val envelope = envelope(progress)
            val vibrato = sin(2.0 * PI * chirp.vibratoHz * sampleIndex / SAMPLE_RATE_HZ) *
                chirp.vibratoDepth
            val frequency = curve(startFrequency, endFrequency, progress, chirp.curve) *
                (1.0 + vibrato)
            phase += 2.0 * PI * frequency / SAMPLE_RATE_HZ
            val base = sin(phase)
            val harmonic = chirp.harmonicMix * sin(phase * 2.0 + 0.25)
            val shimmer = chirp.shimmerMix * sin(phase * 3.0 + 0.65)
            val pulse = 1.0 + chirp.pulseDepth *
                sin(2.0 * PI * chirp.pulseHz * sampleIndex / SAMPLE_RATE_HZ)
            val sample = (base + harmonic + shimmer) *
                pulse *
                envelope *
                chirp.gain *
                MASTER_GAIN
            buffer[sampleIndex] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }

        audioTrack.write(buffer, 0, buffer.size)
    }

    private fun playRest(audioTrack: AudioTrack, durationMs: Int) {
        var remainingSamples = samplesForDuration(durationMs)
        val buffer = ShortArray(minOf(WRITE_CHUNK_SAMPLES, remainingSamples))
        while (playing.get() && remainingSamples > 0) {
            val samplesToWrite = minOf(buffer.size, remainingSamples)
            audioTrack.write(buffer, 0, samplesToWrite)
            remainingSamples -= samplesToWrite
        }
    }

    private fun envelope(progress: Double): Double {
        val attack = (progress / ATTACK_FRACTION).coerceIn(0.0, 1.0)
        val release = ((1.0 - progress) / RELEASE_FRACTION).coerceIn(0.0, 1.0)
        return minOf(attack, release)
    }

    private fun lerp(start: Double, end: Double, progress: Double): Double {
        return start + ((end - start) * progress)
    }

    private fun curve(start: Double, end: Double, progress: Double, bend: Double): Double {
        val bentProgress = (progress + bend * progress * (1.0 - progress)).coerceIn(0.0, 1.0)
        return lerp(start, end, bentProgress)
    }

    private fun samplesForDuration(durationMs: Int): Int {
        return ((durationMs * SAMPLE_RATE_HZ) / 1000).coerceAtLeast(1)
    }

    companion object {
        private const val TAG = "RobotSinger"
        private const val SAMPLE_RATE_HZ = 22_050
        private const val ATTACK_FRACTION = 0.11
        private const val RELEASE_FRACTION = 0.34
        private const val AUDIO_THREAD_JOIN_TIMEOUT_MS = 500L
        private const val WRITE_CHUNK_SAMPLES = 1024
        private const val MASTER_GAIN = 0.74

        // Synthetic approximation of a Light-vented Bulbul call, kept parametric for later
        // evolution work instead of sampling the source recording directly.
        private val LIGHT_VENTED_BULBUL_GENOME = BirdSongGenome(
            repeatsBeforeLongRest = 2,
            phraseRestMs = 250,
            longRestMs = 850,
            phrase = listOf(
                BirdSongEvent.Chirp(
                    startFrequencyHz = 1_460.0,
                    endFrequencyHz = 1_820.0,
                    durationMs = 118,
                    vibratoHz = 7.5,
                    vibratoDepth = 0.006,
                    pulseHz = 8.0,
                    pulseDepth = 0.045,
                    harmonicMix = 0.055,
                    shimmerMix = 0.018,
                    curve = 0.12,
                    gain = 0.34
                ),
                BirdSongEvent.Rest(58),
                BirdSongEvent.Chirp(
                    startFrequencyHz = 1_560.0,
                    endFrequencyHz = 1_930.0,
                    durationMs = 125,
                    vibratoHz = 8.0,
                    vibratoDepth = 0.006,
                    pulseHz = 8.0,
                    pulseDepth = 0.045,
                    harmonicMix = 0.055,
                    shimmerMix = 0.018,
                    curve = 0.08,
                    gain = 0.32
                ),
                BirdSongEvent.Rest(72),
                BirdSongEvent.Chirp(
                    startFrequencyHz = 2_060.0,
                    endFrequencyHz = 1_660.0,
                    durationMs = 145,
                    vibratoHz = 9.0,
                    vibratoDepth = 0.007,
                    pulseHz = 9.0,
                    pulseDepth = 0.05,
                    harmonicMix = 0.05,
                    shimmerMix = 0.016,
                    curve = -0.10,
                    gain = 0.30
                ),
                BirdSongEvent.Rest(70),
                BirdSongEvent.Chirp(
                    startFrequencyHz = 1_520.0,
                    endFrequencyHz = 1_890.0,
                    durationMs = 132,
                    vibratoHz = 8.5,
                    vibratoDepth = 0.006,
                    pulseHz = 8.0,
                    pulseDepth = 0.045,
                    harmonicMix = 0.052,
                    shimmerMix = 0.016,
                    curve = 0.10,
                    gain = 0.31
                )
            )
        )
    }

    private data class BirdSongGenome(
        val phrase: List<BirdSongEvent>,
        val repeatsBeforeLongRest: Int,
        val phraseRestMs: Int,
        val longRestMs: Int
    )

    private sealed class BirdSongEvent {
        data class Chirp(
            val startFrequencyHz: Double,
            val endFrequencyHz: Double,
            val durationMs: Int,
            val vibratoHz: Double,
            val vibratoDepth: Double,
            val pulseHz: Double,
            val pulseDepth: Double,
            val harmonicMix: Double,
            val shimmerMix: Double,
            val curve: Double,
            val gain: Double
        ) : BirdSongEvent()

        data class Rest(val durationMs: Int) : BirdSongEvent()
    }
}

package jp.oist.abcvlib.core.outputs

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import jp.oist.abcvlib.core.AbcvlibActivity
import jp.oist.abcvlib.util.ErrorHandler
import java.io.IOException
import androidx.core.net.toUri

/**
 * @param abcvlibActivity
 * @param audioFile String representing location of audioFile
 */
class MediaPlayerRunnable(
    private val abcvlibActivity: AbcvlibActivity, // todo make this type more specific after knowing what type it actually is.
    private val audioFile: String // was previously "android.resource://jp.oist.abcvlib.claplearn/" + R.raw.custommix
) : Runnable {
    private val TAG: String = javaClass.name

    private lateinit var audioManager: AudioManager
    private lateinit var mediaPlayer: MediaPlayer

    override fun run() {
        audioManager = abcvlibActivity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaPlayer = MediaPlayer()
        val loc = audioFile.toUri()
        try {
            mediaPlayer.setDataSource(abcvlibActivity, loc)
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM)
            mediaPlayer.isLooping = true
            mediaPlayer.prepare()
        } catch (e: Exception) {
            when (e) {
                is IllegalArgumentException,
                is SecurityException,
                is IllegalStateException,
                is IOException -> ErrorHandler.eLog(TAG, "Error running MediaPlayer", e, true)

                else -> throw e
            }
        }
        audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_ALARM,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        mediaPlayer.start()
    }
}

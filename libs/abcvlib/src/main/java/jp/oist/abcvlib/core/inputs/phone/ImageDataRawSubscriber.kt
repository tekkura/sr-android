package jp.oist.abcvlib.core.inputs.phone

import android.graphics.Bitmap
import jp.oist.abcvlib.core.inputs.Subscriber

interface ImageDataRawSubscriber : Subscriber {
    /**
     * Likely easier to use TimeStampDataBuffer where all of this is collected over time into
     * timesteps, but this serves as a way to inspect the stream.
     * @param timestamp in nanoseconds see [System.nanoTime]
     * @param width in pixels
     * @param height in pixels
     * @param bitmap compressed bitmap object
     */
    fun onImageDataRawUpdate(timestamp: Long, width: Int, height: Int, bitmap: Bitmap)
}

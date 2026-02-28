package jp.oist.abcvlib.util

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import jp.oist.abcvlib.core.inputs.TimeStepDataBuffer.TimeStepData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object ImageOps {
    fun compressImage(image: Bitmap, format: CompressFormat, quality: Int): Bitmap {
        val byteArrayOutputStream = ByteArrayOutputStream()
        image.compress(format, quality, byteArrayOutputStream)
        val bais = ByteArrayInputStream(byteArrayOutputStream.toByteArray())
        return BitmapFactory.decodeStream(bais, null, null)!!
    }

    fun generateBitmap(image: ByteArray): Bitmap {
        val bais = ByteArrayInputStream(image)
        return BitmapFactory.decodeStream(bais, null, null)!!
    }

    @JvmStatic
    fun addCompressedImage2Buffer(
        idx: Int,
        timestamp: Long,
        bitmap: Bitmap,
        buffer: Array<TimeStepData>
    ) {
        val webpByteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(CompressFormat.WEBP, 0, webpByteArrayOutputStream)
        val webpBytes = webpByteArrayOutputStream.toByteArray()
        // Bitmap webpBitMap = ImageOps.generateBitmap(webpBytes);
        buffer[idx].imageData[timestamp]!!.webpImage = webpBytes
    }
}

/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jp.oist.abcvlib.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * Helper class used to convert a [Media.Image] object from [ImageFormat.YUV_420_888]
 * format to an RGB [Bitmap] object without relying on deprecated RenderScript APIs.
 */
class YuvToRgbConverter(@Suppress("UNUSED_PARAMETER") context: Context) {
    private var pixelCount: Int = -1
    private lateinit var yuvBuffer: ByteArray
    private lateinit var jpegOutputStream: ByteArrayOutputStream

    @Synchronized
    fun yuvToRgb(image: Image, output: Bitmap) {
        if (!::yuvBuffer.isInitialized) {
            pixelCount = image.cropRect.width() * image.cropRect.height()
            val pixelSizeBits = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888)
            yuvBuffer = ByteArray(pixelCount * pixelSizeBits / 8)
            jpegOutputStream = ByteArrayOutputStream()
        }

        imageToByteBuffer(image, yuvBuffer)

        jpegOutputStream.reset()
        val yuvImage = YuvImage(
            yuvBuffer,
            ImageFormat.NV21,
            image.cropRect.width(),
            image.cropRect.height(),
            null
        )
        yuvImage.compressToJpeg(Rect(0, 0, image.cropRect.width(), image.cropRect.height()), 100, jpegOutputStream)
        val jpegBytes = jpegOutputStream.toByteArray()
        val decodedBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: error("Failed to decode JPEG bytes from YUV image")

        Canvas(output).drawBitmap(decodedBitmap, 0f, 0f, null)
    }

    private fun imageToByteBuffer(image: Image, outputBuffer: ByteArray) {
        assert(image.format == ImageFormat.YUV_420_888)

        val imageCrop = image.cropRect
        val imagePlanes = image.planes

        imagePlanes.forEachIndexed { planeIndex, plane ->
            val outputStride: Int
            var outputOffset: Int

            when (planeIndex) {
                0 -> {
                    outputStride = 1
                    outputOffset = 0
                }
                1 -> {
                    outputStride = 2
                    outputOffset = pixelCount + 1
                }
                2 -> {
                    outputStride = 2
                    outputOffset = pixelCount
                }
                else -> return@forEachIndexed
            }

            val planeBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val planeCrop = if (planeIndex == 0) {
                imageCrop
            } else {
                Rect(
                    imageCrop.left / 2,
                    imageCrop.top / 2,
                    imageCrop.right / 2,
                    imageCrop.bottom / 2
                )
            }

            val planeWidth = planeCrop.width()
            val planeHeight = planeCrop.height()
            val rowBuffer = ByteArray(plane.rowStride)

            val rowLength = if (pixelStride == 1 && outputStride == 1) {
                planeWidth
            } else {
                (planeWidth - 1) * pixelStride + 1
            }

            for (row in 0 until planeHeight) {
                planeBuffer.position((row + planeCrop.top) * rowStride + planeCrop.left * pixelStride)

                if (pixelStride == 1 && outputStride == 1) {
                    planeBuffer.get(outputBuffer, outputOffset, rowLength)
                    outputOffset += rowLength
                } else {
                    planeBuffer.get(rowBuffer, 0, rowLength)
                    for (col in 0 until planeWidth) {
                        outputBuffer[outputOffset] = rowBuffer[col * pixelStride]
                        outputOffset += outputStride
                    }
                }
            }
        }
    }
}

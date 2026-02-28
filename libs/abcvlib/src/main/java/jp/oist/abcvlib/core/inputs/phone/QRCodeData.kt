package jp.oist.abcvlib.core.inputs.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import jp.oist.abcvlib.core.inputs.PublisherManager
import jp.oist.abcvlib.util.Logger
import java.util.concurrent.ExecutorService

class QRCodeData(
    context: Context, publisherManager: PublisherManager, lifecycleOwner: LifecycleOwner,
    imageAnalysis: ImageAnalysis?, imageExecutor: ExecutorService?
) : ImageData<QRCodeDataSubscriber>(
    context, publisherManager, lifecycleOwner,
    null, imageAnalysis, imageExecutor
) {
    class Builder(
        context: Context,
        publisherManager: PublisherManager,
        lifecycleOwner: LifecycleOwner
    ) : ImageData.Builder<QRCodeData, QRCodeDataSubscriber, Builder>(
        context,
        publisherManager,
        lifecycleOwner
    ) {

        fun build(): QRCodeData {
            return QRCodeData(
                context,
                publisherManager,
                lifecycleOwner,
                imageAnalysis,
                imageExecutor
            )
        }

        override fun self(): Builder {
            return this
        }
    }

    override fun customAnalysis(
        imageData: ByteArray,
        rotation: Int,
        format: Int,
        width: Int,
        height: Int,
        timestamp: Long,
        bitmap: Bitmap
    ) {
        if (format == ImageFormat.YUV_420_888 || format == ImageFormat.YUV_422_888
            || format == ImageFormat.YUV_444_888
        ) {
            val source = PlanarYUVLuminanceSource(
                imageData,
                width, height,
                0, 0,
                width, height,
                false
            )

            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            try {
                val result = QRCodeReader().decode(binaryBitmap)
                val qrDecodedData = result.text
                for (subscriber in subscribers) {
                    subscriber.onQRCodeDetected(qrDecodedData)
                }
            } catch (e: FormatException) {
                Logger.v("qrcode", "QR Code cannot be decoded")
            } catch (e: ChecksumException) {
                Logger.v("qrcode", "QR Code error correction failed")
                e.printStackTrace()
            } catch (e: NotFoundException) {
                Logger.v("qrcode", "QR Code not found")
            }
        }
    }

    override fun setDefaultImageAnalysis() {
        imageAnalysis =
            ImageAnalysis.Builder()
                .setTargetResolution(Size(400, 300))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setImageQueueDepth(4)
                .build()
    }
}

package jp.oist.abcvlib.core.inputs.phone

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import jp.oist.abcvlib.core.inputs.PublisherManager
import java.util.concurrent.ExecutorService

/**
 * @param previewView e.g. from your Activity findViewById(R.id.camera_x_preview)
 * @param imageAnalysis If set to null will generate default imageAnalysis object.
 */
class ImageDataRaw(
    context: Context, publisherManager: PublisherManager,
    lifecycleOwner: LifecycleOwner, previewView: PreviewView?,
    imageAnalysis: ImageAnalysis?, imageExecutor: ExecutorService?
) : ImageData<ImageDataRawSubscriber>(
    context, publisherManager,
    lifecycleOwner, previewView,
    imageAnalysis, imageExecutor
), ImageAnalysis.Analyzer {

    // We must specify T to define the extending subclass, S to specify the subscriber type used by the extending subclass, and B to reference the extending subclasses' builder class.
    class Builder(
        context: Context, publisherManager: PublisherManager, lifecycleOwner: LifecycleOwner
    ) : ImageData.Builder<ImageDataRaw, ImageDataRawSubscriber, Builder>(
        context, publisherManager, lifecycleOwner
    ) {
        fun build(): ImageDataRaw {
            return ImageDataRaw(
                context,
                publisherManager,
                lifecycleOwner,
                previewView,
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
        for (subscriber in subscribers) {
            subscriber.onImageDataRawUpdate(timestamp, width, height, bitmap)
        }
    }
}

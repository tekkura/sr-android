package jp.oist.abcvlib.core.inputs.phone

import android.graphics.Bitmap
import jp.oist.abcvlib.core.inputs.Subscriber
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.Detection

interface ObjectDetectorDataSubscriber : Subscriber {
    fun onObjectsDetected(
        bitmap: Bitmap,
        tensorImage: TensorImage,
        results: MutableList<Detection>,
        inferenceTime: Long,
        height: Int,
        width: Int
    )
}

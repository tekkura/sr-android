package jp.oist.abcvlib.core.inputs.phone

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import jp.oist.abcvlib.core.inputs.Subscriber

interface ObjectDetectorDataSubscriber : Subscriber {
    fun onObjectsDetected(
        bitmap: Bitmap,
        mpImage: MPImage,
        results: ObjectDetectorResult,
        inferenceTime: Long,
        height: Int,
        width: Int
    )
}

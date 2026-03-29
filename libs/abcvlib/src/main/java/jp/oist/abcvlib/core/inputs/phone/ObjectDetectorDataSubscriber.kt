package jp.oist.abcvlib.core.inputs.phone

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.Detection
import jp.oist.abcvlib.core.inputs.Subscriber

interface ObjectDetectorDataSubscriber : Subscriber {
    fun onObjectsDetected(
        bitmap: Bitmap,
        mpImage: MPImage,
        results: MutableList<Detection>,
        inferenceTime: Long,
        frameCapturedAtNs: Long,
        detectStartedAtNs: Long,
        detectCompletedAtNs: Long,
        height: Int,
        width: Int
    )
}

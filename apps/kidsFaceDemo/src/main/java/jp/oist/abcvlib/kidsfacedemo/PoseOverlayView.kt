package jp.oist.abcvlib.kidsfacedemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class PoseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val leftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        style = Paint.Style.FILL
    }
    private val rightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(3, 169, 244)
        style = Paint.Style.FILL
    }
    private val posePointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 30, 99)
        style = Paint.Style.FILL
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 193, 7)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private var pose: OverlayPose? = null
    private var gesture: OverlayGesture? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private val imageDest = RectF()

    fun updatePose(pose: OverlayPose?, imageWidth: Int, imageHeight: Int) {
        this.pose = pose
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    fun updateGesture(gesture: OverlayGesture?) {
        this.gesture = gesture
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateImageDest()
        val pose = this.pose
        if (pose == null && gesture == null) {
            canvas.drawText("No target", 32f, 64f, textPaint)
        } else if (pose != null) {
            drawPose(canvas, pose)
        }
        drawGesture(canvas)
    }

    private fun drawPose(canvas: Canvas, pose: OverlayPose) {
        POSE_CONNECTIONS.forEach { connection ->
            val start = pose.landmarks.getOrNull(connection.first)?.toViewPoint()
            val end = pose.landmarks.getOrNull(connection.second)?.toViewPoint()
            if (start != null && end != null) {
                canvas.drawLine(start.x, start.y, end.x, end.y, linePaint)
            }
        }

        pose.landmarks.forEach { landmark ->
            val point = landmark.toViewPoint()
            canvas.drawCircle(point.x, point.y, POSE_POINT_RADIUS, posePointPaint)
        }

        val leftFoot = pose.leftFoot.toViewPoint()
        val rightFoot = pose.rightFoot.toViewPoint()

        canvas.drawLine(leftFoot.x, leftFoot.y, rightFoot.x, rightFoot.y, linePaint)

        canvas.drawCircle(leftFoot.x, leftFoot.y, POINT_RADIUS, leftPaint)
        canvas.drawCircle(rightFoot.x, rightFoot.y, POINT_RADIUS, rightPaint)

        canvas.drawText(
            "target=${"%.2f".format(pose.metrics.targetX)}, " +
                "${"%.2f".format(pose.metrics.targetY)} " +
                "wheels=${"%.2f".format(pose.metrics.leftWheel)}, " +
                "${"%.2f".format(pose.metrics.rightWheel)}",
            32f,
            64f,
            textPaint
        )
    }

    private fun drawGesture(canvas: Canvas) {
        val gesture = this.gesture
        if (gesture == null) {
            canvas.drawText("gesture=None", 32f, 120f, textPaint)
            return
        }
        gesture.landmarks.forEach { landmark ->
            val point = landmark.toViewPoint()
            canvas.drawCircle(point.x, point.y, HAND_POINT_RADIUS, handPaint)
        }
        if (gesture.metrics.targetVisible) {
            val target = NormalizedPoint(
                gesture.metrics.targetX,
                gesture.metrics.targetY
            ).toViewPoint()
            canvas.drawCircle(target.x, target.y, POINT_RADIUS, targetPaint)
        }
        canvas.drawText(
            "gesture=${gesture.label}",
            32f,
            120f,
            textPaint
        )
        canvas.drawText(
            "target=${"%.2f".format(gesture.metrics.targetX)}, " +
                "${"%.2f".format(gesture.metrics.targetY)} " +
                "wheels=${"%.2f".format(gesture.metrics.leftWheel)}, " +
                "${"%.2f".format(gesture.metrics.rightWheel)}",
            32f,
            176f,
            textPaint
        )
    }

    private fun NormalizedPoint.toViewPoint(): ViewPoint {
        return ViewPoint(
            imageDest.left + ((x + 1f) / 2f) * imageDest.width(),
            imageDest.top + (1f - y) * imageDest.height()
        )
    }

    private fun updateImageDest() {
        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) {
            imageDest.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }
        val scale = max(width / imageWidth.toFloat(), height / imageHeight.toFloat())
        val displayWidth = imageWidth * scale
        val displayHeight = imageHeight * scale
        imageDest.set(
            (width - displayWidth) / 2f,
            (height - displayHeight) / 2f,
            (width + displayWidth) / 2f,
            (height + displayHeight) / 2f
        )
    }

    companion object {
        private const val POINT_RADIUS = 18f
        private const val POSE_POINT_RADIUS = 8f
        private const val HAND_POINT_RADIUS = 10f
        private val POSE_CONNECTIONS = listOf(
            0 to 1,
            0 to 4,
            1 to 2,
            2 to 3,
            3 to 7,
            4 to 5,
            5 to 6,
            6 to 8,
            9 to 10,
            11 to 12,
            11 to 13,
            12 to 14,
            13 to 15,
            14 to 16,
            15 to 17,
            15 to 19,
            15 to 21,
            16 to 18,
            16 to 20,
            16 to 22,
            17 to 19,
            18 to 20,
            23 to 24,
            23 to 25,
            24 to 26,
            25 to 27,
            26 to 28,
            27 to 29,
            28 to 30,
            28 to 32,
            29 to 31,
            30 to 32,
            11 to 23,
            12 to 24
        )
    }
}

data class OverlayPose(
    val landmarks: List<NormalizedPoint>,
    val leftFoot: NormalizedPoint,
    val rightFoot: NormalizedPoint,
    val metrics: PoseMetrics
)

data class NormalizedPoint(
    val x: Float,
    val y: Float
)

data class OverlayGesture(
    val label: String,
    val landmarks: List<NormalizedPoint>,
    val metrics: PoseMetrics
)

private data class ViewPoint(
    val x: Float,
    val y: Float
)

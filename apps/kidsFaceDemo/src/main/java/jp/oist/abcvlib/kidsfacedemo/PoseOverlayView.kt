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
    private val raisedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private var pose: OverlayPose? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private val imageDest = RectF()

    fun updatePose(pose: OverlayPose?, imageWidth: Int, imageHeight: Int) {
        this.pose = pose
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateImageDest()
        val pose = this.pose
        if (pose == null) {
            canvas.drawText("No pose", 32f, 64f, textPaint)
            return
        }

        val leftShoulder = pose.leftShoulder.toViewPoint()
        val rightShoulder = pose.rightShoulder.toViewPoint()
        val leftWrist = pose.leftWrist.toViewPoint()
        val rightWrist = pose.rightWrist.toViewPoint()
        val leftAnkle = pose.leftAnkle.toViewPoint()
        val rightAnkle = pose.rightAnkle.toViewPoint()

        canvas.drawLine(leftShoulder.x, leftShoulder.y, rightShoulder.x, rightShoulder.y, linePaint)
        canvas.drawLine(leftShoulder.x, leftShoulder.y, leftWrist.x, leftWrist.y, linePaint)
        canvas.drawLine(rightShoulder.x, rightShoulder.y, rightWrist.x, rightWrist.y, linePaint)
        canvas.drawLine(leftAnkle.x, leftAnkle.y, rightAnkle.x, rightAnkle.y, linePaint)

        canvas.drawCircle(leftShoulder.x, leftShoulder.y, POINT_RADIUS, leftPaint)
        canvas.drawCircle(rightShoulder.x, rightShoulder.y, POINT_RADIUS, rightPaint)
        canvas.drawCircle(leftAnkle.x, leftAnkle.y, POINT_RADIUS, leftPaint)
        canvas.drawCircle(rightAnkle.x, rightAnkle.y, POINT_RADIUS, rightPaint)
        canvas.drawCircle(
            leftWrist.x,
            leftWrist.y,
            POINT_RADIUS,
            if (pose.metrics.leftRaised) raisedPaint else leftPaint
        )
        canvas.drawCircle(
            rightWrist.x,
            rightWrist.y,
            POINT_RADIUS,
            if (pose.metrics.rightRaised) raisedPaint else rightPaint
        )

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
    }
}

data class OverlayPose(
    val leftShoulder: NormalizedPoint,
    val rightShoulder: NormalizedPoint,
    val leftWrist: NormalizedPoint,
    val rightWrist: NormalizedPoint,
    val leftAnkle: NormalizedPoint,
    val rightAnkle: NormalizedPoint,
    val metrics: PoseMetrics
)

data class NormalizedPoint(
    val x: Float,
    val y: Float
)

private data class ViewPoint(
    val x: Float,
    val y: Float
)

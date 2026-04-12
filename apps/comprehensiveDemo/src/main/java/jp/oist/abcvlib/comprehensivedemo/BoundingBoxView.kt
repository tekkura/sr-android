package jp.oist.abcvlib.comprehensivedemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class BoundingBoxView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = 0xFFFF0000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
    }
    private var rectF: RectF? = null

    private var previewWidth = 0
    private var previewHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0

    fun setRect(rectF: RectF) {
        this.rectF = rectF
        invalidate()
    }

    fun clearRect() {
        rectF = null
        invalidate()
    }

    fun setImageDimensions(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
    }

    fun setPreviewDimensions(width: Int, height: Int) {
        previewWidth = width
        previewHeight = height
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = rectF ?: return
        if (imageWidth <= 0 || imageHeight <= 0) {
            return
        }

        val displayImageWidth = imageHeight
        val displayImageHeight = imageWidth
        val correctedRect = mapDetectionRectToDisplay(rect, imageWidth, imageHeight)

        val scaleX = previewWidth.toFloat() / displayImageWidth
        val scaleY = previewHeight.toFloat() / displayImageHeight
        val scale = min(scaleX, scaleY)
        val offsetX = (previewWidth - displayImageWidth * scale) / 2
        val offsetY = (previewHeight - displayImageHeight * scale) / 2

        val transformedRect = RectF(
            correctedRect.left * scale + offsetX,
            correctedRect.top * scale + offsetY,
            correctedRect.right * scale + offsetX,
            correctedRect.bottom * scale + offsetY
        )
        canvas.drawRect(transformedRect, paint)
    }
}

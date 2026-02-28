package jp.oist.abcvlib.basiccharger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint: Paint = Paint().apply {
        color = 0xFFFF0000.toInt() // Red color
        style = Paint.Style.STROKE
        strokeWidth = 5.0f
    }
    private var rectF: RectF? = null
    private val matrix = Matrix()

    private var previewWidth = 0
    private var previewHeight = 0
    private var imageWidth = 0
    private var imageHeight = 0

    fun setRect(rectF: RectF) {
        this.rectF = rectF
        invalidate() // Request to redraw the view
    }

    fun setImageDimensions(width: Int, height: Int) {
        this.imageWidth = width
        this.imageHeight = height
    }

    fun setPreviewDimensions(width: Int, height: Int) {
        this.previewWidth = width
        this.previewHeight = height
        invalidate() // Request to redraw the view
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (rectF != null && imageWidth > 0 && imageHeight > 0) {
            // Calculate the scale and translation
//            float scaleX = (float) previewWidth / imageWidth;
            val scaleY = previewHeight.toFloat() / imageHeight
            val scaleX = scaleY

            val scale = min(scaleX, scaleY)

            val offsetX = (previewWidth - imageWidth * scale) / 2
            val offsetY = (previewHeight - imageHeight * scale) / 2

            //            // Testing coordinates
//            rectF = new RectF(bbleft, bbright, bbtop, bbbottom);
//            if (bbleft >= (this.imageWidth)){
//                direction = -direction;
//            }else if (bbleft <= 70){
//                direction = -direction;
//            }
//            bbleft += direction;
//             Testing coordinates
//            rectF = new RectF(bbleft, bbright, bbtop, bbbottom);
//            if (bbbottom >= (this.imageHeight)){
//                direction = -direction;
//            }else if (bbbottom <= 70){
//                direction = -direction;
//            }
//            bbbottom += direction;

            //            matrix.postScale(scale, scale);
//            matrix.postTranslate(offsetX, offsetY);
            matrix.reset()
            // Apply scaling
            matrix.postScale(scale, scale)
            // Apply horizontal flip around the center of the image
            matrix.postScale(-1f, 1f)
            // Translate back to the correct position after flipping
            matrix.postTranslate(previewWidth.toFloat(), 0f)
            // Apply translation to align the image in the PreviewView
            matrix.postTranslate(-offsetX, offsetY)

            val transformedRect = RectF(rectF)
            matrix.mapRect(transformedRect)

            canvas.drawRect(transformedRect, paint)
        }
    }
}

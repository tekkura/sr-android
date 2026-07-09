package jp.oist.abcvlib.basicassembler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class CircularGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(50, 61, 72)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(32, 166, 152)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(130, 142, 153)
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 240, 244)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(172, 184, 194)
        textAlign = Paint.Align.CENTER
    }

    private val arcBounds = RectF()
    private var label = ""
    private var unit = ""
    private var minValue = 0.0
    private var maxValue = 100.0
    private var value = 0.0

    fun configure(label: String, unit: String, minValue: Double, maxValue: Double) {
        require(maxValue > minValue) { "maxValue must be greater than minValue" }
        this.label = label
        this.unit = unit
        this.minValue = minValue
        this.maxValue = maxValue
        invalidate()
    }

    fun setValue(value: Double) {
        this.value = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val strokeWidth = size * 0.075f
        val radiusPadding = strokeWidth * 1.4f
        val centerX = width / 2f
        val centerY = height / 2f

        trackPaint.strokeWidth = strokeWidth
        valuePaint.strokeWidth = strokeWidth
        tickPaint.strokeWidth = strokeWidth * 0.18f
        textPaint.textSize = size * 0.16f
        labelPaint.textSize = size * 0.085f

        arcBounds.set(
            radiusPadding,
            radiusPadding,
            width - radiusPadding,
            height - radiusPadding
        )

        canvas.drawArc(arcBounds, ARC_START, ARC_SWEEP, false, trackPaint)
        drawTicks(canvas, centerX, centerY, size / 2f - radiusPadding)
        drawValueArc(canvas)

        val formattedValue = if (abs(value) >= 10.0) {
            "%.0f".format(value)
        } else {
            "%.1f".format(value)
        }
        canvas.drawText("$formattedValue $unit".trim(), centerX, centerY + size * 0.055f, textPaint)
        canvas.drawText(label, centerX, centerY + size * 0.21f, labelPaint)
    }

    private fun drawValueArc(canvas: Canvas) {
        val zeroAngle = valueToAngle(0.0.coerceIn(minValue, maxValue))
        val valueAngle = valueToAngle(value)
        if (minValue < 0.0 && maxValue > 0.0) {
            val sweep = valueAngle - zeroAngle
            canvas.drawArc(arcBounds, zeroAngle, sweep, false, valuePaint)
        } else {
            canvas.drawArc(arcBounds, ARC_START, valueAngle - ARC_START, false, valuePaint)
        }
    }

    private fun drawTicks(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        for (tick in 0..8) {
            val angle = Math.toRadians((ARC_START + ARC_SWEEP * tick / 8.0).toDouble())
            val inner = radius * 0.82f
            val outer = radius * 0.92f
            val startX = centerX + cos(angle).toFloat() * inner
            val startY = centerY + sin(angle).toFloat() * inner
            val endX = centerX + cos(angle).toFloat() * outer
            val endY = centerY + sin(angle).toFloat() * outer
            canvas.drawLine(startX, startY, endX, endY, tickPaint)
        }
    }

    private fun valueToAngle(value: Double): Float {
        val normalized = ((value - minValue) / (maxValue - minValue)).coerceIn(0.0, 1.0)
        return (ARC_START + ARC_SWEEP * normalized).toFloat()
    }

    companion object {
        private const val ARC_START = 150f
        private const val ARC_SWEEP = 240f
    }
}

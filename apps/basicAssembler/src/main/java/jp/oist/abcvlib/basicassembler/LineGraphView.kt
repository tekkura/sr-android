package jp.oist.abcvlib.basicassembler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class LineGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val samples = ArrayDeque<FloatArray>()
    private val seriesLabels = listOf("C", "D", "SI", "SB", "SE")
    private val seriesColors = intArrayOf(
        Color.rgb(238, 198, 67),
        Color.rgb(72, 169, 255),
        Color.rgb(42, 191, 132),
        Color.rgb(238, 108, 77),
        Color.rgb(174, 129, 255)
    )
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(44, 58, 71)
        strokeWidth = 1f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(172, 184, 194)
        textSize = 22f
    }

    fun addSample(
        count: Int,
        distance: Double,
        speedInstant: Double,
        speedBuffered: Double,
        speedExpAvg: Double
    ) {
        samples.addLast(
            floatArrayOf(
                count.toFloat(),
                distance.toFloat(),
                speedInstant.toFloat(),
                speedBuffered.toFloat(),
                speedExpAvg.toFloat()
            )
        )
        while (samples.size > MAX_SAMPLES) {
            samples.removeFirst()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat() + 8f
        val top = paddingTop.toFloat() + 8f
        val right = width - paddingRight.toFloat() - 8f
        val bottom = height - paddingBottom.toFloat() - 28f

        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        canvas.drawLine(left, top, left, bottom, gridPaint)
        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        drawLegend(canvas, left, height - paddingBottom.toFloat() - 6f)

        if (samples.size < 2) return

        for (seriesIndex in seriesLabels.indices) {
            val range = rangeFor(seriesIndex)
            if (range.first == range.second) continue

            val path = Path()
            samples.forEachIndexed { index, sample ->
                val x = left + (right - left) * index / (samples.size - 1).coerceAtLeast(1)
                val y = bottom - (bottom - top) *
                        ((sample[seriesIndex] - range.first) / (range.second - range.first))
                if (index == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            linePaint.color = seriesColors[seriesIndex]
            canvas.drawPath(path, linePaint)
        }
    }

    private fun drawLegend(canvas: Canvas, startX: Float, baseline: Float) {
        var x = startX
        seriesLabels.forEachIndexed { index, label ->
            textPaint.color = seriesColors[index]
            canvas.drawText(label, x, baseline, textPaint)
            x += textPaint.measureText(label) + 24f
        }
    }

    private fun rangeFor(seriesIndex: Int): Pair<Float, Float> {
        var minValue = Float.POSITIVE_INFINITY
        var maxValue = Float.NEGATIVE_INFINITY
        for (sample in samples) {
            minValue = min(minValue, sample[seriesIndex])
            maxValue = max(maxValue, sample[seriesIndex])
        }
        val padding = max((maxValue - minValue) * 0.1f, 0.001f)
        return Pair(minValue - padding, maxValue + padding)
    }

    companion object {
        private const val MAX_SAMPLES = 100
    }
}

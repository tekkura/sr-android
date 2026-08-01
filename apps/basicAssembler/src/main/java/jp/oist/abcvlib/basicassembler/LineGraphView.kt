package jp.oist.abcvlib.basicassembler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class LineGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    data class Sample(
        val count: Int,
        val distance: Double,
        val speedInstant: Double,
        val speedBuffered: Double,
        val speedExpAvg: Double
    )

    private val samples = ArrayDeque<FloatArray>()
    private val seriesLabels = listOf(
        "Count",
        "Distance",
        "Instant speed",
        "Buffered speed",
        "Exponential avg"
    )
    private val seriesColors = intArrayOf(
        Color.rgb(238, 198, 67),
        Color.rgb(72, 169, 255),
        Color.rgb(42, 191, 132),
        Color.rgb(238, 108, 77),
        Color.rgb(174, 129, 255)
    )
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(44, 58, 71)
        strokeWidth = 1f.dp()
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f.dp()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(172, 184, 194)
        textSize = 17f.sp()
    }

    fun addSample(
        count: Int,
        distance: Double,
        speedInstant: Double,
        speedBuffered: Double,
        speedExpAvg: Double
    ) {
        addSamples(
            listOf(
                Sample(
                    count,
                    distance,
                    speedInstant,
                    speedBuffered,
                    speedExpAvg
                )
            )
        )
    }

    fun addSamples(newSamples: List<Sample>) {
        if (newSamples.isEmpty()) {
            return
        }
        for (sample in newSamples) {
            samples.addLast(
                floatArrayOf(
                    sample.count.toFloat(),
                    sample.distance.toFloat(),
                    sample.speedInstant.toFloat(),
                    sample.speedBuffered.toFloat(),
                    sample.speedExpAvg.toFloat()
                )
            )
            while (samples.size > MAX_SAMPLES) {
                samples.removeFirst()
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 8f.dp()
        val rowGap = 6f.dp()
        val legendTopGap = 10f.dp()
        val left = paddingLeft.toFloat() + inset
        val top = paddingTop.toFloat() + inset
        val right = width - paddingRight.toFloat() - inset
        if (right <= left) {
            return
        }
        val legendRows = legendRowCount(left, right)
        val legendHeight = legendRows * textPaint.textSize + (legendRows - 1) * rowGap
        val legendTop = height - paddingBottom.toFloat() - inset - legendHeight
        val bottom = legendTop - legendTopGap
        if (bottom <= top) {
            return
        }

        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        canvas.drawLine(left, top, left, bottom, gridPaint)
        for (i in 1..3) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        drawLegend(canvas, left, legendTop, right, rowGap)

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

    private fun drawLegend(
        canvas: Canvas,
        startX: Float,
        startBaseline: Float,
        right: Float,
        rowGap: Float
    ) {
        var x = startX
        var baseline = startBaseline
        seriesLabels.forEachIndexed { index, label ->
            val labelWidth = textPaint.measureText(label)
            if (x > startX && x + labelWidth > right) {
                x = startX
                baseline += textPaint.textSize + rowGap
            }
            textPaint.color = seriesColors[index]
            canvas.drawText(label, x, baseline, textPaint)
            x += labelWidth + 18f.dp()
        }
    }

    private fun legendRowCount(startX: Float, right: Float): Int {
        var rows = 1
        var x = startX
        seriesLabels.forEach { label ->
            val labelWidth = textPaint.measureText(label)
            if (x > startX && x + labelWidth > right) {
                rows++
                x = startX
            }
            x += labelWidth + 18f.dp()
        }
        return rows
    }

    private fun rangeFor(seriesIndex: Int): Pair<Float, Float> {
        val maxAbsValue = samples.maxOf { abs(it[seriesIndex]) }
        val limit = max(maxAbsValue * 1.1f, 0.001f)
        return Pair(-limit, limit)
    }

    companion object {
        private const val MAX_SAMPLES = 100
    }

    private fun Float.dp(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, resources.displayMetrics)

    private fun Float.sp(): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, this, resources.displayMetrics)
}

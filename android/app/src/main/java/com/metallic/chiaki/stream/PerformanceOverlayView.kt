package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class PerformanceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val headerView: TextView
    private val sparklineView: SparklineView

    private val labelTotal = metricRow("Total")
    private val labelNet = metricRow("Net")
    private val labelVisual = metricRow("Visual")
    private val labelFPS = metricRow("FPS")
    private val labelBT = metricRow("BT")
    private val labelRes = metricRow("Res")
    private val labelRTT = metricRow("RTT")
    private val labelJit = metricRow("Jit")
    private val labelDT = metricRow("DT")
    private val labelVL = metricRow("VL")
    private val labelDrops = metricRow("Drops")

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.argb(170, 0, 0, 0))
        setPadding(5, 3, 5, 3)

        headerView = TextView(context).apply {
            setTextColor(Color.argb(230, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        addView(
            headerView,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val columns = LinearLayout(context).apply {
            orientation = HORIZONTAL
        }

        val latencyCol = buildColumn()
        latencyCol.addView(labelTotal)
        latencyCol.addView(labelNet)
        latencyCol.addView(labelVisual)

        val streamCol = buildColumn()
        sparklineView = SparklineView(context)
        streamCol.addView(labelFPS)
        streamCol.addView(
            sparklineView,
            LinearLayout.LayoutParams(dpToPx(48), dpToPx(14))
        )
        streamCol.addView(labelBT)
        streamCol.addView(labelRes)

        val qualityCol = buildColumn()
        qualityCol.addView(labelRTT)
        qualityCol.addView(labelJit)
        qualityCol.addView(labelDT)
        qualityCol.addView(labelVL)
        qualityCol.addView(labelDrops)

        columns.addView(
            latencyCol,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        columns.addView(
            streamCol,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        columns.addView(
            qualityCol,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        addView(
            columns,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        visibility = View.GONE
    }

    private fun buildColumn() = LinearLayout(context).apply {
        orientation = VERTICAL
    }

    private fun labelValue(label: TextView, text: String, ms: Double? = null) {
        val color = when {
            ms == null -> Color.argb(200, 255, 255, 255)
            ms < 30.0 -> Color.rgb(0, 220, 100)
            ms < 50.0 -> Color.rgb(255, 200, 40)
            else -> Color.rgb(255, 80, 80)
        }

        label.text = text
        label.setTextColor(color)
    }

    private fun metricRow(label: String) = TextView(context).apply {
        setTextColor(Color.argb(180, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f)
        setTypeface(Typeface.MONOSPACE)
        text = "$label —"
    }

    fun updateOverlay(data: OverlayData) {
        val m = data.metrics

        headerView.text = data.header

        val oneWay = m.ping / 2.0
        val totalLatency = oneWay + m.decodeTime

        labelValue(
            labelTotal,
            String.format(Locale.US, "Total %5.1f ms", totalLatency),
            totalLatency
        )
        labelValue(
            labelNet,
            String.format(Locale.US, "Net %5.1f ms →", oneWay),
            oneWay
        )
        labelValue(
            labelVisual,
            String.format(Locale.US, "Visual %5.1f ms", m.decodeTime),
            m.decodeTime
        )

        val fpsColor = when {
            m.fps >= 55f -> Color.rgb(0, 220, 100)
            m.fps >= 30f -> Color.rgb(255, 200, 40)
            else -> Color.rgb(255, 80, 80)
        }
        labelFPS.text = String.format(Locale.US, "FPS %5.1f", m.fps)
        labelFPS.setTextColor(fpsColor)

        labelBT.text = String.format(Locale.US, "BT %5.1f Mbps", m.bitrate)

        val resString = when {
            m.height >= 2160 -> "4K"
            m.height >= 1440 -> "1440p"
            m.height >= 1080 -> "1080p"
            m.height >= 720 -> "720p"
            m.height >= 540 -> "540p"
            else -> "${m.width}×${m.height}"
        }
        labelRes.text = String.format(Locale.US, "Res %6s", resString)

        val rttColor = when {
            m.ping < 30.0 -> Color.rgb(0, 220, 100)
            m.ping < 80.0 -> Color.rgb(255, 200, 40)
            else -> Color.rgb(255, 80, 80)
        }
        labelRTT.text = String.format(Locale.US, "RTT %5.1f ms", m.ping)
        labelRTT.setTextColor(rttColor)

        val jitColor = when {
            data.jitter < 15.0 -> Color.rgb(0, 220, 100)
            data.jitter < 30.0 -> Color.rgb(255, 200, 40)
            else -> Color.rgb(255, 80, 80)
        }
        labelJit.text = String.format(Locale.US, "Jit %5.1f ms", data.jitter)
        labelJit.setTextColor(jitColor)

        labelDT.text = String.format(Locale.US, "DT %5.1f ms", m.decodeTime)

        val lossPercent = m.packetLoss * 100.0
        val lossColor = when {
            lossPercent <= 0.01 -> Color.rgb(0, 220, 100)
            lossPercent <= 1.0 -> Color.rgb(255, 200, 40)
            else -> Color.rgb(255, 80, 80)
        }
        labelVL.text = String.format(Locale.US, "VL %5.1f%%", lossPercent)
        labelVL.setTextColor(lossColor)

        labelDrops.text = String.format(Locale.US, "Drops %5d", m.drops)

        sparklineView.setData(data.fpsHistory)
    }

    private class SparklineView(context: Context) : View(context) {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 200, 255)
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

        private var history = listOf<Float>()

        fun setData(data: List<Float>) {
            history = data
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (history.size < 2) return

            val w = width.toFloat()
            val h = height.toFloat()
            val drawH = h - 2f
            val minVal = 0f
            val maxVal = 65f
            val range = maxVal - minVal
            val step = w / (history.size - 1).coerceAtLeast(1)

            var px = 0f
            val py = 1f + drawH * (1f - ((history[0] - minVal) / range).coerceIn(0f, 1f))

            val path = android.graphics.Path()
            path.moveTo(px, py)

            for (i in 1 until history.size) {
                px += step
                val y = 1f + drawH * (1f - ((history[i] - minVal) / range).coerceIn(0f, 1f))
                path.lineTo(px, y)
            }

            canvas.drawPath(path, linePaint)
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
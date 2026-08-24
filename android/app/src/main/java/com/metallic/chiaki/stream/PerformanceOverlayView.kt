package com.metallic.chiaki.stream

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pylux.stream.R
import java.util.Locale

class PerformanceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** FULL is every metric (the original view); MINIMAL is just the numbers most people
     *  actually glance at mid-session — FPS, Bitrate, Resolution, Video Loss, Drops, Ping and
     *  Adaptive Frame Pacing status. */
    enum class OverlayMode { FULL, MINIMAL }

    private val headerView: TextView
    private val sparklineView: SparklineView
    private val latencyCol: LinearLayout

    private val labelTotal = metricRow("Total")
    private val labelNet = metricRow("Net")
    private val labelVisual = metricRow("Visual")
    private val labelFPS = metricRow("FPS")
    private val labelDFPS = metricRow("DFPS")
    private val labelBT = metricRow("BT")
    private val labelRes = metricRow("Res")
    private val labelRTT = metricRow("Ping")
    private val labelJit = metricRow("Jit")
    private val labelDT = metricRow("DT")
    private val labelVL = metricRow("VL")
    private val labelDrops = metricRow("Drops")
    private val labelAfp = metricRow("AFP")

    init {
        orientation = VERTICAL
        setPadding(7, 5, 7, 5)

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

        latencyCol = buildColumn()
        latencyCol.addView(labelTotal)
        latencyCol.addView(labelNet)
        latencyCol.addView(labelVisual)

        val streamCol = buildColumn()
        sparklineView = SparklineView(context)
        streamCol.addView(labelFPS)
        streamCol.addView(labelDFPS)
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
        qualityCol.addView(labelAfp)

        columns.addView(
            latencyCol,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        // marginStart on both (not just qualityCol) so the gap is consistent whether latencyCol
        // is showing or not — in Minimal mode latencyCol collapses to width 0, and without their
        // own margins streamCol/qualityCol would butt directly against each other with no gap at
        // all, cramping "Res"/"Ping" right up against "FPS"/"VL".
        columns.addView(
            streamCol,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(6) }
        )
        columns.addView(
            qualityCol,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(6) }
        )

        addView(
            columns,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setOpacityPercent(50)
        visibility = View.GONE
    }

    private fun resolveAccentColor(): Int
    {
        val tv = TypedValue()
        context.theme.resolveAttribute(R.attr.pyluxAccent, tv, true)
        return tv.data
    }

    /** 0 (fully transparent) – 100 (fully opaque), applied to the grey fill and the theme-accent
     *  border only — the metric text keeps its own fixed alpha regardless, so it stays legible
     *  even at low overlay opacity. */
    fun setOpacityPercent(percent: Int)
    {
        val alpha = (percent.coerceIn(0, 100) / 100f * 255).toInt()
        val accent = resolveAccentColor()
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(4).toFloat()
            setColor((alpha shl 24) or 0x2B2B2B)
            setStroke(dpToPx(1).coerceAtLeast(2), (alpha shl 24) or (accent and 0x00FFFFFF))
        }
    }

    fun setMode(mode: OverlayMode)
    {
        val minimal = mode == OverlayMode.MINIMAL
        latencyCol.visibility = if (minimal) View.GONE else View.VISIBLE
        labelDFPS.visibility = if (minimal) View.GONE else View.VISIBLE
        sparklineView.visibility = if (minimal) View.GONE else View.VISIBLE
        labelJit.visibility = if (minimal) View.GONE else View.VISIBLE
        labelDT.visibility = if (minimal) View.GONE else View.VISIBLE
        // On-device: after a mode switch (and especially after the freeze/hardware-layer window
        // move mode puts this view through — see StreamActivity.overlayMoveModeActive), this
        // view's own rendered width could get stuck narrower than its actual content, clipping
        // part of it away — a fresh explicit measure/layout/draw pass covers whichever of those
        // isn't otherwise triggering reliably by itself.
        requestLayout()
        invalidate()
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

        val oneWay = data.smoothedPing / 2.0
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
        // Labels within streamCol/qualityCol are padded to a fixed width (%-3s / %-5s below) so
        // the values that follow start at the same column regardless of label length — "FPS"/
        // "BT"/"Res" differ from 2-3 chars, "Ping"/"VL"/"Drops" from 2-5, so without padding the
        // numbers landed at different offsets row to row instead of forming a clean column, most
        // noticeable in Minimal mode where these six rows are the entire visible content. The
        // values themselves are left-justified too (%-5.1f, not %5.1f) — right-justifying them
        // kept the label-to-value gap consistent but left the first digit itself landing at a
        // different column per row depending on the number's own width (e.g. "60.0" vs " 5.0").
        labelFPS.text = String.format(Locale.US, "%-3s %-5.1f", "FPS", m.fps)
        labelFPS.setTextColor(fpsColor)

        val dfpsColor = when {
            m.decodedFps >= 55f -> Color.rgb(0, 220, 100)
            m.decodedFps >= 30f -> Color.rgb(255, 200, 40)
            else -> Color.rgb(255, 80, 80)
        }
        labelDFPS.text = String.format(Locale.US, "DFPS %5.1f", m.decodedFps)
        labelDFPS.setTextColor(dfpsColor)

        labelBT.text = String.format(Locale.US, "%-3s %-5.1f Mbps", "BT", m.bitrate)

        val resString = when {
            m.height >= 2160 -> "4K"
            m.height >= 1440 -> "1440p"
            m.height >= 1080 -> "1080p"
            m.height >= 720 -> "720p"
            m.height >= 540 -> "540p"
            else -> "${m.width}×${m.height}"
        }
        labelRes.text = String.format(Locale.US, "%-3s %-6s", "Res", resString)

        val rttColor = when {
            data.smoothedPing < 30.0 -> Color.rgb(0, 220, 100)
            data.smoothedPing < 80.0 -> Color.rgb(255, 200, 40)
            else -> Color.rgb(255, 80, 80)
        }
        labelRTT.text = String.format(Locale.US, "%-5s %-5.1f ms", "Ping", data.smoothedPing)
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
        labelVL.text = String.format(Locale.US, "%-5s %-5.1f%%", "VL", lossPercent)
        labelVL.setTextColor(lossColor)

        labelDrops.text = String.format(Locale.US, "%-5s %-5d", "Drops", m.drops)

        labelAfp.text = String.format(
            Locale.US, "%-5s %-5s", "AFP",
            if (data.adaptiveFramePacingEnabled) "Enabled" else "Disabled"
        )
        labelAfp.setTextColor(
            if (data.adaptiveFramePacingEnabled) Color.rgb(0, 220, 100) else Color.argb(180, 255, 255, 255)
        )

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
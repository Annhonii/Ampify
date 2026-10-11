package com.example.batteryrestrict

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Battery level over REAL time. Samples are placed by their timestamp, hour lines sit on the clock
 * hour, charging is drawn grey, stretches with no readings are dashed, and the strip at the bottom
 * marks when the screen was on.
 */
@Composable
fun BatteryGraph(
    samples: List<BatterySample>,
    windowHours: Int,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val fmt = remember { SimpleDateFormat("h a", Locale.US) }
    val startMs = nowMs - windowHours * DrainMath.HOUR_MS
    val pts = samples.filter { it.timeMs in startMs..nowMs }
    if (pts.size < 2) return

    // Y axis: whole 10% (or 20%) steps around the data.
    val maxP = pts.maxOf { it.percent }
    val minP = pts.minOf { it.percent }
    var top = (((maxP + 9) / 10) * 10).coerceAtMost(100)
    var bottom = (minP / 10) * 10
    if (top - bottom < 10) bottom = (top - 10).coerceAtLeast(0)
    if (top - bottom < 10) top = bottom + 10
    var gridStep = 10
    if (top - bottom > 50) {
        gridStep = 20
        bottom = (bottom / 20) * 20
        top = (((top + 19) / 20) * 20).coerceAtMost(100)
    }
    val yTicks = (bottom..top step gridStep).toList()
    val tickEvery = when (windowHours) { 6 -> 1; 12 -> 2; else -> 4 }

    Canvas(modifier.fillMaxWidth().height(230.dp)) {
        val yLabelW = 36.dp.toPx()
        val xLabelH = 18.dp.toPx()
        val stripH = 6.dp.toPx()
        val plotL = 0f
        val plotR = size.width - yLabelW
        val plotT = 6.dp.toPx()
        val plotB = size.height - xLabelH - stripH - 8.dp.toPx()
        val span = (nowMs - startMs).toFloat()

        fun xFor(t: Long) = plotL + (t - startMs).toFloat() / span * (plotR - plotL)
        fun yFor(pct: Float) = plotB - (pct - bottom) / (top - bottom).toFloat() * (plotB - plotT)

        val yPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.mute.toArgb(); textSize = 10.sp.toPx(); textAlign = Paint.Align.LEFT
        }
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.mute.toArgb(); textSize = 10.sp.toPx(); textAlign = Paint.Align.CENTER
        }
        val native = drawContext.canvas.nativeCanvas

        // Horizontal grid + % labels
        for (v in yTicks) {
            val y = yFor(v.toFloat())
            drawLine(
                p.stroke, Offset(plotL, y), Offset(plotR, y), strokeWidth = 2f,
                pathEffect = if (v == bottom) null else PathEffect.dashPathEffect(floatArrayOf(8f, 10f), 0f),
            )
            native.drawText("$v%", plotR + 6.dp.toPx(), y + 4.dp.toPx(), yPaint)
        }

        // Clock-hour lines + time labels
        var t = DrainMath.hourStart(startMs)
        while (t <= nowMs) {
            if (t >= startMs && DrainMath.localHour(t) % tickEvery == 0) {
                val x = xFor(t)
                drawLine(
                    p.stroke, Offset(x, plotT), Offset(x, plotB), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 12f), 0f),
                )
                native.drawText(fmt.format(Date(t)), x, size.height - 3.dp.toPx(), xPaint)
            }
            t += DrainMath.HOUR_MS
        }

        // Soft fill under the line
        val area = Path()
        area.moveTo(xFor(pts.first().timeMs), plotB)
        pts.forEach { area.lineTo(xFor(it.timeMs), yFor(it.percent.toFloat())) }
        area.lineTo(xFor(pts.last().timeMs), plotB)
        area.close()
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(p.accent.copy(alpha = 0.22f), p.accent.copy(alpha = 0.02f)),
                startY = plotT, endY = plotB,
            ),
        )

        // Line, segment by segment
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val gap = b.timeMs - a.timeMs > DrainMath.GAP_MS
            drawLine(
                color = if (a.charging || gap) p.mute else p.accent,
                start = Offset(xFor(a.timeMs), yFor(a.percent.toFloat())),
                end = Offset(xFor(b.timeMs), yFor(b.percent.toFloat())),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (gap) PathEffect.dashPathEffect(floatArrayOf(6f, 14f), 0f) else null,
            )
        }

        // "Now" marker
        val last = pts.last()
        val lx = xFor(last.timeMs)
        val ly = yFor(last.percent.toFloat())
        drawCircle(p.card, radius = 6.dp.toPx(), center = Offset(lx, ly))
        drawCircle(if (last.charging) p.mute else p.accent, radius = 4.dp.toPx(), center = Offset(lx, ly))

        // Screen-on strip
        val stripTop = plotB + 8.dp.toPx()
        drawRoundRect(
            p.field, Offset(plotL, stripTop), Size(plotR - plotL, stripH),
            CornerRadius(stripH / 2f),
        )
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (a.screenOn && b.timeMs - a.timeMs <= DrainMath.GAP_MS) {
                val x1 = xFor(a.timeMs)
                val x2 = xFor(b.timeMs)
                drawRect(p.accent.copy(alpha = 0.85f), Offset(x1, stripTop), Size((x2 - x1).coerceAtLeast(1f), stripH))
            }
        }
    }
}

/** Percent lost in each clock hour. The busiest hour is red. */
@Composable
fun HourlyDrain(bars: List<HourBar>, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val fmt = remember { SimpleDateFormat("h a", Locale.US) }
    val n = bars.size
    if (n == 0) return
    val maxDrop = bars.maxOf { it.drop }.coerceAtLeast(1.0)
    val peak = bars.indexOfFirst { it.drop == maxDrop && it.drop >= 0.5 }
    val every = when {
        n <= 6 -> 1
        n <= 12 -> 2
        else -> 4
    }

    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val labelH = 18.dp.toPx()
        val topPad = 16.dp.toPx()
        val plotB = size.height - labelH
        val plotT = topPad
        val slot = size.width / n
        val barW = minOf(slot * 0.62f, 18.dp.toPx())

        val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.text.toArgb(); textSize = 10.sp.toPx(); textAlign = Paint.Align.CENTER
        }
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.mute.toArgb(); textSize = 10.sp.toPx(); textAlign = Paint.Align.CENTER
        }
        val native = drawContext.canvas.nativeCanvas

        drawLine(p.stroke, Offset(0f, plotB), Offset(size.width, plotB), strokeWidth = 2f)

        bars.forEachIndexed { k, bar ->
            val cx = slot * k + slot / 2f
            if (bar.drop >= 0.05) {
                val h = ((bar.drop / maxDrop).toFloat() * (plotB - plotT)).coerceAtLeast(3.dp.toPx())
                drawRoundRect(
                    color = if (k == peak) p.accent else p.mute.copy(alpha = 0.6f),
                    topLeft = Offset(cx - barW / 2f, plotB - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f),
                )
                if (bar.drop >= 0.5) {
                    native.drawText("${bar.drop.roundToInt()}", cx, plotB - h - 4.dp.toPx(), valPaint)
                }
            } else {
                drawCircle(p.dotOff, radius = 2.dp.toPx(), center = Offset(cx, plotB - 4.dp.toPx()))
            }
            if (k % every == 0) {
                native.drawText(fmt.format(Date(bar.startMs)), cx, size.height - 3.dp.toPx(), xPaint)
            }
        }
    }
}

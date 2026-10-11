package com.example.batteryrestrict

import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

data class DrainSummary(
    val usedPercent: Double,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val screenOnDrop: Double,
    val screenOffDrop: Double
)

/** Percent lost in one clock hour. */
data class HourBar(val startMs: Long, val drop: Double)

/**
 * Drain maths on the time-stamped log. A drop between two readings belongs to the screen state at
 * the START of that interval, and is spread over the interval's time. So a long gap (say 80% to
 * 60% between 11 pm and 7 am) is shared across those hours instead of landing on one point.
 */
object DrainMath {
    const val HOUR_MS = 3_600_000L
    const val GAP_MS = 45L * 60_000

    /** Start of the local clock hour containing [t]. Works for half-hour zones like IST. */
    fun hourStart(t: Long): Long {
        val off = TimeZone.getDefault().getOffset(t)
        return ((t + off) / HOUR_MS) * HOUR_MS - off
    }

    fun localHour(t: Long): Int {
        val off = TimeZone.getDefault().getOffset(t)
        return (((t + off) / HOUR_MS) % 24).toInt()
    }

    fun summarize(samples: List<BatterySample>, fromMs: Long, toMs: Long): DrainSummary {
        var onMs = 0L
        var offMs = 0L
        var onDrop = 0.0
        var offDrop = 0.0
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            if (a.charging) continue
            val s = max(a.timeMs, fromMs)
            val e = min(b.timeMs, toMs)
            if (e <= s) continue
            val len = max(1L, b.timeMs - a.timeMs)
            val frac = (e - s).toDouble() / len
            val drop = max(0, a.percent - b.percent) * frac
            if (a.screenOn) {
                onMs += e - s
                onDrop += drop
            } else {
                offMs += e - s
                offDrop += drop
            }
        }
        return DrainSummary(onDrop + offDrop, onMs, offMs, onDrop, offDrop)
    }

    /** Percent lost in each of [hours] clock hours starting at [firstHourStartMs]. */
    fun hourly(samples: List<BatterySample>, firstHourStartMs: Long, hours: Int): List<HourBar> {
        val drops = DoubleArray(hours)
        for (i in 0 until samples.size - 1) {
            val a = samples[i]
            val b = samples[i + 1]
            if (a.charging) continue
            val d = max(0, a.percent - b.percent).toDouble()
            if (d <= 0.0) continue
            val len = max(1L, b.timeMs - a.timeMs).toDouble()
            for (k in 0 until hours) {
                val bs = firstHourStartMs + k * HOUR_MS
                val be = bs + HOUR_MS
                val ov = min(b.timeMs, be) - max(a.timeMs, bs)
                if (ov > 0) drops[k] += d * ov / len
            }
        }
        return List(hours) { HourBar(firstHourStartMs + it * HOUR_MS, drops[it]) }
    }
}

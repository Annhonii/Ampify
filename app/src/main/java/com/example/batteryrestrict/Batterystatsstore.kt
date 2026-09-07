package com.example.batteryrestrict

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class BatteryStatsSnapshot(
    val percent: Int,
    val activeDrainPerHr: Double,
    val idleDrainPerHr: Double,
    val screenOnMs: Long,
    val screenOffMs: Long,
    val deepSleepMs: Long,
    val awakeMs: Long,
    val totalElapsedMs: Long
)

/**
 * Simple persisted stats store. The service writes to it every ~10s; the UI polls it
 * while the Battery Monitor screen is visible. Also keeps a rolling history of
 * "percent dropped per hour" buckets (last 24h) for the bar graph.
 */
object BatteryStatsStore {

    private const val PREFS = "battery_stats_store"

    private const val KEY_PERCENT = "percent"
    private const val KEY_ACTIVE_DRAIN = "active_drain"
    private const val KEY_IDLE_DRAIN = "idle_drain"
    private const val KEY_SCREEN_ON_MS = "screen_on_ms"
    private const val KEY_SCREEN_OFF_MS = "screen_off_ms"
    private const val KEY_DEEP_SLEEP_MS = "deep_sleep_ms"
    private const val KEY_AWAKE_MS = "awake_ms"
    private const val KEY_TOTAL_ELAPSED_MS = "total_elapsed_ms"

    private const val KEY_CURRENT_HOUR_KEY = "current_hour_key"
    private const val KEY_CURRENT_HOUR_DROP = "current_hour_drop"
    private const val KEY_HISTORY = "history" // "HH:drop,HH:drop,..." oldest -> newest, max 24

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun writeStats(context: Context, snapshot: BatteryStatsSnapshot) {
        prefs(context).edit().apply {
            putInt(KEY_PERCENT, snapshot.percent)
            putFloat(KEY_ACTIVE_DRAIN, snapshot.activeDrainPerHr.toFloat())
            putFloat(KEY_IDLE_DRAIN, snapshot.idleDrainPerHr.toFloat())
            putLong(KEY_SCREEN_ON_MS, snapshot.screenOnMs)
            putLong(KEY_SCREEN_OFF_MS, snapshot.screenOffMs)
            putLong(KEY_DEEP_SLEEP_MS, snapshot.deepSleepMs)
            putLong(KEY_AWAKE_MS, snapshot.awakeMs)
            putLong(KEY_TOTAL_ELAPSED_MS, snapshot.totalElapsedMs)
            apply()
        }
    }

    fun readStats(context: Context): BatteryStatsSnapshot {
        val p = prefs(context)
        return BatteryStatsSnapshot(
            percent = p.getInt(KEY_PERCENT, 0),
            activeDrainPerHr = p.getFloat(KEY_ACTIVE_DRAIN, 0f).toDouble(),
            idleDrainPerHr = p.getFloat(KEY_IDLE_DRAIN, 0f).toDouble(),
            screenOnMs = p.getLong(KEY_SCREEN_ON_MS, 0L),
            screenOffMs = p.getLong(KEY_SCREEN_OFF_MS, 0L),
            deepSleepMs = p.getLong(KEY_DEEP_SLEEP_MS, 0L),
            awakeMs = p.getLong(KEY_AWAKE_MS, 0L),
            totalElapsedMs = p.getLong(KEY_TOTAL_ELAPSED_MS, 0L)
        )
    }

    /** Call whenever the percent drops (discharging) by [drop] percent, to feed the hourly graph. */
    fun recordPercentDrop(context: Context, drop: Double) {
        if (drop <= 0.0) return
        val p = prefs(context)
        val hourFormat = SimpleDateFormat("HH:00", Locale.US)
        val nowHourKey = hourFormat.format(Calendar.getInstance().time)

        val storedHourKey = p.getString(KEY_CURRENT_HOUR_KEY, null)
        var currentHourDrop = p.getFloat(KEY_CURRENT_HOUR_DROP, 0f).toDouble()

        if (storedHourKey != null && storedHourKey != nowHourKey) {
            // Hour rolled over: push the finished hour into history, start a new bucket.
            pushHistory(context, storedHourKey, currentHourDrop)
            currentHourDrop = 0.0
        }

        currentHourDrop += drop

        p.edit()
            .putString(KEY_CURRENT_HOUR_KEY, nowHourKey)
            .putFloat(KEY_CURRENT_HOUR_DROP, currentHourDrop.toFloat())
            .apply()
    }

    private fun pushHistory(context: Context, hourKey: String, drop: Double) {
        val p = prefs(context)
        val existing = p.getString(KEY_HISTORY, "") ?: ""
        val entries = if (existing.isBlank()) mutableListOf() else existing.split(",").toMutableList()
        entries.add("$hourKey:${"%.1f".format(drop)}")
        while (entries.size > 24) entries.removeAt(0)
        p.edit().putString(KEY_HISTORY, entries.joinToString(",")).apply()
    }

    /** Returns (hourLabel, percentDropped) oldest -> newest, including the in-progress current hour. */
    fun readHistory(context: Context): List<Pair<String, Float>> {
        val p = prefs(context)
        val existing = p.getString(KEY_HISTORY, "") ?: ""
        val entries = if (existing.isBlank()) emptyList() else existing.split(",")
        val history = entries.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size >= 2) {
                val label = parts[0]
                val value = parts[1].toFloatOrNull() ?: 0f
                label to value
            } else null
        }.toMutableList()

        val currentHourKey = p.getString(KEY_CURRENT_HOUR_KEY, null)
        val currentHourDrop = p.getFloat(KEY_CURRENT_HOUR_DROP, 0f)
        if (currentHourKey != null) {
            history.add(currentHourKey to currentHourDrop)
        }
        return history.takeLast(24)
    }
}

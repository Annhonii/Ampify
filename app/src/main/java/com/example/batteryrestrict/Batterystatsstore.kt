package com.example.batteryrestrict

import android.content.Context

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
 * while the Battery Monitor screen is visible.
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
}

package com.example.batteryrestrict

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class BatteryStatsSnapshot(
    val percent: Int,
    val activeDrainPerHr: Double,
    val idleDrainPerHr: Double,
    val deepSleepMs: Long,
    val awakeMs: Long,
    val totalElapsedMs: Long,
    val totalPercentDropped: Double,   // cumulative % drained since last charge
    val monitorScreenOnMs: Long,       // screen-on time in the current discharge session
    val monitorScreenOffMs: Long       // screen-off time in the current discharge session
)

data class SessionAccumulators(
    val screenOnAccumMs: Long,
    val screenOffAccumMs: Long,
    val activeDrop: Double,
    val idleDrop: Double,
    val lastKnownPercent: Int?,
    val sessionStartElapsedMs: Long?,
    val sessionStartUptimeMs: Long?,
    val lastChargingState: Boolean?
)

/**
 * Persisted battery stats. The service writes to it on every update; the UI (Home screen and
 * Battery Monitor screen) polls it.
 */
object BatteryStatsStore {

    private const val PREFS = "battery_stats_store"

    // Display snapshot (for quick UI reads)
    private const val KEY_PERCENT = "percent"
    private const val KEY_ACTIVE_DRAIN = "active_drain"
    private const val KEY_IDLE_DRAIN = "idle_drain"
    private const val KEY_DEEP_SLEEP_MS = "deep_sleep_ms"
    private const val KEY_AWAKE_MS = "awake_ms"
    private const val KEY_TOTAL_ELAPSED_MS = "total_elapsed_ms"
    private const val KEY_TOTAL_DROPPED = "total_dropped"
    private const val KEY_MONITOR_SCREEN_ON_MS = "monitor_screen_on_ms"
    private const val KEY_MONITOR_SCREEN_OFF_MS = "monitor_screen_off_ms"

    // Raw session accumulators (available if you want to persist "since last charge" across restarts)
    private const val KEY_ACC_SCREEN_ON = "acc_screen_on"
    private const val KEY_ACC_SCREEN_OFF = "acc_screen_off"
    private const val KEY_ACC_ACTIVE_DROP = "acc_active_drop"
    private const val KEY_ACC_IDLE_DROP = "acc_idle_drop"
    private const val KEY_ACC_LAST_PERCENT = "acc_last_percent"
    private const val KEY_ACC_SESSION_START_ELAPSED = "acc_session_start_elapsed"
    private const val KEY_ACC_SESSION_START_UPTIME = "acc_session_start_uptime"
    private const val KEY_ACC_LAST_CHARGING = "acc_last_charging"

    // Hourly "% remaining" bar history (rolling last 24 hours), like the reference battery graph.
    private const val KEY_HOURLY_HISTORY = "hourly_history" // "hourKey|label|pct,hourKey|label|pct,..."

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun writeStats(context: Context, snapshot: BatteryStatsSnapshot) {
        prefs(context).edit().apply {
            putInt(KEY_PERCENT, snapshot.percent)
            putFloat(KEY_ACTIVE_DRAIN, snapshot.activeDrainPerHr.toFloat())
            putFloat(KEY_IDLE_DRAIN, snapshot.idleDrainPerHr.toFloat())
            putLong(KEY_DEEP_SLEEP_MS, snapshot.deepSleepMs)
            putLong(KEY_AWAKE_MS, snapshot.awakeMs)
            putLong(KEY_TOTAL_ELAPSED_MS, snapshot.totalElapsedMs)
            putFloat(KEY_TOTAL_DROPPED, snapshot.totalPercentDropped.toFloat())
            putLong(KEY_MONITOR_SCREEN_ON_MS, snapshot.monitorScreenOnMs)
            putLong(KEY_MONITOR_SCREEN_OFF_MS, snapshot.monitorScreenOffMs)
            apply()
        }
    }

    fun readStats(context: Context): BatteryStatsSnapshot {
        val p = prefs(context)
        return BatteryStatsSnapshot(
            percent = p.getInt(KEY_PERCENT, 0),
            activeDrainPerHr = p.getFloat(KEY_ACTIVE_DRAIN, 0f).toDouble(),
            idleDrainPerHr = p.getFloat(KEY_IDLE_DRAIN, 0f).toDouble(),
            deepSleepMs = p.getLong(KEY_DEEP_SLEEP_MS, 0L),
            awakeMs = p.getLong(KEY_AWAKE_MS, 0L),
            totalElapsedMs = p.getLong(KEY_TOTAL_ELAPSED_MS, 0L),
            totalPercentDropped = p.getFloat(KEY_TOTAL_DROPPED, 0f).toDouble(),
            monitorScreenOnMs = p.getLong(KEY_MONITOR_SCREEN_ON_MS, 0L),
            monitorScreenOffMs = p.getLong(KEY_MONITOR_SCREEN_OFF_MS, 0L)
        )
    }

    fun loadSessionAccumulators(context: Context): SessionAccumulators {
        val p = prefs(context)
        return SessionAccumulators(
            screenOnAccumMs = p.getLong(KEY_ACC_SCREEN_ON, 0L),
            screenOffAccumMs = p.getLong(KEY_ACC_SCREEN_OFF, 0L),
            activeDrop = p.getFloat(KEY_ACC_ACTIVE_DROP, 0f).toDouble(),
            idleDrop = p.getFloat(KEY_ACC_IDLE_DROP, 0f).toDouble(),
            lastKnownPercent = if (p.contains(KEY_ACC_LAST_PERCENT)) p.getInt(KEY_ACC_LAST_PERCENT, -1) else null,
            sessionStartElapsedMs = if (p.contains(KEY_ACC_SESSION_START_ELAPSED)) p.getLong(KEY_ACC_SESSION_START_ELAPSED, 0L) else null,
            sessionStartUptimeMs = if (p.contains(KEY_ACC_SESSION_START_UPTIME)) p.getLong(KEY_ACC_SESSION_START_UPTIME, 0L) else null,
            lastChargingState = if (p.contains(KEY_ACC_LAST_CHARGING)) p.getBoolean(KEY_ACC_LAST_CHARGING, false) else null
        )
    }

    fun saveSessionAccumulators(context: Context, acc: SessionAccumulators) {
        prefs(context).edit().apply {
            putLong(KEY_ACC_SCREEN_ON, acc.screenOnAccumMs)
            putLong(KEY_ACC_SCREEN_OFF, acc.screenOffAccumMs)
            putFloat(KEY_ACC_ACTIVE_DROP, acc.activeDrop.toFloat())
            putFloat(KEY_ACC_IDLE_DROP, acc.idleDrop.toFloat())
            acc.lastKnownPercent?.let { putInt(KEY_ACC_LAST_PERCENT, it) }
            acc.sessionStartElapsedMs?.let { putLong(KEY_ACC_SESSION_START_ELAPSED, it) }
            acc.sessionStartUptimeMs?.let { putLong(KEY_ACC_SESSION_START_UPTIME, it) }
            acc.lastChargingState?.let { putBoolean(KEY_ACC_LAST_CHARGING, it) }
            apply()
        }
    }

    /**
     * Starts a completely new discharge session.
     *
     * This is called when charging begins, not when the cable is removed.
     * Clearing the display snapshot and hourly history here is what makes the
     * Home graph and the notification reset at the same instant.
     */
    fun resetSession(context: Context, nowElapsedMs: Long, nowUptimeMs: Long, currentPercent: Int) {
        prefs(context).edit().apply {
            // Display snapshot
            putInt(KEY_PERCENT, currentPercent)
            putFloat(KEY_ACTIVE_DRAIN, 0f)
            putFloat(KEY_IDLE_DRAIN, 0f)
            putLong(KEY_DEEP_SLEEP_MS, 0L)
            putLong(KEY_AWAKE_MS, 0L)
            putLong(KEY_TOTAL_ELAPSED_MS, 0L)
            putFloat(KEY_TOTAL_DROPPED, 0f)
            putLong(KEY_MONITOR_SCREEN_ON_MS, 0L)
            putLong(KEY_MONITOR_SCREEN_OFF_MS, 0L)

            // Raw accumulators
            putLong(KEY_ACC_SCREEN_ON, 0L)
            putLong(KEY_ACC_SCREEN_OFF, 0L)
            putFloat(KEY_ACC_ACTIVE_DROP, 0f)
            putFloat(KEY_ACC_IDLE_DROP, 0f)
            putInt(KEY_ACC_LAST_PERCENT, currentPercent)
            putLong(KEY_ACC_SESSION_START_ELAPSED, nowElapsedMs)
            putLong(KEY_ACC_SESSION_START_UPTIME, nowUptimeMs)
            putBoolean(KEY_ACC_LAST_CHARGING, true)

            // New charge cycle must not inherit old graph points.
            remove(KEY_HOURLY_HISTORY)
            apply()
        }
    }

    /**
     * Records/updates the current hour's "% remaining" bar. The in-progress hour's bar is
     * overwritten on every call (so it updates live); a new bar starts once the clock hour
     * changes. Keeps a rolling window of the last 24 bars.
     */
    fun recordHourlySample(context: Context, percent: Int) {
        val cal = Calendar.getInstance()
        val hourKey = SimpleDateFormat("yyyyMMddHH", Locale.US).format(cal.time)
        val displayLabel = SimpleDateFormat("h a", Locale.US).format(cal.time).lowercase(Locale.US)

        val p = prefs(context)
        val existing = p.getString(KEY_HOURLY_HISTORY, "") ?: ""
        val entries = if (existing.isBlank()) mutableListOf() else existing.split(",").toMutableList()

        if (entries.isNotEmpty() && entries.last().startsWith("$hourKey|")) {
            entries[entries.size - 1] = "$hourKey|$displayLabel|$percent"
        } else {
            entries.add("$hourKey|$displayLabel|$percent")
        }
        while (entries.size > 24) entries.removeAt(0)

        p.edit().putString(KEY_HOURLY_HISTORY, entries.joinToString(",")).apply()
    }

    /**
     * Clears just the persisted hourly graph history, without touching the current-session
     * accumulators/snapshot. Used when the user manually turns the monitor off, so the Home
     * graph goes back to "not enough data yet" instead of showing stale points.
     */
    fun clearHourlyHistory(context: Context) {
        prefs(context).edit().remove(KEY_HOURLY_HISTORY).apply()
    }

    /** Returns (displayLabel, percent) oldest -> newest, e.g. ("3 pm", 62). */
    fun readHourlyHistory(context: Context): List<Pair<String, Int>> {
        val existing = prefs(context).getString(KEY_HOURLY_HISTORY, "") ?: ""
        if (existing.isBlank()) return emptyList()
        return existing.split(",").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size >= 3) {
                val label = parts[1]
                val pct = parts[2].toIntOrNull()
                if (pct != null) label to pct else null
            } else null
        }
    }
}

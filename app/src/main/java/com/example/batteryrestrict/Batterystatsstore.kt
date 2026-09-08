package com.example.batteryrestrict

import android.content.Context

data class BatteryStatsSnapshot(
    val percent: Int,
    val activeDrainPerHr: Double,
    val idleDrainPerHr: Double,
    val screenOnMs: Long,             // since last charge (last unplug)
    val screenOffMs: Long,            // since last charge
    val deepSleepMs: Long,
    val awakeMs: Long,
    val totalElapsedMs: Long,
    val totalPercentDropped: Double   // cumulative % drained since last charge
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
 * Persisted battery stats. The service writes to it every ~10s; the UI (Home screen and
 * Battery Monitor screen) polls it. Raw session accumulators are also persisted so that
 * stopping/starting the monitor service doesn't lose the "since last charge" session —
 * only an actual unplug event (see resetSession) starts a new one.
 */
object BatteryStatsStore {

    private const val PREFS = "battery_stats_store"

    // Display snapshot (for quick UI reads)
    private const val KEY_PERCENT = "percent"
    private const val KEY_ACTIVE_DRAIN = "active_drain"
    private const val KEY_IDLE_DRAIN = "idle_drain"
    private const val KEY_SCREEN_ON_MS = "screen_on_ms"
    private const val KEY_SCREEN_OFF_MS = "screen_off_ms"
    private const val KEY_DEEP_SLEEP_MS = "deep_sleep_ms"
    private const val KEY_AWAKE_MS = "awake_ms"
    private const val KEY_TOTAL_ELAPSED_MS = "total_elapsed_ms"
    private const val KEY_TOTAL_DROPPED = "total_dropped"

    // Raw session accumulators (persisted so a service restart doesn't lose "since last charge")
    private const val KEY_ACC_SCREEN_ON = "acc_screen_on"
    private const val KEY_ACC_SCREEN_OFF = "acc_screen_off"
    private const val KEY_ACC_ACTIVE_DROP = "acc_active_drop"
    private const val KEY_ACC_IDLE_DROP = "acc_idle_drop"
    private const val KEY_ACC_LAST_PERCENT = "acc_last_percent"
    private const val KEY_ACC_SESSION_START_ELAPSED = "acc_session_start_elapsed"
    private const val KEY_ACC_SESSION_START_UPTIME = "acc_session_start_uptime"
    private const val KEY_ACC_LAST_CHARGING = "acc_last_charging"

    // Drain-vs-time history for the home screen graph, since last charge
    private const val KEY_HISTORY = "history_points" // "min:pct,min:pct,..."
    private const val KEY_HISTORY_LAST_SAVED_AT = "history_last_saved_at"

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
            putFloat(KEY_TOTAL_DROPPED, snapshot.totalPercentDropped.toFloat())
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
            totalElapsedMs = p.getLong(KEY_TOTAL_ELAPSED_MS, 0L),
            totalPercentDropped = p.getFloat(KEY_TOTAL_DROPPED, 0f).toDouble()
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

    /** Wipes the "since last charge" session — call this exactly when an unplug is detected. */
    fun resetSession(context: Context, nowElapsedMs: Long, nowUptimeMs: Long, currentPercent: Int) {
        prefs(context).edit().apply {
            putLong(KEY_ACC_SCREEN_ON, 0L)
            putLong(KEY_ACC_SCREEN_OFF, 0L)
            putFloat(KEY_ACC_ACTIVE_DROP, 0f)
            putFloat(KEY_ACC_IDLE_DROP, 0f)
            putInt(KEY_ACC_LAST_PERCENT, currentPercent)
            putLong(KEY_ACC_SESSION_START_ELAPSED, nowElapsedMs)
            putLong(KEY_ACC_SESSION_START_UPTIME, nowUptimeMs)
            putString(KEY_HISTORY, "")
            putLong(KEY_HISTORY_LAST_SAVED_AT, 0L)
            apply()
        }
    }

    /** Records a (minutesSinceSessionStart, percent) point for the drain-vs-time graph, throttled to ~1/min. */
    fun maybeAppendHistoryPoint(context: Context, nowElapsedMs: Long, sessionStartElapsedMs: Long, percent: Int) {
        val p = prefs(context)
        val lastSavedAt = p.getLong(KEY_HISTORY_LAST_SAVED_AT, 0L)
        if (lastSavedAt != 0L && nowElapsedMs - lastSavedAt < 60_000L) return

        val minutes = ((nowElapsedMs - sessionStartElapsedMs) / 60_000L).toInt()
        val existing = p.getString(KEY_HISTORY, "") ?: ""
        val entries = if (existing.isBlank()) mutableListOf() else existing.split(",").toMutableList()
        entries.add("$minutes:$percent")
        while (entries.size > 60) entries.removeAt(0)

        p.edit()
            .putString(KEY_HISTORY, entries.joinToString(","))
            .putLong(KEY_HISTORY_LAST_SAVED_AT, nowElapsedMs)
            .apply()
    }

    /** Returns (minutesSinceSessionStart, percent) oldest -> newest. */
    fun readHistory(context: Context): List<Pair<Int, Int>> {
        val existing = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (existing.isBlank()) return emptyList()
        return existing.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size >= 2) {
                val min = parts[0].toIntOrNull()
                val pct = parts[1].toIntOrNull()
                if (min != null && pct != null) min to pct else null
            } else null
        }
    }
}

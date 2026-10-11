package com.example.batteryrestrict

import android.content.Context

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

/** One reading of the battery: when, how full, plugged in or not, screen on or off. */
data class BatterySample(
    val timeMs: Long,
    val percent: Int,
    val charging: Boolean,
    val screenOn: Boolean
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

    // Time-stamped battery log, "timeMs,percent,flags;..." (flags: 1 = charging, 2 = screen on).
    // The graph is drawn from this log, so it stays correct even when nothing was recorded for a while.
    private const val KEY_SAMPLES = "samples_v2"
    private const val SAMPLE_KEEP_MS = 3L * 24 * 3_600_000
    private const val SAMPLE_REPEAT_MS = 5L * 60_000
    private val sampleLock = Any()

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

            apply()
        }
    }

    /**
     * Appends a reading to the log. Skips it only if nothing changed (same level, same plug and
     * screen state) within the last few minutes. Keeps the last 3 days.
     */
    fun recordSample(
        context: Context,
        percent: Int,
        charging: Boolean,
        screenOn: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(sampleLock) {
            val p = prefs(context)
            val list = parseSamples(p.getString(KEY_SAMPLES, "") ?: "").toMutableList()
            val last = list.lastOrNull()
            if (last != null) {
                if (nowMs < last.timeMs) return // clock moved back; ignore
                if (last.percent == percent && last.charging == charging &&
                    last.screenOn == screenOn && nowMs - last.timeMs < SAMPLE_REPEAT_MS
                ) return
            }
            list.add(BatterySample(nowMs, percent, charging, screenOn))
            val cutoff = nowMs - SAMPLE_KEEP_MS
            val kept = list.filter { it.timeMs >= cutoff }
            p.edit().putString(KEY_SAMPLES, encodeSamples(kept)).apply()
        }
    }

    /** Readings at or after [sinceMs], oldest first. */
    fun readSamples(context: Context, sinceMs: Long): List<BatterySample> {
        val raw = prefs(context).getString(KEY_SAMPLES, "") ?: ""
        return parseSamples(raw).filter { it.timeMs >= sinceMs }
    }

    fun clearSamples(context: Context) {
        prefs(context).edit().remove(KEY_SAMPLES).apply()
    }

    private fun encodeSamples(list: List<BatterySample>): String =
        list.joinToString(";") {
            val flags = (if (it.charging) 1 else 0) or (if (it.screenOn) 2 else 0)
            "${it.timeMs},${it.percent},$flags"
        }

    private fun parseSamples(raw: String): List<BatterySample> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size < 3) return@mapNotNull null
            val t = parts[0].toLongOrNull() ?: return@mapNotNull null
            val pct = parts[1].toIntOrNull() ?: return@mapNotNull null
            val f = parts[2].toIntOrNull() ?: return@mapNotNull null
            BatterySample(t, pct, (f and 1) != 0, (f and 2) != 0)
        }.sortedBy { it.timeMs }
    }
}

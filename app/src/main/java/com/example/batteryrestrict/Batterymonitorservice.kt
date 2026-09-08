package com.example.batteryrestrict

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max

/**
 * Foreground service that keeps a persistent notification with live battery stats, scoped
 * to the current "since last charge" session:
 *   - percent, temperature, instantaneous current
 *   - active/idle drain rate (%/hr)
 *   - screen-on / screen-off / deep-sleep / awake breakdown
 *
 * The session resets only when a real unplug is detected (charging -> discharging), and the
 * raw accumulators are persisted so toggling the service off/on mid-session does NOT lose data
 * — only an actual unplug starts a fresh session. Every 10s (REFRESH_INTERVAL_MS) stats are
 * re-sampled directly from the battery (not just reacting to broadcasts) so both the notification
 * and the drain-rate math stay accurate and current.
 *
 * NOTE: AndroidManifest.xml must declare:
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
 *   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *   <service android:name=".BatteryMonitorService"
 *            android:foregroundServiceType="specialUse"
 *            android:exported="false" />
 */
class BatteryMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "battery_monitor_channel"
        private const val NOTIFICATION_ID = 4201
        private const val REFRESH_INTERVAL_MS = 10_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
    }

    private data class BatteryExtras(
        val percent: Int,
        val tempC: Double,
        val isCharging: Boolean,
        val currentMa: Int
    )

    // "Since last charge" session state
    private var sessionStartElapsedMs = 0L
    private var sessionStartUptimeMs = 0L
    private var screenOnAccumMs = 0L
    private var screenOffAccumMs = 0L
    private var lastScreenSwitchElapsedMs = 0L
    private var screenIsOn = true
    private var activePercentDropAccum = 0.0
    private var idlePercentDropAccum = 0.0
    private var lastKnownPercent: Int? = null
    private var lastPublishedChargingState: Boolean? = null

    private lateinit var batteryManager: BatteryManager
    private lateinit var notificationManager: NotificationManager
    private val refreshHandler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            publishStats()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                publishStats()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> switchScreenState(true)
                Intent.ACTION_SCREEN_OFF -> switchScreenState(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(sticky = "Starting…"))

        val extras = fetchBatteryExtras()
        val now = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val persisted = BatteryStatsStore.loadSessionAccumulators(this)
        val justUnplugged = persisted.lastChargingState == true && !extras.isCharging
        val noPriorSession = persisted.sessionStartElapsedMs == null || persisted.lastChargingState == null

        if (noPriorSession || justUnplugged) {
            // First ever run, or we just detected an unplug: start a fresh "since last charge" session.
            BatteryStatsStore.resetSession(this, now, nowUptime, extras.percent)
            screenOnAccumMs = 0L
            screenOffAccumMs = 0L
            activePercentDropAccum = 0.0
            idlePercentDropAccum = 0.0
            lastKnownPercent = extras.percent
            sessionStartElapsedMs = now
            sessionStartUptimeMs = nowUptime
        } else {
            // Resume the ongoing discharge session across a service restart.
            screenOnAccumMs = persisted.screenOnAccumMs
            screenOffAccumMs = persisted.screenOffAccumMs
            activePercentDropAccum = persisted.activeDrop
            idlePercentDropAccum = persisted.idleDrop
            lastKnownPercent = persisted.lastKnownPercent ?: extras.percent
            sessionStartElapsedMs = persisted.sessionStartElapsedMs ?: now
            sessionStartUptimeMs = persisted.sessionStartUptimeMs ?: nowUptime
        }
        lastPublishedChargingState = extras.isCharging
        lastScreenSwitchElapsedMs = now
        screenIsOn = true // best-effort; corrected as soon as a real SCREEN_ON/OFF broadcast fires

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)

        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        refreshHandler.removeCallbacks(refreshRunnable)
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun switchScreenState(turningOn: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastScreenSwitchElapsedMs
        if (screenIsOn) screenOnAccumMs += delta else screenOffAccumMs += delta
        lastScreenSwitchElapsedMs = now
        screenIsOn = turningOn
    }

    /** Reads the battery state directly from the system, not from a cached broadcast extra. */
    private fun fetchBatteryExtras(): BatteryExtras {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val currentMa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000
        return BatteryExtras(percent, tempTenths / 10.0, isCharging, currentMa)
    }

    /**
     * Re-samples the battery fresh every tick, detects unplug events to reset the "since last
     * charge" session, updates the active/idle drop accumulators, and refreshes the notification
     * + persisted store (+ drain-vs-time history for the home screen graph).
     */
    private fun publishStats() {
        val extras = fetchBatteryExtras()
        val now = SystemClock.elapsedRealtime()

        val wasCharging = lastPublishedChargingState
        if (wasCharging == true && !extras.isCharging) {
            // Just unplugged -> start a brand new "since last charge" session.
            val nowUptime = SystemClock.uptimeMillis()
            BatteryStatsStore.resetSession(this, now, nowUptime, extras.percent)
            screenOnAccumMs = 0L
            screenOffAccumMs = 0L
            activePercentDropAccum = 0.0
            idlePercentDropAccum = 0.0
            lastKnownPercent = extras.percent
            sessionStartElapsedMs = now
            sessionStartUptimeMs = nowUptime
            lastScreenSwitchElapsedMs = now
        } else {
            val previous = lastKnownPercent
            if (previous != null && !extras.isCharging && extras.percent < previous) {
                val drop = (previous - extras.percent).toDouble()
                if (screenIsOn) activePercentDropAccum += drop else idlePercentDropAccum += drop
            }
            lastKnownPercent = extras.percent
        }
        lastPublishedChargingState = extras.isCharging

        val liveScreenOnMs = screenOnAccumMs + if (screenIsOn) (now - lastScreenSwitchElapsedMs) else 0
        val liveScreenOffMs = screenOffAccumMs + if (!screenIsOn) (now - lastScreenSwitchElapsedMs) else 0

        val totalElapsedMs = max(1L, now - sessionStartElapsedMs)
        val totalUptimeMs = max(0L, SystemClock.uptimeMillis() - sessionStartUptimeMs)
        val deepSleepMs = (totalElapsedMs - totalUptimeMs).coerceAtLeast(0L)
        val awakeMs = totalUptimeMs

        // e.g. 1% dropped over 20 active minutes -> 1 / (20/60) = 3%/hr
        val activeHours = liveScreenOnMs / 3_600_000.0
        val idleHours = liveScreenOffMs / 3_600_000.0
        val activeDrainPerHr = if (activeHours > 0) activePercentDropAccum / activeHours else 0.0
        val idleDrainPerHr = if (idleHours > 0) idlePercentDropAccum / idleHours else 0.0
        val totalDropped = activePercentDropAccum + idlePercentDropAccum

        BatteryStatsStore.saveSessionAccumulators(
            this,
            SessionAccumulators(
                screenOnAccumMs = liveScreenOnMs,
                screenOffAccumMs = liveScreenOffMs,
                activeDrop = activePercentDropAccum,
                idleDrop = idlePercentDropAccum,
                lastKnownPercent = lastKnownPercent,
                sessionStartElapsedMs = sessionStartElapsedMs,
                sessionStartUptimeMs = sessionStartUptimeMs,
                lastChargingState = extras.isCharging
            )
        )

        if (!extras.isCharging) {
            BatteryStatsStore.maybeAppendHistoryPoint(this, now, sessionStartElapsedMs, extras.percent)
        }

        BatteryStatsStore.writeStats(
            this,
            BatteryStatsSnapshot(
                percent = extras.percent,
                activeDrainPerHr = activeDrainPerHr,
                idleDrainPerHr = idleDrainPerHr,
                screenOnMs = liveScreenOnMs,
                screenOffMs = liveScreenOffMs,
                deepSleepMs = deepSleepMs,
                awakeMs = awakeMs,
                totalElapsedMs = totalElapsedMs,
                totalPercentDropped = totalDropped
            )
        )

        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(
                sticky = null,
                percent = extras.percent,
                tempC = extras.tempC,
                isCharging = extras.isCharging,
                currentMa = extras.currentMa,
                liveScreenOnMs = liveScreenOnMs,
                liveScreenOffMs = liveScreenOffMs,
                deepSleepMs = deepSleepMs,
                awakeMs = awakeMs,
                totalElapsedMs = totalElapsedMs,
                activeDrainPerHr = activeDrainPerHr,
                idleDrainPerHr = idleDrainPerHr,
                totalPercentDropped = totalDropped
            )
        )
    }

    private fun buildNotification(
        sticky: String?,
        percent: Int = 0,
        tempC: Double = 0.0,
        isCharging: Boolean = false,
        currentMa: Int = 0,
        liveScreenOnMs: Long = 0,
        liveScreenOffMs: Long = 0,
        deepSleepMs: Long = 0,
        awakeMs: Long = 0,
        totalElapsedMs: Long = 1,
        activeDrainPerHr: Double = 0.0,
        idleDrainPerHr: Double = 0.0,
        totalPercentDropped: Double = 0.0
    ): Notification {
        fun pct(part: Long) = if (totalElapsedMs > 0) part * 100.0 / totalElapsedMs else 0.0

        val chargeLine = if (isCharging) "Charging ${abs(currentMa)} mA" else "Discharging ${abs(currentMa)} mA"

        val contentText = sticky ?: "$percent% · ${"%.0f".format(tempC)}°C · $chargeLine"

        val bigText = sticky ?: buildString {
            appendLine("$percent% · ${"%.0f".format(tempC)}°C · $chargeLine")
            appendLine()
            appendLine("Active drain: ${"%.1f".format(activeDrainPerHr)}%/hr · idle drain: ${"%.1f".format(idleDrainPerHr)}%/hr")
            // Bracket here shows total % drained since the monitor was turned on / last charge,
            // not a time-percentage.
            appendLine("Screen on: ${formatDurationShort(liveScreenOnMs)} (${"%.1f".format(totalPercentDropped)}%)")
            appendLine("Screen off: ${formatDurationShort(liveScreenOffMs)} (${"%.1f".format(totalPercentDropped)}%)")
            appendLine("Deep sleep: ${formatDurationShort(deepSleepMs)} (${"%.1f".format(pct(deepSleepMs))}%)")
            append("Awake: ${formatDurationShort(awakeMs)} (${"%.1f".format(pct(awakeMs))}%)")
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: replace with a custom app icon
            .setContentTitle("Battery Monitor")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        pendingIntent?.let { builder.setContentIntent(it) }

        return builder.build()
    }

    /** e.g. 61_000ms -> "1m 1s" (never rolls past 59 without carrying into the next unit). */
    private fun formatDurationShort(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "${h}h ${m}m ${s}s"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live active/idle drain and battery stats"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}

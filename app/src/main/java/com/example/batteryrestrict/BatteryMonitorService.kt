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
import kotlin.math.roundToInt

class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_monitor_channel"
        const val NOTIFICATION_ID = 1001
        private const val REFRESH_INTERVAL_MS = 10_000L

        var isRunning = false
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
            val intent = Intent(context, BatteryMonitorService::class.java)
            context.stopService(intent)
        }
    }

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private val batteryManager by lazy {
        getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    // "Since last charge" session (resets on unplug)
    private var screenIsOn = true
    private var lastScreenSwitchElapsedMs = 0L
    private var screenOnAccumMs = 0L
    private var screenOffAccumMs = 0L
    private var activePercentDropAccum = 0.0
    private var idlePercentDropAccum = 0.0
    private var lastKnownPercent = -1
    private var sessionStartElapsedMs = 0L
    private var sessionStartUptimeMs = 0L
    private var lastPublishedChargingState = false
    private var currentChargeSpeedMa = 0
    private var currentTemperatureC = 0.0

    // "Since monitor service started" (only resets when the service itself (re)starts)
    private var monitorScreenOnAccumMs = 0L
    private var monitorScreenOffAccumMs = 0L
    private var monitorActiveDropAccum = 0.0   // % drained while screen ON since service start
    private var monitorIdleDropAccum = 0.0     // % drained while screen OFF since service start
    private var monitorLastKnownPercent = -1

    // Live values used when building the notification
    private var liveScreenOnMs = 0L
    private var liveScreenOffMs = 0L

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatsAndNotification()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private data class BatteryExtras(
        val percent: Int,
        val isCharging: Boolean,
        val currentNowMa: Int,
        val temperatureC: Double
    )

    private fun readBatteryExtras(): BatteryExtras {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = registerReceiver(null, filter)
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10.0

        val microAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return BatteryExtras(pct, charging, microAmps / 1000, tempC)
    }

    private fun handleScreenChanged(isOn: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val delta = max(0L, now - lastScreenSwitchElapsedMs)
        if (screenIsOn) {
            screenOnAccumMs += delta
            monitorScreenOnAccumMs += delta
        } else {
            screenOffAccumMs += delta
            monitorScreenOffAccumMs += delta
        }
        lastScreenSwitchElapsedMs = now
        screenIsOn = isOn
        updateStatsAndNotification()
    }

    private fun updateStatsAndNotification() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val b = readBatteryExtras()
        currentChargeSpeedMa = b.currentNowMa
        currentTemperatureC = b.temperatureC

        // Reset the "since last charge" session on a real UNPLUG (charging -> not charging),
        // not when charging starts.
        if (b.isCharging != lastPublishedChargingState && b.isCharging) {
            // Plugged in -> reset screen on/off timers and their drain counters.
            monitorScreenOnAccumMs = 0L
            monitorScreenOffAccumMs = 0L
            monitorActiveDropAccum = 0.0
            monitorIdleDropAccum = 0.0
            monitorLastKnownPercent = b.percent
            lastScreenSwitchElapsedMs = nowElapsed
        }

        if (b.isCharging != lastPublishedChargingState && !b.isCharging) {
            BatteryStatsStore.resetSession(this, nowElapsed, nowUptime, b.percent)
            screenOnAccumMs = 0L
            screenOffAccumMs = 0L
            activePercentDropAccum = 0.0
            idlePercentDropAccum = 0.0
            lastKnownPercent = b.percent
            sessionStartElapsedMs = nowElapsed
            sessionStartUptimeMs = nowUptime
            lastScreenSwitchElapsedMs = nowElapsed
        } else if (lastKnownPercent != -1 && !b.isCharging && b.percent < lastKnownPercent) {
            val drop = (lastKnownPercent - b.percent).toDouble()
            if (screenIsOn) {
                activePercentDropAccum += drop
            } else {
                idlePercentDropAccum += drop
            }
            lastKnownPercent = b.percent
        } else if (lastKnownPercent == -1) {
            lastKnownPercent = b.percent
        }
        lastPublishedChargingState = b.isCharging

        // Drain split since the monitor service was enabled (never reset by unplug)
        if (monitorLastKnownPercent == -1) {
            monitorLastKnownPercent = b.percent
        } else if (!b.isCharging && b.percent < monitorLastKnownPercent) {
            val drop = (monitorLastKnownPercent - b.percent).toDouble()
            if (screenIsOn) monitorActiveDropAccum += drop else monitorIdleDropAccum += drop
            monitorLastKnownPercent = b.percent
        } else if (b.percent > monitorLastKnownPercent) {
            monitorLastKnownPercent = b.percent
        }

        val currentScreenOn = screenOnAccumMs + (if (screenIsOn) max(0L, nowElapsed - lastScreenSwitchElapsedMs) else 0L)
        val currentScreenOff = screenOffAccumMs + (if (!screenIsOn) max(0L, nowElapsed - lastScreenSwitchElapsedMs) else 0L)
        val liveMonitorScreenOn = monitorScreenOnAccumMs + (if (screenIsOn) max(0L, nowElapsed - lastScreenSwitchElapsedMs) else 0L)
        val liveMonitorScreenOff = monitorScreenOffAccumMs + (if (!screenIsOn) max(0L, nowElapsed - lastScreenSwitchElapsedMs) else 0L)

        liveScreenOnMs = liveMonitorScreenOn
        liveScreenOffMs = liveMonitorScreenOff

        val totalSessionMs = max(1000L, nowElapsed - sessionStartElapsedMs)
        val awakeMs = max(0L, nowUptime - sessionStartUptimeMs)
        val deepSleepMs = max(0L, totalSessionMs - awakeMs)

        // Rates are extrapolated from what happened since the last plug-in:
        // e.g. 1% lost in 20 min of screen on -> 3%/hr.
        val activeHours = liveMonitorScreenOn / 3_600_000.0
        val idleHours = liveMonitorScreenOff / 3_600_000.0

        val activeDrainRate = if (activeHours > 0.0028 && monitorActiveDropAccum > 0)
            (monitorActiveDropAccum / activeHours) else 0.0
        val idleDrainRate = if (idleHours > 0.0028 && monitorIdleDropAccum > 0)
            (monitorIdleDropAccum / idleHours) else 0.0

        val snapshot = BatteryStatsSnapshot(
            percent = b.percent,
            activeDrainPerHr = activeDrainRate,
            idleDrainPerHr = idleDrainRate,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            totalElapsedMs = totalSessionMs,
            totalPercentDropped = activePercentDropAccum + idlePercentDropAccum,
            monitorScreenOnMs = liveMonitorScreenOn,
            monitorScreenOffMs = liveMonitorScreenOff
        )

        BatteryStatsStore.writeStats(this, snapshot)
        BatteryStatsStore.recordHourlySample(this, b.percent)

        val notif = buildNotification(snapshot)
        notificationManager.notify(NOTIFICATION_ID, notif)
    }

    /** 3m 40s / 1h 12m style formatting. */
    private fun formatDuration(ms: Long): String {
        val totalSec = max(0L, ms) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    private fun formatPercent(value: Double): String = "%.1f".format(value)

    /** No decimals, e.g. 3 instead of 3.0 */
    private fun formatWhole(value: Double): String = value.roundToInt().toString()

    private fun buildNotification(stats: BatteryStatsSnapshot): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val powerState = if (lastPublishedChargingState) {
            "Charging ${abs(currentChargeSpeedMa)} mA"
        } else {
            "Discharging ${abs(currentChargeSpeedMa)} mA"
        }

        val headline = "${stats.percent}% · ${currentTemperatureC.roundToInt()}°C · $powerState"

        val deepSleepPct = if (stats.totalElapsedMs > 0)
            stats.deepSleepMs * 100.0 / stats.totalElapsedMs else 0.0
        val awakePct = if (stats.totalElapsedMs > 0)
            stats.awakeMs * 100.0 / stats.totalElapsedMs else 0.0

        val lines = listOf(
            headline,
            "Active drain: ${formatPercent(stats.activeDrainPerHr)}%/hr · idle drain: ${formatPercent(stats.idleDrainPerHr)}%/hr",
            "Screen on: ${formatDuration(liveScreenOnMs)} (${formatWhole(monitorActiveDropAccum)}%)",
            "Screen off: ${formatDuration(liveScreenOffMs)} (${formatWhole(monitorIdleDropAccum)}%)",
            "Deep sleep: ${formatDuration(stats.deepSleepMs)} (${formatWhole(deepSleepPct)}%)",
            "Awake: ${formatDuration(stats.awakeMs)} (${formatWhole(awakePct)}%)"
        )

        val body = lines.joinToString("\n")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Monitor")
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors battery status and drain rates"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenChanged(true)
                Intent.ACTION_SCREEN_OFF -> handleScreenChanged(false)
                Intent.ACTION_BATTERY_CHANGED -> updateStatsAndNotification()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        val now = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        lastScreenSwitchElapsedMs = now
        sessionStartElapsedMs = now
        sessionStartUptimeMs = nowUptime
        monitorScreenOnAccumMs = 0L
        monitorScreenOffAccumMs = 0L
        monitorActiveDropAccum = 0.0
        monitorIdleDropAccum = 0.0

        val b = readBatteryExtras()
        lastKnownPercent = b.percent
        monitorLastKnownPercent = b.percent
        lastPublishedChargingState = b.isCharging
        currentChargeSpeedMa = b.currentNowMa
        currentTemperatureC = b.temperatureC

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(screenReceiver, filter)

        val initialStats = BatteryStatsSnapshot(
            percent = b.percent,
            activeDrainPerHr = 0.0,
            idleDrainPerHr = 0.0,
            deepSleepMs = 0L,
            awakeMs = 0L,
            totalElapsedMs = 0L,
            totalPercentDropped = 0.0,
            monitorScreenOnMs = 0L,
            monitorScreenOffMs = 0L
        )
        startForeground(NOTIFICATION_ID, buildNotification(initialStats))

        // Keep the notification + graph + drain-rate math fresh every 10s even with no events.
        refreshHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        refreshHandler.removeCallbacks(refreshRunnable)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

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
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max

class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_monitor_channel"
        const val NOTIFICATION_ID = 1001
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

    private var screenIsOn = true
    private var lastScreenSwitchElapsedMs = 0L
    private var screenOnAccumMs = 0L
    private var screenOffAccumMs = 0L

    private var activePercentDropAccum = 0
    private var idlePercentDropAccum = 0
    private var lastKnownPercent = -1
    private var sessionStartElapsedMs = 0L
    private var sessionStartUptimeMs = 0L
    private var lastPublishedChargingState = false
    private var currentChargeSpeedMa = 0

    private data class BatteryExtras(
        val percent: Int,
        val isCharging: Boolean,
        val currentNowMa: Int
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

        val microAmps = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return BatteryExtras(pct, charging, microAmps / 1000)
    }

    private fun handleScreenChanged(isOn: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val delta = max(0L, now - lastScreenSwitchElapsedMs)
        if (screenIsOn) {
            screenOnAccumMs += delta
        } else {
            screenOffAccumMs += delta
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

        if (b.isCharging != lastPublishedChargingState && b.isCharging) {
            BatteryStatsStore.resetSession(this, nowElapsed, nowUptime, b.percent)
            screenOnAccumMs = 0L
            screenOffAccumMs = 0L
            activePercentDropAccum = 0
            idlePercentDropAccum = 0
            lastKnownPercent = b.percent
            sessionStartElapsedMs = nowElapsed
            sessionStartUptimeMs = nowUptime
            lastScreenSwitchElapsedMs = nowElapsed
        } else if (lastKnownPercent != -1 && b.percent < lastKnownPercent) {
            val drop = lastKnownPercent - b.percent
            if (screenIsOn) {
                activePercentDropAccum += drop
            } else {
                idlePercentDropAccum += drop
            }
            lastKnownPercent = b.percent
        }
        lastPublishedChargingState = b.isCharging

        val currentScreenOn = screenOnAccumMs + (if (screenIsOn) max(0L, nowElapsed - lastScreenSwitchElapsedMs) else 0L)
        val currentScreenOff = screenOffAccumMs + (if (!screenIsOn) max(0L, nowElapsed - lastScreenSwitchElapsedMs) else 0L)

        val totalSessionMs = max(1000L, nowElapsed - sessionStartElapsedMs)
        val awakeMs = max(0L, SystemClock.uptimeMillis() - sessionStartUptimeMs)
        val deepSleepMs = max(0L, totalSessionMs - awakeMs)

        val activeHours = currentScreenOn / 3_600_000.0
        val idleHours = currentScreenOff / 3_600_000.0

        val activeDrainRate = if (activeHours > 0.05) (activePercentDropAccum / activeHours) else 0.0
        val idleDrainRate = if (idleHours > 0.05) (idlePercentDropAccum / idleHours) else 0.0

        val snapshot = BatteryStatsSnapshot(
            percent = b.percent,
            activeDrainPerHr = activeDrainRate,
            idleDrainPerHr = idleDrainRate,
            deepSleepMs = deepSleepMs,
            awakeMs = awakeMs,
            totalElapsedMs = totalSessionMs,
            totalPercentDropped = activePercentDropAccum + idlePercentDropAccum
        )

        BatteryStatsStore.writeStats(this, snapshot)
        BatteryStatsStore.addHistoryPoint(this, b.percent)

        val notif = buildNotification(snapshot)
        notificationManager.notify(NOTIFICATION_ID, notif)
    }

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

        val title = if (lastPublishedChargingState) {
            "Charging: ${stats.percent}% (${abs(currentChargeSpeedMa)} mA)"
        } else {
            "Battery: ${stats.percent}% | Active: ${"%.1f".format(stats.activeDrainPerHr)}%/h | Idle: ${"%.1f".format(stats.idleDrainPerHr)}%/h"
        }

        val content = "Deep sleep: ${stats.deepSleepMs / 60000}m | Awake: ${stats.awakeMs / 60000}m"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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

        val b = readBatteryExtras()
        lastKnownPercent = b.percent
        lastPublishedChargingState = b.isCharging
        currentChargeSpeedMa = b.currentNowMa

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
            totalPercentDropped = 0
        )
        startForeground(NOTIFICATION_ID, buildNotification(initialStats))
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

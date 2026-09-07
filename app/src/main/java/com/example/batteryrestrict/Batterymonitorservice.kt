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
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.max

/**
 * Foreground service that keeps a persistent notification with live battery stats:
 * percent, temperature, instantaneous current, and active/idle drain rates plus a
 * breakdown of screen-on / screen-off / deep-sleep / awake time for the current session.
 *
 * NOTE: you must add this to AndroidManifest.xml:
 *
 * <service
 *     android:name=".BatteryMonitorService"
 *     android:foregroundServiceType="specialUse"
 *     android:exported="false" />
 *
 * and request these permissions:
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
 *   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 */
class BatteryMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "battery_monitor_channel"
        private const val NOTIFICATION_ID = 4201

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

    // Session baselines
    private var sessionStartElapsedMs = 0L
    private var sessionStartUptimeMs = 0L

    // Accumulated screen-on / screen-off time (elapsed real time, ms)
    private var screenOnAccumMs = 0L
    private var screenOffAccumMs = 0L
    private var lastScreenSwitchElapsedMs = 0L
    private var screenIsOn = true

    // Percent drop attributed to each state, used to compute %/hr drain
    private var activePercentDropAccum = 0.0
    private var idlePercentDropAccum = 0.0
    private var lastKnownPercent: Int? = null

    private lateinit var batteryManager: BatteryManager
    private lateinit var notificationManager: NotificationManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                onBatteryChanged(intent)
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

        val now = SystemClock.elapsedRealtime()
        sessionStartElapsedMs = now
        sessionStartUptimeMs = SystemClock.uptimeMillis()
        lastScreenSwitchElapsedMs = now
        screenIsOn = true

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(sticky = "Starting…"))

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
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

    private fun onBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempTenths / 10.0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val currentMa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000

        val previous = lastKnownPercent
        if (previous != null && !isCharging && percent < previous) {
            val drop = (previous - percent).toDouble()
            if (screenIsOn) activePercentDropAccum += drop else idlePercentDropAccum += drop
        }
        lastKnownPercent = percent

        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(
                sticky = null,
                percent = percent,
                tempC = tempC,
                isCharging = isCharging,
                currentMa = currentMa
            )
        )
    }

    private fun buildNotification(
        sticky: String?,
        percent: Int = 0,
        tempC: Double = 0.0,
        isCharging: Boolean = false,
        currentMa: Int = 0
    ): Notification {
        val now = SystemClock.elapsedRealtime()

        // Bring accumulators up to date with time spent in the current screen state.
        val liveScreenOnMs = screenOnAccumMs + if (screenIsOn) (now - lastScreenSwitchElapsedMs) else 0
        val liveScreenOffMs = screenOffAccumMs + if (!screenIsOn) (now - lastScreenSwitchElapsedMs) else 0

        val totalElapsedMs = max(1L, now - sessionStartElapsedMs)
        val totalUptimeMs = max(0L, SystemClock.uptimeMillis() - sessionStartUptimeMs)
        val deepSleepMs = (totalElapsedMs - totalUptimeMs).coerceAtLeast(0L)
        val awakeMs = totalUptimeMs

        fun pct(part: Long) = if (totalElapsedMs > 0) part * 100.0 / totalElapsedMs else 0.0
        fun secs(ms: Long) = ms / 1000

        val activeHours = liveScreenOnMs / 3_600_000.0
        val idleHours = liveScreenOffMs / 3_600_000.0
        val activeDrainPerHr = if (activeHours > 0) activePercentDropAccum / activeHours else 0.0
        val idleDrainPerHr = if (idleHours > 0) idlePercentDropAccum / idleHours else 0.0

        val chargeLine = if (isCharging) "Charging ${abs(currentMa)} mA" else "Discharging ${abs(currentMa)} mA"

        val contentText = sticky ?: buildString {
            append("$percent% · ${"%.0f".format(tempC)}°C · $chargeLine")
        }

        val bigText = sticky ?: buildString {
            appendLine("$percent% · ${"%.0f".format(tempC)}°C · $chargeLine")
            appendLine()
            appendLine("Active drain: ${"%.0f".format(activeDrainPerHr)}%/hr · idle drain: ${"%.0f".format(idleDrainPerHr)}%/hr")
            appendLine("Screen on: ${secs(liveScreenOnMs)}s (${"%.0f".format(pct(liveScreenOnMs))}%)")
            appendLine("Screen off: ${secs(liveScreenOffMs)}s (${"%.0f".format(pct(liveScreenOffMs))}%)")
            appendLine("Deep sleep: ${secs(deepSleepMs)}s (${"%.1f".format(pct(deepSleepMs))}%)")
            append("Awake: ${secs(awakeMs)}s (${"%.1f".format(pct(awakeMs))}%)")
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

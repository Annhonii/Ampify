package com.example.batterymonitor

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
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class BatteryMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_monitor_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_START_SERVICE = "START_BATTERY_MONITOR"
        const val ACTION_STOP_SERVICE = "STOP_BATTERY_MONITOR"
    }

    private var isServiceRunning = false

    // Battery info
    private var lastLevel = -1
    private var lastTemp = 0.0
    private var lastCurrentMa = 0

    // Screen state
    private var isScreenOn = false

    // Accumulators
    private var screenOnTimeMs: Long = 0L
    private var screenOffTimeMs: Long = 0L

    private var screenOnDrain: Int = 0          // % drained while screen was on
    private var screenOffDrain: Int = 0         // % drained while screen was off

    // Session tracking
    private var screenOnSessionStartMs: Long = 0L
    private var screenOnSessionStartLevel: Int = -1

    private var screenOffSessionStartMs: Long = 0L
    private var screenOffSessionStartLevel: Int = -1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                updateBatteryInfo(intent)
                updateNotification()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_POWER_CONNECTED -> resetCounters()
            }
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> stopSelf()
            else -> startMonitor()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitor() {
        if (isServiceRunning) return
        isServiceRunning = true

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Register receivers
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        registerReceiver(screenReceiver, screenFilter)

        // Initial state
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            updateBatteryInfo(batteryIntent)
        }
        resetCounters()
        handleScreenOn() // assume screen is on when service starts; will correct via receiver if off
    }

    private fun resetCounters() {
        screenOnTimeMs = 0L
        screenOffTimeMs = 0L
        screenOnDrain = 0
        screenOffDrain = 0

        screenOnSessionStartMs = 0L
        screenOnSessionStartLevel = -1

        screenOffSessionStartMs = 0L
        screenOffSessionStartLevel = -1

        if (isScreenOn) {
            screenOnSessionStartMs = System.currentTimeMillis()
            screenOnSessionStartLevel = lastLevel
        } else {
            screenOffSessionStartMs = System.currentTimeMillis()
            screenOffSessionStartLevel = lastLevel
        }
    }

    private fun updateBatteryInfo(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else lastLevel

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        lastTemp = tempTenths / 10.0

        lastCurrentMa = getCurrentNowMa()

        if (lastLevel == -1) {
            lastLevel = pct
            return
        }

        // Track drain for the current session
        if (isScreenOn && screenOnSessionStartLevel >= 0) {
            val dropped = screenOnSessionStartLevel - pct
            if (dropped > 0) screenOnDrain += dropped
        } else if (!isScreenOn && screenOffSessionStartLevel >= 0) {
            val dropped = screenOffSessionStartLevel - pct
            if (dropped > 0) screenOffDrain += dropped
        }

        lastLevel = pct
    }

    private fun handleScreenOn() {
        if (isScreenOn) return
        isScreenOn = true

        // close screen-off session
        if (screenOffSessionStartMs > 0L) {
            val elapsed = System.currentTimeMillis() - screenOffSessionStartMs
            screenOffTimeMs += elapsed
        }

        // start screen-on session
        screenOnSessionStartMs = System.currentTimeMillis()
        screenOnSessionStartLevel = lastLevel
    }

    private fun handleScreenOff() {
        if (!isScreenOn) return
        isScreenOn = false

        // close screen-on session
        if (screenOnSessionStartMs > 0L) {
            val elapsed = System.currentTimeMillis() - screenOnSessionStartMs
            screenOnTimeMs += elapsed
        }

        // start screen-off session
        screenOffSessionStartMs = System.currentTimeMillis()
        screenOffSessionStartLevel = lastLevel
    }

    private fun getCurrentNowMa(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val microAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return microAmps / 1000
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(): Notification {
        // Use application launcher intent so tapping notification opens the app
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Live values
        val current = lastCurrentMa
        val sign = if (current < 0) "" else "+"
        val currentText = "$sign${current}mA"

        val tempText = "%.1f°C".format(lastTemp)
        val levelText = "$lastLevel%"

        // Active / idle drain rates
        val activeDrain = drainRatePerHour(
            durationMs = currentSessionDuration(isScreenOn, screenOnSessionStartMs),
            startLevel = screenOnSessionStartLevel,
            currentLevel = lastLevel
        )
        val idleDrain = drainRatePerHour(
            durationMs = currentSessionDuration(!isScreenOn, screenOffSessionStartMs),
            startLevel = screenOffSessionStartLevel,
            currentLevel = lastLevel
        )

        val activeDrainText = "%.1f%%/hr".format(activeDrain)
        val idleDrainText = "%.1f%%/hr".format(idleDrain)

        // Total durations including current live session
        val totalScreenOn = screenOnTimeMs + if (isScreenOn) currentSessionDuration(true, screenOnSessionStartMs) else 0L
        val totalScreenOff = screenOffTimeMs + if (!isScreenOn) currentSessionDuration(false, screenOffSessionStartMs) else 0L

        val screenOnTimeText = formatDuration(totalScreenOn)
        val screenOffTimeText = formatDuration(totalScreenOff)

        // Collapsed: one compact line
        val collapsedText = "$levelText · $tempText · $currentText · drain $activeDrainText"

        // Expanded: two tight lines
        val expandedText = buildString {
            appendLine("Screen on: $screenOnTimeText (${screenOnDrain}%) · Screen off: $screenOffTimeText (${screenOffDrain}%)")
            append("Active: $activeDrainText · Idle: $idleDrainText · Sleep: $screenOffTimeText")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // apni battery icon drawable se replace karna
            .setContentTitle("Battery Monitor")
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent)
            .build()
    }

    private fun currentSessionDuration(sessionActive: Boolean, startMs: Long): Long {
        return if (sessionActive && startMs > 0L) System.currentTimeMillis() - startMs else 0L
    }

    private fun drainRatePerHour(durationMs: Long, startLevel: Int, currentLevel: Int): Double {
        if (durationMs < 60_000 || startLevel < 0 || currentLevel < 0) return 0.0
        val minutes = durationMs / 60_000.0
        val drained = startLevel - currentLevel
        return if (drained > 0) (drained * 60.0) / minutes else 0.0
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live battery drain stats"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        isServiceRunning = false
    }
}

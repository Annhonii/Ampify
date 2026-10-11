package com.example.batteryrestrict

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock

/**
 * Records battery readings independently of the UI. A repeating alarm (allowed during Doze)
 * wakes the app about every 10 minutes while the monitor is on, so drain that happens with the
 * screen off is logged at the right time instead of showing up only when you open the app.
 */
object BatterySampler {

    private const val HEARTBEAT_MS = 10L * 60_000
    private const val REQUEST_CODE = 7001

    /** Reads the battery now and appends it to the log. */
    fun sample(context: Context) {
        val app = context.applicationContext
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val screenOn = (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        BatteryStatsStore.recordSample(app, percent, charging, screenOn)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            Intent(context.applicationContext, SampleReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun scheduleHeartbeat(context: Context) {
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + HEARTBEAT_MS,
            pendingIntent(context)
        )
    }

    fun cancelHeartbeat(context: Context) {
        val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context))
    }
}

class SampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        BatteryMonitorService.refreshNow() // also advances the notification if the service is alive
        BatterySampler.sample(app)
        BatterySampler.scheduleHeartbeat(app)
    }
}

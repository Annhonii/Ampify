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

    private fun publishStats() {
        val extras = fetchBatteryExtras()
        val now = SystemClock.elapsedRealtime()

        val wasCharging = lastPublishedChargingState
        if (wasCharging == true && !extras.isCharging) {
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

        // Updated without screenOnMs and screenOffMs to match BatteryStatsSnapshot definition
        BatteryStatsStore.writeStats(
            this,
            BatteryStatsSnapshot(
                percent = extras.percent,
                activeDrainPerHr = activeDrainPerHr,
                idleDrainPerHr = idleDrainPerHr,
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
                activePercentDropped = activePercentDropAccum,
                idlePercentDropped = idlePercentDropAccum
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
        activePercentDropped: Double = 0.0,
        idlePercentDropped: Double = 0.0
    ): Notification {
        fun pct(part: Long) = if (totalElapsedMs > 0) part * 100.0 / totalElapsedMs else 0.0

        val chargeLine = if (isCharging) "Charging ${abs(currentMa)} mA" else "Discharging ${abs(currentMa)} mA"

        val contentText = sticky ?: "$percent% · ${"%.0f".format(tempC)}°C · $chargeLine"

        val bigText = sticky ?: buildString {
            appendLine("$percent% · ${"%.0f".format(tempC)}°C · $chargeLine")
            appendLine()
            appendLine("Active drain: ${"%.1f".format(activeDrainPerHr)}%/hr · idle drain: ${"%.1f".format(idleDrainPerHr)}%/hr")
            appendLine("Screen on: ${formatDurationShort(liveScreenOnMs)} (${activePercentDropped.toInt()}%)")
            appendLine("Screen off: ${formatDurationShort(liveScreenOffMs)} (${idlePercentDropped.toInt()}%)")
            appendLine("Deep sleep: ${formatDurationShort(deepSleepMs)} (${pct(deepSleepMs).toInt()}%)")
            append("Awake: ${formatDurationShort(awakeMs)} (${pct(awakeMs).toInt()}%)")
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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

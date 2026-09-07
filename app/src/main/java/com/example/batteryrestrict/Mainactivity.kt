package com.example.batteryrestrict

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    companion object {
        init {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val darkTheme = isSystemInDarkTheme()
            val context = LocalContext.current
            val colorScheme = when {
                dynamicColorSupported && darkTheme -> dynamicDarkColorScheme(context)
                dynamicColorSupported && !darkTheme -> dynamicLightColorScheme(context)
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

// ---------- Card design tokens — hardcoded to match the reference screenshot exactly,
// independent of system/dynamic theme so the look never varies by device. ----------
private val CardShape = RoundedCornerShape(32.dp)
private val CardBackground = Color(0xFF232B29)
private val CardTitleColor = Color(0xFFEFF3F1)
private val CardSubtitleColor = Color(0xFF9CA6A2)

private enum class Screen { Home, ChargeSpeed, BatteryMonitor }

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) {
        screen = Screen.Home
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState == Screen.Home) {
                slideInHorizontally(animationSpec = tween(300)) { fullWidth -> -fullWidth } togetherWith
                    slideOutHorizontally(animationSpec = tween(300)) { fullWidth -> fullWidth }
            } else {
                slideInHorizontally(animationSpec = tween(300)) { fullWidth -> fullWidth } togetherWith
                    slideOutHorizontally(animationSpec = tween(300)) { fullWidth -> -fullWidth }
            }
        },
        label = "screenTransition"
    ) { targetScreen ->
        when (targetScreen) {
            Screen.Home -> HomeScreen(
                onOpenChargeSpeed = { screen = Screen.ChargeSpeed },
                onOpenBatteryMonitor = { screen = Screen.BatteryMonitor }
            )
            Screen.ChargeSpeed -> ChargeSpeedScreen(onBack = { screen = Screen.Home })
            Screen.BatteryMonitor -> BatteryMonitorScreen(onBack = { screen = Screen.Home })
        }
    }
}

// ---------- Live battery state (polled every 1s, so charging speed updates continuously
// instead of only refreshing when you navigate in/out of a screen) ----------

private data class BatteryState(
    val percent: Int,
    val isCharging: Boolean,
    val currentNowMa: Int
)

@Composable
private fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BatteryState(0, false, 0)) }

    LaunchedEffect(Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

        while (true) {
            // Sticky-intent lookup with a null receiver reads the current value instantly,
            // no listener/registration needed — safe to call every second.
            val intent = context.registerReceiver(null, filter)
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val microAmps = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                state = BatteryState(pct, charging, microAmps / 1000)
            }
            delay(1_000)
        }
    }

    return state
}

// ---------- Home screen (Settings-style) ----------

@Composable
fun HomeScreen(
    onOpenChargeSpeed: () -> Unit,
    onOpenBatteryMonitor: () -> Unit
) {
    val battery = rememberBatteryState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(12.dp))

        Text(
            "Ampify",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${battery.percent}",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "%",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp, start = 2.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        BatteryLevelBar(percent = battery.percent)

        Spacer(Modifier.height(10.dp))

        Text(
            if (battery.isCharging) "Charging" else "Not charging",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (battery.isCharging) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Charging speed: ${kotlin.math.abs(battery.currentNowMa)} mA",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
        }

        Spacer(Modifier.height(28.dp))

        SettingsCard(
            title = "Charge Control",
            subtitle = null,
            onClick = onOpenChargeSpeed
        )

        Spacer(Modifier.height(14.dp))

        SettingsCard(
            title = "Battery Monitor",
            subtitle = null,
            onClick = onOpenBatteryMonitor
        )

        Spacer(Modifier.height(24.dp))
    }
}
@Composable
private fun BatteryLevelBar(percent: Int) {
    val fraction = (percent.coerceIn(0, 100)) / 100f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * Settings-style card, styled to match the reference screenshot exactly:
 * dark rounded card, bold white title, muted gray wrapping subtitle, generous padding.
 */
@Composable
private fun SettingsCard(
    title: String,
    subtitle: String?,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { onClick() },
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = CardTitleColor
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    color = CardSubtitleColor
                )
            }
        }
    }
}

// ---------- Charge Control detail screen ----------

@Composable
fun ChargeSpeedScreen(onBack: () -> Unit) {
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    var nodesPresent by remember { mutableStateOf(true) }
    var restrictEnabled by remember { mutableStateOf(false) }
    var currentInputMa by remember { mutableStateOf("1500") }
    var status by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val granted = RootUtils.hasRoot()
        rootGranted = granted
        if (granted) {
            nodesPresent = RootUtils.nodeExists("/sys/class/qcom-battery/restrict_chg") &&
                RootUtils.nodeExists("/sys/class/qcom-battery/restrict_cur")
            if (nodesPresent) {
                restrictEnabled = RootUtils.readRestrictChg() == "1"
                val curMicroAmps = RootUtils.readRestrictCur().toIntOrNull()
                if (curMicroAmps != null && curMicroAmps > 0) {
                    currentInputMa = (curMicroAmps / 1000).toString()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp)
        ) {
            Text("←", fontSize = 20.sp)
        }

        Text(
            "Charge Control",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        when {
            rootGranted == null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Checking root access…")
                }
            }
            rootGranted == false -> {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "Root access was not granted. This app requires a rooted device with Magisk (or similar) and root must be allowed for this app.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            !nodesPresent -> {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "This device's kernel doesn't expose /sys/class/qcom-battery/restrict_chg or restrict_cur.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            else -> {
                ElevatedCard(shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enable services", style = MaterialTheme.typography.titleMedium)
                            Switch(
                                checked = restrictEnabled,
                                onCheckedChange = { checked ->
                                    val ok = RootUtils.setRestrictChg(checked)
                                    if (ok) {
                                        restrictEnabled = checked
                                        status = "Services ${if (checked) "enabled" else "disabled"}"
                                        isError = false
                                    } else {
                                        status = "Failed to update services"
                                        isError = true
                                    }
                                }
                            )
                        }

                        HorizontalDivider()

                        Text("Charge current limit", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = currentInputMa,
                            onValueChange = { input -> currentInputMa = input.filter { it.isDigit() } },
                            label = { Text("mA (e.g. 1200, 1500)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                val ma = currentInputMa.toIntOrNull()
                                if (ma == null || ma <= 0) {
                                    status = "Enter a valid positive number"
                                    isError = true
                                } else {
                                    val microAmps = ma * 1000
                                    val ok = RootUtils.setRestrictCur(microAmps)
                                    status = if (ok) "Charge current set to ${ma}mA" else "Failed to set charge current"
                                    isError = !ok
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Apply current limit")
                        }
                    }
                }
            }
        }

        status?.let {
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ---------- Battery Monitor detail screen ----------

@Composable
fun BatteryMonitorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var monitorEnabled by remember { mutableStateOf(BatteryMonitorService.isRunning) }
    var stats by remember { mutableStateOf(BatteryStatsStore.readStats(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            BatteryMonitorService.start(context)
            monitorEnabled = true
        } else {
            monitorEnabled = false
        }
    }

    fun toggleMonitor(enabled: Boolean) {
        if (enabled) {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                BatteryMonitorService.start(context)
                monitorEnabled = true
            }
        } else {
            BatteryMonitorService.stop(context)
            monitorEnabled = false
        }
    }

    // Poll stats every couple seconds while this screen is visible so the table stays fresh.
    LaunchedEffect(monitorEnabled) {
        while (true) {
            stats = BatteryStatsStore.readStats(context)
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp)
        ) {
            Text("←", fontSize = 20.sp)
        }

        Text(
            "Battery Monitor",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // Enable/disable card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Monitor service",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = CardTitleColor
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (monitorEnabled) "Running — updates every 10s" else "Stopped",
                        fontSize = 16.sp,
                        color = CardSubtitleColor
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = monitorEnabled, onCheckedChange = { toggleMonitor(it) })
            }
        }

        // Stats table
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = CardShape,
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "Current session",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = CardTitleColor
                )
                Spacer(Modifier.height(12.dp))

                StatRow("Battery", "${stats.percent}%")
                StatRow("Active drain", "${"%.1f".format(stats.activeDrainPerHr)}%/hr")
                StatRow("Idle drain", "${"%.1f".format(stats.idleDrainPerHr)}%/hr")
                StatRow("Screen on", formatDuration(stats.screenOnMs))
                StatRow("Screen off", formatDuration(stats.screenOffMs))
                StatRow("Deep sleep", formatDuration(stats.deepSleepMs))
                StatRow("Awake", formatDuration(stats.awakeMs))
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 16.sp, color = CardSubtitleColor)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CardTitleColor)
    }
    HorizontalDivider(color = CardSubtitleColor.copy(alpha = 0.15f))
}

/** e.g. 61_000ms -> "1m 1s" ; 3_700_000ms -> "1h 1m" */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
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

// Outer corner radius for the grouped card list (see HomeScreen) — the seam between the two
// cards stays flat/rectangular, only the very top and very bottom corners are rounded.
private val OuterCardCorner = 28.dp

// Corner radius for the Settings-style entries on the home screen.
private val SettingsCardCorner = 20.dp

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
@Composable
fun HomeScreen(
    onOpenChargeSpeed: () -> Unit,
    onOpenBatteryMonitor: () -> Unit
) {
    val battery = rememberBatteryState()
    val context = LocalContext.current
    var monitorStats by remember { mutableStateOf(BatteryStatsStore.readStats(context)) }
    var hourlyHistory by remember { mutableStateOf(BatteryStatsStore.readHourlyHistory(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            monitorStats = BatteryStatsStore.readStats(context)
            hourlyHistory = BatteryStatsStore.readHourlyHistory(context)
            delay(2_000)
        }
    }

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

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
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
                    if (battery.isCharging) {
                        Text(
                            "${kotlin.math.abs(battery.currentNowMa)} mA",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                BatteryLevelBar(percent = battery.percent)

                Spacer(Modifier.height(12.dp))

                Text(
                    if (battery.isCharging) "Charging" else "Not charging",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        BatteryUsageGraph(
            history = hourlyHistory,
            screenOnMs = monitorStats.monitorScreenOnMs,
            screenOffMs = monitorStats.monitorScreenOffMs
        )

        Spacer(Modifier.height(24.dp))

        SettingsCard(
            title = "Charge Control",
            onClick = onOpenChargeSpeed
        )

        Spacer(Modifier.height(4.dp))

        SettingsCard(
            title = "Battery Monitor",
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

@Composable
private fun BatteryUsageGraph(
    history: List<Pair<String, Int>>,
    screenOnMs: Long,
    screenOffMs: Long
) {
    val subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    if (history.size < 2) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            )
        ) {
            Text(
                "Not enough data yet — check back after the monitor has run a while.",
                modifier = Modifier.padding(20.dp),
                fontSize = 13.sp,
                color = subtitleColor
            )
        }
        return
    }

    val percents = history.map { it.second }
    val startPct = percents.first()
    val endPct = percents.last()
    val used = (startPct - endPct).coerceAtLeast(0)
    val monitoredMs = screenOnMs + screenOffMs

    val dataMax = percents.max()
    val dataMin = percents.min()
    val rawTop = (dataMax + 2).coerceAtMost(100)
    val rawBottom = (dataMin - 2).coerceAtLeast(0)
    var axisTop = ((rawTop + 4) / 5) * 5
    var axisBottom = (rawBottom / 5) * 5
    if (axisTop > 100) axisTop = 100
    if (axisTop - axisBottom < 5) {
        axisBottom = (axisTop - 5).coerceAtLeast(0)
        if (axisTop - axisBottom < 5) axisTop = (axisBottom + 5).coerceAtMost(100)
    }
    val axisRange = (axisTop - axisBottom).coerceAtLeast(1)

    val yTicks = 4
    val yLabels = (0..yTicks).map { i -> axisTop - (axisRange * i / yTicks) }

    val areaTop = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val areaBottom = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
    val lineColor = MaterialTheme.colorScheme.primary
    val projectionColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val markerRing = MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Battery usage",
                style = MaterialTheme.typography.labelLarge,
                color = subtitleColor
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$used%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "  used in ${formatDuration(monitoredMs)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = subtitleColor,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Screen on", formatDuration(screenOnMs), Modifier.weight(1f))
                StatChip("Screen off", formatDuration(screenOffMs), Modifier.weight(1f))
            }

            Spacer(Modifier.height(18.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(180.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    fun yFor(pct: Int): Float {
                        val clamped = pct.coerceIn(axisBottom, axisTop)
                        return h - ((clamped - axisBottom).toFloat() / axisRange) * h
                    }

                    for (i in 0..yTicks) {
                        val y = h * i / yTicks
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 2f,
                            pathEffect = if (i == 0 || i == yTicks) null
                            else PathEffect.dashPathEffect(floatArrayOf(8f, 10f), 0f)
                        )
                    }

                    val historyWidth = w * 0.6f
                    val stepX = historyWidth / (history.size - 1)

                    val xTicks = 3
                    for (i in 0..xTicks) {
                        val x = w * i / xTicks
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, h),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 12f), 0f)
                        )
                    }

                    val curve = Path()
                    curve.moveTo(0f, yFor(startPct))
                    history.forEachIndexed { index, (_, pct) ->
                        curve.lineTo(index * stepX, yFor(pct))
                    }

                    val area = Path()
                    area.addPath(curve)
                    area.lineTo(historyWidth, h)
                    area.lineTo(0f, h)
                    area.close()
                    drawPath(
                        area,
                        brush = Brush.verticalGradient(listOf(areaTop, areaBottom))
                    )
                    drawPath(
                        curve,
                        color = lineColor,
                        style = Stroke(width = 6f, cap = StrokeCap.Round)
                    )

                    val markerY = yFor(endPct)
                    drawCircle(color = markerRing, radius = 12f, center = Offset(historyWidth, markerY))
                    drawCircle(color = lineColor, radius = 8f, center = Offset(historyWidth, markerY))

                    val perHour = used.toFloat() / (history.size - 1).toFloat()
                    val hoursLeft = if (perHour > 0.1f) (endPct - axisBottom) / perHour else 24f
                    val projX = (historyWidth + hoursLeft * stepX).coerceAtMost(w)
                    val projFraction =
                        if (hoursLeft > 0f && projX > historyWidth) (projX - historyWidth) / (hoursLeft * stepX) else 1f
                    val projY = markerY + (h - markerY) * projFraction.coerceIn(0f, 1f)
                    drawLine(
                        color = projectionColor,
                        start = Offset(historyWidth, markerY),
                        end = Offset(projX, projY),
                        strokeWidth = 7f,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 20f), 0f)
                    )
                }

                Spacer(Modifier.width(8.dp))

                Column(
                    modifier = Modifier.height(180.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.Start
                ) {
                    yLabels.forEach { value ->
                        Text("$value%", fontSize = 11.sp, color = labelColor)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            val xStamps = buildList {
                add(history.first().first)
                if (history.size >= 4) add(history[history.size / 3].first)
                if (history.size >= 3) add(history[(history.size * 2) / 3].first)
                add(history.last().first)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                xStamps.forEach { stamp ->
                    Text(stamp, fontSize = 11.sp, color = labelColor)
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(SettingsCardCorner)
) {
    val interactionSource = remember { MutableInteractionSource() }

    Card(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { onClick() },
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    FilledTonalIconButton(
        onClick = onBack,
        modifier = Modifier.size(52.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("←", fontSize = 26.sp)
        }
    }
}
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
        BackButton(onBack)

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
                            label = { Text("In mA") },
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
        BackButton(onBack)

        Text(
            "Battery Monitor",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(OuterCardCorner),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Monitor service",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Switch(checked = monitorEnabled, onCheckedChange = { toggleMonitor(it) })
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(OuterCardCorner),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Current session",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                StatRow("Battery", "${stats.percent}%")
                StatRow("Active drain", "${"%.1f".format(stats.activeDrainPerHr)}%/hr")
                StatRow("Idle drain", "${"%.1f".format(stats.idleDrainPerHr)}%/hr")
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
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
}

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

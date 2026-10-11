@file:OptIn(ExperimentalFoundationApi::class)

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
        setContent { AmpifyTheme { AppRoot() } }
    }
}

// ---------- root: title, swipeable pages, floating nav pill ----------

@Composable
fun AppRoot() {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { 3 })

    BackHandler(enabled = pager.currentPage != 0) {
        scope.launch { pager.animateScrollToPage(0) }
    }

    // The nav pill slides away when the keyboard opens; pages keep their content clear of it.
    val imeOpen = WindowInsets.ime.getBottom(density) > 0
    val navInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val reserve by animateDpAsState(
        if (imeOpen) 8.dp else 72.dp + navInset,
        tween(300, easing = FastOutSlowInEasing), label = "reserve",
    )
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }

    CompositionLocalProvider(LocalBottomReserve provides reserve) {
        Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ampify", style = Type.headline)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(9.dp).clip(CircleShape).background(p.accent))
                }

                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f),
                    key = { it },
                ) { page ->
                    when (page) {
                        0 -> HomePage()
                        1 -> ChargePage()
                        else -> MonitorPage()
                    }
                }
            }

            AnimatedVisibility(
                visible = started && !imeOpen,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(tween(450, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(250, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(200)),
            ) {
                NavPill(
                    labels = listOf("Home", "Charge", "Monitor"),
                    position = pager.currentPage + pager.currentPageOffsetFraction,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    onTap = { i -> scope.launch { pager.animateScrollToPage(i) } },
                    onDrag = { f ->
                        val size = pager.layoutInfo.pageSize
                        if (size > 0) pager.dispatchRawDelta(f * size)
                    },
                    onRelease = {
                        val target = (pager.currentPage + pager.currentPageOffsetFraction).roundToInt().coerceIn(0, 2)
                        scope.launch { pager.animateScrollToPage(target) }
                    },
                )
            }
        }
    }
}

// ---------- battery state ----------

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
            // 1.5s still feels live for the % / mA readout, but polls the
            // sticky broadcast ~33% less often than before.
            delay(1_500)
        }
    }

    return state
}

// ---------- home ----------

@Composable
private fun HomePage() {
    val p = LocalPalette.current
    val battery = rememberBatteryState()
    val context = LocalContext.current
    var monitorStats by remember { mutableStateOf(BatteryStatsStore.readStats(context)) }
    var hourlyHistory by remember { mutableStateOf(BatteryStatsStore.readHourlyHistory(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            monitorStats = BatteryStatsStore.readStats(context)
            hourlyHistory = BatteryStatsStore.readHourlyHistory(context)
            // BatteryMonitorService only writes fresh data every 10s (REFRESH_INTERVAL_MS),
            // so polling faster than that just re-reads identical data.
            delay(10_000)
        }
    }

    val meter by animateFloatAsState(
        (battery.percent.coerceIn(0, 100)) / 100f,
        spring(dampingRatio = 0.6f, stiffness = 150f),
        label = "meter",
    )

    PageScaffold {
        SectionCard {
            DotMeter(meter)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Readout("Battery", "${battery.percent}%")
                Readout(
                    "Current",
                    if (battery.isCharging) "${kotlin.math.abs(battery.currentNowMa)} mA" else "--",
                    alignEnd = true
                )
            }
            Rule()
            Section("Status") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(9.dp).clip(CircleShape)
                            .background(if (battery.isCharging) p.accent else p.dotOff)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(if (battery.isCharging) "Charging" else "Not charging", style = Type.title)
                }
            }
        }

        BatteryUsageCard(
            history = hourlyHistory,
            screenOnMs = monitorStats.monitorScreenOnMs,
            screenOffMs = monitorStats.monitorScreenOffMs
        )
    }
}

@Composable
private fun BatteryUsageCard(
    history: List<Pair<String, Int>>,
    screenOnMs: Long,
    screenOffMs: Long
) {
    val p = LocalPalette.current

    if (history.size < 2) {
        SectionCard {
            Section("Battery usage") {
                Text(
                    "Not enough data yet. Check back after the monitor has been running for a while.",
                    color = p.mute
                )
            }
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

    val lineColor = p.accent
    val areaTop = p.accent.copy(alpha = 0.30f)
    val areaBottom = p.accent.copy(alpha = 0.02f)
    val projectionColor = p.mute
    val gridColor = p.stroke
    val markerRing = p.card

    SectionCard {
        Section("Battery usage") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$used%", style = Type.headline)
                Spacer(Modifier.width(8.dp))
                Text(
                    "used in ${formatDuration(monitoredMs)}",
                    color = p.mute,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatChip("Screen on", formatDuration(screenOnMs), Modifier.weight(1f))
                StatChip("Screen off", formatDuration(screenOffMs), Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(20.dp))

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
                    Text("$value%", color = p.mute, style = Type.tiny)
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
                Text(stamp, color = p.mute, style = Type.tiny)
            }
        }
    }
}

// ---------- charge control ----------

@Composable
private fun ChargePage() {
    val p = LocalPalette.current
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

    val ready = rootGranted == true && nodesPresent
    val validMa = currentInputMa.toIntOrNull()?.let { it > 0 } == true

    PageScaffold {
        SectionCard {
            when {
                rootGranted == null -> {
                    Section("Root") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WorkingDots()
                            Spacer(Modifier.width(12.dp))
                            Text("Checking root access", color = p.mute)
                        }
                    }
                }
                rootGranted == false -> {
                    Section("Root") {
                        Text(
                            "Root access was not granted. This app needs a rooted device with Magisk (or similar), and root must be allowed for Ampify.",
                            color = p.accent
                        )
                    }
                }
                !nodesPresent -> {
                    Section("Kernel") {
                        Text(
                            "This device's kernel doesn't expose /sys/class/qcom-battery/restrict_chg or restrict_cur.",
                            color = p.accent
                        )
                    }
                }
                else -> {
                    SwitchRow("Enable services", restrictEnabled) { checked ->
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

                    Rule()

                    Section("Charge current limit") {
                        Field(
                            value = currentInputMa,
                            onValueChange = { input -> currentInputMa = input.filter { it.isDigit() } },
                            placeholder = "0",
                            keyboard = KeyboardType.Number,
                            suffix = "mA"
                        )
                    }

                    Reveal(status != null) {
                        Rule()
                        Text(status.orEmpty(), color = if (isError) p.accent else p.text)
                    }
                }
            }
        }

        if (ready) {
            ActionPill("Apply limit", enabled = validMa) {
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
            }
        }
    }
}

// ---------- battery monitor ----------

@Composable
private fun MonitorPage() {
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
            BatteryStatsStore.clearHourlyHistory(context)
            monitorEnabled = false
        }
    }

    LaunchedEffect(monitorEnabled) {
        while (true) {
            stats = BatteryStatsStore.readStats(context)
            delay(10_000)
        }
    }

    PageScaffold {
        SectionCard {
            SwitchRow("Monitor service", monitorEnabled) { toggleMonitor(it) }

            Rule()

            Section("Current session") {
                Column {
                    ValueRow("Battery", "${stats.percent}%")
                    HairLine()
                    ValueRow("Active drain", "${"%.1f".format(stats.activeDrainPerHr)}%/hr")
                    HairLine()
                    ValueRow("Idle drain", "${"%.1f".format(stats.idleDrainPerHr)}%/hr")
                    HairLine()
                    ValueRow("Deep sleep", formatDuration(stats.deepSleepMs))
                    HairLine()
                    ValueRow("Awake", formatDuration(stats.awakeMs))
                }
            }
        }
    }
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

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()
    // The graph only runs while the notification monitor is on.
    val monitorOn = BatteryMonitorService.isRunning
    var samples by remember { mutableStateOf<List<BatterySample>>(emptyList()) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var windowIdx by rememberSaveable { mutableIntStateOf(1) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<PowerReport?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanFailed by remember { mutableStateOf(false) }

    fun scan() {
        if (scanning) return
        scope.launch {
            scanning = true
            scanFailed = false
            val result = withContext(Dispatchers.IO) {
                runCatching { if (RootUtils.hasRoot()) PowerStats.load(context) else null }.getOrNull()
            }
            if (result == null) scanFailed = true else report = result
            scanning = false
        }
    }

    LaunchedEffect(monitorOn) {
        if (!monitorOn) {
            samples = emptyList()
            return@LaunchedEffect
        }
        // Log a reading as soon as the monitor is on, then keep the chart fresh.
        BatterySampler.sample(context)
        while (true) {
            samples = BatteryStatsStore.readSamples(context, System.currentTimeMillis() - 26 * DrainMath.HOUR_MS)
            nowMs = System.currentTimeMillis()
            delay(10_000)
        }
    }

    // Per-app usage is read with root the first time that tab is opened.
    LaunchedEffect(mode) {
        if (mode == 1 && report == null) scan()
    }

    val meter by animateFloatAsState(
        (battery.percent.coerceIn(0, 100)) / 100f,
        spring(dampingRatio = 0.6f, stiffness = 150f),
        label = "meter",
    )

    val windowHours = listOf(6, 12, 24)[windowIdx]
    // Add "right now" as the last point so the line always reaches the present.
    val lastT = samples.lastOrNull()?.timeMs ?: 0L
    val all = if (monitorOn && battery.percent > 0 && nowMs >= lastT)
        samples + BatterySample(nowMs, battery.percent, battery.isCharging, true)
    else samples

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
            mode = mode,
            onMode = { mode = it },
            monitorOn = monitorOn,
            all = all,
            windowHours = windowHours,
            nowMs = nowMs,
            selected = windowIdx,
            onSelect = { windowIdx = it },
            report = report,
            scanning = scanning,
            scanFailed = scanFailed,
        )

        if (mode == 1) {
            ActionPill(if (report == null) "Scan app usage" else "Scan again", enabled = !scanning) { scan() }
        }
    }
}

@Composable
private fun BatteryUsageCard(
    mode: Int,
    onMode: (Int) -> Unit,
    monitorOn: Boolean,
    all: List<BatterySample>,
    windowHours: Int,
    nowMs: Long,
    selected: Int,
    onSelect: (Int) -> Unit,
    report: PowerReport?,
    scanning: Boolean,
    scanFailed: Boolean,
) {
    val p = LocalPalette.current
    val startMs = nowMs - windowHours * DrainMath.HOUR_MS
    val enough = monitorOn && all.count { it.timeMs in startMs..nowMs } >= 2

    SectionCard {
        Segmented(listOf("Graph", "Per app"), mode, onSelect = onMode)
        Spacer(Modifier.height(16.dp))

        if (mode == 1) {
            PerAppUsage(report, scanning, scanFailed)
            return@SectionCard
        }

        Section("Battery usage") {
            if (!monitorOn) {
                Text(
                    "Graph is off. Turn on the Monitor service in the Monitor tab to start logging.",
                    color = p.mute
                )
            } else {
                Segmented(listOf("6 h", "12 h", "24 h"), selected, onSelect = onSelect)

                if (!enough) {
                    Text(
                        "Not enough data yet. Ampify logs the battery level in the background while the monitor is on.",
                        color = p.mute
                    )
                } else {
                    val summary = DrainMath.summarize(all, startMs, nowMs)

                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${summary.usedPercent.roundToInt()}%", style = Type.headline)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "used in the last $windowHours h",
                            color = p.mute,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatChip(
                            "Screen on",
                            "${summary.screenOnDrop.roundToInt()}% in ${formatDuration(summary.screenOnMs)}",
                            Modifier.weight(1f)
                        )
                        StatChip(
                            "Screen off",
                            "${summary.screenOffDrop.roundToInt()}% in ${formatDuration(summary.screenOffMs)}",
                            Modifier.weight(1f)
                        )
                    }

                    BatteryGraph(all, windowHours, nowMs)

                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendItem(p.accent, "On battery")
                        LegendItem(p.mute, "Charging or no data")
                    }
                }
            }
        }

        if (enough) {
            Rule()
            val firstHour = DrainMath.hourStart(nowMs) - (windowHours - 1) * DrainMath.HOUR_MS
            val bars = DrainMath.hourly(all, firstHour, windowHours)
            Section("Drain per hour") {
                HourlyDrain(bars)
            }
        }
    }
}

/** Battery use per app (mAh and share of all apps) since the last full charge. Needs root. */
@Composable
private fun PerAppUsage(report: PowerReport?, scanning: Boolean, scanFailed: Boolean) {
    val p = LocalPalette.current
    Section("Battery use by app") {
        when {
            scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                WorkingDots()
                Spacer(Modifier.width(12.dp))
                Text("Reading battery stats", color = p.mute)
            }
            scanFailed -> Text(
                "Couldn't read battery stats. Allow root for Ampify and try again.",
                color = p.accent
            )
            report == null -> Text("Tap Scan app usage to see which apps used the most battery.", color = p.mute)
            report.apps.isEmpty() -> Text("Android hasn't reported any app usage yet.", color = p.mute)
            else -> {
                Text(
                    "Since the last full charge" +
                        (report.capacityMah?.let { ". Battery capacity ${it.roundToInt()} mAh" } ?: "") + ".",
                    color = p.mute
                )
                val max = report.apps.first().mah
                val total = report.apps.sumOf { it.mah }.coerceAtLeast(0.001)
                Column {
                    report.apps.take(15).forEach {
                        val share = (it.mah / total * 100).roundToInt()
                        UsageRow(it.name, "${formatMah(it.mah)} · $share%", (it.mah / max).toFloat())
                    }
                }
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
            monitorEnabled = false
        }
    }

    LaunchedEffect(monitorEnabled) {
        while (true) {
            stats = BatteryStatsStore.readStats(context)
            delay(10_000)
        }
    }

    var report by remember { mutableStateOf<PowerReport?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val p = LocalPalette.current

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

        SectionCard {
            Section("Power use") {
                when {
                    scanning -> Row(verticalAlignment = Alignment.CenterVertically) {
                        WorkingDots()
                        Spacer(Modifier.width(12.dp))
                        Text("Reading battery stats", color = p.mute)
                    }
                    scanFailed -> Text(
                        "Couldn't read battery stats. Allow root for Ampify and try again.",
                        color = p.accent
                    )
                    report == null -> Text(
                        "Scan with root to see which apps and which parts of the phone used the most battery since the last full charge.",
                        color = p.mute
                    )
                    else -> Text(
                        "Since the last full charge" +
                            (report?.capacityMah?.let { ". Battery capacity ${it.roundToInt()} mAh" } ?: "") + ".",
                        color = p.mute
                    )
                }
            }

            report?.let { r ->
                if (r.apps.isNotEmpty()) {
                    Rule()
                    Section("Apps") {
                        val top = r.apps.take(10)
                        val max = top.first().mah
                        Column {
                            top.forEach { UsageRow(it.name, formatMah(it.mah), (it.mah / max).toFloat()) }
                        }
                        if (r.appsIncludeShared) {
                            Text(
                                "App figures include their share of the screen and radios.",
                                color = p.mute, style = Type.small
                            )
                        }
                    }
                }
                if (r.components.isNotEmpty()) {
                    Rule()
                    Section("Phone components") {
                        val max = r.components.first().mah
                        Column {
                            r.components.take(12).forEach {
                                UsageRow(it.name, formatMah(it.mah), (it.mah / max).toFloat())
                            }
                        }
                    }
                }
            }
        }

        ActionPill(if (report == null) "Scan power use" else "Scan again", enabled = !scanning) {
            scope.launch {
                scanning = true
                scanFailed = false
                val result = withContext(Dispatchers.IO) {
                    runCatching { if (RootUtils.hasRoot()) PowerStats.load(context) else null }.getOrNull()
                }
                if (result == null) scanFailed = true else report = result
                scanning = false
            }
        }
    }
}

private fun formatMah(v: Double): String =
    if (v >= 100) "${v.roundToInt()} mAh" else "${"%.1f".format(v)} mAh"

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

package com.example.batteryrestrict

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.topjohnwu.superuser.Shell

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

private enum class Screen { Home, ChargeSpeed }

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.Home) }

    BackHandler(enabled = screen != Screen.Home) {
        screen = Screen.Home
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState == Screen.ChargeSpeed) {
                (scaleIn(
                    initialScale = 0.8f,
                    animationSpec = tween(420, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(420))) togetherWith
                    (scaleOut(
                        targetScale = 1.15f,
                        animationSpec = tween(420, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(300)))
            } else {
                (scaleIn(
                    initialScale = 1.15f,
                    animationSpec = tween(420, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(420))) togetherWith
                    (scaleOut(
                        targetScale = 0.8f,
                        animationSpec = tween(420, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(300)))
            }
        },
        label = "screenTransition"
    ) { targetScreen ->
        when (targetScreen) {
            Screen.Home -> HomeScreen(onOpenChargeSpeed = { screen = Screen.ChargeSpeed })
            Screen.ChargeSpeed -> ChargeSpeedScreen(onBack = { screen = Screen.Home })
        }
    }
}

// ---------- Live battery state ----------

private data class BatteryState(val percent: Int, val isCharging: Boolean)

@Composable
private fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BatteryState(0, false)) }

    DisposableEffect(Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

        fun update(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            state = BatteryState(pct, charging)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) = update(intent)
        }

        val sticky = context.registerReceiver(receiver, filter)
        update(sticky)

        onDispose { context.unregisterReceiver(receiver) }
    }

    return state
}

// ---------- Home screen (Settings-style) ----------

@Composable
fun HomeScreen(onOpenChargeSpeed: () -> Unit) {
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
            "Battery",
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

        Spacer(Modifier.height(28.dp))

        SettingsCard(
            title = "Charge Control",
            subtitle = "Limit charging current to reduce heat and battery wear",
            enabled = true,
            onClick = onOpenChargeSpeed
        )

        Spacer(Modifier.height(14.dp))

        SettingsCard(
            title = "Thermal Profiles",
            subtitle = "Coming soon",
            enabled = false,
            onClick = {}
        )

        Spacer(Modifier.height(14.dp))

        SettingsCard(
            title = "Power Profile",
            subtitle = "Coming soon",
            enabled = false,
            onClick = {}
        )

        Spacer(Modifier.height(14.dp))

        SettingsCard(
            title = "Battery usage",
            subtitle = "Coming soon",
            enabled = false,
            onClick = {}
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
private fun SettingsCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "settingsCardScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { onClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
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
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "Root access was not granted. This app requires a rooted device with Magisk (or similar) and root must be allowed for this app.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            !nodesPresent -> {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "This device's kernel doesn't expose /sys/class/qcom-battery/restrict_chg or restrict_cur.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            else -> {
                ElevatedCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

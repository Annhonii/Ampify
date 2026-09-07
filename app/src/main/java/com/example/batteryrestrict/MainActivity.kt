package com.example.batteryrestrict

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private data class FeatureCard(
    val title: String,
    val subtitle: String,
    val emoji: String,
    val enabled: Boolean,
    val onClick: () -> Unit
)

@Composable
fun HomeScreen(onOpenChargeSpeed: () -> Unit) {
    val cards = listOf(
        FeatureCard("Change charge speed", "Limit charge current", "⚡", true, onOpenChargeSpeed),
        FeatureCard("Battery health", "Coming soon", "🔋", false, {}),
        FeatureCard("More tools", "Coming soon", "🛠️", false, {})
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(20.dp)
    ) {
        Text("Battery Tools", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(cards) { card -> FeatureCardItem(card) }
        }
    }
}

@Composable
private fun FeatureCardItem(card: FeatureCard) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "cardScale"
    )

    ElevatedCard(
        modifier = Modifier
            .aspectRatio(1f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clickable(
                enabled = card.enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { card.onClick() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (card.enabled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(card.emoji, style = MaterialTheme.typography.headlineLarge)
            Column {
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (card.enabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (card.enabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
        // Small circular back button, standalone, settings-style
        FilledTonalIconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp)
        ) {
            Text("←", fontSize = 20.sp)
        }

        Text(
            "Change speed",
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
                            label = { Text("In milliamps") },
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

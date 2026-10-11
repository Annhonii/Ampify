package com.example.batteryrestrict

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------- motion ----------

/** Space the floating nav pill takes at the bottom. Pages keep their content clear of it. */
val LocalBottomReserve = compositionLocalOf { 72.dp }

/** Press feedback: a ripple that grows from the touch point, clipped to [shape]. */
@Composable
fun Modifier.tap(
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    ripple: Color? = null,
    action: () -> Unit,
): Modifier {
    val p = LocalPalette.current
    val current by rememberUpdatedState(action)
    val scope = rememberCoroutineScope()
    val grow = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val rippleColor = ripple ?: p.high
    return this
        .semantics(mergeDescendants = true) { role = Role.Button; onClick { current(); true } }
        .clip(shape)
        .drawWithContent {
            drawContent()
            if (glow.value > 0f) {
                drawCircle(
                    rippleColor.copy(alpha = rippleColor.alpha * 0.6f * glow.value),
                    radius = size.maxDimension * grow.value,
                    center = origin,
                )
            }
        }
        .pointerInput(enabled) {
            if (enabled) {
                detectTapGestures(
                    onPress = { o ->
                        origin = o
                        scope.launch {
                            glow.snapTo(1f)
                            grow.snapTo(0f)
                            grow.animateTo(1.2f, tween(320, easing = FastOutSlowInEasing))
                        }
                        tryAwaitRelease()
                        scope.launch { glow.animateTo(0f, tween(260)) }
                    },
                    onTap = { current() },
                )
            }
        }
}

/** A thin scrollbar that fades in while scrolling. Apply BEFORE verticalScroll. */
@Composable
fun Modifier.scrollbar(state: ScrollState): Modifier {
    val p = LocalPalette.current
    val scrolling = state.isScrollInProgress
    val visible by animateFloatAsState(
        if (scrolling) 1f else 0f,
        tween(durationMillis = if (scrolling) 120 else 400, delayMillis = if (scrolling) 0 else 700),
        label = "scrollbar",
    )
    return drawWithContent {
        drawContent()
        val max = state.maxValue
        if (visible > 0.01f && max > 0) {
            val view = size.height
            val h = (view * view / (view + max)).coerceAtLeast(28.dp.toPx())
            val y = (view - h) * state.value / max
            val w = 3.dp.toPx()
            drawRoundRect(
                p.mute.copy(alpha = 0.55f * visible),
                Offset(size.width - w - 3.dp.toPx(), y),
                Size(w, h),
                CornerRadius(w / 2f),
            )
        }
    }
}

/** Smooth expand + fade for sections that come and go inside a card. */
@Composable
fun Reveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(250)) + expandVertically(tween(320, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(250, easing = FastOutSlowInEasing)),
    ) { Column(content = content) }
}

// ---------- layout ----------

@Composable
fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(p.card, shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(title.uppercase(), color = p.mute, style = Type.em)
        content()
    }
}

@Composable
fun Rule() {
    val p = LocalPalette.current
    Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(p.stroke))
}

/** A scrolling page that leaves room for the floating nav pill. */
@Composable
fun PageScaffold(content: @Composable ColumnScope.() -> Unit) {
    val reserve = LocalBottomReserve.current
    val scroll = rememberScrollState()
    Column(
        Modifier.fillMaxSize()
            .scrollbar(scroll)
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp)
            .padding(top = 4.dp, bottom = reserve + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

// ---------- the memorable element: a dot grid that fills to show a level ----------

@Composable
fun DotMeter(fraction: Float, cols: Int = 24, rows: Int = 7) {
    val p = LocalPalette.current
    Canvas(Modifier.fillMaxWidth().aspectRatio(cols.toFloat() / rows)) {
        val cell = size.width / cols
        val total = cols * rows
        val filled = (fraction * total).roundToInt().coerceIn(0, total)
        for (i in 0 until total) {
            val c = i / rows
            val r = i % rows
            val color = when {
                i == filled - 1 -> p.accent
                i < filled -> p.dotOn
                else -> p.dotOff
            }
            drawCircle(color, radius = cell * 0.27f, center = Offset(cell * (c + 0.5f), cell * (r + 0.5f)))
        }
    }
}

@Composable
fun WorkingDots() {
    val p = LocalPalette.current
    val t = rememberInfiniteTransition(label = "working")
    val pos by t.animateFloat(0f, 5f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "pos")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(5) { i ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (pos.toInt() == i) p.accent else p.dotOff))
        }
    }
}

// ---------- controls ----------

/** The one primary action of a page: a full-width red pill that fades to 25% when disabled. */
@Composable
fun ActionPill(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val fade by animateFloatAsState(if (enabled) 1f else 0.25f, tween(200), label = "pill-fade")
    Box(
        Modifier.fillMaxWidth().height(56.dp)
            .graphicsLayer { alpha = fade }
            .tap(enabled, CircleShape, ripple = Color(0x33000000)) { onClick() }
            .background(p.primary, CircleShape)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = p.onPrimary, style = Type.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** On/off switch: a grey track that turns red, with a thumb that glides across. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val pos by animateFloatAsState(if (checked) 1f else 0f, tween(200, easing = FastOutSlowInEasing), label = "toggle")
    Box(
        Modifier.width(56.dp).height(32.dp)
            .tap(true, CircleShape, ripple = Color(0x33000000)) { onChange(!checked) }
            .background(lerp(p.field, p.primary, pos), CircleShape),
    ) {
        Box(
            Modifier.padding(4.dp).size(24.dp)
                .offset { IntOffset((24.dp.toPx() * pos).roundToInt(), 0) }
                .clip(CircleShape)
                .background(lerp(p.mute, Color.White, pos)),
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = Type.title)
        Spacer(Modifier.width(12.dp))
        Toggle(checked, onChange)
    }
}

/** A filled well (no outline); a red ring eases in on focus. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    style: TextStyle = Type.input,
    suffix: String? = null,
) {
    val p = LocalPalette.current
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "ring")
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = p.text),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth()
                    .background(p.field, shape)
                    .then(
                        if (ring > 0.01f) Modifier.border((2f * ring).dp, p.accent.copy(alpha = ring), shape)
                        else Modifier,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = p.mute, style = style, maxLines = 1)
                    inner()
                }
                if (suffix != null) Text(suffix, color = p.mute, style = style, maxLines = 1)
            }
        },
    )
}

/** The floating bottom nav: a pill with the active label in red and a soft thumb that follows swipes. */
@Composable
fun NavPill(
    labels: List<String>,
    position: Float,
    modifier: Modifier = Modifier,
    onTap: (Int) -> Unit,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val padPx = with(density) { 4.dp.toPx() }
    val n = labels.size
    val segPx = if (widthPx > 0) (widthPx - 2 * padPx) / n else 0f
    val segDp = with(density) { segPx.toDp() }
    val shape = CircleShape

    Box(
        modifier.fillMaxWidth().height(56.dp)
            .shadow(6.dp, shape, clip = false, ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
            .clip(shape)
            .background(p.card)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(n, segPx) {
                detectTapGestures { o ->
                    if (segPx > 0f) onTap(((o.x - padPx) / segPx).toInt().coerceIn(0, n - 1))
                }
            }
            .pointerInput(n, segPx) {
                detectHorizontalDragGestures(
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                ) { change, amount ->
                    change.consume()
                    if (segPx > 0f) onDrag(amount / segPx)
                }
            },
    ) {
        Box(
            Modifier.padding(4.dp).fillMaxHeight().width(segDp)
                .offset { IntOffset((position * segPx).roundToInt(), 0) }
                .clip(shape)
                .background(p.field),
        )
        Row(Modifier.fillMaxSize().padding(4.dp)) {
            labels.forEachIndexed { i, l ->
                val near = (1f - abs(position - i)).coerceIn(0f, 1f)
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(l, color = lerp(p.text, p.primary, near), style = Type.title, maxLines = 1)
                }
            }
        }
    }
}

// ---------- readouts ----------

/** Small muted label over a large value, like Shrinky's Original / Target. */
@Composable
fun Readout(label: String, value: String, alignEnd: Boolean = false) {
    val p = LocalPalette.current
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(label, color = p.mute, style = Type.title)
        Text(value, style = Type.input)
    }
}

/** Label on the left, value on the right. */
@Composable
fun ValueRow(label: String, value: String) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = p.mute, style = Type.title)
        Text(value, style = Type.title)
    }
}

@Composable
fun HairLine() {
    val p = LocalPalette.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.stroke))
}

/** A small filled tile with a label and a value. */
@Composable
fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(p.field)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, color = p.mute, style = Type.small)
        Spacer(Modifier.height(2.dp))
        Text(value, style = Type.title)
    }
}

// ---------- usage widgets ----------

/** Pill-shaped picker with a thumb that glides to the chosen option. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val pos by animateFloatAsState(selected.toFloat(), tween(250, easing = FastOutSlowInEasing), label = "seg")
    var widthPx by remember { mutableIntStateOf(0) }
    val n = options.size
    val padPx = with(density) { 4.dp.toPx() }
    val segPx = if (widthPx > 0) (widthPx - 2 * padPx) / n else 0f
    val segDp = with(density) { segPx.toDp() }

    Box(
        modifier.fillMaxWidth().height(40.dp)
            .clip(CircleShape)
            .background(p.field)
            .onSizeChanged { widthPx = it.width },
    ) {
        Box(
            Modifier.padding(4.dp).fillMaxHeight().width(segDp)
                .offset { IntOffset((pos * segPx).roundToInt(), 0) }
                .clip(CircleShape)
                .background(p.text),
        )
        Row(Modifier.fillMaxSize().padding(4.dp)) {
            options.forEachIndexed { i, label ->
                val near = (1f - abs(pos - i)).coerceIn(0f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight().tap(true, CircleShape) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = lerp(p.text, p.bg, near), style = Type.label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = p.mute, style = Type.small)
    }
}

/** A name, a value on the right and a thin bar showing the share against the biggest item. */
@Composable
fun UsageRow(name: String, value: String, fraction: Float) {
    val p = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(12.dp))
            Text(value, color = p.mute, style = Type.body, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.field)) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight()
                    .clip(CircleShape).background(p.accent),
            )
        }
    }
}

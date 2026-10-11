package com.example.batteryrestrict

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/** Colour roles, flattened to the handful this app needs. Same look as Shrinky. */
@Immutable
class Palette(
    val bg: Color,          // the page
    val card: Color,        // cards and the floating nav pill
    val stroke: Color,      // hairlines
    val text: Color,
    val mute: Color,
    val dotOff: Color,
    val dotOn: Color,
    val primary: Color,     // red
    val onPrimary: Color,
    val accent: Color,
    val field: Color,       // inputs, toggle track, chips
    val disabled: Color,
    val high: Color,        // ripple / pressed
)

private val Red = Color(0xFFD71921)

val DarkPalette = Palette(
    bg = Color(0xFF000000), card = Color(0xFF1C1C1C), stroke = Color(0xFF323232),
    text = Color(0xFFFFFFFF), mute = Color(0xFFADADAD),
    dotOff = Color(0xFF323232), dotOn = Color(0xFFFFFFFF),
    primary = Red, onPrimary = Color(0xFFFFFFFF), accent = Red,
    field = Color(0xFF292929), disabled = Color(0xFF323232), high = Color(0xFF323232),
)

val LightPalette = Palette(
    bg = Color(0xFFF2F2F2), card = Color(0xFFFFFFFF), stroke = Color(0xFFD9D9D9),
    text = Color(0xFF000000), mute = Color(0xFF606060),
    dotOff = Color(0xFFD9D9D9), dotOn = Color(0xFF000000),
    primary = Red, onPrimary = Color(0xFFFFFFFF), accent = Red,
    field = Color(0xFFEBEBEB), disabled = Color(0xFFD9D9D9), high = Color(0xFFD9D9D9),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Geist by Vercel (SIL Open Font License 1.1), same typeface as Shrinky. */
val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
)

object Type {
    val headline = TextStyle(fontFamily = Geist, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
    val input = TextStyle(fontFamily = Geist, fontSize = 22.sp, lineHeight = 28.sp)
    val title = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 22.sp)
    val button = TextStyle(fontFamily = Geist, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val label = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp)
    val small = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 16.sp)
    val tiny = TextStyle(fontFamily = Geist, fontSize = 10.sp, lineHeight = 14.sp)
    /** Section headings: small, medium weight, wide tracking. */
    val em = TextStyle(fontFamily = Geist, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.text,
    style: TextStyle = Type.body,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    BasicText(text, modifier, style.copy(color = color, textAlign = textAlign), overflow = overflow, maxLines = maxLines)
}

@Composable
fun AmpifyTheme(content: @Composable () -> Unit) {
    val p = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        Box(Modifier.fillMaxSize().background(p.bg)) { content() }
    }
}

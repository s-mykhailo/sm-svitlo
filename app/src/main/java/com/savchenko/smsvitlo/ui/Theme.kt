package com.savchenko.smsvitlo.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.savchenko.smsvitlo.data.Settings
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Налаштування вигляду, що живуть у Compose-стані (щоб зміни застосовувались одразу). */
class Look(st: Settings) {
    var theme by mutableStateOf(st.theme)
    var dynamicColor by mutableStateOf(st.dynamicColor)
    var accent by mutableStateOf(st.accent)
    var amoled by mutableStateOf(st.amoled)
    var snow by mutableStateOf(st.snow)
    var snowAmount by mutableStateOf(st.snowAmount)
    var tileStyle by mutableStateOf(st.tileStyle)
    var columns by mutableStateOf(st.columns)
    var cardTimeline by mutableStateOf(st.cardTimeline)
    var scale by mutableStateOf(st.scale)
}

val LocalLook = compositionLocalOf<Look?> { null }
val LocalDark = compositionLocalOf { false }

data class Accent(val name: String, val light: Color, val dark: Color, val lightContainer: Color, val darkContainer: Color)

val ACCENTS = listOf(
    Accent("Індиго", Color(0xFF344E8F), Color(0xFFB3C5FF), Color(0xFFDCE2F9), Color(0xFF2A3A63)),
    Accent("Бурштин", Color(0xFF8A5A00), Color(0xFFFFB952), Color(0xFFFFDDB3), Color(0xFF5A3E05)),
    Accent("Смарагд", Color(0xFF00695C), Color(0xFF7FD8C6), Color(0xFFB9F0E4), Color(0xFF0F4A42)),
    Accent("Лаванда", Color(0xFF6750A4), Color(0xFFD0BCFF), Color(0xFFEADDFF), Color(0xFF4A3B78)),
    Accent("Корал", Color(0xFFA2393A), Color(0xFFFFB3AE), Color(0xFFFFDAD7), Color(0xFF6E2627)),
    Accent("Графіт", Color(0xFF3A3A3F), Color(0xFFE3E3E8), Color(0xFFE2E2E6), Color(0xFF3A3A3F)),
)

private fun scheme(a: Accent, dark: Boolean, amoled: Boolean): ColorScheme =
    if (!dark) lightColorScheme(
        primary = a.light, onPrimary = Color.White,
        primaryContainer = a.lightContainer, onPrimaryContainer = a.light,
        secondaryContainer = a.lightContainer, onSecondaryContainer = a.light,
        background = Color(0xFFF6F7FB), surface = Color(0xFFF6F7FB),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
        surfaceContainer = Color(0xFFF1F2F6), surfaceContainerHigh = Color(0xFFEBECF1),
        surfaceContainerHighest = Color(0xFFE4E5EB),
        outlineVariant = Color(0xFFDDDEE5),
    ) else darkColorScheme(
        primary = a.dark, onPrimary = Color(0xFF10131A),
        primaryContainer = a.darkContainer, onPrimaryContainer = a.dark,
        secondaryContainer = a.darkContainer, onSecondaryContainer = a.dark,
        background = if (amoled) Color.Black else Color(0xFF111318),
        surface = if (amoled) Color.Black else Color(0xFF111318),
        surfaceContainerLowest = if (amoled) Color(0xFF0B0B0D) else Color(0xFF1A1C22),
        surfaceContainerLow = if (amoled) Color(0xFF0F0F12) else Color(0xFF1C1E25),
        surfaceContainer = if (amoled) Color(0xFF161619) else Color(0xFF22252C),
        surfaceContainerHigh = if (amoled) Color(0xFF1D1D21) else Color(0xFF2A2D35),
        surfaceContainerHighest = if (amoled) Color(0xFF26262B) else Color(0xFF33363F),
        outlineVariant = if (amoled) Color(0xFF2A2A2F) else Color(0xFF3A3D46),
    )

@Composable
fun AppTheme(look: Look? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val l = look ?: remember { Look(Settings(ctx)) }
    val dark = when (l.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    var cs = if (l.dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else scheme(ACCENTS[l.accent.coerceIn(0, ACCENTS.lastIndex)], dark, l.amoled)
    if (dark && l.amoled && l.dynamicColor) cs = cs.copy(background = Color.Black, surface = Color.Black)
    val base = androidx.compose.ui.platform.LocalDensity.current
    val k = when (l.scale) { 0 -> 0.86f; 2 -> 1.08f; else -> 0.95f }
    val dens = androidx.compose.ui.unit.Density(base.density * k, base.fontScale)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalLook provides l, LocalDark provides dark, androidx.compose.ui.platform.LocalDensity provides dens,
    ) {
        MaterialTheme(colorScheme = cs, content = content)
    }
}

// ------------------- сніг -------------------

private class Flake(val x0: Float, val y0: Float, val r: Float, val speed: Float, val amp: Float, val freq: Float, val phase: Float, val alpha: Float)

@Composable
fun Snow(amount: Int, dark: Boolean, modifier: Modifier = Modifier) {
    val n = when (amount) { 0 -> 35; 1 -> 75; else -> 150 }
    val flakes = remember(n) {
        val r = Random(42)
        List(n) {
            val depth = r.nextFloat() // далекі - дрібні й повільні
            Flake(
                x0 = r.nextFloat(), y0 = r.nextFloat(),
                r = 1.2f + depth * 3.2f,
                speed = 0.035f + depth * 0.09f,
                amp = 0.005f + r.nextFloat() * 0.02f,
                freq = 0.3f + r.nextFloat() * 0.8f,
                phase = r.nextFloat() * 2 * PI.toFloat(),
                alpha = 0.35f + depth * 0.6f,
            )
        }
    }
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = it - start }
    }
    val base = if (dark) Color.White else Color(0xFF9DB0D6)
    Canvas(modifier.fillMaxSize()) {
        val t = time / 1e9f
        val wind = sin(t * 0.15f) * 0.015f
        for (f in flakes) {
            val y = ((f.y0 + t * f.speed) % 1.08f) - 0.04f
            val x = ((f.x0 + sin(t * f.freq + f.phase) * f.amp + wind * f.r + 1f) % 1f)
            drawCircle(base.copy(alpha = f.alpha * if (dark) 0.85f else 0.9f), f.r * density, Offset(x * size.width, y * size.height))
        }
    }
}

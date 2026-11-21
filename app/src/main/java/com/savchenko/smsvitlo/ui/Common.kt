package com.savchenko.smsvitlo.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.savchenko.smsvitlo.data.Half
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.widget.WidgetRenderer

@Composable
fun ScheduleBar(halves: List<Half>, nowIdx: Int, nowFrac: Float, possible: Boolean, modifier: Modifier = Modifier) {
    val d = LocalDensity.current.density
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest.toArgb()
    val dark = isSystemInDarkTheme()
    val base = WidgetRenderer.palette(Settings.STYLE_COLOR, 100)
    val p = WidgetRenderer.Palette(
        bg = bg, text = base.text, sub = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
        on = base.on, off = base.off, maybe = base.maybe,
        unknown = MaterialTheme.colorScheme.outlineVariant.toArgb(),
        marker = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK,
        hatch = false, outline = null,
    )
    Canvas(modifier.fillMaxWidth().height(18.dp)) {
        drawIntoCanvas {
            WidgetRenderer.drawBar(it.nativeCanvas, halves, p, d, 0f, 2 * d, size.width, size.height - 4 * d, nowIdx, nowFrac, possible)
        }
    }
}

@Composable
fun HourLabels() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val d = LocalDensity.current.density
    Canvas(Modifier.fillMaxWidth().height(13.dp)) {
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = c; textSize = 10 * d }
        for (h in 0..24 step 3) {
            p.textAlign = when (h) {
                0 -> android.graphics.Paint.Align.LEFT; 24 -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            drawIntoCanvas { it.nativeCanvas.drawText(h.toString(), size.width * h / 24f, 10 * d, p) }
        }
    }
}

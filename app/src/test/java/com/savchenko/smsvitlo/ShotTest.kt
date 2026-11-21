package com.savchenko.smsvitlo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.savchenko.smsvitlo.data.Parser
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.ui.*
import org.junit.Rule
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import org.junit.Test
import java.io.File

class ShotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 100.0)
    private val s = Parser.parse(File("src/test/dnipro.json").readText(), "mirror", System.currentTimeMillis())
    private val e = Settings.Entry("k", "GPV6.2", "дім", true)

    private fun shot(theme: Int, style: Int, detail: Boolean) {
        val st = Settings(paparazzi.context)
        val look = Look(st).apply { this.theme = theme; tileStyle = style; snow = true }
        paparazzi.snapshot {
            AppTheme(look) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (detail) DetailScreen(s, e, 0, {}, {}, {})
                    else HomeScreen(s, listOf(e, Settings.Entry("k2", "GPV1.1", "дача", false)), 0, {}, {}, {}, {})
                    Snow(1, LocalDark.current)
                }
            }
        }
    }
    @Test fun homeLight() = shot(1, 0, false)
    @Test fun homeDark() = shot(2, 0, false)
    @Test fun detailLight() = shot(1, 0, true)
    @Test fun detailDarkColor() = shot(2, 1, true)
}

class WidgetShot {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 100.0)
    private val s = Parser.parse(File("src/test/dnipro.json").readText(), "mirror", System.currentTimeMillis())
    @Test fun widgets() {
        val c = paparazzi.context
        paparazzi.snapshot {
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF55657F))) {
                for (style in 0..2) {
                    val b = com.savchenko.smsvitlo.widget.WidgetRenderer.render(c, s,
                        Settings.WidgetCfg(if (style == 1) listOf("GPV1.1") else listOf("GPV6.2", "GPV1.1"), style, 85, false), if (style == 1) 165 else 340, if (style == 1) 70 else 150)
                    androidx.compose.foundation.Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().padding(10.dp))
                }
            }
        }
    }
}

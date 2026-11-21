package com.savchenko.smsvitlo.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.savchenko.smsvitlo.data.Repository
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.ui.AppTheme
import com.savchenko.smsvitlo.ui.Chips
import com.savchenko.smsvitlo.ui.Section
import com.savchenko.smsvitlo.ui.Toggle
import com.savchenko.smsvitlo.work.Alarms

class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val st = Settings(this)
        val snap = Repository.current(this)

        setContent {
            AppTheme {
                var cfg by remember { mutableStateOf(st.widget(id)) }
                val all = snap.allGroups.ifEmpty { (1..6).flatMap { listOf("GPV$it.1", "GPV$it.2") } }
                Scaffold { pad ->
                    Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                        Text("Віджет SM Світло", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        // прев'ю на "шпалерах"
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                .background(Brush.linearGradient(listOf(Color(0xFF3A4A6B), Color(0xFF8A6A5A))), MaterialTheme.shapes.large)
                                .padding(14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val h = 50 + 52 * cfg.groups.size.coerceAtLeast(1) + if (cfg.tomorrow) 18 * cfg.groups.size else 0
                            val bmp = remember(cfg) { WidgetRenderer.render(this@WidgetConfigActivity, snap, cfg, 320, h) }
                            Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth())
                        }
                        Section("Стиль")
                        Chips(listOf(Settings.STYLE_COLOR, Settings.STYLE_BW_DARK, Settings.STYLE_BW_LIGHT), cfg.style,
                            { when (it) { Settings.STYLE_COLOR -> "Кольоровий"; Settings.STYLE_BW_DARK -> "Ч/Б темний"; else -> "Ч/Б світлий" } }) {
                            cfg = cfg.copy(style = it)
                        }
                        Text("Непрозорість фону: ${cfg.alpha}%", Modifier.padding(top = 8.dp))
                        Slider(cfg.alpha.toFloat(), { cfg = cfg.copy(alpha = it.toInt()) }, valueRange = 0f..100f)
                        Toggle("Показувати завтра (коли опубліковано)", cfg.tomorrow) { cfg = cfg.copy(tomorrow = it) }
                        Section("Групи на віджеті")
                        all.forEach { g ->
                            Row(Modifier.fillMaxWidth().clickable {
                                cfg = cfg.copy(groups = if (g in cfg.groups) cfg.groups - g else cfg.groups + g)
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(g in cfg.groups, { cfg = cfg.copy(groups = if (g in cfg.groups) cfg.groups - g else cfg.groups + g) })
                                Text(snap.groupName(g))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            st.saveWidget(id, cfg)
                            if (st.groups.isEmpty()) st.groups = cfg.groups
                            ScheduleWidget.update(this@WidgetConfigActivity, AppWidgetManager.getInstance(this@WidgetConfigActivity), id)
                            Alarms.scheduleTick(this@WidgetConfigActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        }, Modifier.fillMaxWidth(), enabled = cfg.groups.isNotEmpty()) { Text("Готово") }
                    }
                }
            }
        }
    }
}

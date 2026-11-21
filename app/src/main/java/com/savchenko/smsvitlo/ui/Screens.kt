package com.savchenko.smsvitlo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.savchenko.smsvitlo.data.Half
import com.savchenko.smsvitlo.data.Parser
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.data.Snapshot
import com.savchenko.smsvitlo.logic.Timeline
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDate
import java.util.Locale

val BOLT_YELLOW = Color(0xFFF5B800)
val GREEN = Color(0xFF2FB457)
val RED = Color(0xFFE5484D)

// ---------- спільне ----------

@Composable
fun Chip(text: String, icon: ImageVector?, bg: Color, fg: Color, iconTint: Color = fg) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = iconTint); Spacer(Modifier.width(8.dp)) }
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusChip(s: Snapshot, id: String) {
    val st = Timeline.status(s, id) ?: return Chip("Немає даних", Icons.Outlined.HelpOutline,
        MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
    val dark = LocalDark.current
    val (bg, fg, icon, tint) = when (st.current) {
        Half.ON -> Quad(if (dark) Color(0xFF1E3326) else Color(0xFFE6F6EA), if (dark) Color(0xFFB8EBC6) else Color(0xFF1C6B36), Icons.Filled.Bolt, BOLT_YELLOW)
        Half.OFF -> Quad(if (dark) Color(0xFF3A1F22) else Color(0xFFFDE8E8), if (dark) Color(0xFFFFC2C2) else Color(0xFF9A2A2D), Icons.Outlined.FlashOff, if (dark) Color(0xFFFFC2C2) else Color(0xFF9A2A2D))
        Half.MAYBE -> Quad(if (dark) Color(0xFF3A3118) else Color(0xFFFFF4D6), if (dark) Color(0xFFFFE08A) else Color(0xFF7A5A00), Icons.Outlined.FlashOff, if (dark) Color(0xFFFFE08A) else Color(0xFF7A5A00))
        Half.UNKNOWN -> Quad(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Outlined.HelpOutline, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val change = st.changeAt?.let { at ->
        " до ${Timeline.hm(at)}" + if (at.toLocalDate() != Timeline.now().toLocalDate()) " завтра" else ""
    } ?: ""
    Chip(Timeline.stateText(st.current) + change, icon, bg, fg, tint)
}

private data class Quad(val a: Color, val b: Color, val c: ImageVector, val d: Color)

@Composable
fun EmergencyChip() {
    val dark = LocalDark.current
    Chip("Екстрені відключення", Icons.Outlined.FlashOff,
        if (dark) Color(0xFF3A1F22) else Color(0xFFFDE8E8), MaterialTheme.colorScheme.onSurface)
}

// ---------- головний екран ----------

@Composable
fun HomeScreen(
    s: Snapshot, entries: List<Settings.Entry>, tick: Long,
    onOpen: (Settings.Entry) -> Unit, onEdit: (Settings.Entry) -> Unit, onDelete: (Settings.Entry) -> Unit,
    onAdd: () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") tick
    val look = LocalLook.current!!
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (s.emergency || s.attention != null) item { AttentionCard(s) }
            if (entries.isEmpty()) item { EmptyState() }
            items(entries, key = { it.key }) { e ->
                EntryCard(s, e, look.cardTimeline, { onOpen(e) }, { onEdit(e) }, { onDelete(e) })
            }
        }
        Button(
            onClick = onAdd,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp).navigationBarsPadding().height(52.dp),
            shape = RoundedCornerShape(26.dp),
        ) { Text("Додати графік", fontSize = 17.sp) }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Bolt, null, Modifier.size(56.dp), tint = BOLT_YELLOW)
        Spacer(Modifier.height(12.dp))
        Text("Додайте свою чергу ДТЕК", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Наприклад: Черга 1.1 - \"дім\"", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AttentionCard(s: Snapshot) {
    var open by remember { mutableStateOf(false) }
    val orange = if (LocalDark.current) Color(0xFFFF9B6B) else Color(0xFFE2622B)
    Card(
        Modifier.fillMaxWidth().clickable { open = !open },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = if (open) Alignment.Top else Alignment.CenterVertically) {
            Icon(Icons.Outlined.FlashOff, null, Modifier.size(24.dp), tint = orange)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    if (s.emergency) "Діють екстрені відключення" else "Повідомлення ДТЕК",
                    color = orange, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                )
                if (open && s.attention != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(s.attention, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun EntryCard(s: Snapshot, e: Settings.Entry, timeline: Boolean, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)) {
            CardHeader(s, e, onEdit, onDelete)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(s, e.id)
            }
            val st = Timeline.status(s, e.id)
            if (st?.changeAt != null) {
                val left = Timeline.inText(Duration.between(Timeline.now(), st.changeAt))
                val what = when (st.next) { Half.OFF -> "Вимкнення"; Half.ON -> "Увімкнення"; Half.MAYBE -> "Можливе вимкнення"; else -> "Зміна" }
                Text("$what через $left", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
            if (timeline) {
                val now = Timeline.now()
                Timeline.day(s, e.id, now.toLocalDate())?.let { v ->
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.padding(end = 10.dp)) {
                        ScheduleBar(v.halves, Timeline.slotIndex(now), now.minute % 30 / 30f, !v.exact)
                        HourLabels()
                    }
                }
            }
        }
    }
}

@Composable
fun CardHeader(s: Snapshot, e: Settings.Entry, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (e.label.isNotBlank())
                Text("ДТЕК  •  ${s.groupName(e.id)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            Text(e.label.ifBlank { s.groupName(e.id) }, color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp, fontWeight = FontWeight.Medium)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Змінити", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Видалити", tint = RED) }
    }
}

@Composable
fun Freshness(s: Snapshot) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    val src = if (s.source == "dtek") "ДТЕК напряму" else "дзеркало ДТЕК"
    val checked = if (s.fetchedAtMillis > 0) fmt.format(s.fetchedAtMillis) else "—"
    Text(
        "Графік ДТЕК від ${s.factUpdate ?: "—"}\nПеревірено $checked · $src",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
}

// ---------- екран графіку ----------

@Composable
fun DetailScreen(
    s: Snapshot, e: Settings.Entry, tick: Long,
    onEdit: () -> Unit, onDelete: () -> Unit, onNotify: (Boolean) -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION") tick
    val look = LocalLook.current!!
    var tomorrow by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Int?>(null) }
    val now = Timeline.now()
    val date = if (tomorrow) now.toLocalDate().plusDays(1) else now.toLocalDate()
    val v = Timeline.day(s, e.id, date)

    Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp, 4.dp, 14.dp, 24.dp)) {
        if (s.emergency) { AttentionCard(s); Spacer(Modifier.height(10.dp)) }
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)) {
                CardHeader(s, e, onEdit, onDelete)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusChip(s, e.id) }
                Column(Modifier.padding(end = 10.dp)) {
                    Spacer(Modifier.height(14.dp))
                    DaySwitch(tomorrow) { tomorrow = it; picked = null }
                    Spacer(Modifier.height(14.dp))

                    when {
                        v == null -> Banner("Графіку ще немає")
                        !v.exact -> Banner("Графік ще не сформовано")
                    }
                    if (v != null) {
                        if (!v.exact) Spacer(Modifier.height(12.dp))
                        HourGrid(v.halves, if (tomorrow) -1 else Timeline.slotIndex(now), look.columns, look.tileStyle, !v.exact, picked) {
                            picked = if (picked == it) null else it
                        }
                        Spacer(Modifier.height(16.dp))
                        Legend(look.tileStyle)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Сповіщення", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Switch(e.notify, onNotify)
                    }
                }
            }
        }
    }
}

@Composable
private fun Banner(t: String) {
    val dark = LocalDark.current
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (dark) Color(0xFF3A3118) else Color(0xFFFFF7E0)).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(t, color = if (dark) Color(0xFFFFD36B) else Color(0xFFB38600), fontSize = 15.sp, textAlign = TextAlign.Center) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySwitch(tomorrow: Boolean, on: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(!tomorrow, { on(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Сьогодні", fontSize = 16.sp) }
        SegmentedButton(tomorrow, { on(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Завтра", fontSize = 16.sp) }
    }
}

/** Кольори клітинок для стилю. */
@Composable
fun tileColor(h: Half, style: Int): Color {
    val cs = MaterialTheme.colorScheme
    val dark = LocalDark.current
    return if (style == 1) when (h) {
        Half.ON -> if (dark) Color(0xFF1E3326) else Color(0xFFE3F5E8)
        Half.OFF -> if (dark) Color(0xFF4A2326) else Color(0xFFFBD9DA)
        Half.MAYBE -> if (dark) Color(0xFF3A3118) else Color(0xFFFFF0C7)
        Half.UNKNOWN -> cs.surfaceContainer
    } else when (h) {
        Half.ON -> cs.surfaceContainerLowest
        Half.OFF -> cs.surfaceContainerHighest
        Half.MAYBE -> cs.surfaceContainer
        Half.UNKNOWN -> cs.surfaceContainer
    }
}

@Composable
fun HourGrid(halves: List<Half>, nowIdx: Int, columns: Int, style: Int, possible: Boolean, picked: Int?, onPick: (Int) -> Unit) {
    val rows = (24 + columns - 1) / columns
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (r in 0 until rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (columns >= 6) 6.dp else 8.dp)) {
                for (c in 0 until columns) {
                    val h = r * columns + c
                    if (h < 24) HourTile(
                        h, halves[h * 2], halves[h * 2 + 1],
                        isNow = nowIdx >= 0 && nowIdx / 2 == h, isPast = nowIdx >= 0 && h < nowIdx / 2,
                        style = style, compact = columns >= 6, picked = false, possible = possible,
                        modifier = Modifier.weight(1f),
                    ) {} else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun HourTile(
    hour: Int, a: Half, b: Half, isNow: Boolean, isPast: Boolean, style: Int, compact: Boolean,
    picked: Boolean, possible: Boolean, modifier: Modifier, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ca = tileColor(a, style); val cb = tileColor(b, style)
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    val border = when {
        isNow -> BorderStroke(2.5.dp, cs.primary)
        picked -> BorderStroke(2.dp, cs.onSurfaceVariant)
        else -> BorderStroke(1.dp, cs.outlineVariant)
    }
    val stripe = if (LocalDark.current) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    Box(
        modifier.height(if (compact) 42.dp else 50.dp).clip(shape).border(border, shape)
            .alpha(if (isPast) 0.55f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(ca, size = size.copy(width = size.width / 2))
            drawRect(cb, topLeft = Offset(size.width / 2, 0f), size = size.copy(width = size.width / 2))
            // штрихування "можливих"
            fun hatch(x0: Float, x1: Float) {
                var x = x0 - size.height
                while (x < x1) {
                    val sx = maxOf(x, x0); val ex = minOf(x + size.height, x1)
                    if (ex > sx) drawLine(stripe, Offset(sx, size.height - (sx - x)), Offset(ex, size.height - (ex - x)), 2.dp.toPx())
                    x += 7.dp.toPx()
                }
            }
            if (a == Half.MAYBE) hatch(0f, size.width / 2)
            if (b == Half.MAYBE) hatch(size.width / 2, size.width)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a == b && !compact) {
                when (a) {
                    Half.ON -> Icon(Icons.Filled.Bolt, null, Modifier.size(17.dp), tint = BOLT_YELLOW)
                    Half.OFF -> Icon(Icons.Outlined.FlashOff, null, Modifier.size(17.dp), tint = cs.onSurfaceVariant)
                    Half.MAYBE -> Icon(Icons.Outlined.FlashOff, null, Modifier.size(17.dp).alpha(0.55f), tint = cs.onSurfaceVariant)
                    else -> {}
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "%02d:00".format(hour), fontSize = if (compact) 13.sp else 15.sp,
                color = if (a == Half.OFF && b == Half.OFF) cs.onSurfaceVariant else cs.onSurface,
                fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun HourInfo(halves: List<Half>, h: Int) {
    fun word(x: Half) = when (x) { Half.ON -> "світло є"; Half.OFF -> "світла немає"; Half.MAYBE -> "можливе відключення"; else -> "немає даних" }
    val a = halves[h * 2]; val b = halves[h * 2 + 1]
    val t = if (a == b) "%02d:00–%02d:00: ${word(a)}".format(h, h + 1)
    else "%02d:00–%02d:30: ${word(a)}\n%02d:30–%02d:00: ${word(b)}".format(h, h, h, h + 1)
    Text(t, Modifier.padding(top = 12.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Legend(style: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        LegendItem(tileColor(Half.ON, style), Icons.Filled.Bolt, BOLT_YELLOW, "Світло є", true)
        LegendItem(tileColor(Half.OFF, style), Icons.Outlined.FlashOff, MaterialTheme.colorScheme.onSurfaceVariant, "Немає", true)
        LegendItem(tileColor(Half.MAYBE, style), Icons.Outlined.FlashOff, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), "Можливо", true)
    }
}

@Composable
private fun RowScope.LegendItem(bg: Color, icon: ImageVector, tint: Color, text: String, outline: Boolean) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                .then(if (outline) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(16.dp), tint = tint) }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, lineHeight = 15.sp)
    }
}

@Composable
private fun Summary(h: List<Half>, exact: Boolean) {
    val iv = Timeline.intervals(h)
    val t = if (iv.isEmpty()) "Відключень немає"
    else "Без світла: " + iv.joinToString(", ") { (if (it.state == Half.MAYBE) "можл. " else "") + it.label() } +
        "\nВсього ${com.savchenko.smsvitlo.work.Notifier.hrs(Timeline.totalOffHours(h))} год" + if (!exact) " (прогноз)" else ""
    Text(t, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------- додавання / редагування ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntrySheet(s: Snapshot, initial: Settings.Entry?, onDismiss: () -> Unit, onSave: (id: String, label: String) -> Unit) {
    var id by remember { mutableStateOf(initial?.id ?: "GPV1.1") }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var showCek by remember { mutableStateOf(initial?.id?.let { Parser.sortKey(it).first == 1 } ?: false) }
    val all = s.allGroups.ifEmpty { (1..6).flatMap { listOf("GPV$it.1", "GPV$it.2") } }
    val main = all.filter { Parser.sortKey(it).first == 0 }
    val cek = all.filter { Parser.sortKey(it).first == 1 }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(if (initial == null) "Новий графік" else "Редагувати", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(label, { label = it.take(30) }, Modifier.fillMaxWidth(),
                label = { Text("Назва (напр. дім, дача, школа)") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(16.dp))
            Text("Черга ДТЕК", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                main.forEach { g -> GroupPill(Parser.shortName(g), g == id) { id = g } }
            }
            if (cek.isNotEmpty()) {
                TextButton(onClick = { showCek = !showCek }) { Text(if (showCek) "Сховати ЦЕК" else "Групи ЦЕК…") }
                if (showCek) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cek.forEach { g -> GroupPill(Parser.shortName(g), g == id) { id = g } }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(onClick = { onSave(id, label.trim()) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp)) {
                Text("Зберегти", fontSize = 17.sp)
            }
        }
    }
}

@Composable
private fun GroupPill(t: String, sel: Boolean, on: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (sel) cs.primary else cs.surfaceContainer, label = "pill")
    Box(
        Modifier.size(width = 66.dp, height = 48.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = on),
        contentAlignment = Alignment.Center,
    ) { Text(t, color = if (sel) cs.onPrimary else cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
}

// ---------- налаштування ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(st: Settings, extra: @Composable ColumnScope.() -> Unit) {
    val look = LocalLook.current!!
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 32.dp)) {
        Group("Вигляд") {
            Label("Тема")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Системна", "Світла", "Темна").forEachIndexed { i, t ->
                    SegmentedButton(look.theme == i, { look.theme = i; st.theme = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 31)
                Toggle("Кольори Material You (зі шпалер)", look.dynamicColor) { look.dynamicColor = it; st.dynamicColor = it }
            if (!look.dynamicColor) {
                Label("Акцент")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ACCENTS.forEachIndexed { i, a ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { look.accent = i; st.accent = i }) {
                            Box(
                                Modifier.size(42.dp).clip(CircleShape).background(if (LocalDark.current) a.dark else a.light)
                                    .border(if (look.accent == i) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
                            )
                            Text(a.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Toggle("AMOLED чорний (темна тема)", look.amoled) { look.amoled = it; st.amoled = it }
            Label("Масштаб інтерфейсу")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Дрібний", "Звичайний", "Великий").forEachIndexed { i, t ->
                    SegmentedButton(look.scale == i, { look.scale = i; st.scale = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) }
                }
            }
        }
        Group("Графік") {
            Label("Клітинки годин")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Мінімал", "Кольорові").forEachIndexed { i, t ->
                    SegmentedButton(look.tileStyle == i, { look.tileStyle = i; st.tileStyle = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) }
                }
            }
            Label("Колонок у сітці")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(3, 4, 6).forEachIndexed { i, n ->
                    SegmentedButton(look.columns == n, { look.columns = n; st.columns = n }, SegmentedButtonDefaults.itemShape(i, 3)) { Text("$n") }
                }
            }
            Toggle("Смуга 24 год на картках", look.cardTimeline) { look.cardTimeline = it; st.cardTimeline = it }
        }
        Group("Сніг ❄") {
            Toggle("Сніжинки", look.snow) { look.snow = it; st.snow = it }
            if (look.snow) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Легкий", "Середній", "Завірюха").forEachIndexed { i, t ->
                        SegmentedButton(look.snowAmount == i, { look.snowAmount = i; st.snowAmount = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) }
                    }
                }
            }
        }
        extra()
        Spacer(Modifier.height(16.dp))
        Freshness(com.savchenko.smsvitlo.data.Repository.current(androidx.compose.ui.platform.LocalContext.current))
    }
}

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp))
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
}

@Composable
fun Label(t: String) = Text(t, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

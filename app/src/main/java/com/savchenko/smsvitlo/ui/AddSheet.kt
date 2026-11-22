package com.savchenko.smsvitlo.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.savchenko.smsvitlo.data.Address
import com.savchenko.smsvitlo.data.House
import com.savchenko.smsvitlo.data.Lookup
import com.savchenko.smsvitlo.data.Parser
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.data.Snapshot
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Що зберегти: група + (необов'язково) адреса, з якої її взяли. */
data class SheetResult(val id: String, val label: String, val lookup: Lookup?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntrySheet(s: Snapshot, initial: Settings.Entry?, onDismiss: () -> Unit, onSave: (SheetResult) -> Unit) {
    var tab by remember { mutableIntStateOf(if (initial?.approx == true) 2 else if (initial?.hasAddress == true) 1 else 0) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var manualId by remember { mutableStateOf(initial?.id ?: "GPV1.1") }
    var lookup by remember { mutableStateOf<Lookup?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(if (initial == null) "Новий графік" else "Редагувати", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Черга", "Адреса", "GPS").forEachIndexed { i, t ->
                    SegmentedButton(tab == i, { tab = i; lookup = null }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) }
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(label, { label = it.take(30) }, Modifier.fillMaxWidth(),
                label = { Text("Назва (дім, дача, школа)") }, singleLine = true, shape = RoundedCornerShape(16.dp))
            Spacer(Modifier.height(14.dp))

            when (tab) {
                0 -> ManualTab(s, manualId) { manualId = it }
                1 -> AddressTab(initial) { lookup = it }
                else -> GpsTab { lookup = it }
            }
            lookup?.let { LookupCard(it) }

            Spacer(Modifier.height(18.dp))
            val canSave = tab == 0 || lookup != null
            Button(
                onClick = {
                    val l = if (tab == 0) null else lookup
                    onSave(SheetResult(l?.group ?: manualId, label.trim(), l))
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp),
            ) { Text("Зберегти", fontSize = 17.sp) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualTab(s: Snapshot, id: String, onPick: (String) -> Unit) {
    var showCek by remember { mutableStateOf(Parser.sortKey(id).first == 1) }
    val all = s.allGroups.ifEmpty { (1..6).flatMap { listOf("GPV$it.1", "GPV$it.2") } }
    val main = all.filter { Parser.sortKey(it).first == 0 }
    val cek = all.filter { Parser.sortKey(it).first == 1 }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        main.forEach { g -> GroupPill(Parser.shortName(g), g == id) { onPick(g) } }
    }
    if (cek.isNotEmpty()) {
        TextButton(onClick = { showCek = !showCek }) { Text(if (showCek) "Сховати ЦЕК" else "Групи ЦЕК…") }
        if (showCek) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cek.forEach { g -> GroupPill(Parser.shortName(g), g == id) { onPick(g) } }
        }
    }
}

/** Поле з підказками зі списку. */
@Composable
private fun Picker(title: String, value: String?, options: List<String>, enabled: Boolean, onPick: (String) -> Unit, onClear: () -> Unit) {
    var q by remember(value) { mutableStateOf("") }
    if (value != null) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClear).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, fontSize = 16.sp)
            }
            Icon(Icons.Outlined.Close, "Змінити", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(title) }, singleLine = true, shape = RoundedCornerShape(16.dp))
    if (enabled) {
        val list = remember(q, options) { Address.filter(options, q, 12) }
        Column(Modifier.padding(vertical = 4.dp)) {
            list.forEach { o ->
                Text(o, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(o) }
                    .padding(horizontal = 12.dp, vertical = 9.dp), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun AddressTab(initial: Settings.Entry?, onResult: (Lookup?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var streets by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var city by remember { mutableStateOf(initial?.city?.takeIf { initial.approx.not() }) }
    var street by remember { mutableStateOf(initial?.street?.takeIf { initial.approx.not() }) }
    var houses by remember { mutableStateOf<List<House>?>(null) }
    var house by remember { mutableStateOf<House?>(null) }

    LaunchedEffect(Unit) {
        streets = Address.streets(ctx)
        loading = false
        if (streets == null) error = "Не вдалося відкрити сайт ДТЕК. Перевір інтернет і спробуй ще раз."
    }
    LaunchedEffect(city, street) {
        houses = null; house = null; onResult(null)
        if (city != null && street != null) {
            loading = true; error = null
            houses = Address.houses(ctx, city!!, street!!)
            loading = false
            if (houses == null) error = "ДТЕК не відповів. Спробуй ще раз."
            else if (houses!!.isEmpty()) error = "ДТЕК не знає будинків на цій вулиці"
            initial?.house?.let { h -> houses?.firstOrNull { it.num == h }?.let { house = it } }
        }
    }
    LaunchedEffect(house) {
        val h = house
        onResult(if (h != null && h.groups.isNotEmpty()) Lookup(city!!, street!!, h.num, h.groups.first(), h, approx = false,
            otherGroups = h.groups.drop(1)) else null)
    }

    val st = streets
    Picker("Населений пункт", city, st?.let { Address.cities(it) } ?: emptyList(), st != null, { city = it; street = null }) {
        city = null; street = null
    }
    if (city != null) {
        Spacer(Modifier.height(8.dp))
        Picker("Вулиця", street, st?.let { Address.streetsOf(it, city!!) } ?: emptyList(), true, { street = it }) { street = null }
    }
    if (houses != null && house == null && houses!!.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Picker("Будинок", null, houses!!.map { it.num }, true, { n -> house = houses!!.first { it.num == n } }) {}
    } else if (house != null) {
        Spacer(Modifier.height(8.dp))
        Picker("Будинок", house!!.num, emptyList(), true, {}) { house = null }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = {
            scope.launch {
                loading = true; error = null
                streets = Address.streets(ctx, force = true); loading = false
                if (streets == null) error = "Сайт ДТЕК не відповідає"
            }
        }) { Text("Спробувати ще") }
    }
}

@Composable
private fun GpsTab(onResult: (Lookup?) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("idle") } // idle | loc | dtek | done | err
    var error by remember { mutableStateOf<String?>(null) }

    fun search() {
        scope.launch {
            onResult(null); error = null
            state = "loc"
            val l = Address.location(ctx)
            if (l == null) { state = "err"; error = "Не вдалося визначити місце. Увімкни геолокацію і спробуй ще."; return@launch }
            state = "dtek"
            try {
                onResult(Address.nearest(ctx, l.latitude, l.longitude)); state = "done"
            } catch (e: Address.LookupError) { state = "err"; error = e.message }
            catch (e: Exception) { state = "err"; error = "Помилка пошуку: ${e.message}" }
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) search() else { state = "err"; error = "Без доступу до геолокації пошук не працює" }
    }

    Text(
        "Знайде найближчу адресу, яка є в базі ДТЕК, і покаже її групу. Підходить, якщо твого СТ чи вулиці немає на сайті ДТЕК.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))
    FilledTonalButton(
        onClick = { perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
        enabled = state != "loc" && state != "dtek", modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Outlined.MyLocation, null); Spacer(Modifier.width(8.dp))
        Text(if (state == "done") "Шукати ще раз" else "Знайти за GPS")
    }
    when (state) {
        "loc" -> Progress("Визначаю місце…")
        "dtek" -> Progress("Шукаю найближчу адресу ДТЕК…")
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun Progress(t: String) {
    Column(Modifier.padding(top = 10.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(t, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Картка результату: група, адреса, статус. */
@Composable
fun LookupCard(l: Lookup) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cs.primaryContainer).padding(16.dp),
    ) {
        Text(if (l.approx) "Приблизно ваша група" else "Ваша група", fontSize = 13.sp, color = cs.onPrimaryContainer)
        Text(Parser.prettyName(l.group), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
        Spacer(Modifier.height(4.dp))
        Text(
            (if (l.approx) "За найближчою адресою ДТЕК: " else "") + "${l.city}, ${l.street}, ${l.house}" +
                (l.distanceKm?.let { " (~${"%.1f".format(it).replace('.', ',')} км)" } ?: ""),
            fontSize = 14.sp, color = cs.onPrimaryContainer,
        )
        if (l.otherGroups.isNotEmpty()) Text(
            (if (l.approx) "На цій вулиці є й інші групи: " else "Також: ") + l.otherGroups.joinToString { Parser.shortName(it) },
            fontSize = 13.sp, color = cs.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp),
        )
        if (l.approx) Text(
            "Не 100%: перевір по факту, чи збігається.",
            fontSize = 12.sp, color = cs.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.padding(top = 6.dp),
        )
        l.status?.takeIf { it.active }?.let { h ->
            Spacer(Modifier.height(10.dp))
            HouseChip(h)
        }
    }
}

@Composable
fun HouseChip(h: House) {
    val dark = LocalDark.current
    Chip(
        h.shortType + (h.untilText?.let { " · $it" } ?: ""), Icons.Outlined.FlashOff,
        if (dark) Color(0xFF3A1F22) else Color(0xFFFDE8E8), if (dark) Color(0xFFFFC2C2) else Color(0xFF9A2A2D),
    )
}

package com.savchenko.smsvitlo.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.savchenko.smsvitlo.data.Repository
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.data.Snapshot
import com.savchenko.smsvitlo.widget.ScheduleWidget
import com.savchenko.smsvitlo.work.Alarms
import com.savchenko.smsvitlo.work.LiveService
import com.savchenko.smsvitlo.work.Notifier
import com.savchenko.smsvitlo.work.Sync
import com.savchenko.smsvitlo.work.SyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val snapshot = mutableStateOf(Snapshot.EMPTY)
    private val loading = mutableStateOf(false)
    private val message = mutableStateOf<String?>(null)
    private val tick = mutableLongStateOf(0L)
    private lateinit var st: Settings
    private lateinit var look: Look
    private val entries = mutableStateOf(emptyList<Settings.Entry>())

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        st = Settings(this)
        look = Look(st)
        entries.value = st.entries
        Notifier.createChannels(this)
        SyncWorker.schedule(this)
        Alarms.scheduleTick(this)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (st.liveMode && !LiveService.running) LiveService.start(this)
        snapshot.value = Repository.current(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                refresh(direct = st.directDtek, silent = true)
                var n = 0
                while (true) {
                    delay(30_000)
                    tick.longValue++
                    n++
                    if (n % 2 == 0) refresh(direct = false, silent = true)
                }
            }
        }

        setContent { AppTheme(look) { Root() } }
    }

    private fun refresh(direct: Boolean, silent: Boolean = false) {
        if (loading.value) return
        lifecycleScope.launch {
            loading.value = true
            val r = Sync.run(this@MainActivity, direct)
            snapshot.value = Repository.current(this@MainActivity)
            loading.value = false
            if (!silent) message.value = if (r.ok) r.message else "Помилка: ${r.message}"
        }
    }

    private fun saveEntries(list: List<Settings.Entry>) {
        st.entries = list
        entries.value = list
        Notifier.primeGroups(this, snapshot.value)
        Alarms.rescheduleReminders(this)
        ScheduleWidget.updateAll(this)
        LiveService.refreshNotification(this)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Root() {
        var screen by remember { mutableStateOf("home") } // home | detail:<key> | settings
        var sheetFor by remember { mutableStateOf<Settings.Entry?>(null) }
        var sheetOpen by remember { mutableStateOf(false) }
        var toDelete by remember { mutableStateOf<Settings.Entry?>(null) }
        val snack = remember { SnackbarHostState() }
        val msg by message
        LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); message.value = null } }
        BackHandler(screen != "home") { screen = "home" }
        val s = snapshot.value
        val list = entries.value
        val detail = if (screen.startsWith("detail:")) list.firstOrNull { it.key == screen.removePrefix("detail:") } else null
        if (screen.startsWith("detail:") && detail == null) screen = "home"

        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                        title = {
                            Text(
                                when { screen == "settings" -> "Налаштування"; detail != null -> "Графік відключень"; else -> "SM Світло" },
                                fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                            )
                        },
                        navigationIcon = {
                            if (screen != "home") IconButton(onClick = { screen = "home" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                        },
                        actions = {
                            if (screen != "settings") {
                                if (loading.value) CircularProgressIndicator(Modifier.padding(12.dp).size(22.dp), strokeWidth = 2.dp)
                                else IconButton(onClick = { refresh(direct = st.directDtek) }) { Icon(Icons.Outlined.Refresh, "Оновити") }
                                if (screen == "home") IconButton(onClick = { screen = "settings" }) { Icon(Icons.Outlined.Settings, "Налаштування") }
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snack) },
            ) { pad ->
                AnimatedContent(
                    targetState = screen, modifier = Modifier.padding(pad),
                    transitionSpec = {
                        if (targetState == "home") (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
                        else (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                    }, label = "nav",
                ) { scr ->
                    when {
                        scr == "settings" -> SettingsScreen(st) { NotifySettings() }
                        scr.startsWith("detail:") -> {
                            val e = list.firstOrNull { it.key == scr.removePrefix("detail:") }
                            if (e != null) DetailScreen(s, e, tick.longValue,
                                onEdit = { sheetFor = e; sheetOpen = true },
                                onDelete = { toDelete = e },
                                onNotify = { v -> saveEntries(list.map { if (it.key == e.key) it.copy(notify = v) else it }) })
                        }
                        else -> HomeScreen(s, list, tick.longValue,
                            onOpen = { screen = "detail:" + it.key },
                            onEdit = { sheetFor = it; sheetOpen = true },
                            onDelete = { toDelete = it },
                            onAdd = { sheetFor = null; sheetOpen = true })
                    }
                }
            }
            if (look.snow) Snow(look.snowAmount, LocalDark.current)
        }

        if (sheetOpen) EntrySheet(s, sheetFor, onDismiss = { sheetOpen = false }) { id, label ->
            val e = sheetFor
            saveEntries(
                if (e == null) list + Settings.Entry(id + "#" + System.nanoTime(), id, label, true)
                else list.map { if (it.key == e.key) it.copy(id = id, label = label) else it }
            )
            sheetOpen = false
        }
        toDelete?.let { e ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Видалити графік?") },
                text = { Text("\"${e.label.ifBlank { s.groupName(e.id) }}\" зникне зі списку.") },
                confirmButton = {
                    TextButton(onClick = {
                        saveEntries(list.filter { it.key != e.key }); toDelete = null; screen = "home"
                    }) { Text("Видалити", color = RED) }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Скасувати") } },
            )
        }
    }

    @Composable
    private fun ColumnScope.NotifySettings() {
        val ctx = LocalContext.current
        var changes by remember { mutableStateOf(st.notifyChanges) }
        var reminders by remember { mutableStateOf(st.notifyReminders) }
        var restore by remember { mutableStateOf(st.notifyRestore) }
        var attention by remember { mutableStateOf(st.notifyAttention) }
        var lead by remember { mutableIntStateOf(st.leadMinutes) }
        var live by remember { mutableStateOf(st.liveMode) }
        var interval by remember { mutableIntStateOf(st.liveIntervalMin) }
        var direct by remember { mutableStateOf(st.directDtek) }

        Group("Сповіщення") {
            Toggle("Зміна графіку / графік на завтра", changes) { changes = it; st.notifyChanges = it }
            Toggle("Перед відключенням", reminders) { reminders = it; st.notifyReminders = it; Alarms.rescheduleReminders(ctx) }
            Toggle("Коли світло повернеться", restore) { restore = it; st.notifyRestore = it; Alarms.rescheduleReminders(ctx) }
            Toggle("Екстрені повідомлення ДТЕК", attention) { attention = it; st.notifyAttention = it }
            Label("Нагадувати за")
            Chips(listOf(0, 5, 10, 15, 30, 60), lead, { if (it == 0) "в момент" else "$it хв" }) {
                lead = it; st.leadMinutes = it; Alarms.rescheduleReminders(ctx)
            }
            Text("Пуші для окремої групи вмикаються на її екрані.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Group("Оновлення даних") {
            Text("У фоні - кожні 15 хв (мінімум Android). Відкритий застосунок - щохвилини. Реальний час - постійний статус у шторці і перевірка з вибраним інтервалом.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle("Режим реального часу", live) {
                live = it; st.liveMode = it
                if (it) LiveService.start(ctx) else LiveService.stop(ctx)
                Alarms.scheduleTick(ctx)
            }
            if (live) {
                Label("Інтервал")
                Chips(listOf(1, 2, 5, 10), interval, { "$it хв" }) { interval = it; st.liveIntervalMin = it }
            }
            Toggle("Пряме джерело ДТЕК (WebView, експериментально)", direct) { direct = it; st.directDtek = it }
        }
        Group("Дозволи") {
            OutlinedButton(onClick = { if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) },
                Modifier.fillMaxWidth()) { Text("Дозволити сповіщення") }
            BatteryButton()
            if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                OutlinedButton(onClick = {
                    startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }, Modifier.fillMaxWidth()) { Text("Дозволити точні нагадування") }
            }
        }
        Text("Віджет: утримуйте робочий стіл → Віджети → SM Світло. Кожен віджет - свої групи і стиль (кольоровий / ч/б).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, start = 6.dp))
        Text("SM Світло 1.2 · @savchenko_mykhailo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 6.dp))
    }

    @SuppressLint("BatteryLife")
    @Composable
    private fun BatteryButton() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        OutlinedButton(onClick = {
            startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }, Modifier.fillMaxWidth()) { Text("Вимкнути оптимізацію батареї") }
    }
}

@Composable
fun Section(t: String) {
    Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
fun Toggle(t: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { on(!v) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, Modifier.weight(1f), fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Switch(v, on)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(opts: List<T>, sel: T, label: (T) -> String, on: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { o -> FilterChip(selected = o == sel, onClick = { on(o) }, label = { Text(label(o)) }) }
    }
}

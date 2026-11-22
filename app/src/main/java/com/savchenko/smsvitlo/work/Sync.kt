package com.savchenko.smsvitlo.work

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.savchenko.smsvitlo.R
import com.savchenko.smsvitlo.data.Repository
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.logic.Timeline
import com.savchenko.smsvitlo.widget.ScheduleWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.util.concurrent.TimeUnit

object Sync {
    private val mutex = Mutex()

    suspend fun run(c: Context, direct: Boolean): Repository.FetchResult = mutex.withLock {
        val m = Repository.fetchMirror(c)
        val d = if (direct) Repository.fetchDirect(c) else null
        // статуси адрес (екстрені відключення саме у твоєму будинку)
        if (direct || Settings(c).entries.any { it.hasAddress }) {
            try { kotlinx.coroutines.withTimeoutOrNull(60_000) { com.savchenko.smsvitlo.data.Address.refreshAll(c) } } catch (_: Exception) {}
        }
        Repository.invalidate()
        val s = Repository.current(c)
        Notifier.processChanges(c, s)
        Alarms.rescheduleReminders(c)
        ScheduleWidget.updateAll(c)
        LiveService.refreshNotification(c)
        val ok = m.ok || d?.ok == true
        val msg = when {
            d?.ok == true -> "ДТЕК напряму + дзеркало"
            m.ok -> m.message
            else -> m.message
        }
        Repository.FetchResult(ok, m.changed || d?.changed == true, msg)
    }
}

class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val r = Sync.run(applicationContext, direct = false)
        return if (r.ok) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(c: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

/** Режим реального часу: постійна перевірка кожні 1-5 хв + статус у шторці. */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        if (loop?.isActive != true) loop = scope.launch {
            var i = 0
            while (isActive) {
                val st = Settings(this@LiveService)
                val interval = st.liveIntervalMin.coerceIn(1, 30)
                // WebView-джерело не частіше ніж раз на 5 хв
                val directEvery = maxOf(1, 5 / interval)
                try { Sync.run(this@LiveService, direct = st.directDtek && i % directEvery == 0) } catch (_: Exception) {}
                i++
                delay(interval * 60_000L)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    companion object {
        const val ID = 4242
        @Volatile var running = false

        fun start(c: Context) {
            running = true
            ContextCompat.startForegroundService(c, Intent(c, LiveService::class.java))
        }

        fun stop(c: Context) {
            running = false
            c.stopService(Intent(c, LiveService::class.java))
        }

        fun build(c: Context): Notification {
            val st = Settings(c)
            val s = Repository.current(c)
            val lines = st.groups.take(4).mapNotNull { g ->
                val status = Timeline.status(s, g) ?: return@mapNotNull null
                val ch = Timeline.changeText(status)
                val inT = status.changeAt?.let { " (" + Timeline.inShort(Duration.between(Timeline.now(), it)) + ")" } ?: ""
                val h = com.savchenko.smsvitlo.data.Address.activeForGroup(c, g)
                if (h != null) "${st.displayName(g, s)}: ${h.shortType}" + (h.untilText?.let { " · $it" } ?: "")
                else "${st.displayName(g, s)}: ${Timeline.stateText(status.current)}" + (ch?.let { " · $it$inT" } ?: "")
            }
            val title = lines.firstOrNull() ?: "SM Світло: моніторинг"
            val upd = if (s.fetchedAtMillis > 0) "перевірено " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(s.fetchedAtMillis) else ""
            return NotificationCompat.Builder(c, Notifier.CH_LIVE)
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle(title)
                .setContentText(if (lines.size > 1) lines.drop(1).joinToString(" | ") else upd)
                .setStyle(NotificationCompat.BigTextStyle().bigText((lines.drop(1) + upd).joinToString("\n")))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(Notifier.openApp(c))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun refreshNotification(c: Context) {
            if (!Settings(c).liveMode || !Notifier.canPost(c)) return
            try { NotificationManagerCompat.from(c).notify(ID, build(c)) } catch (_: SecurityException) {}
        }
    }
}

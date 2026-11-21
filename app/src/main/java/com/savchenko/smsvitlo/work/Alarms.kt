package com.savchenko.smsvitlo.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.savchenko.smsvitlo.data.Repository
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.logic.Timeline
import com.savchenko.smsvitlo.widget.ScheduleWidget
import java.time.ZonedDateTime

object Alarms {
    const val ACTION_REMIND = "com.savchenko.smsvitlo.REMIND"
    const val ACTION_TICK = "com.savchenko.smsvitlo.TICK"

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun pi(c: Context, action: String, code: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getBroadcast(
            c, code, Intent(c, AlarmReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun setExact(c: Context, at: Long, p: PendingIntent) {
        val a = am(c)
        if (Build.VERSION.SDK_INT >= 31 && !a.canScheduleExactAlarms()) a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        else a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
    }

    /** Перепланувати всі нагадування з поточного знімка. */
    fun rescheduleReminders(c: Context) {
        val st = Settings(c)
        val pr = c.getSharedPreferences("alarms", Context.MODE_PRIVATE)
        pr.getStringSet("codes", emptySet())!!.forEach { code ->
            code.toIntOrNull()?.let { am(c).cancel(pi(c, ACTION_REMIND, it)) }
        }
        val codes = HashSet<String>()
        if (st.notifyReminders || st.notifyRestore) {
            val s = Repository.current(c)
            val now = Timeline.now()
            val lead = st.leadMinutes.toLong()
            for (g in st.notifyGroups) {
                for (e in Timeline.events(s, g, now)) {
                    val isRestore = e.type == Timeline.EventType.ON_START
                    if (isRestore && !st.notifyRestore) continue
                    if (!isRestore && !st.notifyReminders) continue
                    val fireAt = (if (isRestore) e.time.minusMinutes(minOf(lead, 5)) else e.time.minusMinutes(lead))
                    if (!fireAt.isAfter(now)) continue
                    if (fireAt.isAfter(now.plusHours(36))) continue
                    val code = ("$g|${e.time.toEpochSecond()}|${e.type}").hashCode()
                    setExact(c, fireAt.toInstant().toEpochMilli(), pi(c, ACTION_REMIND, code) {
                        putExtra("group", g)
                        putExtra("time", e.time.toEpochSecond())
                        putExtra("type", e.type.name)
                    })
                    codes.add(code.toString())
                }
            }
        }
        pr.edit().putStringSet("codes", codes).apply()
    }

    /** Перемальовування віджетів кожні 5 хв (рівно на :00, :05 ...). */
    fun scheduleTick(c: Context) {
        if (!ScheduleWidget.hasWidgets(c) && !Settings(c).liveMode) {
            am(c).cancel(pi(c, ACTION_TICK, 1)); return
        }
        val now = ZonedDateTime.now(Timeline.ZONE)
        val next = now.withSecond(0).withNano(0).plusMinutes((5 - now.minute % 5).toLong())
        val p = pi(c, ACTION_TICK, 1)
        val a = am(c)
        if (Build.VERSION.SDK_INT >= 31 && !a.canScheduleExactAlarms()) a.set(AlarmManager.RTC, next.toInstant().toEpochMilli(), p)
        else a.setExact(AlarmManager.RTC, next.toInstant().toEpochMilli() + 1000, p)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Alarms.ACTION_TICK -> {
                ScheduleWidget.updateAll(c)
                LiveService.refreshNotification(c)
                Alarms.scheduleTick(c)
            }
            Alarms.ACTION_REMIND -> {
                val g = i.getStringExtra("group") ?: return
                val t = i.getLongExtra("time", 0)
                val type = i.getStringExtra("type") ?: return
                val s = Repository.current(c)
                // перевіряємо, що подія ще актуальна (графік міг змінитися)
                val still = Timeline.events(s, g, Timeline.now().minusMinutes(1))
                    .any { it.time.toEpochSecond() == t && it.type.name == type }
                if (!still) return
                val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(t), Timeline.ZONE)
                val mins = java.time.Duration.between(Timeline.now(), at)
                val inTxt = if (mins.toMinutes() <= 0) "зараз" else "через ${Timeline.inText(mins.plusSeconds(30))}"
                val name = Settings(c).displayName(g, s)
                val (title, text) = when (type) {
                    "OFF_START" -> "Відключення $inTxt" to "$name: світло вимкнуть о ${Timeline.hm(at)}"
                    "ON_START" -> "Світло повернеться $inTxt" to "$name: увімкнення о ${Timeline.hm(at)}"
                    else -> "Можливе відключення $inTxt" to "$name: можливе відключення о ${Timeline.hm(at)}"
                }
                Notifier.post(c, Notifier.CH_REMIND, ("rem$g$t").hashCode(), title, text)
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                SyncWorker.schedule(c)
                Alarms.rescheduleReminders(c)
                Alarms.scheduleTick(c)
                ScheduleWidget.updateAll(c)
                if (Settings(c).liveMode) LiveService.start(c)
            }
        }
    }
}

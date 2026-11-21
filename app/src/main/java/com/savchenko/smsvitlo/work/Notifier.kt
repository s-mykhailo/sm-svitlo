package com.savchenko.smsvitlo.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.savchenko.smsvitlo.R
import com.savchenko.smsvitlo.data.Half
import com.savchenko.smsvitlo.data.Settings
import com.savchenko.smsvitlo.data.Snapshot
import com.savchenko.smsvitlo.logic.Timeline
import com.savchenko.smsvitlo.ui.MainActivity
import java.time.Instant
import java.time.LocalDate

object Notifier {
    const val CH_CHANGES = "changes"
    const val CH_REMIND = "remind"
    const val CH_ATTENTION = "attention"
    const val CH_LIVE = "live"

    fun createChannels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CHANGES, "Зміни графіку", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "Нагадування про відключення", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ATTENTION, "Екстрені повідомлення ДТЕК", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Моніторинг у реальному часі", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
    }

    fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun canPost(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(c: Context, channel: String, id: Int, title: String, text: String) {
        if (!canPost(c)) return
        val n = NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) {}
    }

    fun hrs(x: Double): String = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()

    private fun sig(h: List<Half>) = h.joinToString("") { it.ordinal.toString() }

    private fun offText(h: List<Half>): String {
        val iv = Timeline.intervals(h)
        if (iv.isEmpty()) return "Відключень немає"
        return iv.joinToString(", ") { (if (it.state == Half.MAYBE) "можл. " else "") + it.label() } +
            " · всього ${hrs(Timeline.totalOffHours(h))} год без світла"
    }

    private fun dayWord(day: Long): String {
        val d = Instant.ofEpochSecond(day).atZone(Timeline.ZONE).toLocalDate()
        val today = LocalDate.now(Timeline.ZONE)
        return when (d) {
            today -> "сьогодні"
            today.plusDays(1) -> "завтра"
            else -> "%02d.%02d".format(d.dayOfMonth, d.monthValue)
        }
    }

    /** Порівнює новий знімок зі збереженими підписами і шле пуші про зміни. */
    fun processChanges(c: Context, s: Snapshot) {
        if (s.factDays.isEmpty()) return
        val st = Settings(c)
        val pr = c.getSharedPreferences("sigs", Context.MODE_PRIVATE)
        val first = !pr.getBoolean("init", false)
        val ed = pr.edit()
        val todayStart = Timeline.dayStart(LocalDate.now(Timeline.ZONE))

        for (g in st.notifyGroups) {
            for ((day, groups) in s.factDays.toSortedMap()) {
                if (day < todayStart) continue
                val h = groups[g] ?: continue
                val key = "$g@$day"
                val newSig = sig(h)
                val old = pr.getString(key, null)
                if (old == newSig) continue
                ed.putString(key, newSig)
                if (first || !st.notifyChanges) continue
                val name = st.displayName(g, s)
                val title = if (old == null) "Графік на ${dayWord(day)}: $name" else "Змінено графік на ${dayWord(day)}: $name"
                post(c, CH_CHANGES, ("chg$key").hashCode(), title, offText(h))
            }
        }
        // екстрені повідомлення
        val att = s.attention?.trim().orEmpty()
        val oldAtt = pr.getString("attention", null)
        if (att != oldAtt) {
            ed.putString("attention", att)
            if (!first && st.notifyAttention && att.isNotEmpty()) post(c, CH_ATTENTION, 7001, "ДТЕК: повідомлення", att)
        }
        // прибираємо старі ключі
        pr.all.keys.filter { it.contains('@') }.forEach { k ->
            val d = k.substringAfter('@').toLongOrNull() ?: return@forEach
            if (d < todayStart - 86400) ed.remove(k)
        }
        ed.putBoolean("init", true).apply()
    }

    /** Коли змінюється список груп - запам'ятати поточний стан без пушів. */
    fun primeGroups(c: Context, s: Snapshot) {
        val pr = c.getSharedPreferences("sigs", Context.MODE_PRIVATE)
        val ed = pr.edit()
        for (g in Settings(c).groups) for ((day, groups) in s.factDays) {
            groups[g]?.let { if (!pr.contains("$g@$day")) ed.putString("$g@$day", sig(it)) }
        }
        ed.apply()
    }
}

package com.savchenko.smsvitlo.logic

import com.savchenko.smsvitlo.data.Half
import com.savchenko.smsvitlo.data.Snapshot
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Timeline {
    val ZONE: ZoneId = ZoneId.of("Europe/Kyiv")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun now(): ZonedDateTime = ZonedDateTime.now(ZONE)
    fun dayStart(date: LocalDate): Long = date.atStartOfDay(ZONE).toEpochSecond()

    /** exact = фактичний графік ДТЕК; false = тижневий графік можливих відключень */
    data class DayView(val date: LocalDate, val halves: List<Half>, val exact: Boolean)

    fun day(s: Snapshot, group: String, date: LocalDate, allowPreset: Boolean = true): DayView? {
        s.factDays[dayStart(date)]?.get(group)?.let { return DayView(date, it, true) }
        if (!allowPreset) return null
        s.preset[group]?.get(date.dayOfWeek.value)?.let { return DayView(date, it, false) }
        return null
    }

    fun hasFact(s: Snapshot, date: LocalDate): Boolean = s.factDays.containsKey(dayStart(date))

    fun slotTime(date: LocalDate, idx: Int): ZonedDateTime =
        if (idx >= 48) date.plusDays(1).atStartOfDay(ZONE)
        else date.atTime(idx / 2, (idx % 2) * 30).atZone(ZONE)

    fun slotIndex(t: ZonedDateTime): Int = t.hour * 2 + if (t.minute >= 30) 1 else 0

    data class Status(
        val current: Half,
        val changeAt: ZonedDateTime?,
        val next: Half?,
        val exact: Boolean,
    )

    private class Seq(val halves: List<Half>, val start: LocalDate, val exact: Boolean)

    /** Сьогодні (факт або тижневий) + завтра (тільки якщо є факт, або якщо сьогодні теж тижневий). */
    private fun sequence(s: Snapshot, group: String, today: LocalDate): Seq? {
        val t = day(s, group, today) ?: return null
        val list = ArrayList(t.halves)
        val tm = day(s, group, today.plusDays(1), allowPreset = !t.exact)
        if (tm != null) list.addAll(tm.halves)
        return Seq(list, today, t.exact)
    }

    fun status(s: Snapshot, group: String, now: ZonedDateTime = now()): Status? {
        val today = now.toLocalDate()
        val seq = sequence(s, group, today) ?: return null
        val idx = slotIndex(now)
        val cur = seq.halves[idx]
        for (j in idx + 1 until seq.halves.size) {
            if (seq.halves[j] != cur) {
                return Status(cur, slotTime(today, 0).plusMinutes(30L * j), seq.halves[j], seq.exact)
            }
        }
        return Status(cur, null, null, seq.exact)
    }

    data class Interval(val from: Int, val to: Int, val state: Half) {
        fun label(): String = "${hm(from)}–${hm(to)}"
        private fun hm(i: Int) = "%02d:%02d".format(i / 2, (i % 2) * 30)
    }

    /** Інтервали відключень (OFF та MAYBE) за добу. */
    fun intervals(halves: List<Half>): List<Interval> {
        val res = ArrayList<Interval>()
        var i = 0
        while (i < halves.size) {
            val st = halves[i]
            var j = i
            while (j < halves.size && halves[j] == st) j++
            if (st == Half.OFF || st == Half.MAYBE) res.add(Interval(i, j, st))
            i = j
        }
        return res
    }

    fun totalOffHours(halves: List<Half>): Double = halves.count { it == Half.OFF } / 2.0

    enum class EventType { OFF_START, ON_START, MAYBE_START }
    data class Event(val group: String, val time: ZonedDateTime, val type: EventType)

    /** Майбутні переходи для нагадувань (тільки по точному графіку). */
    fun events(s: Snapshot, group: String, now: ZonedDateTime = now()): List<Event> {
        val today = now.toLocalDate()
        val t = day(s, group, today, allowPreset = false) ?: return emptyList()
        val list = ArrayList(t.halves)
        day(s, group, today.plusDays(1), allowPreset = false)?.let { list.addAll(it.halves) }
        val res = ArrayList<Event>()
        for (j in 1 until list.size) {
            val a = list[j - 1]; val b = list[j]
            if (a == b) continue
            val time = slotTime(today, 0).plusMinutes(30L * j)
            if (!time.isAfter(now)) continue
            val type = when {
                b == Half.OFF -> EventType.OFF_START
                a == Half.OFF && b == Half.ON -> EventType.ON_START
                b == Half.MAYBE -> EventType.MAYBE_START
                else -> null
            } ?: continue
            res.add(Event(group, time, type))
        }
        return res
    }

    fun hm(t: ZonedDateTime): String = t.format(HM)

    fun inText(d: Duration): String {
        val m = (d.toMinutes()).coerceAtLeast(0)
        val h = m / 60; val mm = m % 60
        return when {
            h == 0L -> "$mm хв"
            mm == 0L -> "$h год"
            else -> "$h год $mm хв"
        }
    }

    fun inShort(d: Duration): String {
        val m = d.toMinutes().coerceAtLeast(0)
        val h = m / 60; val mm = m % 60
        return if (h == 0L) "${mm}хв" else "${h}г ${"%02d".format(mm)}хв"
    }

    fun stateText(h: Half): String = when (h) {
        Half.ON -> "Світло є"
        Half.OFF -> "Світла немає"
        Half.MAYBE -> "Можливе відключення"
        Half.UNKNOWN -> "Немає даних"
    }

    /** "вимкнуть о 18:00" / "увімкнуть о 21:30" */
    fun changeText(st: Status): String? {
        val at = st.changeAt ?: return when (st.current) {
            Half.UNKNOWN -> null
            Half.ON -> "без відключень"
            else -> "до кінця графіку"
        }
        val t = hm(at).let { if (at.toLocalDate() != now().toLocalDate()) "$it завтра" else it }
        return when (st.next) {
            Half.OFF -> "вимкнуть о $t"
            Half.ON -> "увімкнуть о $t"
            Half.MAYBE -> "можливе відкл. о $t"
            else -> "до $t"
        }
    }
}

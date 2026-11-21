package com.savchenko.smsvitlo

import com.savchenko.smsvitlo.data.Half
import com.savchenko.smsvitlo.data.Parser
import com.savchenko.smsvitlo.logic.Timeline
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

class LogicTest {
    private val s = Parser.parse(File("src/test/dnipro.json").readText(), "mirror", 0)
    private val day = LocalDate.of(2026, 11, 21)

    @Test fun parses() {
        assertEquals("21.11.2026 01:37", s.factUpdate)
        assertTrue(Timeline.hasFact(s, day))
        assertEquals(24, s.names.size)
        assertEquals("Черга 6.2", s.groupName("GPV6.2"))
        assertTrue(s.emergency)
        val h = Timeline.day(s, "GPV6.2", day)!!
        assertTrue(h.exact)
        // "no" 00-01, "first" 01-02
        assertEquals(listOf(Half.OFF, Half.OFF, Half.OFF, Half.ON, Half.ON), h.halves.take(5))
        assertEquals("00:00–01:30", Timeline.intervals(h.halves).single().label())
        println(s.allGroups)
    }

    @Test fun status() {
        val t = ZonedDateTime.of(day.atTime(0, 40), Timeline.ZONE)
        val st = Timeline.status(s, "GPV6.2", t)!!
        assertEquals(Half.OFF, st.current)
        assertEquals(1, st.changeAt!!.hour); assertEquals(30, st.changeAt!!.minute)
        val ev = Timeline.events(s, "GPV6.2", t)
        assertEquals(Timeline.EventType.ON_START, ev.first().type)
        // тижневий графік на завтра, бо факту немає
        val tm = Timeline.day(s, "GPV1.1", day.plusDays(1))!!
        assertFalse(tm.exact)
        println(Timeline.intervals(tm.halves).map { it.label() + it.state })
        assertEquals("Черга 1.1", Parser.prettyName("GPV1.1")); assertEquals("ЦЕК 2.1", Parser.prettyName("GPV1002.1"))
    }
}

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

    @Test fun addressMatching() {
        val a = com.savchenko.smsvitlo.data.Address
        assertEquals(a.core("вул. Центральна"), a.core("вулиця Центральна"))
        assertEquals(a.core("вул. Центральна"), a.core("Центральна вулиця"))
        assertEquals(a.core("с. Волоське"), a.core("Волоське"))
        assertEquals(a.core("м. Дніпро"), a.core("Дніпро"))
        assertEquals(a.core("пров. Шевченка"), a.core("провулок Шевченка"))
        assertEquals(listOf("с. Волоське", "с. Волосківці"), a.filter(listOf("м. Дніпро", "с. Волоське", "с. Волосківці"), "волос"))
    }

    @Test fun houseParsing() {
        val o = org.json.JSONObject("""{"sub_type":"Екстрені відключення (Аварійне без застосування графіку погодинних відключень)",
            "start_date":"14:02 22.11.2026","end_date":"19:30 22.11.2026","type":"2","sub_type_reason":["GPV1.1"],"voluntarily":null}""")
        val h = com.savchenko.smsvitlo.data.House.fromDtek("1", o)
        assertEquals(listOf("GPV1.1"), h.groups)
        assertTrue(h.active)
        assertEquals("Екстрене відключення", h.shortType)
        val back = com.savchenko.smsvitlo.data.House.fromJson(h.toJson())
        assertEquals(h, back)
        val none = com.savchenko.smsvitlo.data.House.fromDtek("2", org.json.JSONObject("""{"sub_type":"","start_date":"","end_date":"","type":"","sub_type_reason":["GPV2.1"]}"""))
        assertFalse(none.active)
    }
}

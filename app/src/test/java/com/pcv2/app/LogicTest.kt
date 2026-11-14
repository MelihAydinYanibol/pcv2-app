package com.pcv2.app

import com.pcv2.app.core.DAYS
import com.pcv2.app.core.Dash
import com.pcv2.app.core.ExcEntry
import com.pcv2.app.core.ExcKind
import com.pcv2.app.core.I18n
import com.pcv2.app.core.JSON
import com.pcv2.app.core.NightLockdown
import com.pcv2.app.core.Schedule
import com.pcv2.app.core.TimeWindow
import com.pcv2.app.core.classifyEntry
import com.pcv2.app.core.compactDur
import com.pcv2.app.core.computeLimit
import com.pcv2.app.core.excSummary
import com.pcv2.app.core.fmtClock
import com.pcv2.app.core.fmtDur
import com.pcv2.app.core.isBlockedAt
import com.pcv2.app.core.levelFor
import com.pcv2.app.core.Level
import com.pcv2.app.core.nlWindowLen
import com.pcv2.app.core.normalizeExcList
import com.pcv2.app.core.scanLockdown
import com.pcv2.app.core.t
import com.pcv2.app.core.UNCONFIGURED_LIMIT
import com.pcv2.app.data.normalizeServer
import com.pcv2.app.data.seg
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** The limit / lockdown / formatting maths must match the dashboard and the engines. */
class LogicTest {
    @Before fun en() { I18n.lang = "en" }
    @After fun reset() { I18n.lang = "en" }

    private fun exc(vararg v: Any?): List<ExcEntry> = v.map {
        ExcEntry(
            when (it) {
                is String -> JsonPrimitive(it)
                is Number -> JsonPrimitive(it)
                is Boolean -> JsonPrimitive(it)
                else -> JsonPrimitive(0)
            },
            null,
        )
    }

    @Test fun classify_integers_add_strings_set() {
        assertEquals(ExcKind.ADD, classifyEntry(JsonPrimitive(900)).kind)
        assertEquals(-300L, classifyEntry(JsonPrimitive(-300)).value)
        assertEquals(ExcKind.SET, classifyEntry(JsonPrimitive("3600")).kind)
        assertEquals(3600L, classifyEntry(JsonPrimitive("3600")).value)
        assertEquals(ExcKind.SET, classifyEntry(JsonPrimitive(1800.0)).kind)
        assertEquals(ExcKind.SET, classifyEntry(JsonPrimitive(true)).kind)
        assertNull(classifyEntry(JsonPrimitive("abc")).kind)
    }

    @Test fun compute_limit_like_the_engine() {
        val limits = mapOf("OVERALL" to mapOf("Monday" to 7200L, "Tuesday" to null))
        // configured + add + subtract
        var l = computeLimit("OVERALL", "Monday", limits, mapOf("OVERALL" to exc(900, -300)))
        assertEquals(7800L, l.effective); assertEquals(600L, l.added); assertFalse(l.unlimited)
        // set resets the running total, later adds stack on top
        l = computeLimit("OVERALL", "Monday", limits, mapOf("OVERALL" to exc(900, "3600", 600)))
        assertEquals(4200L, l.effective); assertEquals(3600L, l.setTo); assertEquals(600L, l.added)
        assertEquals("Set to 1h 00m +10m", excSummary(l))
        // unset day = unlimited, until an exact limit is set
        l = computeLimit("OVERALL", "Tuesday", limits, null)
        assertTrue(l.unlimited); assertEquals(UNCONFIGURED_LIMIT, l.effective); assertNull(l.configured)
        l = computeLimit("OVERALL", "Tuesday", limits, mapOf("OVERALL" to exc("1200")))
        assertFalse(l.unlimited); assertEquals(1200L, l.effective)
        // unknown app
        assertTrue(computeLimit("x.exe", "Monday", limits, null).unlimited)
    }

    @Test fun old_single_exception_format() {
        val old = JSON.parseToJsonElement("""[300, "reason"]""")
        assertEquals(1, normalizeExcList(old).size)
        assertEquals("reason", normalizeExcList(old)[0].reason)
        val list = JSON.parseToJsonElement("""[[300, null], ["600", "x"]]""")
        assertEquals(2, normalizeExcList(list).size)
    }

    @Test fun lockdown_windows_cross_midnight_and_whitelist_wins() {
        val nl = NightLockdown(
            enabled = true,
            allDays = Schedule(blocked = listOf(TimeWindow("22:00", "07:00")), whitelist = listOf(TimeWindow("23:00", "23:30"))),
        )
        val day = LocalDateTime.of(2026, 2, 23, 0, 0) // a Monday
        assertTrue(isBlockedAt(nl, day.withHour(22).withMinute(0)))
        assertTrue(isBlockedAt(nl, day.withHour(3)))
        assertTrue(isBlockedAt(nl, day.withHour(7).withMinute(0)))   // inclusive end minute
        assertFalse(isBlockedAt(nl, day.withHour(7).withMinute(1)))
        assertFalse(isBlockedAt(nl, day.withHour(23).withMinute(15)))
        assertFalse(isBlockedAt(nl.copy(enabled = false), day.withHour(3)))

        val before = scanLockdown(nl, day.withHour(20))
        assertFalse(before.activeNow)
        assertEquals(day.withHour(22), before.startsAt)
        assertEquals(day.withHour(23), before.endsAt) // whitelist opens a gap at 23:00
        val during = scanLockdown(nl, day.withHour(23).withMinute(40))
        assertTrue(during.activeNow)
        assertEquals(day.plusDays(1).withHour(7).withMinute(1), during.endsAt)
    }

    @Test fun per_day_schedule() {
        val nl = NightLockdown(
            enabled = true, perDay = true,
            days = DAYS.associateWith { if (it == "Friday") Schedule(blocked = listOf(TimeWindow("10:00", "11:00"))) else Schedule() },
        )
        assertTrue(isBlockedAt(nl, LocalDateTime.of(2026, 2, 27, 10, 30)))  // Friday
        assertFalse(isBlockedAt(nl, LocalDateTime.of(2026, 2, 26, 10, 30))) // Thursday
    }

    @Test fun formatting_matches_dashboard() {
        assertEquals("1h 05m", fmtDur(3900.0))
        assertEquals("1h 05m 09s", fmtDur(3909.0, seconds = true))
        assertEquals("9m 05s", fmtDur(545.0))
        assertEquals("15m", fmtDur(905.0))
        assertEquals("45s", fmtDur(45.0))
        assertEquals("-30m", fmtDur(-1800.0))
        assertEquals("1:02:03", fmtClock(3723.4))
        assertEquals("05:00", fmtClock(300.0))
        assertEquals("1h30", compactDur(5400))
        assertEquals("45m", compactDur(2700))
        assertEquals("9h 00m · crosses midnight", nlWindowLen(TimeWindow("22:00", "07:00")))
        assertEquals(Level.DANGER, levelFor(200.0, 3600.0))
        assertEquals(Level.WARN, levelFor(800.0, 3600.0))
        assertEquals(Level.NONE, levelFor(3000.0, 3600.0))
    }

    @Test fun turkish_translations() {
        I18n.lang = "tr"
        assertEquals("1sa 05dk", fmtDur(3900.0))
        assertEquals("Süre doldu", t("Time is up"))
        assertEquals("2 bekliyor", t("{n} pending", "n" to 2))
        assertEquals("Sunucu adresi", t("Server address"))
    }

    @Test fun server_address_normalisation() {
        assertEquals("http://192.168.1.10:5035", normalizeServer("192.168.1.10:5035/api/"))
        assertEquals("https://pcv2.example.com", normalizeServer(" https://pcv2.example.com/login "))
        assertEquals("http://10.0.2.2:5035", normalizeServer("http://10.0.2.2:5035/"))
        assertEquals("My%20App.exe", seg("My App.exe"))
    }

    @Test fun parse_dashboard_batch() {
        val raw = JSON.parseToJsonElement(
            """{
              "limits": {"OVERALL": {"Monday": 3600, "Sunday": null}, "chrome.exe": {"Monday": 1800.0}},
              "today_exceptions": {"OVERALL": [[900, "x"], ["1200", null]]},
              "today_usage": {"OVERALL": 120, "chrome.exe": 60.5},
              "night_lockdown": {"enabled": true, "per_day": false, "all_days": {"blocked": [["22:00","07:00"]], "whitelist": []}, "days": {}},
              "server": {"primary_api": "offline", "server_date": "2026-02-23", "last_synced": "2026-02-23T10:00:00Z"},
              "queue": [{"id": 3, "method": "DELETE", "endpoint": "/limits/chrome.exe", "data": null}],
              "status": {"current_date": "2026-02-23"},
              "config": {}
            }""",
        ).jsonObject
        val d = Dash.parse(raw)
        assertEquals(3600L, d.limits["OVERALL"]!!["Monday"])
        assertNull(d.limits["OVERALL"]!!["Sunday"])
        assertEquals(1800L, d.limits["chrome.exe"]!!["Monday"])
        assertEquals(2, d.todayExceptions["OVERALL"]!!.size)
        assertEquals(60.5, d.todayUsage["chrome.exe"]!!, 0.0)
        assertTrue(d.nightLockdown.enabled)
        assertEquals("22:00", d.nightLockdown.allDays.blocked[0].start)
        assertFalse(d.primaryOnline)
        assertEquals(3L, d.queue[0].id)
        // round trip for PUT /nightlockdown
        val back = NightLockdown.parse(d.nightLockdown.toJson())
        assertEquals(d.nightLockdown, back)
    }
}

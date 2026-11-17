package com.pcv2.app.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/** Mirrors client_engine.UNCONFIGURED_LIMIT: an unset day means "no limit". */
const val UNCONFIGURED_LIMIT = 24L * 60 * 60 - 1

fun localDate(d: LocalDate = LocalDate.now()): String = d.toString()
fun dayNameOf(d: LocalDate): String = DAYS[d.dayOfWeek.value - 1]
fun dayNameOf(date: String): String = runCatching { dayNameOf(LocalDate.parse(date)) }.getOrDefault("Monday")

// =====================================================================
// Limit / exception math — mirrors client_engine.check_exception()
// =====================================================================
enum class ExcKind { ADD, SET }

data class Classified(val kind: ExcKind?, val value: Long)

/** Integers add/subtract, numeric strings (and floats / booleans) set an exact limit. */
fun classifyEntry(v: JsonElement?): Classified {
    val p = v as? JsonPrimitive ?: return Classified(null, 0)
    if (p is JsonNull) return Classified(null, 0)
    if (!p.isString) {
        if (p.content == "true") return Classified(ExcKind.SET, 1)
        if (p.content == "false") return Classified(ExcKind.SET, 0)
        val c = p.content
        if (Regex("^-?\\d+$").matches(c)) return Classified(ExcKind.ADD, c.toLong())
        val d = c.toDoubleOrNull() ?: return Classified(null, 0)
        return Classified(ExcKind.SET, d.toLong())
    }
    val m = Regex("^\\s*([+-]?\\d+)\\s*$").find(p.content) ?: return Classified(null, 0)
    return Classified(ExcKind.SET, m.groupValues[1].toLong())
}

data class LimitCalc(
    val base: Long,
    val configured: Long?,
    val effective: Long,
    val unlimited: Boolean,
    val added: Long,
    val setTo: Long?,
    val entries: Int,
)

fun computeLimit(
    name: String,
    dayName: String,
    limits: Map<String, Map<String, Long?>>,
    excToday: Map<String, List<ExcEntry>>?,
): LimitCalc {
    val configured = limits[name]?.get(dayName)
    val unset = configured == null
    val base = configured ?: UNCONFIGURED_LIMIT
    var effective = base
    var unlimited = unset
    var added = 0L
    var setTo: Long? = null
    val entries = excToday?.get(name) ?: emptyList()
    for (e in entries) {
        val c = classifyEntry(e.value)
        when (c.kind) {
            ExcKind.ADD -> { effective += c.value; added += c.value }
            ExcKind.SET -> { effective = c.value; setTo = c.value; added = 0; unlimited = false }
            null -> {}
        }
    }
    return LimitCalc(base, if (unset) null else base, effective, unlimited, added, setTo, entries.size)
}

// =====================================================================
// Night lockdown — mirrors night_lockdown.py (inclusive minutes, wraps midnight)
// =====================================================================
fun toMin(v: String?): Int? {
    val m = Regex("^(\\d{1,2}):(\\d{2})$").find(v ?: "") ?: return null
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    return if (h <= 23 && mi <= 59) h * 60 + mi else null
}

fun inWin(now: Int, s: Int?, e: Int?): Boolean {
    if (s == null || e == null) return false
    return if (s <= e) now in s..e else (now >= s || now <= e)
}

fun scheduleFor(nl: NightLockdown, dayName: String): Schedule = if (nl.perDay) nl.day(dayName) else nl.allDays

fun isBlockedAt(nl: NightLockdown?, t: LocalDateTime): Boolean {
    if (nl == null || !nl.enabled) return false
    val sch = scheduleFor(nl, dayNameOf(t.toLocalDate()))
    val mins = t.hour * 60 + t.minute
    fun any(ws: List<TimeWindow>) = ws.any { inWin(mins, toMin(it.start), toMin(it.end)) }
    return any(sch.blocked) && !any(sch.whitelist)
}

data class LockInfo(val activeNow: Boolean, val startsAt: LocalDateTime? = null, val endsAt: LocalDateTime? = null)

private var lockMemoKey: Any? = null
private var lockMemoValue: LockInfo? = null

/** null when lockdown is off. Scanned minute by minute up to 8 days ahead, memoised per minute. */
fun lockdownInfo(nl: NightLockdown?, now: LocalDateTime = LocalDateTime.now()): LockInfo? {
    if (nl == null || !nl.enabled) return null
    val key = nl to now.withSecond(0).withNano(0)
    synchronized(DAYS) {
        if (lockMemoKey != key) {
            lockMemoValue = scanLockdown(nl, now)
            lockMemoKey = key
        }
        return lockMemoValue
    }
}

fun scanLockdown(nl: NightLockdown, now: LocalDateTime): LockInfo {
    val schedules = if (nl.perDay) DAYS.map { scheduleFor(nl, it) } else listOf(nl.allDays)
    if (schedules.none { s -> s.blocked.any { toMin(it.start) != null && toMin(it.end) != null } }) return LockInfo(false)
    val base = now.withSecond(0).withNano(0)
    val limit = 8 * 24 * 60
    if (isBlockedAt(nl, now)) {
        for (i in 1 until limit) {
            val t = base.plusMinutes(i.toLong())
            if (!isBlockedAt(nl, t)) return LockInfo(true, endsAt = t)
        }
        return LockInfo(true)
    }
    for (i in 1 until limit) {
        val t = base.plusMinutes(i.toLong())
        if (isBlockedAt(nl, t)) {
            var end: LocalDateTime? = null
            for (j in i + 1 until i + limit) {
                val u = base.plusMinutes(j.toLong())
                if (!isBlockedAt(nl, u)) { end = u; break }
            }
            return LockInfo(false, startsAt = t, endsAt = end)
        }
    }
    return LockInfo(false)
}

fun secondsUntil(t: LocalDateTime, now: LocalDateTime = LocalDateTime.now()): Double =
    java.time.Duration.between(now, t).toMillis() / 1000.0

/** Length text of a zone, e.g. "9h 00m · crosses midnight". */
fun nlWindowLen(w: TimeWindow): String {
    val s = toMin(w.start) ?: return ""
    val e = toMin(w.end) ?: return ""
    val len = ((e - s + 1440) % 1440).let { if (it == 0) 1440 else it }
    return fmtDur(len * 60.0) + if (e < s) " · " + t("crosses midnight") else ""
}

// =====================================================================
// Formatting — same output as the dashboard
// =====================================================================
fun fmtDur(secIn: Double, seconds: Boolean = false): String {
    val sec = secIn.roundToLong()
    val neg = sec < 0
    var s = abs(sec)
    val h = s / 3600; s -= h * 3600
    val m = s / 60; s -= m * 60
    val H = t("h"); val M = t("m"); val S = t("s")
    val out = when {
        h > 0 -> if (seconds) "$h$H ${pad(m)}$M ${pad(s)}$S" else "$h$H ${pad(m)}$M"
        m > 0 -> if ((seconds || m < 10) && s != 0L) "$m$M ${pad(s)}$S" else "$m$M"
        else -> "$s$S"
    }
    return (if (neg) "-" else "") + out
}

fun fmtDur(sec: Long, seconds: Boolean = false) = fmtDur(sec.toDouble(), seconds)

private fun pad(n: Long) = n.toString().padStart(2, '0')

fun fmtClock(secIn: Double): String {
    val sec = max(0.0, floor(secIn)).toLong()
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "$h:${pad(m)}:${pad(s)}" else "${pad(m)}:${pad(s)}"
}

fun fmtAgo(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0) return t("never")
    val s = max(0L, (now - ms + 500) / 1000)
    return when {
        s < 10 -> t("just now")
        s < 60 -> t("{n}s ago", "n" to s)
        s < 3600 -> t("{n}m ago", "n" to s / 60)
        s < 86400 -> t("{h}h {m}m ago", "h" to s / 3600, "m" to (s % 3600) / 60)
        else -> t("{n}d ago", "n" to s / 86400)
    }
}

fun fmtTime(ms: Long): String =
    fmtTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()))

fun fmtTime(d: LocalDateTime): String {
    val sameDay = d.toLocalDate() == LocalDate.now()
    val time = d.format(DateTimeFormatter.ofPattern("HH:mm", I18n.locale))
    if (sameDay) return time
    val date = d.format(DateTimeFormatter.ofPattern(if (I18n.lang == "tr") "d MMM" else "MMM d", I18n.locale))
    return "$date $time"
}

fun fmtDate(date: String): String = runCatching {
    LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(I18n.locale))
}.getOrDefault(date)

fun toMillis(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Parses "2026-02-21T10:00:00Z" or a local ISO time into epoch millis. */
fun parseIsoMillis(s: String?): Long {
    if (s.isNullOrBlank()) return 0
    return runCatching { Instant.parse(s).toEpochMilli() }.getOrElse {
        runCatching { toMillis(LocalDateTime.parse(s)) }.getOrDefault(0)
    }
}

fun appLabel(name: String) = if (name == "OVERALL") t("Whole computer") else name
fun appInitial(name: String) = if (name == "OVERALL") "◎" else (name.ifEmpty { "?" }.replace(Regex("\\.exe$", RegexOption.IGNORE_CASE), "").take(1).uppercase())
fun signed(sec: Long) = (if (sec >= 0) "+" else "-") + fmtDur(abs(sec).toDouble())

/** Short text for the exceptions applied to a limit, e.g. "Set to 40m +15m" or "+30m". */
fun excSummary(lim: LimitCalc): String {
    if (lim.setTo != null) return t("Set to {v}", "v" to fmtDur(lim.setTo.toDouble())) + if (lim.added != 0L) " " + signed(lim.added) else ""
    return if (lim.added != 0L) signed(lim.added) else ""
}

enum class Level { NONE, OK, WARN, DANGER }

fun levelFor(remaining: Double, limit: Double): Level = when {
    remaining <= 0 -> Level.DANGER
    remaining <= 300 || (limit > 0 && remaining / limit < 0.1) -> Level.DANGER
    remaining <= 900 || (limit > 0 && remaining / limit < 0.25) -> Level.WARN
    else -> Level.NONE
}

/** "1h30" / "45m": compact day cell used on phones in the limits table. */
fun compactDur(v: Long): String {
    val h = v / 3600
    val m = ((v % 3600) / 60.0).roundToLong()
    return if (h > 0) "$h${t("h")}${if (m > 0) pad(m) else ""}" else "$m${t("m")}"
}

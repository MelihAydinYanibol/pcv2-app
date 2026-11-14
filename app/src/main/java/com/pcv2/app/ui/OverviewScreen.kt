@file:OptIn(ExperimentalLayoutApi::class)

package com.pcv2.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcv2.app.core.Level
import com.pcv2.app.core.appInitial
import com.pcv2.app.core.excSummary
import com.pcv2.app.core.fmtAgo
import com.pcv2.app.core.fmtClock
import com.pcv2.app.core.fmtDur
import com.pcv2.app.core.fmtTime
import com.pcv2.app.core.isBlockedAt
import com.pcv2.app.core.levelFor
import com.pcv2.app.core.t
import com.pcv2.app.core.toMillis
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun OverviewScreen(vm: AppViewModel) {
    val now = vm.now
    val m = vm.model(now)
    if (m == null) {
        NoDataHero(vm)
        return
    }
    val d = vm.data!!
    HeroCard(vm, m)
    Spacer(Modifier.height(12.dp))

    // ---------------------------------------------------------------- glance
    PCard {
        CardTitle(t("Today at a glance"))
        val lk = m.lockdown
        val lockTxt = when {
            lk == null -> t("Off")
            lk.activeNow -> lk.endsAt?.let { t("Active until {t}", "t" to fmtTime(it)) } ?: t("Active")
            lk.startsAt != null -> "${fmtTime(lk.startsAt)} → ${lk.endsAt?.let { fmtTime(it) } ?: "…"}"
            else -> t("No blocked zones")
        }
        val ov = m.overall
        val rows = mutableListOf(
            t("Day") to "${t(m.dayName)}, ${m.today}",
            t("Overall limit") to if (ov.lim.unlimited) t("No limit") else fmtDur((ov.lim.configured ?: ov.lim.base).toDouble()),
            t("Exceptions") to excSummary(ov.lim).ifEmpty { t("None") },
            t("Effective limit") to if (ov.lim.unlimited) t("No limit") else fmtDur(ov.lim.effective.toDouble()),
            t("Night lockdown") to lockTxt,
            t("Kid's PC") to if (vm.source == Source.LIVE) t("Online") else t("Offline · seen {ago}", "ago" to fmtAgo(vm.lastSeenMs(), now)),
        )
        if (!m.sameDay) rows += t("Last data") to t("from {d}", "d" to vm.dataDate())
        Kv(rows)
    }

    // ---------------------------------------------------------------- stats
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(t("Used today"), fmtDur(m.overall.used), Modifier.weight(1f))
        Stat(t("Apps monitored"), d.limits.keys.count { it != "OVERALL" }.toString(), Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(t("Exceptions today"), m.excToday.size.toString(), Modifier.weight(1f))
        Stat(t("Pending changes"), d.queue.size.toString(), Modifier.weight(1f))
    }

    // ---------------------------------------------------------------- activity
    val samples = vm.history.filter { it.date == m.today }
    SectionTitle(t("Activity today")) {
        Hint(
            if (samples.isNotEmpty()) t(if (samples.size == 1) "{n} sample buffered on this phone · every {s}s" else "{n} samples buffered on this phone · every {s}s", "n" to samples.size, "s" to vm.settings.bufferSec)
            else t("Samples appear here while the app is open"),
            Modifier.weight(1f, fill = false),
            align = TextAlign.End,
        )
    }
    PCard {
        val start = remember(m.today) { toMillis(LocalDate.parse(m.today).atStartOfDay()) }
        val lockSegs = remember(d.nightLockdown, m.today) {
            val segs = mutableListOf<Seg>()
            if (d.nightLockdown.enabled) {
                val base = LocalDate.parse(m.today).atStartOfDay()
                var runStart = -1
                for (i in 0..1440) {
                    val b = i < 1440 && isBlockedAt(d.nightLockdown, base.plusMinutes(i.toLong()))
                    if (b && runStart < 0) runStart = i
                    if (!b && runStart >= 0) { segs += Seg(runStart / 1440f, i / 1440f, 1); runStart = -1 }
                }
            }
            segs
        }
        val frac = { ms: Long -> ((ms - start) / 86_400_000f).coerceIn(0f, 1f) }
        val onSegs = buildList {
            for (i in 1 until samples.size) {
                val a = samples[i - 1]; val b = samples[i]
                if (b.overall > a.overall) {
                    val from = max(max(a.t, b.t - ((b.overall - a.overall) * 1000).toLong()), start)
                    add(Seg(frac(from), max(frac(b.t), frac(from) + 0.0015f), 0))
                }
            }
        }
        Timeline(lockSegs + onSegs, if (com.pcv2.app.core.localDate() == m.today) frac(now) else null)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendItem(pal.ok, t("Computer on (from buffered samples)"))
            LegendItem(pal.danger.copy(alpha = 0.55f), t("Night lockdown"))
        }
    }

    // ---------------------------------------------------------------- applications
    SectionTitle(t("Applications")) {
        Btn(t("+ Add application"), { vm.sheet = Sheet.Limit(null) }, small = true)
    }
    if (m.apps.isEmpty()) {
        EmptyCard(t("No applications are limited yet"), t("Add an application to start limiting its daily time.")) {
            Btn(t("+ Add application"), { vm.sheet = Sheet.Limit(null) }, kind = BtnKind.PRIMARY)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            m.apps.forEach { AppCard(vm, m, it) }
        }
    }
    if (m.extra.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        PCard {
            CardTitle(t("Used today without a limit"))
            Chips { m.extra.forEach { Chip("$it · ${fmtDur(m.usage[it] ?: 0.0)}") } }
        }
    }
}

@Composable
private fun NoDataHero(vm: AppViewModel) {
    PCard {
        CardTitle(t("Computer time left today"))
        if (vm.source == Source.BUFFER && !vm.booting) {
            Txt(t("No data available yet"), color = pal.text2, size = 14.5.sp, weight = FontWeight.Medium)
            Txt("—", size = 34.sp, weight = FontWeight.Bold)
            Txt(t("The dashboard will fill in as soon as the server answers."), color = pal.text2, size = 13.5.sp)
        } else {
            Txt(t("Loading…"), color = pal.text2, size = 14.5.sp, weight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Skeleton(Modifier.width(180.dp).height(40.dp))
        }
    }
}

@Composable
private fun HeroCard(vm: AppViewModel, m: Model) {
    val ov = m.overall
    val rem = m.pcRemaining
    val label: String
    val time: String
    var sub = ""
    when {
        m.pcReason == "lockdown-active" -> {
            label = t("Night lockdown is active")
            time = t("Locked")
            sub = m.lockdown?.endsAt?.let { t("Computer can be used again at {t}", "t" to fmtTime(it)) } ?: ""
        }
        rem == Double.POSITIVE_INFINITY -> {
            label = t("No overall limit today")
            time = t("Unlimited")
            sub = t("Used {v} so far", "v" to fmtDur(ov.used))
        }
        else -> {
            label = if (m.pcReason == "lockdown") t("Until night lockdown") else t("Until the daily limit")
            time = if (rem <= 0) t("Time is up") else fmtClock(rem)
            if (m.pcReason == "lockdown") {
                sub = t("Lockdown starts at {t}", "t" to fmtTime(m.lockdown!!.startsAt!!))
                if (!ov.lim.unlimited) sub += " · " + t("{v} left on the daily limit", "v" to fmtDur(ov.remaining))
            } else {
                sub = t("Used {a} of {b}", "a" to fmtDur(ov.used), "b" to fmtDur(ov.lim.effective.toDouble()))
                m.lockdown?.startsAt?.let { sub += " · " + t("lockdown at {t}", "t" to fmtTime(it)) }
            }
        }
    }
    val lvl = if (rem == Double.POSITIVE_INFINITY) Level.OK else levelFor(rem, if (ov.lim.unlimited) 0.0 else ov.lim.effective.toDouble())
    val frac = when {
        m.pcReason == "lockdown-active" -> 0f
        !ov.lim.unlimited && ov.lim.effective > 0 -> (rem / ov.lim.effective).toFloat().coerceIn(0f, 1f)
        !ov.lim.unlimited -> 0f
        else -> 1f
    }

    PCard {
        CardTitle(t("Computer time left today"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Ring(frac, if (lvl == Level.OK) Level.OK else lvl, 104.dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(if (rem == Double.POSITIVE_INFINITY) "∞" else "${(frac * 100).roundToInt()}%", size = 18.sp, weight = FontWeight.Bold)
                    Txt(t("left"), color = pal.text3, size = 11.5.sp)
                }
            }
            Column(Modifier.weight(1f)) {
                Txt(label, color = pal.text2, size = 14.5.sp, weight = FontWeight.Medium)
                Txt(
                    time, Modifier.padding(vertical = 2.dp), size = 32.sp, weight = FontWeight.Bold, maxLines = 1,
                    color = when (lvl) { Level.WARN -> pal.warn; Level.DANGER -> pal.danger; else -> pal.text },
                    letterSpacing = (-0.5).sp,
                )
                Txt(sub, color = pal.text2, size = 13.sp)
            }
        }
        val chips = buildList<@Composable () -> Unit> {
            if (vm.source == Source.LIVE && ov.active) add { Chip(t("Counting down"), Tone.OK, live = true) }
            else if (vm.source != Source.LIVE) add { Chip(t("Paused · PC offline"), Tone.WARN) }
            if (excSummary(ov.lim).isNotEmpty()) add { Chip(t("Exception: {v}", "v" to excSummary(ov.lim)), Tone.ACCENT) }
            if (ov.pending.isNotEmpty()) {
                val sum = vm.pendingSum(ov.pending)
                add { Chip(t("{n} pending", "n" to ov.pending.size) + if (sum != 0L) " (${if (sum > 0) "+" else ""}${fmtDur(sum.toDouble())})" else "", Tone.INFO) }
            }
            if (!m.sameDay) add { Chip(t("No data for today yet")) }
        }
        if (chips.isNotEmpty()) Chips(Modifier.padding(top = 12.dp)) { chips.forEach { it() } }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(900L to t("+15m"), 1800L to t("+30m"), 3600L to t("+1h")).forEach { (secs, lbl) ->
                Btn(lbl, { vm.quickException("OVERALL", secs) }, Modifier.weight(1f), kind = BtnKind.SOFT, small = true)
            }
        }
        Btn(t("Custom exception…"), { vm.sheet = Sheet.Exception("OVERALL") }, Modifier.fillMaxWidth().padding(top = 6.dp), kind = BtnKind.GHOST, small = true)
    }
}

@Composable
private fun AppCard(vm: AppViewModel, m: Model, a: AppRow) {
    val p = pal
    val own = if (a.lim.unlimited) Double.POSITIVE_INFINITY else a.remaining
    val shown = a.effectiveRemaining
    val lvl = if (shown == Double.POSITIVE_INFINITY) Level.NONE else levelFor(shown, if (a.lim.unlimited) 0.0 else a.lim.effective.toDouble())
    PCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                Txt(appInitial(a.name), color = p.accent, size = 15.sp, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Txt(a.name, weight = FontWeight.SemiBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.active) { LiveDot(); Spacer(Modifier.width(2.dp)) }
                    Txt(
                        if (a.active) t("Running now") else if ((m.usage[a.name] ?: 0.0) > 0) t("Not running") else t("Not used today"),
                        color = p.text3, size = 12.5.sp,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Txt(
                    when {
                        shown == Double.POSITIVE_INFINITY -> t("No limit")
                        shown <= 0 -> if (m.pcReason == "lockdown-active") t("Locked") else t("Time up")
                        else -> fmtDur(shown, seconds = shown < 3600)
                    },
                    size = 17.sp, weight = FontWeight.Bold, maxLines = 1,
                    color = when (lvl) { Level.WARN -> p.warn; Level.DANGER -> p.danger; else -> p.text },
                )
                Txt(
                    if (a.cappedByPc && own != Double.POSITIVE_INFINITY) t("left · {v} on app", "v" to fmtDur(own))
                    else if (a.cappedByPc) t("until PC stops") else t("left"),
                    color = p.text3, size = 11.5.sp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val pctUsed = if (a.lim.unlimited) 0f else if (a.lim.effective > 0) min(1.0, a.used / a.lim.effective).toFloat() else 1f
        Bar(pctUsed, lvl)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Txt(t("Used {v}", "v" to fmtDur(a.used)), color = p.text3, size = 12.5.sp)
            Txt(if (a.lim.unlimited) t("No limit today") else t("Limit {v}", "v" to fmtDur(a.lim.effective.toDouble())), color = p.text3, size = 12.5.sp)
        }
        val exc = excSummary(a.lim)
        if (exc.isNotEmpty() || a.pending.isNotEmpty() || (!a.lim.unlimited && a.lim.effective <= 0)) {
            Chips(Modifier.padding(top = 10.dp)) {
                if (exc.isNotEmpty()) Chip(t("Exception: {v}", "v" to exc), Tone.ACCENT)
                if (a.pending.isNotEmpty()) Chip(t("+{n} pending", "n" to a.pending.size), Tone.INFO)
                if (!a.lim.unlimited && a.lim.effective <= 0) Chip(t("Blocked today"), Tone.DANGER)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(900L to t("+15m"), 1800L to t("+30m"), 3600L to t("+1h")).forEach { (secs, lbl) ->
                Btn(lbl, { vm.quickException(a.name, secs) }, Modifier.weight(1f), kind = BtnKind.SOFT, small = true, hPad = 4.dp)
            }
            Btn(t("More…"), { vm.sheet = Sheet.Exception(a.name) }, kind = BtnKind.GHOST, small = true, hPad = 8.dp)
            Btn(t("Edit"), { vm.sheet = Sheet.Limit(a.name) }, kind = BtnKind.GHOST, small = true, hPad = 8.dp)
        }
    }
}

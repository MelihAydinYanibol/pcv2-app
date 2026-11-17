package com.pcv2.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcv2.app.core.DAYS
import com.pcv2.app.core.DAY_SHORT
import com.pcv2.app.core.ExcKind
import com.pcv2.app.core.I18n
import com.pcv2.app.core.Level
import com.pcv2.app.core.appLabel
import com.pcv2.app.core.classifyEntry
import com.pcv2.app.core.compactDur
import com.pcv2.app.core.computeLimit
import com.pcv2.app.core.dayNameOf
import com.pcv2.app.core.fmtDur
import com.pcv2.app.core.strOrNull
import com.pcv2.app.core.t
import com.pcv2.app.core.tHtml
import java.net.URLDecoder
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

// ====================================================================== LIMITS
@Composable
fun LimitsScreen(vm: AppViewModel) {
    PageHead(t("Time limits"), tHtml("limitsIntro", "Daily allowance per application. <b>OVERALL</b> limits the whole computer.")) {
        Btn(t("+ Add application"), { vm.sheet = Sheet.Limit(null) }, Modifier.fillMaxWidth(), kind = BtnKind.PRIMARY)
    }
    val d = vm.data ?: return LoadingCard()
    val limits = d.limits
    val queue = d.queue
    val names = limits.keys.sortedWith { a, b -> if (a == "OVERALL") -1 else if (b == "OVERALL") 1 else a.compareTo(b, ignoreCase = true) }
    val pendingNew = queue.filter { it.method == "POST" && it.endpoint == "/limits" && it.data?.get("app_name").strOrNull() != null }
    val pendingDel = queue.filter { it.method == "DELETE" && it.endpoint.startsWith("/limits/") }.map { decode(it.endpoint.drop(8)) }.toSet()
    val pendingEdit = queue.filter { it.method == "PUT" && it.endpoint.startsWith("/limits/") }.map { decode(it.endpoint.drop(8).substringBefore('/')) }.toSet()
    if (names.isEmpty() && pendingNew.isEmpty()) {
        EmptyCard(t("No limits yet"), tHtml("noLimitsHint", "Add an application — or <span class=\"mono\">OVERALL</span> for the whole computer."))
        return
    }
    val today = dayNameOf(LocalDate.now())
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (name in names) {
            val l = limits[name] ?: emptyMap()
            PCard(padding = 14.dp) {
                Chips {
                    Txt(appLabel(name), weight = FontWeight.Bold)
                    if (name == "OVERALL") Txt("OVERALL", color = pal.text3, size = 13.sp, mono = true)
                    if (name in pendingDel) Chip(t("Deletion queued"), Tone.DANGER)
                    if (name in pendingEdit) Chip(t("Edit queued"), Tone.INFO)
                }
                DayCells(l, today)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Btn(t("Exception"), { vm.sheet = Sheet.Exception(name) }, Modifier.weight(1f), kind = BtnKind.SOFT, small = true)
                    Btn(t("Edit"), { vm.sheet = Sheet.Limit(name) }, Modifier.weight(1f), small = true)
                    Btn("✕", { vm.deleteLimit(name) }, Modifier.weight(1f).semanticsLabel(t("Delete")), kind = BtnKind.GHOST, small = true)
                }
            }
        }
        for (q in pendingNew) {
            val l = q.data?.get("limits")?.let { com.pcv2.app.core.Dash.parseLimits(kotlinx.serialization.json.buildJsonObject { put("x", it) })["x"] } ?: emptyMap()
            PCard(padding = 14.dp, background = pal.infoSoft) {
                Chips {
                    Txt(appLabel(q.data?.get("app_name").strOrNull() ?: ""), weight = FontWeight.Bold)
                    Chip(t("Queued"), Tone.INFO)
                }
                DayCells(l, null)
                Btn(t("Cancel"), { vm.cancelQueue(q.id) }, Modifier.fillMaxWidth().padding(top = 10.dp), small = true)
            }
        }
    }
    Hint(t("— means no limit for that day. A limit of 0 blocks the app for the whole day."), Modifier.padding(top = 8.dp))
}

@Composable
private fun DayCells(l: Map<String, Long?>, today: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        DAYS.forEach { day ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(
                    t(DAY_SHORT[day]!!).uppercase(I18n.locale), color = if (day == today) pal.accent else pal.text3,
                    size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp, maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                val v = l[day]
                when {
                    v == null -> Txt("—", color = pal.text3, size = 13.5.sp)
                    v == 0L -> Box(Modifier.clip(RoundedCornerShape(50)).background(pal.dangerSoft).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Txt(t("Blocked"), color = pal.danger, size = 10.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    }
                    else -> Txt(compactDur(v), size = 13.5.sp, weight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun LoadingCard() {
    PCard { Skeleton(Modifier.fillMaxWidth().height(80.dp)) }
}

// ====================================================================== EXCEPTIONS
@Composable
fun ExceptionsScreen(vm: AppViewModel) {
    PageHead(t("Exceptions"), t("One-off adjustments to a day's limit: add time, remove time, or set an exact limit.")) {
        Btn(t("+ New exception"), { vm.sheet = Sheet.Exception(null) }, Modifier.fillMaxWidth(), kind = BtnKind.PRIMARY)
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(t("Date"), Modifier.weight(1f)) {
            DateField(vm.excDate, { vm.excDate = it; vm.loadExceptions(force = true) }, Modifier.fillMaxWidth())
        }
        Btn(t("Today"), { vm.excDate = vm.dataDate(); vm.loadExceptions(force = true) }, Modifier.padding(bottom = 2.dp))
    }
    val d = vm.data ?: return LoadingCard()
    val date = vm.excDate
    LaunchedEffect(date, d.server.serverDate) { vm.loadExceptions(force = false) }
    val exc = vm.exceptionsFor(date)
    if (exc == null) {
        if (vm.excError != null && !vm.excLoading) EmptyCard(t("Could not load exceptions"), vm.excError ?: "") else LoadingCard()
        return
    }
    val queue = d.queue
    val queuedAdd = queue.filter { it.method == "POST" && it.endpoint == "/exceptions" && it.data?.get("date").strOrNull() == date }
    val prefix = "/exceptions/$date/"
    val delAll = mutableMapOf<String, Long>()
    val delTx = mutableMapOf<String, Long>()
    for (i in queue) {
        if (i.method != "DELETE" || !i.endpoint.startsWith(prefix)) continue
        val rest = i.endpoint.drop(prefix.length)
        val k = rest.indexOf('/')
        if (k == -1) delAll[decode(rest)] = i.id else delTx["${decode(rest.take(k))}/${rest.drop(k + 1)}"] = i.id
    }
    if (exc.isEmpty() && queuedAdd.isEmpty()) {
        EmptyCard(t("No exceptions on {date}", "date" to date), t("Grant extra time or set a special limit for a single day.")) {
            Btn(t("+ New exception"), { vm.sheet = Sheet.Exception(null) }, kind = BtnKind.PRIMARY)
        }
        return
    }
    val dayName = dayNameOf(date)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((app, list) in exc) {
            list.forEachIndexed { i, e ->
                val qid = delTx["$app/$i"]
                PCard(padding = 14.dp) {
                    Chips {
                        Txt(appLabel(app), weight = FontWeight.Bold)
                        if (qid != null) Chip(t("Deletion queued"), Tone.DANGER)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column {
                            CellLabel(t("Adjustment"))
                            ExcChip(e.value)
                        }
                        Column(Modifier.weight(1f)) {
                            CellLabel(t("Reason"))
                            val reason = e.reason?.takeIf { it.isNotEmpty() && it != "null" }
                            if (reason != null) Txt(reason, size = 14.5.sp) else Txt("—", color = pal.text3)
                        }
                        if (qid != null) Btn(t("Undo"), { vm.cancelQueue(qid) }, small = true)
                        Btn("✕", { vm.deleteExceptionTx(date, app, i) }, Modifier.semanticsLabel(t("Delete")), kind = BtnKind.GHOST, small = true)
                    }
                }
            }
            val lim = computeLimit(app, dayName, d.limits, exc)
            val allQ = delAll[app]
            PCard(padding = 14.dp, background = pal.surface2) {
                Chips {
                    Txt(t("{app} · result", "app" to appLabel(app)), weight = FontWeight.SemiBold)
                    if (allQ != null) Chip(t("Deletion queued"), Tone.DANGER)
                }
                CellLabel(t("Today's limit"), Modifier.padding(top = 8.dp))
                if (lim.unlimited) Txt(t("No limit configured"), size = 14.5.sp)
                else Text(
                    rich("${lim.configured?.let { fmtDur(it.toDouble()) } ?: t("No base limit")} → <b>${fmtDur(lim.effective.toDouble())}</b>"),
                    color = pal.text, fontSize = 14.5.sp,
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (allQ != null) Btn(t("Undo"), { vm.cancelQueue(allQ) }, Modifier.weight(1f), small = true)
                    Btn(t("Delete all"), { vm.deleteExceptionAll(date, app) }, Modifier.weight(1f), small = true)
                }
            }
        }
        for (q in queuedAdd) {
            PCard(padding = 14.dp, background = pal.infoSoft) {
                Chips {
                    Txt(appLabel(q.data?.get("app_name").strOrNull() ?: ""), weight = FontWeight.Bold)
                    Chip(t("Queued"), Tone.INFO)
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column { CellLabel(t("Adjustment")); q.data?.get("exception_time")?.let { ExcChip(it) } }
                    Column(Modifier.weight(1f)) {
                        CellLabel(t("Reason"))
                        Txt(q.data?.get("reason").strOrNull()?.takeIf { it.isNotEmpty() } ?: "—", color = if (q.data?.get("reason").strOrNull().isNullOrEmpty()) pal.text3 else pal.text)
                    }
                    Btn(t("Cancel"), { vm.cancelQueue(q.id) }, small = true)
                }
            }
        }
    }
}

@Composable
fun CellLabel(text: String, modifier: Modifier = Modifier) {
    Txt(text.uppercase(I18n.locale), modifier.padding(bottom = 2.dp), color = pal.text3, size = 10.5.sp, weight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
}

@Composable
fun ExcChip(v: kotlinx.serialization.json.JsonElement) {
    val c = classifyEntry(v)
    when (c.kind) {
        ExcKind.SET -> Chip("= ${fmtDur(c.value.toDouble())}", Tone.ACCENT)
        ExcKind.ADD -> Chip((if (c.value >= 0) "+" else "-") + fmtDur(abs(c.value).toDouble()), if (c.value >= 0) Tone.OK else Tone.DANGER)
        null -> Chip(v.toString())
    }
}

// ====================================================================== USAGE
@Composable
fun UsageScreen(vm: AppViewModel) {
    PageHead(t("Usage"), t("How long each monitored app was used."))
    val d = vm.data ?: return LoadingCard()
    val date = vm.usageDate
    LaunchedEffect(date) { vm.loadUsage() }
    PCard(Modifier.padding(bottom = 16.dp)) {
        CardTitle(t("Last 7 days · whole computer"))
        WeekChart(vm.weekColumns(), date) { vm.usageDate = it; vm.loadUsage() }
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Field(t("Date"), Modifier.weight(1f)) { DateField(date, { vm.usageDate = it; vm.loadUsage() }, Modifier.fillMaxWidth()) }
        Btn(t("Today"), { vm.usageDate = vm.dataDate() }, Modifier.padding(bottom = 2.dp))
    }
    val usage = vm.usageFor(date)
    if (usage == null) {
        if (vm.usageError != null && !vm.usageLoading) EmptyCard(t("Could not load usage"), vm.usageError ?: "") else LoadingCard()
        return
    }
    val names = usage.keys.sortedWith { a, b -> if (a == "OVERALL") -1 else if (b == "OVERALL") 1 else (usage[b] ?: 0.0).compareTo(usage[a] ?: 0.0) }
    if (names.isEmpty()) {
        EmptyCard(t("No usage recorded on {date}", "date" to date), t("Usage appears once the engine runs on the kid's computer."))
        return
    }
    val max = max(names.maxOf { usage[it] ?: 0.0 }, 1.0)
    val dayName = dayNameOf(date)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (n in names) {
            val secs = usage[n] ?: 0.0
            val lim = d.limits[n]?.get(dayName)
            val over = lim != null && secs >= lim
            PCard(padding = 14.dp) {
                Txt(appLabel(n), weight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { CellLabel(t("Used")); Txt(fmtDur(secs), weight = FontWeight.SemiBold) }
                    Column(Modifier.weight(1f)) {
                        CellLabel(t("Limit"))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (lim == null) Txt("—", color = pal.text3) else Txt(fmtDur(lim.toDouble()), weight = FontWeight.SemiBold)
                            if (over) Chip(t("reached"), Tone.DANGER)
                        }
                    }
                }
                Bar((secs / max).toFloat(), if (over) Level.DANGER else if (n == "OVERALL") Level.OK else Level.NONE)
            }
        }
    }
}

@Composable
private fun WeekChart(cols: List<Pair<String, Double>>, selected: String, onSelect: (String) -> Unit) {
    val max = max(cols.maxOfOrNull { it.second } ?: 0.0, 3600.0)
    Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        cols.forEach { (key, v) ->
            val sel = key == selected
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = "$key: ${fmtDur(v)}") { onSelect(key) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
            ) {
                Txt(if (v > 0) fmtDur(v) else "", color = pal.text2, size = 9.5.sp, maxLines = 1)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp)
                    Box(
                        Modifier.fillMaxWidth(0.7f).widthIn(max = 38.dp)
                            .fillMaxHeight(max((v / max).toFloat(), 0.02f))
                            .then(if (sel) Modifier.border(2.dp, pal.accentSoft, shape) else Modifier)
                            .clip(shape)
                            .background(pal.accent.copy(alpha = if (sel) 1f else 0.85f)),
                    )
                }
                Txt(t(DAY_SHORT[dayNameOf(key)]!!), color = if (sel) pal.accent else pal.text3, size = 11.5.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

// ====================================================================== QUEUE
@Composable
fun QueueScreen(vm: AppViewModel) {
    PageHead(t("Pending changes"), t("Changes made while the kid's computer was off. They are applied automatically when it comes back online.")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(t("Sync now"), { vm.syncNow() }, Modifier.weight(1f))
            Btn(t("Clear all"), { vm.clearQueue() }, Modifier.weight(1f), kind = BtnKind.DANGER)
        }
    }
    val d = vm.data ?: return LoadingCard()
    if (d.queue.isEmpty()) {
        EmptyCard(t("Nothing pending"), t("All changes have reached the kid's computer."))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (q in d.queue) {
            PCard(padding = 14.dp) {
                Text(rich(vm.describeQueueItem(q)), color = pal.text, fontSize = 15.sp, lineHeight = 21.sp)
                Btn(t("Cancel"), { vm.cancelQueue(q.id) }, Modifier.fillMaxWidth().padding(top = 10.dp), small = true)
            }
        }
    }
}

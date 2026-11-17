package com.pcv2.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcv2.app.core.DAYS
import com.pcv2.app.core.appLabel
import com.pcv2.app.core.computeLimit
import com.pcv2.app.core.dayNameOf
import com.pcv2.app.core.fmtDur
import com.pcv2.app.core.localDate
import com.pcv2.app.core.t
import com.pcv2.app.core.tHtml
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import kotlin.math.max

// ====================================================================== limit editor
@Composable
fun LimitSheet(vm: AppViewModel, appName: String?) {
    val isNew = appName == null
    val d = vm.data
    val existing = if (!isNew) d?.limits?.get(appName) ?: emptyMap() else emptyMap()
    val known = d?.limits?.keys ?: emptySet()
    val today = dayNameOf(LocalDate.now())
    val suggestions = (listOf("OVERALL") + (d?.todayUsage?.keys ?: emptySet())).distinct().filter { it !in known }

    var name by remember { mutableStateOf(appName ?: "") }
    val hours = remember { mutableStateMapOf<String, String>() }
    val mins = remember { mutableStateMapOf<String, String>() }
    val off = remember { mutableStateMapOf<String, Boolean>() }
    remember {
        DAYS.forEach { day ->
            val v = if (isNew) 3600L else existing[day]
            val unset = v == null
            val secs = v ?: 3600L
            hours[day] = (secs / 3600).toString()
            mins[day] = ((secs % 3600) / 60).toString()
            off[day] = unset
        }
        true
    }

    fun validate(): String? {
        val n = name.trim()
        if (n.isEmpty()) return t("Enter an application name.")
        if (isNew && n in known) return t("{app} already has a limit — edit it instead.", "app" to n)
        for (day in DAYS) {
            val h = hours[day]?.toIntOrNull() ?: 0
            val m = mins[day]?.toIntOrNull() ?: 0
            if (off[day] != true && (h < 0 || m < 0 || m > 59 || h * 3600 + m * 60 > 86400)) return t("{day}: enter a time between 0 and 24 hours.", "day" to t(day))
        }
        return null
    }

    fun fill(targets: List<String>) = targets.forEach { day ->
        hours[day] = hours["Monday"] ?: "0"; mins[day] = mins["Monday"] ?: "0"; off[day] = off["Monday"] == true
    }

    PSheet(
        onDismiss = { vm.sheet = null },
        icon = Ic.clock,
        title = if (isNew) t("Add application limit") else t("Edit {app}", "app" to appLabel(appName!!)),
        subtitle = if (isNew) t("Set how long the app may be used each day.") else t("Changes apply on the next check (about 5 seconds)."),
        body = {
            Field(t("Application"), Modifier.padding(bottom = 14.dp), hint = tHtml("processHint", "Process name exactly as Windows shows it (case-sensitive), or <span class=\"mono\">OVERALL</span> for total computer time.")) {
                Input(name, { name = it }, Modifier.fillMaxWidth(), mono = true, readOnly = !isNew, placeholder = "chrome.exe")
                if (isNew && suggestions.isNotEmpty()) {
                    Chips(Modifier.padding(top = 4.dp)) {
                        suggestions.forEach { s ->
                            Btn(if (s == "OVERALL") "OVERALL · ${t("Whole computer")}" else s, { name = s }, kind = BtnKind.SOFT, small = true)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint(t("Daily allowance"), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Btn(t("Copy Monday to all"), { fill(DAYS.drop(1)) }, Modifier.weight(1f), small = true, hPad = 6.dp)
                Btn(t("Copy Monday to weekdays"), { fill(DAYS.subList(1, 5)) }, Modifier.weight(1f), small = true, hPad = 6.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DAYS.forEach { day ->
                    val isOff = off[day] == true
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(t(day) + if (day == today) t(" · today") else "", Modifier.weight(1f), color = if (day == today) pal.accent else pal.text, size = 14.5.sp, weight = FontWeight.SemiBold)
                            CheckRow(t("No limit"), isOff, { off[day] = it })
                        }
                        HmInput(hours[day] ?: "", mins[day] ?: "", { hours[day] = it }, { mins[day] = it }, Modifier.fillMaxWidth(), enabled = !isOff)
                    }
                }
            }
        },
    ) { close ->
        Btn(t("Cancel"), { close {} }, Modifier.weight(1f).height(46.dp), kind = BtnKind.GHOST)
        Btn(if (isNew) t("Add") else t("Save"), {
            val err = validate()
            if (err != null) vm.toast(ToastType.ERROR, t("Check the form"), err)
            else {
                val limits = DAYS.associateWith { day ->
                    if (off[day] == true) null else (hours[day]?.toLongOrNull() ?: 0) * 3600 + (mins[day]?.toLongOrNull() ?: 0) * 60
                }
                val n = name.trim()
                close { vm.saveLimit(n, limits, isNew) }
            }
        }, Modifier.weight(1f).height(46.dp), kind = BtnKind.PRIMARY)
    }
}

// ====================================================================== exception dialog
@Composable
fun ExceptionSheet(vm: AppViewModel, app: String?, presetSecs: Long?) {
    val m = vm.model()
    val names = buildList {
        val ks = vm.data?.limits?.keys?.toList() ?: emptyList()
        if ("OVERALL" !in ks) add("OVERALL")
        addAll(ks)
        if (app != null && app !in this) add(app)
    }
    val initSecs = presetSecs ?: 1800L
    var sel by remember { mutableStateOf(app ?: "OVERALL") }
    var date by remember { mutableStateOf(localDate()) }
    var mode by remember { mutableStateOf("add") }
    var h by remember { mutableStateOf((initSecs / 3600).toString()) }
    var mm by remember { mutableStateOf(((initSecs % 3600) / 60).toString()) }
    var reason by remember { mutableStateOf("") }
    val secs = (h.toLongOrNull() ?: 0) * 3600 + (mm.toLongOrNull() ?: 0) * 60

    val preview: String = run {
        if (m == null || date != m.today || !m.sameDay) {
            val tpl = when (mode) {
                "set" -> "Sets the limit to <b>{v}</b> for {app} on {date}."
                "add" -> "Adds <b>{v}</b> for {app} on {date}."
                else -> "Removes <b>{v}</b> for {app} on {date}."
            }
            t(tpl, "v" to fmtDur(secs), "app" to appLabel(sel), "date" to date)
        } else {
            val lim = computeLimit(sel, m.dayName, vm.data?.limits ?: emptyMap(), m.excToday)
            val used = if (sel == "OVERALL") m.overall.used else m.apps.firstOrNull { it.name == sel }?.used ?: (m.usage[sel] ?: 0.0)
            val newLimit = if (mode == "set") secs else lim.effective + if (mode == "add") secs else -secs
            if (lim.unlimited && mode != "set") t("{app} has no limit today, so this adjustment changes nothing until a limit is set.", "app" to appLabel(sel))
            else t(
                "Today's limit becomes <b>{lim}</b> → <b>{rem}</b> left (now {now}).",
                "lim" to fmtDur(newLimit), "rem" to fmtDur(max(0.0, newLimit - used)), "now" to fmtDur(max(0.0, lim.effective - used)),
            )
        }
    }

    PSheet(
        onDismiss = { vm.sheet = null },
        icon = Ic.gift,
        title = t("Grant an exception"),
        subtitle = t("Applies to the selected day only."),
        body = {
            Field(t("Application"), Modifier.padding(bottom = 12.dp)) {
                Dropdown(names.map { it to appLabel(it) }, sel, { sel = it }, Modifier.fillMaxWidth())
            }
            Field(t("Date")) { DateField(date, { date = it }, Modifier.fillMaxWidth()) }
            Field(t("Adjustment"), Modifier.padding(top = 14.dp)) {
                SegCtl(listOf("add" to t("Add time"), "remove" to t("Remove time"), "set" to t("Set exact limit")), mode, { mode = it })
            }
            Field(t("Amount"), Modifier.padding(top = 14.dp)) {
                HmInput(h, mm, { h = it }, { mm = it }, Modifier.fillMaxWidth(), hLabel = t("hours"), mLabel = t("minutes"))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 90, 120).forEach { v ->
                        Btn(
                            if (v % 60 != 0) fmtDur(v * 60L) else "${v / 60}${t("h")}",
                            { h = (v / 60).toString(); mm = (v % 60).toString() },
                            Modifier.weight(1f), small = true, hPad = 2.dp,
                        )
                    }
                }
            }
            Field(t("Reason (optional)"), Modifier.padding(top = 14.dp)) {
                Input(reason, { reason = it.take(120) }, Modifier.fillMaxWidth(), placeholder = t("Homework, birthday, good grades…"))
            }
            Text(
                rich(preview, pal.accent), color = pal.text, fontSize = 14.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(pal.accentSoft).padding(horizontal = 14.dp, vertical = 12.dp),
            )
        },
    ) { close ->
        Btn(t("Cancel"), { close {} }, Modifier.weight(1f).height(46.dp), kind = BtnKind.GHOST)
        Btn(t("Apply exception"), {
            val err = when {
                date.isEmpty() -> t("Pick a date.")
                (mm.toIntOrNull() ?: 0) > 59 -> t("Minutes must be between 0 and 59.")
                mode != "set" && secs <= 0 -> t("Enter an amount greater than zero.")
                else -> null
            }
            if (err != null) vm.toast(ToastType.ERROR, t("Check the form"), err)
            else {
                // The engine treats integers as add/subtract and numeric strings as "set exact limit"
                val value = when (mode) {
                    "set" -> JsonPrimitive(secs.toString())
                    "remove" -> JsonPrimitive(-secs)
                    else -> JsonPrimitive(secs)
                }
                val a = sel; val dt = date; val r = reason.trim().ifEmpty { null }; val md = mode
                close { vm.createException(a, dt, value, r, md) }
            }
        }, Modifier.weight(1f).height(46.dp), kind = BtnKind.PRIMARY)
    }
}


package com.pcv2.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcv2.app.core.DAYS
import com.pcv2.app.core.I18n
import com.pcv2.app.core.NightLockdown
import com.pcv2.app.core.Schedule
import com.pcv2.app.core.TimeWindow
import com.pcv2.app.core.dayNameOf
import com.pcv2.app.core.fmtDur
import com.pcv2.app.core.fmtTime
import com.pcv2.app.core.localDate
import com.pcv2.app.core.lockdownInfo
import com.pcv2.app.core.nlWindowLen
import com.pcv2.app.core.secondsUntil
import com.pcv2.app.core.t
import com.pcv2.app.core.tHtml
import com.pcv2.app.core.toMin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime

// ====================================================================== NIGHT LOCKDOWN
@Composable
fun LockdownScreen(vm: AppViewModel) {
    PageHead(
        t("Night lockdown"),
        tHtml("lockdownIntro", "The computer shuts down inside <b>blocked</b> zones unless a <b>whitelisted</b> zone covers that time. Zones may cross midnight (22:00 → 07:00)."),
    )
    val d = vm.data ?: return LoadingCard()
    val nl = vm.nlCurrent()
    PCard(Modifier.padding(bottom = 14.dp)) {
        SwitchRow(t("Enable night lockdown"), nl.enabled, { v -> vm.editNl { it.copy(enabled = v) } })
        SwitchRow(t("Different schedule per day"), nl.perDay, { v ->
            vm.editNl {
                var n = it.copy(perDay = v)
                if (v && DAYS.all { day -> n.day(day).blocked.isEmpty() && n.day(day).whitelist.isEmpty() }) {
                    n = n.copy(days = DAYS.associateWith { n.allDays })
                }
                n
            }
        })
        LockdownStatus(vm, nl)
    }
    if (d.queue.any { it.endpoint == "/nightlockdown" }) {
        Chips(Modifier.padding(bottom = 12.dp)) { Chip(t("A lockdown change is queued and will apply when the kid's PC is online"), Tone.INFO) }
    }
    val today = dayNameOf(LocalDate.now())
    val scopes: List<Triple<String, String, Schedule>> =
        if (nl.perDay) DAYS.map { Triple(it, t(it), nl.day(it)) } else listOf(Triple("all", t("Every day"), nl.allDays))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((key, title, sch) in scopes) {
            PCard(alpha = if (nl.enabled) 1f else 0.7f) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(title, weight = FontWeight.SemiBold, size = 15.5.sp)
                    if (key == today) Chip(t("Today"), Tone.ACCENT)
                    Spacer(Modifier.weight(1f))
                    if (nl.perDay) Btn(t("Copy to all days"), { copyToAll(vm, key) }, kind = BtnKind.GHOST, small = true)
                }
                Timeline(scheduleSegs(sch), null)
                for (type in listOf("blocked", "whitelist")) {
                    Column(Modifier.padding(top = 14.dp)) {
                        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (type == "blocked") Chip(t("Blocked"), Tone.DANGER) else Chip(t("Whitelisted"), Tone.OK)
                            Txt(if (type == "blocked") t("computer shuts down") else t("allowed even inside a blocked zone"), color = pal.text2, size = 13.sp, weight = FontWeight.SemiBold)
                        }
                        val list = sch.get(type)
                        if (list.isEmpty()) {
                            Txt(if (type == "blocked") t("No blocked zones.") else t("No whitelisted zones."), Modifier.padding(bottom = 8.dp), color = pal.text3, size = 14.sp)
                        }
                        list.forEachIndexed { i, w ->
                            Column(Modifier.padding(bottom = 12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TimeField(w.start, { v -> updateWindow(vm, key, type, i) { it.copy(start = v) } }, Modifier.weight(1f), t("Start"))
                                    Txt("→", color = pal.text3)
                                    TimeField(w.end, { v -> updateWindow(vm, key, type, i) { it.copy(end = v) } }, Modifier.weight(1f), t("End"))
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Hint(nlWindowLen(w), Modifier.weight(1f))
                                    Btn("✕", { removeWindow(vm, key, type, i) }, Modifier.semanticsLabel(t("Remove")), kind = BtnKind.GHOST, small = true)
                                }
                            }
                        }
                        Btn(
                            if (type == "blocked") t("+ Add blocked zone") else t("+ Add whitelisted zone"),
                            { addWindow(vm, key, type) }, Modifier.fillMaxWidth(), small = true,
                        )
                    }
                }
            }
        }
    }
}

private fun schedOf(nl: NightLockdown, key: String) = if (key == "all") nl.allDays else nl.day(key)
private fun withSched(nl: NightLockdown, key: String, s: Schedule) =
    if (key == "all") nl.copy(allDays = s) else nl.copy(days = nl.days + (key to s))

private fun updateWindow(vm: AppViewModel, key: String, type: String, i: Int, f: (TimeWindow) -> TimeWindow) = vm.editNl { nl ->
    val s = schedOf(nl, key)
    withSched(nl, key, s.with(type, s.get(type).mapIndexed { j, w -> if (j == i) f(w) else w }))
}

private fun removeWindow(vm: AppViewModel, key: String, type: String, i: Int) = vm.editNl { nl ->
    val s = schedOf(nl, key)
    withSched(nl, key, s.with(type, s.get(type).filterIndexed { j, _ -> j != i }))
}

private fun addWindow(vm: AppViewModel, key: String, type: String) = vm.editNl { nl ->
    val s = schedOf(nl, key)
    val w = if (type == "blocked") TimeWindow("22:00", "07:00") else TimeWindow("20:00", "21:00")
    withSched(nl, key, s.with(type, s.get(type) + w))
}

private fun copyToAll(vm: AppViewModel, day: String) = vm.launch {
    if (!vm.confirm(
            t("Copy {day} to every day?", "day" to t(day)),
            t("All other days will get the same blocked and whitelisted zones. Nothing is saved until you press Save."),
            okLabel = t("Copy"),
        )
    ) return@launch
    vm.editNl { nl -> nl.copy(days = DAYS.associateWith { nl.day(day) }) }
}

private fun scheduleSegs(sch: Schedule): List<Seg> {
    val segs = mutableListOf<Seg>()
    fun add(ws: List<TimeWindow>, kind: Int) = ws.forEach { w ->
        val s = toMin(w.start) ?: return@forEach
        val e = toMin(w.end) ?: return@forEach
        if (s <= e) segs += Seg(s / 1440f, (e + 1) / 1440f, kind)
        else { segs += Seg(s / 1440f, 1f, kind); segs += Seg(0f, (e + 1) / 1440f, kind) }
    }
    add(sch.blocked, 1)
    add(sch.whitelist, 2)
    return segs
}

@Composable
private fun LockdownStatus(vm: AppViewModel, nl: NightLockdown) {
    vm.now
    val lk = lockdownInfo(nl, LocalDateTime.now())
    Chips(Modifier.padding(top = 14.dp)) {
        when {
            lk == null -> Chip(t("Lockdown is off"))
            lk.activeNow -> Chip(
                lk.endsAt?.let { t("Active now · ends {t} (in {d})", "t" to fmtTime(it), "d" to fmtDur(secondsUntil(it))) } ?: t("Active now"),
                Tone.DANGER,
            )
            lk.startsAt != null -> {
                val inSec = secondsUntil(lk.startsAt)
                Chip(t("Next lockdown {t} · in {d}", "t" to fmtTime(lk.startsAt), "d" to fmtDur(inSec, seconds = inSec < 3600)), Tone.WARN)
                lk.endsAt?.let { Chip(t("until {t}", "t" to fmtTime(it))) }
            }
            else -> Chip(t("No blocked zones configured"))
        }
        if (vm.nlDirty) Chip(t("Preview of unsaved changes"), Tone.INFO)
    }
}

/** Sticky "You have unsaved changes · Discard · Save" bar. */
@Composable
fun LockdownSaveBar(vm: AppViewModel) {
    val p = pal
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(p.text)
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(t("You have unsaved changes"), Modifier.weight(1f), color = p.bg, size = 14.5.sp, weight = FontWeight.SemiBold)
        Box(Modifier.clip(RoundedCornerShape(8.dp))) {
            Btn(t("Discard"), { vm.discardNl() }, kind = BtnKind.GHOST, small = true)
        }
        Btn(t("Save"), { vm.saveNl() }, kind = BtnKind.PRIMARY, small = true)
    }
}

// ====================================================================== SETTINGS
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    PageHead(t("Settings"), t("These settings are stored on this phone only."))
    val s = vm.settings
    var server by remember(s.server) { mutableStateOf(s.server) }
    var poll by remember(s.pollSec) { mutableStateOf(s.pollSec.toString()) }
    var buf by remember(s.bufferSec) { mutableStateOf(s.bufferSec.toString()) }
    var theme by remember(s.theme) { mutableStateOf(s.theme) }

    PCard {
        CardTitle(t("Dashboard"))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field(t("Server address"), hint = t("Changing the address signs you out of the current server.")) {
                Input(server, { server = it }, Modifier.fillMaxWidth(), mono = true, placeholder = "http://192.168.1.10:5035", keyboardType = KeyboardType.Uri)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                Field(t("Refresh every (seconds)"), Modifier.weight(1f)) {
                    Input(poll, { poll = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), center = true, keyboardType = KeyboardType.Number)
                }
                Field(t("Buffer a sample every (seconds)"), Modifier.weight(1f)) {
                    Input(buf, { buf = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), center = true, keyboardType = KeyboardType.Number)
                }
            }
            Field(t("Theme")) {
                SegCtl(listOf("auto" to t("Match system"), "light" to t("Light"), "dark" to t("Dark")), theme, { theme = it })
            }
            Field(t("Language")) {
                SegCtl(listOf("en" to "English", "tr" to "Türkçe"), I18n.lang, { vm.setLang(it) })
            }
            Btn(t("Save settings"), {
                vm.saveSettings(server, poll.toIntOrNull() ?: 5, buf.toIntOrNull() ?: 30, theme)
            }, Modifier.fillMaxWidth(), kind = BtnKind.PRIMARY)
        }
    }

    // ---------------------------------------------------------------- buffer
    val exportBuffer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(vm.buffer.exportJson().toByteArray()) } != null }.getOrDefault(false)
            }
            if (ok) vm.toast(ToastType.SUCCESS, t("Buffer saved")) else vm.toast(ToastType.ERROR, t("Could not save the file"))
        }
    }
    Spacer(Modifier.height(16.dp))
    PCard {
        CardTitle(t("App buffer"))
        Hint(t("The app keeps the latest data and periodic usage samples on this phone, so you can still see them if the kid's computer is turned off or the server is unreachable."), Modifier.padding(bottom = 12.dp))
        val todaySamples = vm.history.count { it.date == localDate() }
        val size = remember(vm.bufferSavedAt, vm.history.size) { vm.buffer.sizeBytes() }
        Kv(
            listOf(
                t("Last snapshot") to if (vm.bufferSavedAt > 0) fmtTime(vm.bufferSavedAt) else t("None"),
                t("Kid's PC last seen") to if (vm.lastSeenMs() > 0) fmtTime(vm.lastSeenMs()) else t("Unknown"),
                t("Usage samples") to t("{n} ({today} today)", "n" to vm.history.size, "today" to todaySamples),
                t("Storage used") to "%.1f KB".format(size / 1024.0),
            ),
        )
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(t("Download buffer"), { exportBuffer.launch("pcv2_buffer_${localDate()}.json") }, Modifier.weight(1f))
            Btn(t("Clear buffer"), { vm.clearBuffer() }, Modifier.weight(1f), kind = BtnKind.DANGER)
        }
    }

    // ---------------------------------------------------------------- account
    Spacer(Modifier.height(16.dp))
    PCard {
        CardTitle(t("Account"))
        Txt(t("Signed in to {url}", "url" to s.server), size = 14.5.sp, weight = FontWeight.Medium)
        Hint(
            when (s.authMode) {
                "token" -> t("with an API token")
                "none" -> "AUTH_DISABLED"
                else -> t("with the dashboard password")
            },
        )
        Btn(t("Sign out"), { vm.sheet = Sheet.SignOut }, Modifier.fillMaxWidth().padding(top = 12.dp))
    }

    // ---------------------------------------------------------------- configuration
    val exportConfig = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = vm.configJson()
        if (uri != null && json != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null }.getOrDefault(false)
            }
            if (ok) vm.toast(ToastType.SUCCESS, t("Configuration downloaded")) else vm.toast(ToastType.ERROR, t("Could not save the file"))
        }
    }
    val importConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "config.json"
            if (text == null) vm.toast(ToastType.ERROR, t("Could not read the file")) else vm.uploadConfig(text, name)
        }
    }
    SectionTitle(t("Configuration")) {
        Btn(t("Download"), {
            if (vm.configJson() == null) vm.toast(ToastType.ERROR, t("No configuration loaded yet"))
            else exportConfig.launch("pcv2_config_${localDate()}.json")
        }, small = true)
        Btn(t("Upload…"), { importConfig.launch(arrayOf("application/json", "text/plain", "*/*")) }, small = true)
    }
    val json = vm.configJson()
    Box(
        Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(9.dp)).background(pal.surface2)
            .border(1.dp, pal.border, RoundedCornerShape(9.dp)),
    ) {
        SelectionContainer {
            Txt(
                json ?: t("No configuration loaded yet"),
                Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
                color = if (json == null) pal.text3 else pal.text, size = 12.sp, mono = true, lineHeight = 17.sp,
            )
        }
    }
}

// ====================================================================== SIGN OUT
@Composable
fun SignOutSheet(vm: AppViewModel) {
    var wipe by remember { mutableStateOf(true) }
    PSheet(
        onDismiss = { vm.sheet = null },
        icon = Ic.question,
        title = t("Sign out?"),
        subtitle = t("You will need the dashboard password to sign in again."),
        body = {
            CheckRow(t("Also delete the data buffered on this phone"), wipe, { wipe = it })
            Hint(t("Recommended on a shared or borrowed device."), Modifier.padding(top = 6.dp))
        },
    ) { close ->
        Btn(t("Cancel"), { close {} }, Modifier.weight(1f).height(46.dp), kind = BtnKind.GHOST)
        Btn(t("Sign out"), { close { vm.signOut(wipe) } }, Modifier.weight(1f).height(46.dp), kind = BtnKind.PRIMARY)
    }
}

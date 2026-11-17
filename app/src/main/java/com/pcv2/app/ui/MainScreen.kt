package com.pcv2.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcv2.app.core.I18n
import com.pcv2.app.core.fmtAgo
import com.pcv2.app.core.fmtTime
import com.pcv2.app.core.t
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel, dark: Boolean) {
    val p = pal
    val scope = rememberCoroutineScope()
    var manualRefreshing by remember { mutableStateOf(false) }
    val backStack = remember { mutableStateListOf<Tab>() }
    val go: (Tab) -> Unit = { tab ->
        if (tab != vm.tab) {
            backStack.remove(tab)
            backStack.add(vm.tab)
            vm.tab = tab
            when (tab) {
                Tab.USAGE -> { vm.loadUsage(); vm.loadUsageAll() }
                Tab.EXCEPTIONS -> vm.loadExceptions(force = true)
                else -> {}
            }
        }
    }
    BackHandler(enabled = backStack.isNotEmpty()) { vm.tab = backStack.removeAt(backStack.lastIndex) }

    Column(Modifier.fillMaxSize().background(p.bg)) {
        TopBar(vm, dark, go, onRefresh = {
            scope.launch { manualRefreshing = true; vm.doRefresh(manual = true); manualRefreshing = false }
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PullToRefreshBox(
                isRefreshing = manualRefreshing,
                onRefresh = { scope.launch { manualRefreshing = true; vm.doRefresh(manual = true); manualRefreshing = false } },
                modifier = Modifier.fillMaxSize(),
            ) {
                val scroll = remember(vm.tab) { ScrollState(0) }
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(ScreenPadding)) {
                        ConnectionBanner(vm)
                        when (vm.tab) {
                            Tab.OVERVIEW -> OverviewScreen(vm)
                            Tab.LIMITS -> LimitsScreen(vm)
                            Tab.EXCEPTIONS -> ExceptionsScreen(vm)
                            Tab.LOCKDOWN -> LockdownScreen(vm)
                            Tab.USAGE -> UsageScreen(vm)
                            Tab.QUEUE -> QueueScreen(vm)
                            Tab.SETTINGS -> SettingsScreen(vm)
                        }
                        FootNote(vm)
                        // Room for the lockdown save bar
                        if (vm.tab == Tab.LOCKDOWN && vm.nlDirty) Spacer(Modifier.height(72.dp))
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp)) {
                ToastHost(vm)
                if (vm.tab == Tab.LOCKDOWN && vm.nlDirty) LockdownSaveBar(vm)
            }
        }
        BottomNav(vm.tab, go)
    }

    // Sheets
    when (val s = vm.sheet) {
        is Sheet.Limit -> LimitSheet(vm, s.app)
        is Sheet.Exception -> ExceptionSheet(vm, s.app, s.secs)
        Sheet.SignOut -> SignOutSheet(vm)
        null -> {}
    }
    ConfirmSheet(vm)
}

// ====================================================================== top bar
@Composable
private fun TopBar(vm: AppViewModel, dark: Boolean, go: (Tab) -> Unit, onRefresh: () -> Unit) {
    val p = pal
    Column(Modifier.fillMaxWidth().background(p.surface.copy(alpha = 0.96f)).statusBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 400.dp
            val tiny = maxWidth < 350.dp
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (narrow) 6.dp else 8.dp),
            ) {
                if (!narrow) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(p.accent), contentAlignment = Alignment.Center) {
                        Icon(Ic.brand, null, tint = p.accentText, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                ConnPill(vm, dotOnly = tiny)
                val qn = vm.data?.queue?.size ?: 0
                IconBtn(Ic.queue, t("Pending changes"), { go(Tab.QUEUE) }, badge = qn)
                IconBtn(Ic.settings, t("Settings"), { go(Tab.SETTINGS) })
                IconBtn(
                    Ic.info, if (I18n.lang == "tr") t("Switch to English (L)") else t("Switch to Turkish (L)"),
                    { vm.toggleLang() }, text = if (I18n.lang == "tr") "EN" else "TR",
                )
                IconBtn(
                    if (dark) Ic.sun else Ic.moon, if (dark) t("Switch to light mode") else t("Switch to dark mode"),
                    { vm.setTheme(if (dark) "light" else "dark") },
                )
                IconBtn(Ic.refresh, t("Refresh now"), onRefresh, spinning = vm.refreshing)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
    }
}

@Composable
private fun ConnPill(vm: AppViewModel, dotOnly: Boolean) {
    val p = pal
    vm.now // re-read every second for "seen 12s ago"
    val (bg, fg, text) = when {
        vm.source == Source.LIVE -> Triple(p.okSoft, p.ok, t("Live"))
        vm.source == Source.CACHE -> Triple(p.warnSoft, p.warn, t("PC off"))
        vm.source == Source.BUFFER && !vm.booting -> Triple(p.dangerSoft, p.danger, t("Offline"))
        else -> Triple(p.surface2, p.text2, "…")
    }
    val description = when (vm.source) {
        Source.LIVE -> t("Live · kid's PC online")
        Source.CACHE -> t("Kid's PC offline · seen {ago}", "ago" to fmtAgo(vm.lastSeenMs()))
        Source.BUFFER -> if (vm.booting) t("Connecting…") else if (vm.data != null) t("Server unreachable · buffered {ago}", "ago" to fmtAgo(vm.bufferSavedAt)) else t("Server unreachable")
        Source.NONE -> t("Connecting…")
    }
    val neutral = bg == p.surface2
    Row(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .then(if (neutral) Modifier.border(1.dp, p.border, CircleShape) else Modifier)
            .semanticsLabel(description)
            .then(if (dotOnly) Modifier.size(30.dp) else Modifier.heightIn(min = 30.dp).padding(horizontal = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        if (vm.source == Source.LIVE) LiveDot(p.ok, 8.dp)
        else Box(Modifier.size(if (dotOnly) 10.dp else 8.dp).clip(CircleShape).background(if (neutral) p.text3 else fg))
        if (!dotOnly) Txt(text, color = fg, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ====================================================================== bottom navigation
private data class NavItem(val tab: Tab, val icon: ImageVector, val label: String)

@Composable
private fun BottomNav(current: Tab, go: (Tab) -> Unit) {
    val p = pal
    val items = listOf(
        NavItem(Tab.OVERVIEW, Ic.today, t("Today")),
        NavItem(Tab.LIMITS, Ic.limits, t("Limits")),
        NavItem(Tab.EXCEPTIONS, Ic.gift, t("Exceptions")),
        NavItem(Tab.LOCKDOWN, Ic.moon, t("Lockdown")),
        NavItem(Tab.USAGE, Ic.usage, t("Usage")),
    )
    Column(Modifier.fillMaxWidth().background(p.surface.copy(alpha = 0.97f))) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(6.dp)) {
            items.forEach { item ->
                val active = item.tab == current
                Column(
                    Modifier.weight(1f).heightIn(min = 54.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Tab, onClickLabel = item.label) { go(item.tab) }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(if (active) p.accentSoft else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    ) { Icon(item.icon, null, tint = if (active) p.accent else p.text3, modifier = Modifier.size(22.dp)) }
                    Txt(item.label, color = if (active) p.accent else p.text3, size = 11.sp, weight = FontWeight.SemiBold, maxLines = 1, align = TextAlign.Center)
                }
            }
        }
    }
}

// ====================================================================== banner + footer
@Composable
private fun ConnectionBanner(vm: AppViewModel) {
    vm.now
    val seen = vm.lastSeenMs()
    when {
        vm.source == Source.CACHE -> Banner(
            Tone.WARN, t("The kid's computer is turned off or unreachable."),
            if (seen > 0) t("Showing the last known data from {t}. Changes you make are queued and applied when it comes back online.", "t" to fmtTime(seen))
            else t("Showing the last known data. Changes you make are queued and applied when it comes back online."),
        )
        vm.source == Source.BUFFER && !vm.booting -> Banner(
            Tone.DANGER, t("Cannot reach the server at {url}.", "url" to vm.apiBase),
            when {
                vm.data == null -> t("No buffered data on this phone yet. Check that secondary_api.py is running, or change the address under Settings.")
                seen > 0 -> t("Showing data buffered on this phone at {t} (kid's PC last seen {seen}). Changes cannot be saved until the server is reachable.", "t" to fmtTime(vm.bufferSavedAt), "seen" to fmtTime(seen))
                else -> t("Showing data buffered on this phone at {t}. Changes cannot be saved until the server is reachable.", "t" to fmtTime(vm.bufferSavedAt))
            } + (vm.lastError?.takeIf { it != t("Cannot reach the server") }?.let { "\n$it" } ?: ""),
        )
    }
}

@Composable
private fun FootNote(vm: AppViewModel) {
    vm.now
    val seen = vm.lastSeenMs()
    val text = if (vm.data != null) {
        if (vm.source == Source.LIVE) t("Data updated {t} · refreshing every {n}s · {api}", "t" to fmtAgo(vm.receivedAt), "n" to vm.settings.pollSec, "api" to vm.apiBase)
        else t(
            "Data from {t} · refreshing every {n}s · {api}",
            "t" to fmtTime(if (vm.source == Source.BUFFER) (if (vm.bufferSavedAt > 0) vm.bufferSavedAt else seen) else (if (seen > 0) seen else vm.receivedAt)),
            "n" to vm.settings.pollSec, "api" to vm.apiBase,
        )
    } else vm.apiBase
    Txt(text, Modifier.fillMaxWidth().padding(top = 18.dp), color = pal.text3, size = 12.sp, align = TextAlign.Center)
}

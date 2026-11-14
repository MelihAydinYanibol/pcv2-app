package com.pcv2.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pcv2.app.core.I18n
import com.pcv2.app.core.t

/** Mirrors serverside/login.html, plus the server address and the API-token option. */
@Composable
fun LoginScreen(vm: AppViewModel) {
    val p = pal
    val focus = LocalFocusManager.current
    var server by remember { mutableStateOf(vm.loginServer) }
    var mode by remember { mutableStateOf(if (vm.settings.authMode == "token") "token" else "password") }
    var secret by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        focus.clearFocus()
        error = null
        vm.login(server, mode, secret) { error = it }
    }

    Box(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
        Box(
            Modifier.align(Alignment.TopEnd).padding(14.dp).size(40.dp).clip(RoundedCornerShape(11.dp)).background(p.surface)
                .border(1.dp, p.border, RoundedCornerShape(11.dp)).clickable(role = Role.Button) { vm.toggleLang() },
            contentAlignment = Alignment.Center,
        ) { Txt(if (I18n.lang == "tr") "EN" else "TR", color = p.text2, size = 12.5.sp, weight = FontWeight.ExtraBold) }

        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(40.dp))
            val shape = RoundedCornerShape(20.dp)
            Column(
                Modifier.widthIn(max = 400.dp).fillMaxWidth()
                    .shadow(if (p.dark) 0.dp else 6.dp, shape, ambientColor = Color(0x22141828), spotColor = Color(0x22141828))
                    .clip(shape).background(p.surface).border(1.dp, p.border, shape)
                    .padding(start = 26.dp, end = 26.dp, top = 30.dp, bottom = 26.dp),
            ) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(p.accent), contentAlignment = Alignment.Center) {
                    Icon(Ic.lockShield, null, tint = p.accentText, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(18.dp))
                Txt(t("Parental Controls"), size = 21.sp, weight = FontWeight.Bold)
                Txt(t("Enter the dashboard password to continue."), Modifier.padding(top = 4.dp, bottom = 22.dp), color = p.text2, size = 14.5.sp)

                val msg = error ?: vm.loginNotice
                if (msg != null) {
                    Txt(
                        msg, Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(10.dp))
                            .background(p.dangerSoft).padding(horizontal = 12.dp, vertical = 10.dp),
                        color = p.danger, size = 14.sp, weight = FontWeight.Medium,
                    )
                }

                Field(t("Server address"), hint = t("Address of the server running secondary_api.py, e.g. http://192.168.1.10:5035")) {
                    Input(
                        server, { server = it }, Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
                        mono = true, placeholder = "http://192.168.1.10:5035", keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Field(t("Sign-in method")) {
                    SegCtl(listOf("password" to t("Password"), "token" to t("API token")), mode, { mode = it; secret = ""; error = null })
                }
                Spacer(Modifier.height(14.dp))
                Field(if (mode == "token") t("API token") else t("Password"), hint = if (mode == "token") t("Token from DASHBOARD_API_TOKEN in serverside/.env.") else t("Leave it empty if the server runs without sign-in (AUTH_DISABLED).")) {
                    Input(
                        secret, { secret = it.take(1024) },
                        Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                        password = !show, mono = mode == "token", keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Go, onIme = submit, minHeight = 48.dp,
                        trailing = {
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).clickable(role = Role.Button, onClickLabel = if (show) t("Hide password") else t("Show password")) { show = !show },
                                contentAlignment = Alignment.Center,
                            ) { Icon(if (show) Ic.eyeOff else Ic.eye, null, tint = p.text3, modifier = Modifier.size(20.dp)) }
                        },
                    )
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(11.dp)).background(p.accent.copy(alpha = if (vm.loggingIn) 0.6f else 1f))
                        .clickable(enabled = !vm.loggingIn, role = Role.Button, onClick = submit),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (vm.loggingIn) { Spinner(18.dp, p.accentText); Spacer(Modifier.size(8.dp)) }
                    Txt(if (vm.loggingIn) t("Signing in…") else t("Sign in"), color = p.accentText, size = 16.sp, weight = FontWeight.SemiBold)
                }
                Txt(
                    t("Android can save this password for next time."), Modifier.fillMaxWidth().padding(top = 18.dp),
                    color = p.text3, size = 12.5.sp, align = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

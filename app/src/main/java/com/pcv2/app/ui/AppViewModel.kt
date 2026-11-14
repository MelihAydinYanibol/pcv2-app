package com.pcv2.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcv2.app.BuildConfig
import com.pcv2.app.core.DAYS
import com.pcv2.app.core.Dash
import com.pcv2.app.core.ExcEntry
import com.pcv2.app.core.ExcKind
import com.pcv2.app.core.I18n
import com.pcv2.app.core.JSON
import com.pcv2.app.core.JSON_PRETTY
import com.pcv2.app.core.LimitCalc
import com.pcv2.app.core.LockInfo
import com.pcv2.app.core.NightLockdown
import com.pcv2.app.core.QueueItem
import com.pcv2.app.core.appLabel
import com.pcv2.app.core.asObj
import com.pcv2.app.core.classifyEntry
import com.pcv2.app.core.computeLimit
import com.pcv2.app.core.dayNameOf
import com.pcv2.app.core.fmtDur
import com.pcv2.app.core.limitsToJson
import com.pcv2.app.core.localDate
import com.pcv2.app.core.lockdownInfo
import com.pcv2.app.core.parseExceptionDay
import com.pcv2.app.core.parseIsoMillis
import com.pcv2.app.core.parseUsageDay
import com.pcv2.app.core.secondsUntil
import com.pcv2.app.core.signed
import com.pcv2.app.core.strOrNull
import com.pcv2.app.core.t
import com.pcv2.app.core.toMin
import com.pcv2.app.data.ApiClient
import com.pcv2.app.data.ApiException
import com.pcv2.app.data.ApiResult
import com.pcv2.app.data.AuthRequiredException
import com.pcv2.app.data.Buffer
import com.pcv2.app.data.BufferSnapshot
import com.pcv2.app.data.LoginResult
import com.pcv2.app.data.Prefs
import com.pcv2.app.data.Sample
import com.pcv2.app.data.Settings
import com.pcv2.app.data.normalizeServer
import com.pcv2.app.data.seg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min

enum class Source { NONE, LIVE, CACHE, BUFFER }

enum class Tab { OVERVIEW, LIMITS, EXCEPTIONS, LOCKDOWN, USAGE, QUEUE, SETTINGS }

enum class ToastType { SUCCESS, ERROR, WARNING, INFO }

data class Toast(val id: Long, val type: ToastType, val title: String, val message: String, val durationMs: Long)

enum class DlgIcon { QUESTION, DANGER, WARN, CLOCK, EDIT, GIFT }

sealed interface Sheet {
    data class Limit(val app: String?) : Sheet
    data class Exception(val app: String?, val secs: Long? = null) : Sheet
    data object SignOut : Sheet
}

class ConfirmReq(
    val title: String,
    val message: String,
    val okLabel: String,
    val danger: Boolean,
    val icon: DlgIcon,
    val result: CompletableDeferred<Boolean>,
)

data class AppRow(
    val name: String,
    val lim: LimitCalc,
    val used: Double,
    val remaining: Double,
    val active: Boolean,
    val pending: List<QueueItem>,
    val effectiveRemaining: Double = Double.POSITIVE_INFINITY,
    val cappedByPc: Boolean = false,
)

data class Model(
    val today: String,
    val dayName: String,
    val sameDay: Boolean,
    val overall: AppRow,
    val lockdown: LockInfo?,
    val pcRemaining: Double,
    val pcReason: String?,          // null | "limit" | "lockdown" | "lockdown-active"
    val apps: List<AppRow>,
    val extra: List<String>,
    val usage: Map<String, Double>,
    val excToday: Map<String, List<ExcEntry>>,
    val live: Boolean,
)

private const val ACTIVE_WINDOW = 16_000L   // usage grew within this window -> counting down
private const val HISTORY_MAX = 2000

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val buffer = Buffer(app)
    val api = ApiClient()

    // ------------------------------------------------------------ settings / session
    var settings by mutableStateOf(prefs.load())
        private set
    var signedIn by mutableStateOf(settings.signedIn)
        private set
    var loginNotice by mutableStateOf<String?>(null)
    var loginServer by mutableStateOf(settings.server.ifEmpty { BuildConfig.DEFAULT_SERVER })

    // ------------------------------------------------------------ data
    var data by mutableStateOf<Dash?>(null)
        private set
    var source by mutableStateOf(Source.NONE)
        private set
    var receivedAt by mutableLongStateOf(0L)
        private set
    var lastLiveAt by mutableLongStateOf(0L)
        private set
    var bufferSavedAt by mutableLongStateOf(0L)
        private set
    var booting by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    /** Ticks every second; read it to make a composable live. */
    var now by mutableLongStateOf(System.currentTimeMillis())
        private set

    private val lastIncrease = mutableMapOf<String, Long>()
    private var prevUsage: Pair<String, Map<String, Double>>? = null
    var history by mutableStateOf(buffer.loadHistory())
        private set
    private var lastSampleAt = history.lastOrNull()?.t ?: 0L

    var usageAll by mutableStateOf<Map<String, Map<String, Double>>>(emptyMap())
        private set
    val excCache = mutableStateMapOf<String, Map<String, List<ExcEntry>>>()
    var excDate by mutableStateOf(localDate())
    var excLoading by mutableStateOf(false)
    var excError by mutableStateOf<String?>(null)
    var usageDate by mutableStateOf(localDate())
    var usageLoading by mutableStateOf(false)
    var usageError by mutableStateOf<String?>(null)

    /** Night-lockdown editing copy; non-null while there are unsaved changes. */
    var nlEdit by mutableStateOf<NightLockdown?>(null)
        private set
    val nlDirty get() = nlEdit != null

    // ------------------------------------------------------------ UI state
    var tab by mutableStateOf(Tab.OVERVIEW)
    var sheet by mutableStateOf<Sheet?>(null)
    var confirmReq by mutableStateOf<ConfirmReq?>(null)
        private set
    val toasts = mutableStateListOf<Toast>()
    private var toastSeq = 0L

    private val refreshMutex = Mutex()
    private var pollJob: Job? = null
    private var tickJob: Job? = null

    init {
        I18n.lang = settings.lang ?: I18n.lang
        applyApiSettings()
        if (signedIn && loadBufferIntoState()) booting = true
    }

    private fun applyApiSettings() {
        api.root = settings.server
        api.cookie = settings.cookie.ifEmpty { null }
        api.token = if (settings.authMode == "token") settings.token else null
        api.lang = I18n.lang
    }

    private fun updateSettings(s: Settings) {
        settings = s
        prefs.save(s)
        applyApiSettings()
    }

    val apiBase get() = settings.server.trimEnd('/') + "/api"

    // ================================================================ lifecycle
    fun onForeground() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                now = System.currentTimeMillis()
                delay(1000 - now % 1000)
            }
        }
        restartPolling()
    }

    fun onBackground() {
        pollJob?.cancel(); pollJob = null
        tickJob?.cancel(); tickJob = null
    }

    private fun restartPolling() {
        pollJob?.cancel()
        if (!signedIn) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                doRefresh(manual = false)
                booting = false
                delay(settings.pollSec * 1000L)
            }
        }
    }

    // ================================================================ sign-in
    var loggingIn by mutableStateOf(false)
        private set

    fun login(serverInput: String, mode: String, secret: String, onError: (String) -> Unit) {
        val server = normalizeServer(serverInput)
        when {
            server.isEmpty() -> return onError(t("Enter the server address."))
            !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(server) -> return onError(t("The address must start with http:// or https://"))
            // An empty password is allowed: the server may run without sign-in (AUTH_DISABLED)
            secret.isEmpty() && mode == "token" -> return onError(t("Enter the API token."))
        }
        loggingIn = true
        viewModelScope.launch {
            api.lang = I18n.lang
            val r = when {
                mode == "token" -> api.loginWithToken(server, secret)
                secret.isEmpty() -> api.loginWithoutPassword(server)
                else -> api.loginWithPassword(server, secret)
            }
            loggingIn = false
            when (r) {
                is LoginResult.Failure -> onError(r.message)
                is LoginResult.Success -> {
                    val serverChanged = server != settings.server
                    if (serverChanged) resetData(wipeBuffer = true)
                    updateSettings(
                        settings.copy(
                            server = server,
                            authMode = if (mode == "token") "token" else if (r.cookie == null) "none" else "password",
                            token = if (mode == "token") secret else "",
                            cookie = r.cookie ?: "",
                        ),
                    )
                    loginServer = server
                    loginNotice = null
                    signedIn = true
                    if (!serverChanged) loadBufferIntoState()
                    booting = data != null
                    restartPolling()
                }
            }
        }
    }

    private fun onAuthLost() {
        if (!signedIn) return
        signedIn = false
        nlEdit = null
        sheet = null
        confirmReq?.result?.complete(false)
        loginNotice = t("Your sign-in has expired. Please sign in again.")
        loginServer = settings.server
        updateSettings(settings.copy(cookie = ""))
        pollJob?.cancel()
    }

    fun signOut(wipe: Boolean) {
        viewModelScope.launch {
            api.logout()
            if (wipe) {
                withContext(Dispatchers.IO) { buffer.clear() }
                history = emptyList()
                lastSampleAt = 0
            }
            pollJob?.cancel()
            nlEdit = null
            resetData(wipeBuffer = false)
            loginServer = settings.server
            // "none" (no sign-in on the server) would sign straight back in on the next start
            updateSettings(settings.copy(cookie = "", token = "", authMode = if (settings.authMode == "token") "token" else "password"))
            signedIn = false
            loginNotice = null
            tab = Tab.OVERVIEW
        }
    }

    private fun resetData(wipeBuffer: Boolean) {
        data = null
        source = Source.NONE
        receivedAt = 0
        lastLiveAt = 0
        prevUsage = null
        lastIncrease.clear()
        usageAll = emptyMap()
        excCache.clear()
        if (wipeBuffer) {
            buffer.clear()
            history = emptyList()
            lastSampleAt = 0
        }
    }

    // ================================================================ language / theme / settings
    fun setLang(lang: String) {
        I18n.lang = lang
        api.lang = lang
        updateSettings(settings.copy(lang = lang))
    }

    fun toggleLang() = setLang(if (I18n.lang == "tr") "en" else "tr")

    fun setTheme(theme: String) = updateSettings(settings.copy(theme = theme))

    /** Returns false (after a toast) when the server address changed and a new sign-in is needed. */
    fun saveSettings(server: String, pollSec: Int, bufferSec: Int, theme: String) {
        val norm = normalizeServer(server)
        if (norm.isNotEmpty() && !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(norm)) {
            toast(ToastType.ERROR, t("Invalid address"), t("The API URL must start with http:// or https://"))
            return
        }
        updateSettings(
            settings.copy(
                pollSec = pollSec.coerceIn(2, 300),
                bufferSec = bufferSec.coerceIn(10, 3600),
                theme = theme,
            ),
        )
        toast(ToastType.SUCCESS, t("Settings saved"))
        if (norm.isNotEmpty() && norm != settings.server) {
            // A different server needs its own sign-in
            loginServer = norm
            loginNotice = t("Changing the address signs you out of the current server.")
            viewModelScope.launch { api.logout() }
            pollJob?.cancel()
            signedIn = false
        } else {
            restartPolling()
        }
    }

    // ================================================================ networking
    fun refresh(manual: Boolean = false) {
        viewModelScope.launch { doRefresh(manual) }
    }

    suspend fun doRefresh(manual: Boolean) {
        if (!signedIn) return
        if (refreshMutex.isLocked) {
            if (manual) refreshMutex.withLock { } // wait for the running one
            return
        }
        refreshMutex.withLock {
            refreshing = true
            try {
                val r = api.call("GET", "/dashboard/data")
                val d = r.data.asObj()
                if (!r.ok || r.json["status"].strOrNull() != "success" || d == null) {
                    throw ApiException(r.message ?: t("Server error {n}", "n" to r.status))
                }
                val dash = Dash.parse(d)
                ingest(dash, if (dash.primaryOnline) Source.LIVE else Source.CACHE)
                if (manual) {
                    toast(
                        ToastType.SUCCESS, t("Up to date"),
                        if (dash.primaryOnline) t("Fresh data from the kid's computer.") else t("Kid's computer is offline — showing the server's cached data."),
                    )
                }
            } catch (e: AuthRequiredException) {
                onAuthLost()
            } catch (e: IOException) {
                fallbackToBuffer(e.message)
                if (manual) toast(ToastType.ERROR, t("Refresh failed"), e.message ?: "")
            } finally {
                refreshing = false
                booting = false
            }
        }
    }

    private fun ingest(d: Dash, src: Source) {
        val nowMs = System.currentTimeMillis()
        val date = d.server.serverDate ?: d.statusDate ?: localDate()
        val usage = d.todayUsage
        if (src == Source.LIVE) {
            val prev = prevUsage
            if (prev != null && prev.first == date) {
                for ((name, secs) in usage) if (secs > (prev.second[name] ?: 0.0)) lastIncrease[name] = nowMs
            } else if (prev == null) {
                // First sample: the engine counts OVERALL whenever the PC is on
                lastIncrease["OVERALL"] = nowMs
            }
            prevUsage = date to usage
            lastLiveAt = nowMs
        }
        data = d
        source = src
        receivedAt = nowMs
        lastError = null
        excCache.remove(date)
        persistBuffer()
        maybeSample(date, usage, src)
    }

    private fun fallbackToBuffer(err: String?) {
        if (data == null) {
            buffer.loadSnapshot()?.let { buf ->
                buf.data?.let { data = runCatching { Dash.parse(it) }.getOrNull() }
                lastLiveAt = max(lastLiveAt, buf.lastLiveAt)
                bufferSavedAt = buf.savedAt
                if (usageAll.isEmpty()) usageAll = buf.usageAll
            }
        }
        source = Source.BUFFER
        receivedAt = System.currentTimeMillis()
        lastError = err
    }

    private fun loadBufferIntoState(): Boolean {
        val buf = buffer.loadSnapshot() ?: return false
        val raw = buf.data ?: return false
        data = runCatching { Dash.parse(raw) }.getOrNull() ?: return false
        usageAll = buf.usageAll
        lastLiveAt = buf.lastLiveAt
        bufferSavedAt = buf.savedAt
        source = Source.BUFFER
        receivedAt = System.currentTimeMillis()
        return true
    }

    private fun persistBuffer() {
        val d = data ?: return
        val snap = BufferSnapshot(System.currentTimeMillis(), source.name.lowercase(), lastLiveAt, d.raw, usageAll)
        bufferSavedAt = snap.savedAt
        viewModelScope.launch(Dispatchers.IO) { buffer.saveSnapshot(snap) }
    }

    private fun maybeSample(date: String, usage: Map<String, Double>, src: Source) {
        val nowMs = System.currentTimeMillis()
        if (src != Source.LIVE) return
        if (nowMs - lastSampleAt < settings.bufferSec * 1000L) return
        lastSampleAt = nowMs
        val cutoff = nowMs - 8 * 86_400_000L
        val list = (history + Sample(nowMs, date, usage["OVERALL"] ?: 0.0, usage))
            .filter { it.t >= cutoff }
            .takeLast(HISTORY_MAX)
        history = list
        viewModelScope.launch(Dispatchers.IO) { buffer.saveHistory(list) }
    }

    // ================================================================ derived model
    fun dataDate(): String = data?.let { it.server.serverDate ?: it.statusDate } ?: localDate()

    fun lastSeenMs(): Long = max(lastLiveAt, parseIsoMillis(data?.server?.lastSynced))

    fun pendingExceptionsFor(date: String): List<QueueItem> =
        data?.queue?.filter { it.method == "POST" && it.endpoint == "/exceptions" && it.data?.get("date").strOrNull() == date } ?: emptyList()

    fun model(nowMs: Long = now): Model? {
        val d = data ?: return null
        val today = localDate()
        val sameDay = dataDate() == today
        val dayName = dayNameOf(java.time.LocalDate.now())
        val limits = d.limits
        val excToday = if (sameDay) d.todayExceptions else emptyMap()
        val usage = if (sameDay) d.todayUsage else emptyMap()
        val live = source == Source.LIVE
        val elapsed = if (live) max(0.0, (nowMs - receivedAt) / 1000.0) else 0.0
        val isActive = { name: String -> live && (lastIncrease[name]?.let { nowMs - it < ACTIVE_WINDOW } ?: false) }
        val pending = pendingExceptionsFor(today)

        fun mk(name: String): AppRow {
            val lim = computeLimit(name, dayName, limits, excToday)
            val active = isActive(name)
            val used = (usage[name] ?: 0.0) + if (active) elapsed else 0.0
            return AppRow(name, lim, used, max(0.0, lim.effective - used), active, pending.filter { it.data?.get("app_name").strOrNull() == name })
        }

        val overall = mk("OVERALL")
        val lockdown = lockdownInfo(d.nightLockdown, LocalDateTime.now())
        var pcRemaining = if (overall.lim.unlimited) Double.POSITIVE_INFINITY else overall.remaining
        var pcReason: String? = if (overall.lim.unlimited) null else "limit"
        if (lockdown != null) {
            if (lockdown.activeNow) {
                pcRemaining = 0.0; pcReason = "lockdown-active"
            } else if (lockdown.startsAt != null) {
                val inSec = secondsUntil(lockdown.startsAt)
                if (inSec < pcRemaining) { pcRemaining = max(0.0, inSec); pcReason = "lockdown" }
            }
        }
        val apps = limits.keys.filter { it != "OVERALL" }.sortedWith(String.CASE_INSENSITIVE_ORDER).map { name ->
            val a = mk(name)
            val own = if (a.lim.unlimited) Double.POSITIVE_INFINITY else a.remaining
            val eff = min(own, pcRemaining)
            a.copy(effectiveRemaining = eff, cappedByPc = eff < own)
        }
        val extra = usage.keys.filter { it != "OVERALL" && it !in limits }
        return Model(today, dayName, sameDay, overall, lockdown, pcRemaining, pcReason, apps, extra, usage, excToday, live)
    }

    // ================================================================ toasts & confirm
    fun toast(type: ToastType, title: String, message: String = "", ms: Long? = null) {
        val dur = ms ?: when (type) { ToastType.ERROR -> 7000L; ToastType.WARNING -> 6000L; else -> 3800L }
        while (toasts.size >= 4) toasts.removeAt(0)
        toasts.add(Toast(++toastSeq, type, title, message, dur))
    }

    fun dismissToast(id: Long) { toasts.removeAll { it.id == id } }

    suspend fun confirm(
        title: String,
        message: String,
        okLabel: String = t("Confirm"),
        danger: Boolean = false,
        icon: DlgIcon? = null,
    ): Boolean {
        confirmReq?.result?.complete(false)
        val req = ConfirmReq(title, message, okLabel, danger, icon ?: if (danger) DlgIcon.DANGER else DlgIcon.QUESTION, CompletableDeferred())
        confirmReq = req
        return try { req.result.await() } finally { if (confirmReq === req) confirmReq = null }
    }

    fun answerConfirm(ok: Boolean) { confirmReq?.result?.complete(ok) }

    // ================================================================ mutations
    suspend fun mutate(method: String, path: String, body: JsonElement?, what: String, timeoutMs: Long = 8000): ApiResult? {
        return try {
            val r = api.call(method, path, body, timeoutMs)
            when {
                r.status == 202 -> toast(ToastType.WARNING, t("{what} queued", "what" to what), t("The kid's computer is offline. This will apply automatically when it comes back online."))
                r.ok -> toast(ToastType.SUCCESS, t("{what} saved", "what" to what), if (I18n.lang == "en") r.message ?: "" else "")
                else -> {
                    toast(ToastType.ERROR, t("{what} failed", "what" to what), r.message ?: t("Server responded with {n}", "n" to r.status))
                    return null
                }
            }
            refresh()
            r
        } catch (e: AuthRequiredException) {
            onAuthLost(); null
        } catch (e: IOException) {
            toast(ToastType.ERROR, t("{what} failed", "what" to what), e.message ?: ""); null
        }
    }

    fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }

    // ---------------------------------------------------------------- limits
    fun saveLimit(name: String, limits: Map<String, Long?>, isNew: Boolean) = launch {
        val what = t("Limit for {app}", "app" to appLabel(name))
        if (isNew) {
            mutate("POST", "/limits", buildJsonObject { put("app_name", name); put("limits", limitsToJson(limits)) }, what)
        } else {
            // Optimistic update so the app reacts instantly
            data?.let { d -> data = d.copy(limits = LinkedHashMap(d.limits).apply { put(name, limits) }) }
            mutate("PUT", "/limits/${seg(name)}", buildJsonObject { put("limits", limitsToJson(limits)) }, what)
        }
    }

    fun deleteLimit(name: String) = launch {
        if (!confirm(
                t("Remove {app}?", "app" to appLabel(name)),
                t("{app} will no longer be limited or tracked against a limit. Usage history is kept.", "app" to name),
                okLabel = t("Remove"), danger = true,
            )
        ) return@launch
        mutate("DELETE", "/limits/${seg(name)}", null, t("Removal of {app}", "app" to appLabel(name)))
    }

    // ---------------------------------------------------------------- exceptions
    /** exceptionTime: an integer to add/remove, or a numeric string to set an exact limit. */
    fun createException(app: String, date: String, exceptionTime: JsonPrimitive, reason: String?, mode: String) = launch {
        val c = classifyEntry(exceptionTime)
        val label = if (mode == "set") t("Set {app} to {v}", "app" to appLabel(app), "v" to fmtDur(c.value.toDouble()))
        else t("{v} for {app}", "v" to signed(c.value), "app" to appLabel(app))
        val d = data
        if (d != null && date == dataDate() && source == Source.LIVE) {
            val ex = d.todayExceptions.toMutableMap()
            ex[app] = (ex[app] ?: emptyList()) + ExcEntry(exceptionTime, reason)
            data = d.copy(todayExceptions = ex)
        }
        mutate(
            "POST", "/exceptions",
            buildJsonObject {
                put("date", date); put("app_name", app); put("exception_time", exceptionTime)
                put("adjustment_type", mode)
                if (reason != null) put("reason", reason) else put("reason", JsonNull)
            },
            label,
        )
        excCache.clear()
    }

    fun quickException(app: String, secs: Long) = launch {
        if (!confirm(
                t("Add {v} for {app}?", "v" to fmtDur(secs.toDouble()), "app" to appLabel(app)),
                t("Today's limit for {app} will be extended by {v}.", "app" to appLabel(app), "v" to fmtDur(secs.toDouble())),
                okLabel = t("Add {v}", "v" to fmtDur(secs.toDouble())), icon = DlgIcon.GIFT,
            )
        ) return@launch
        createException(app, localDate(), JsonPrimitive(secs), null, "add")
    }

    fun deleteExceptionTx(date: String, app: String, index: Int) = launch {
        if (!confirm(
                t("Delete this exception?"),
                t("The adjustment for {app} on {date} will be removed and the limit recalculated.", "app" to appLabel(app), "date" to date),
                okLabel = t("Delete"), danger = true,
            )
        ) return@launch
        mutate("DELETE", "/exceptions/$date/${seg(app)}/$index", null, t("Exception removal"))
        excCache.remove(date)
        loadExceptions(force = true)
    }

    fun deleteExceptionAll(date: String, app: String) = launch {
        if (!confirm(
                t("Delete all exceptions for {app}?", "app" to appLabel(app)),
                t("Every adjustment on {date} for {app} will be removed.", "date" to date, "app" to appLabel(app)),
                okLabel = t("Delete all"), danger = true,
            )
        ) return@launch
        mutate("DELETE", "/exceptions/$date/${seg(app)}", null, t("Exception removal"))
        excCache.remove(date)
        loadExceptions(force = true)
    }

    /** Exceptions shown on the Exceptions tab for [excDate]. */
    fun exceptionsFor(date: String): Map<String, List<ExcEntry>>? =
        if (date == dataDate()) data?.todayExceptions else excCache[date]

    fun loadExceptions(force: Boolean) {
        val date = excDate
        if (data == null || date == dataDate() || (!force && excCache.containsKey(date))) return
        excLoading = true
        excError = null
        viewModelScope.launch {
            try {
                val r = api.call("GET", "/exceptions/$date")
                excCache[date] = parseExceptionDay(r.data)
                if (r.json["cached"].strOrNull() == "true") toast(ToastType.INFO, t("Showing cached exceptions"), t("The kid's computer is offline."))
            } catch (e: AuthRequiredException) {
                onAuthLost()
            } catch (e: IOException) {
                excError = e.message
            } finally {
                excLoading = false
            }
        }
    }

    // ---------------------------------------------------------------- night lockdown
    fun nlCurrent(): NightLockdown = nlEdit ?: data?.nightLockdown ?: NightLockdown()

    fun editNl(f: (NightLockdown) -> NightLockdown) { nlEdit = f(nlCurrent()) }

    fun discardNl() {
        nlEdit = null
        toast(ToastType.INFO, t("Changes discarded"))
    }

    fun saveNl() = launch {
        val nl = nlEdit ?: return@launch
        val scopes = if (nl.perDay) DAYS.map { t(it) to nl.day(it) } else listOf(t("Every day") to nl.allDays)
        for ((label, sch) in scopes) {
            for (w in sch.blocked + sch.whitelist) {
                if (toMin(w.start) == null || toMin(w.end) == null) {
                    toast(ToastType.ERROR, t("Incomplete zone"), t("{label}: every zone needs a start and an end time.", "label" to label))
                    return@launch
                }
            }
        }
        val lk = com.pcv2.app.core.scanLockdown(nl, LocalDateTime.now()).takeIf { nl.enabled }
        if (lk != null && lk.activeNow && source == Source.LIVE) {
            if (!confirm(
                    t("Lockdown will start immediately"),
                    t("The current time is inside a blocked zone. The kid's computer will shut down within about 15 seconds after saving."),
                    okLabel = t("Save anyway"), danger = true, icon = DlgIcon.WARN,
                )
            ) return@launch
        }
        val r = mutate("PUT", "/nightlockdown", nl.toJson(), t("Night lockdown"))
        if (r != null) {
            data?.let { data = it.copy(nightLockdown = nl) }
            nlEdit = null
        }
    }

    // ---------------------------------------------------------------- usage
    fun usageFor(date: String): Map<String, Double>? =
        if (date == dataDate()) data?.todayUsage else usageAll[date]

    fun loadUsage() {
        val date = usageDate
        if (data == null || date == dataDate() || usageAll.containsKey(date)) return
        usageLoading = true
        usageError = null
        viewModelScope.launch {
            try {
                val r = api.call("GET", "/usage/$date")
                usageAll = usageAll + (date to parseUsageDay(r.data))
            } catch (e: AuthRequiredException) {
                onAuthLost()
            } catch (e: IOException) {
                usageError = e.message
            } finally {
                usageLoading = false
            }
        }
    }

    fun loadUsageAll() = launch {
        try {
            val r = api.call("GET", "/usage")
            val all = r.data.asObj()
            if (r.ok && all != null) {
                usageAll = usageAll + all.mapValues { parseUsageDay(it.value) }
                persistBuffer()
            }
        } catch (e: AuthRequiredException) {
            onAuthLost()
        } catch (e: IOException) { /* keep the buffered copy */ }
    }

    /** Last 7 days of OVERALL usage for the week chart. */
    fun weekColumns(): List<Pair<String, Double>> {
        val all = usageAll.toMutableMap()
        data?.let { all[dataDate()] = it.todayUsage }
        val today = java.time.LocalDate.now()
        return (6 downTo 0).map { i ->
            val key = today.minusDays(i.toLong()).toString()
            key to (all[key]?.get("OVERALL") ?: 0.0)
        }
    }

    // ---------------------------------------------------------------- queue
    fun cancelQueue(id: Long) = launch {
        if (!confirm(t("Cancel this pending change?"), t("It will be removed from the queue and never applied."), okLabel = t("Cancel change"), danger = true)) return@launch
        try {
            val r = api.call("DELETE", "/server/queue/$id")
            when {
                r.ok -> toast(ToastType.SUCCESS, t("Pending change cancelled"))
                r.status == 409 || r.status == 404 -> toast(ToastType.INFO, t("Already applied"), t("This change has already reached the kid's computer."))
                else -> toast(ToastType.ERROR, t("Could not cancel"), r.message ?: t("Error {n}", "n" to r.status))
            }
        } catch (e: AuthRequiredException) {
            onAuthLost()
        } catch (e: IOException) {
            toast(ToastType.ERROR, t("Could not cancel"), e.message ?: "")
        }
        refresh()
    }

    fun syncNow() = launch {
        try {
            val r = api.call("POST", "/server/sync", null, timeoutMs = 30_000)
            val d = r.data.asObj()
            val synced = d?.get("synced").strOrNull() ?: "0"
            val failed = d?.get("failed").strOrNull() ?: "0"
            toast(if (failed != "0") ToastType.WARNING else ToastType.SUCCESS, t("Sync finished"), t("{a} applied, {b} failed.", "a" to synced, "b" to failed))
        } catch (e: AuthRequiredException) {
            onAuthLost()
        } catch (e: IOException) {
            toast(ToastType.ERROR, t("Sync failed"), e.message ?: "")
        }
        refresh()
    }

    fun clearQueue() = launch {
        if (!confirm(t("Discard all pending changes?"), t("Every queued change will be deleted and never applied. This cannot be undone."), okLabel = t("Discard all"), danger = true)) return@launch
        try {
            val r = api.call("POST", "/server/queue/clear")
            if (r.ok) toast(ToastType.SUCCESS, t("Queue cleared")) else toast(ToastType.ERROR, t("Could not clear queue"), r.message ?: "")
        } catch (e: AuthRequiredException) {
            onAuthLost()
        } catch (e: IOException) {
            toast(ToastType.ERROR, t("Could not clear queue"), e.message ?: "")
        }
        refresh()
    }

    // ---------------------------------------------------------------- config / buffer
    fun configJson(): String? = data?.config?.let { JSON_PRETTY.encodeToString(JsonElement.serializer(), it) }

    fun uploadConfig(text: String, fileName: String) = launch {
        val cfg = runCatching { JSON.parseToJsonElement(text).asObj() }.getOrNull()
        if (cfg == null) {
            toast(ToastType.ERROR, t("Invalid file"), t("That file is not valid JSON.")); return@launch
        }
        val parts = listOf("limits", "exceptions", "night_lockdown").filter { it in cfg }
        if (parts.isEmpty()) {
            toast(ToastType.ERROR, t("Nothing to upload"), t("The file needs a \"limits\", \"exceptions\" or \"night_lockdown\" section.")); return@launch
        }
        if (!confirm(
                t("Replace configuration?"),
                t("This overwrites {parts} on the kid's computer with the contents of {file}.", "parts" to parts.joinToString(", "), "file" to fileName),
                okLabel = t("Replace"), danger = true, icon = DlgIcon.WARN,
            )
        ) return@launch
        mutate("POST", "/config", cfg, t("Configuration upload"))
    }

    fun clearBuffer() = launch {
        if (!confirm(t("Clear the app buffer?"), t("Buffered snapshots and usage samples stored on this phone will be deleted. Nothing on the kid's computer changes."), okLabel = t("Clear"), danger = true)) return@launch
        withContext(Dispatchers.IO) { buffer.clear() }
        history = emptyList()
        lastSampleAt = 0
        toast(ToastType.SUCCESS, t("App buffer cleared"))
    }

    // ---------------------------------------------------------------- helpers for the queue screen
    fun describeQueueItem(q: QueueItem): String {
        val d = q.data ?: JsonObject(emptyMap())
        val ep = q.endpoint
        fun esc(s: String?) = (s ?: "").replace("<", "‹").replace(">", "›")
        if (ep == "/exceptions" && q.method == "POST") {
            val c = classifyEntry(d["exception_time"])
            val change = if (c.kind == ExcKind.SET) t("set to {v}", "v" to fmtDur(c.value.toDouble())) else signed(c.value)
            val reason = d["reason"].strOrNull()
            return t("Exception for <b>{app}</b> on {date}: {change}", "app" to esc(appLabel(d["app_name"].strOrNull() ?: "")), "date" to esc(d["date"].strOrNull()), "change" to change) +
                if (!reason.isNullOrEmpty()) " · “${esc(reason)}”" else ""
        }
        val parts = ep.split("/").drop(2).map { runCatching { java.net.URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
        if (ep.startsWith("/exceptions/") && q.method == "DELETE") {
            return t(
                if (parts.size > 2) "Delete an exception for <b>{app}</b> on {date}" else "Delete all exceptions for <b>{app}</b> on {date}",
                "app" to esc(appLabel(parts.getOrNull(1) ?: "")), "date" to esc(parts.getOrNull(0)),
            )
        }
        if (ep == "/limits" && q.method == "POST") return t("Add limit for <b>{app}</b>", "app" to esc(appLabel(d["app_name"].strOrNull() ?: "")))
        if (ep.startsWith("/limits/") && q.method == "PUT") return t("Update limit for <b>{app}</b>", "app" to esc(appLabel(parts.getOrNull(0) ?: "")))
        if (ep.startsWith("/limits/") && q.method == "DELETE") return t("Remove limit for <b>{app}</b>", "app" to esc(appLabel(parts.getOrNull(0) ?: "")))
        if (ep == "/nightlockdown") return if (d["enabled"].strOrNull() == "true") t("Update night lockdown (enabled)") else t("Update night lockdown (disabled)")
        if (ep == "/config") return t("Upload full configuration")
        return "<mono>${esc(q.method)} ${esc(ep)}</mono>"
    }

    /** Sum of queued "add" exceptions, for the hero chip. */
    fun pendingSum(items: List<QueueItem>): Long = items.sumOf {
        val c = classifyEntry(it.data?.get("exception_time"))
        if (c.kind == ExcKind.ADD) c.value else 0L
    }
}

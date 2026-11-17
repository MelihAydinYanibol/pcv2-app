package com.pcv2.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pcv2.app.MainActivity
import com.pcv2.app.R
import com.pcv2.app.core.Dash
import com.pcv2.app.core.I18n
import com.pcv2.app.core.Level
import com.pcv2.app.core.Summary
import com.pcv2.app.core.appLabel
import com.pcv2.app.core.asObj
import com.pcv2.app.core.fmtAgo
import com.pcv2.app.core.fmtClock
import com.pcv2.app.core.fmtDur
import com.pcv2.app.core.fmtTime
import com.pcv2.app.core.levelFor
import com.pcv2.app.core.parseIsoMillis
import com.pcv2.app.core.strOrNull
import com.pcv2.app.core.summarize
import com.pcv2.app.core.t
import com.pcv2.app.data.ApiClient
import com.pcv2.app.data.AuthRequiredException
import com.pcv2.app.data.Buffer
import com.pcv2.app.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** What the widget shows; built from a fresh fetch, the app's own data, or the on-phone buffer. */
data class WidgetState(
    val summary: Summary? = null,
    /** The kid's PC answered (time is counting). */
    val live: Boolean = false,
    /** The server could not be reached (data from the phone's buffer). */
    val unreachable: Boolean = false,
    val signedOut: Boolean = false,
    val lastSeenMs: Long = 0,
    /** When [summary] was computed (wall clock), so the countdown can be continued. */
    val at: Long = System.currentTimeMillis(),
)

/**
 * Home-screen widget: computer time left today, counting down live with a
 * Chronometer (so it ticks without the app running), like the web /widget.
 */
class TimeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { WidgetWorker.schedulePeriodic(context) }  // also after an app update / reboot
        WidgetUpdater.renderLast(context)
        refreshAsync(context)
    }

    override fun onEnabled(context: Context) {
        runCatching { WidgetWorker.schedulePeriodic(context) }
    }

    override fun onDisabled(context: Context) {
        runCatching { WidgetWorker.cancel(context) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // Resized: re-render with the new size (more or fewer app rows)
        if (!WidgetUpdater.renderLast(context)) refreshAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAsync(context)
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { WidgetUpdater.refresh(context.applicationContext) } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.pcv2.app.widget.REFRESH"
    }
}

/** Periodic refresh (every 15 minutes, Android's minimum) plus one at the moment time runs out. */
class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetUpdater.refresh(applicationContext)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "pcv2-widget-periodic"
        private const val AT_ZERO = "pcv2-widget-zero"

        fun schedulePeriodic(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        /** Refresh again just after the countdown reaches zero (time up / lockdown starts). */
        fun scheduleAt(ctx: Context, delayMs: Long) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                AT_ZERO, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build(),
            )
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(ctx).cancelUniqueWork(AT_ZERO)
        }
    }
}

object WidgetUpdater {
    @Volatile private var last: WidgetState? = null
    @Volatile private var lastSignature: String? = null

    fun ids(ctx: Context): IntArray = runCatching {
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, TimeWidget::class.java))
    }.getOrDefault(IntArray(0))

    private fun applyLang(ctx: Context) {
        val lang = Prefs(ctx).load().lang
        I18n.lang = lang ?: if (Locale.getDefault().language == "tr") "tr" else "en"
    }

    /** Fetches GET /api/dashboard/data with the app's sign-in and updates every widget. */
    suspend fun refresh(ctx: Context) {
        if (ids(ctx).isEmpty()) return
        applyLang(ctx)
        val s = Prefs(ctx).load()
        val state = if (!s.signedIn) WidgetState(signedOut = true) else {
            val api = ApiClient().apply {
                root = s.server
                cookie = s.cookie.ifEmpty { null }
                token = if (s.authMode == "token") s.token else null
                lang = I18n.lang
            }
            try {
                val r = api.call("GET", "/dashboard/data", timeoutMs = 10_000)
                val d = r.data.asObj()
                if (!r.ok || r.json["status"].strOrNull() != "success" || d == null) throw IOException("HTTP ${r.status}")
                fromDash(Dash.parse(d), unreachable = false)
            } catch (e: AuthRequiredException) {
                WidgetState(signedOut = true)
            } catch (e: IOException) {
                fromBuffer(ctx)
            }
        }
        render(ctx, state)
    }

    private fun fromDash(d: Dash, unreachable: Boolean, lastLiveAt: Long = 0): WidgetState {
        val live = d.primaryOnline && !unreachable
        val seen = if (live) System.currentTimeMillis() else max(lastLiveAt, parseIsoMillis(d.server.lastSynced))
        return WidgetState(summarize(d, LocalDateTime.now()), live = live, unreachable = unreachable, lastSeenMs = seen)
    }

    private fun fromBuffer(ctx: Context): WidgetState {
        val snap = Buffer(ctx).loadSnapshot() ?: return WidgetState(unreachable = true)
        val d = snap.data?.let { runCatching { Dash.parse(it) }.getOrNull() } ?: return WidgetState(unreachable = true)
        return fromDash(d, unreachable = true, lastLiveAt = snap.lastLiveAt)
    }

    /** Called by the app whenever it receives fresh data, so the widget follows along. */
    fun push(ctx: Context, d: Dash, live: Boolean, lastSeenMs: Long) {
        if (ids(ctx).isEmpty()) return
        val st = fromDash(d, unreachable = false).copy(live = live && d.primaryOnline, lastSeenMs = lastSeenMs)
        val sum = st.summary
        // Skip identical updates (the app polls every few seconds)
        val sig = listOf(st.live, sum?.reason, sum?.pcRemaining?.let { (it / 60).toLong() }, sum?.effectiveLimit, sum?.apps?.map { it.remaining?.let { r -> (r / 60).toLong() } }, I18n.lang).toString()
        if (sig == lastSignature) return
        render(ctx, st)
    }

    fun pushSignedOut(ctx: Context) {
        if (ids(ctx).isNotEmpty()) render(ctx, WidgetState(signedOut = true))
    }

    /** Re-renders the last state (e.g. after a resize). Returns false when there is none. */
    fun renderLast(ctx: Context): Boolean {
        val st = last ?: return false
        render(ctx, st)
        return true
    }

    fun render(ctx: Context, st: WidgetState) {
        last = st
        val sum = st.summary
        lastSignature = listOf(st.live, sum?.reason, sum?.pcRemaining?.let { (it / 60).toLong() }, sum?.effectiveLimit, sum?.apps?.map { it.remaining?.let { r -> (r / 60).toLong() } }, I18n.lang).toString()
        val mgr = AppWidgetManager.getInstance(ctx)
        for (id in ids(ctx)) {
            val o = mgr.getAppWidgetOptions(id)
            val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 110
            mgr.updateAppWidget(id, build(ctx, st, h))
        }
        // Refresh right after the countdown ends
        val rem = sum?.pcRemaining
        if (st.live && rem != null && rem > 0) runCatching { WidgetWorker.scheduleAt(ctx, (rem * 1000).toLong() + 5_000) }
    }

    /** Builds the RemoteViews for one widget of the given height (dp). */
    fun build(ctx: Context, st: WidgetState, heightDp: Int): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_time)
        val sum = st.summary
        val elapsed = if (st.live) max(0.0, (System.currentTimeMillis() - st.at) / 1000.0) else 0.0
        v.setTextViewText(R.id.w_label, t("Computer time left today"))
        v.setContentDescription(R.id.w_refresh, t("Refresh now"))
        v.setContentDescription(R.id.w_add, t("Custom exception…"))

        // Status dot: green = live, amber = PC off (server cache), red = server unreachable
        v.setImageViewResource(
            R.id.w_dot,
            when {
                st.signedOut -> R.drawable.widget_dot_text3
                st.unreachable -> R.drawable.widget_dot_danger
                sum == null -> R.drawable.widget_dot_text3
                st.live -> R.drawable.widget_dot_ok
                else -> R.drawable.widget_dot_warn
            },
        )

        var big: String? = null
        var countdown: Double? = null
        var bigColor = R.color.w_text
        var sub: String
        var frac = 0f
        var level = Level.NONE
        if (st.signedOut) {
            big = "—"; sub = t("Sign-in required")
        } else if (sum == null) {
            big = "—"; sub = if (st.unreachable) t("Server unreachable") else t("No data available yet")
        } else {
            val rem = sum.pcRemaining?.let { max(0.0, it - elapsed) }
            when {
                sum.reason == "lockdown-active" -> {
                    big = t("Locked"); bigColor = R.color.w_danger; level = Level.DANGER
                    sub = sum.lockdown?.endsAt?.let { t("Computer can be used again at {t}", "t" to fmtTime(it)) } ?: t("Night lockdown is active")
                }
                rem == null -> {
                    big = t("Unlimited"); level = Level.OK; frac = 1f
                    sub = t("Used {v} so far", "v" to fmtDur(sum.used + elapsed))
                }
                rem <= 0 -> {
                    big = t("Time is up"); bigColor = R.color.w_danger; level = Level.DANGER
                    sub = t("Used {a} of {b}", "a" to fmtDur(sum.used), "b" to fmtDur((sum.effectiveLimit ?: 0).toDouble()))
                }
                else -> {
                    level = levelFor(rem, (sum.effectiveLimit ?: 0).toDouble())
                    bigColor = when (level) { Level.WARN -> R.color.w_warn; Level.DANGER -> R.color.w_danger; else -> R.color.w_text }
                    if (st.live) countdown = rem else big = fmtClock(rem)
                    frac = sum.effectiveLimit?.takeIf { it > 0 }?.let { (rem / it).toFloat().coerceIn(0f, 1f) } ?: 0f
                    sub = if (sum.reason == "lockdown") t("Until night lockdown") + " · " + fmtTime(sum.lockdown!!.startsAt!!)
                    else t("Used {a} of {b}", "a" to fmtDur(sum.used + elapsed), "b" to fmtDur((sum.effectiveLimit ?: 0).toDouble())) +
                        (sum.lockdown?.startsAt?.let { " · " + t("lockdown at {t}", "t" to fmtTime(it)) } ?: "")
                }
            }
            if (!sum.sameDay) sub = t("No data for today yet")
            when {
                st.unreachable -> sub = t("Server unreachable") + " · " + sub
                !st.live -> sub = t("Paused · PC offline") + " · " + t("seen {ago}", "ago" to fmtAgo(st.lastSeenMs)) + "\n" + sub
            }
        }

        if (countdown != null) {
            v.setViewVisibility(R.id.w_count, View.VISIBLE)
            v.setViewVisibility(R.id.w_big, View.GONE)
            v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime() + (countdown * 1000).toLong(), null, true)
            v.setChronometerCountDown(R.id.w_count, true)
            setTextColor(ctx, v, R.id.w_count, bigColor)
        } else {
            v.setViewVisibility(R.id.w_count, View.GONE)
            v.setViewVisibility(R.id.w_big, View.VISIBLE)
            v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime(), null, false)
            v.setTextViewText(R.id.w_big, big ?: "—")
            setTextColor(ctx, v, R.id.w_big, bigColor)
        }
        v.setTextViewText(R.id.w_sub, sub)

        // Progress bar in the level's colour (one bar per colour so it follows dark mode)
        val bars = mapOf(Level.NONE to R.id.w_bar_accent, Level.WARN to R.id.w_bar_warn, Level.DANGER to R.id.w_bar_danger, Level.OK to R.id.w_bar_ok)
        for ((l, id) in bars) {
            v.setViewVisibility(id, if (l == level) View.VISIBLE else View.GONE)
            v.setProgressBar(id, 1000, (frac * 1000).toInt(), false)
        }

        // Size: tiny -> header + countdown; then the subtitle, the bar, and app rows as it grows
        val bigSize = if (heightDp < 100) 26f else 34f
        v.setTextViewTextSize(R.id.w_count, android.util.TypedValue.COMPLEX_UNIT_SP, bigSize)
        v.setTextViewTextSize(R.id.w_big, android.util.TypedValue.COMPLEX_UNIT_SP, bigSize)
        v.setViewVisibility(R.id.w_sub, if (heightDp >= 100) View.VISIBLE else View.GONE)
        v.setInt(R.id.w_sub, "setMaxLines", if (heightDp >= 135) 2 else 1)
        v.setViewVisibility(R.id.w_bars, if (heightDp >= 135 && sum != null) View.VISIBLE else View.GONE)
        val rows = if (sum == null) 0 else ((heightDp - 165) / 21).coerceIn(0, 4)
        val apps = sum?.apps?.take(rows) ?: emptyList()
        v.setViewVisibility(R.id.w_apps, if (apps.isEmpty()) View.GONE else View.VISIBLE)
        val rowIds = listOf(
            Triple(R.id.w_app1, R.id.w_app1_name, R.id.w_app1_time), Triple(R.id.w_app2, R.id.w_app2_name, R.id.w_app2_time),
            Triple(R.id.w_app3, R.id.w_app3_name, R.id.w_app3_time), Triple(R.id.w_app4, R.id.w_app4_name, R.id.w_app4_time),
        )
        rowIds.forEachIndexed { i, (row, name, time) ->
            val a = apps.getOrNull(i)
            v.setViewVisibility(row, if (a == null) View.GONE else View.VISIBLE)
            if (a != null) {
                val r = a.remaining?.let { max(0.0, it - if (st.live) elapsed else 0.0) }
                v.setTextViewText(name, appLabel(a.name))
                v.setTextViewText(time, when {
                    r == null -> t("No limit")
                    r <= 0 -> t("Time up")
                    else -> fmtDur(r)
                })
                val lv = if (r == null) Level.NONE else levelFor(r, (a.limit ?: 0).toDouble())
                setTextColor(ctx, v, time, when (lv) { Level.WARN -> R.color.w_warn; Level.DANGER -> R.color.w_danger; else -> R.color.w_text })
            }
        }

        // Taps: open the app, "+" opens the exception sheet (still needs confirming), refresh
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags))
        v.setOnClickPendingIntent(
            R.id.w_add,
            PendingIntent.getActivity(
                ctx, 1,
                Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_EXCEPTION)
                    .putExtra(MainActivity.EXTRA_APP, "OVERALL").putExtra(MainActivity.EXTRA_SECS, 900L),
                flags,
            ),
        )
        v.setOnClickPendingIntent(
            R.id.w_refresh,
            PendingIntent.getBroadcast(ctx, 2, Intent(ctx, TimeWidget::class.java).setAction(TimeWidget.ACTION_REFRESH), flags),
        )
        return v
    }

    /** Text colour that follows the phone's light/dark mode (API 31+), else the current one. */
    private fun setTextColor(ctx: Context, v: RemoteViews, id: Int, @ColorRes res: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setColorStateList(id, "setTextColor", ColorStateList.valueOf(themed(ctx, res, false)), ColorStateList.valueOf(themed(ctx, res, true)))
        } else {
            v.setTextColor(id, ctx.getColor(res))
        }
    }

    private fun themed(ctx: Context, @ColorRes res: Int, night: Boolean): Int {
        val cfg = Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ctx.createConfigurationContext(cfg).getColor(res)
    }
}

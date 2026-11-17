package com.pcv2.app.core

import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min

/** One app row of the summary (time left is capped by the computer's own time left). */
data class AppSummary(val name: String, val used: Double, val limit: Long?, val remaining: Double?)

/**
 * Today's state reduced to what a widget shows — same maths as the dashboard's
 * hero card and serverside/widget.py: overall limit + exceptions, capped by
 * the next night lockdown.
 */
data class Summary(
    /** null = no limit today. */
    val pcRemaining: Double?,
    /** null | "limit" | "lockdown" | "lockdown-active" */
    val reason: String?,
    val used: Double,
    val effectiveLimit: Long?,
    val lockdown: LockInfo?,
    val sameDay: Boolean,
    val apps: List<AppSummary>,
)

fun summarize(d: Dash, now: LocalDateTime = LocalDateTime.now(), elapsedSec: Double = 0.0): Summary {
    val today = now.toLocalDate()
    val dataDate = d.server.serverDate ?: d.statusDate ?: today.toString()
    val sameDay = dataDate == today.toString()
    val dayName = dayNameOf(today)
    val exc = if (sameDay) d.todayExceptions else emptyMap()
    val usage = if (sameDay) d.todayUsage else emptyMap()

    val ov = computeLimit("OVERALL", dayName, d.limits, exc)
    val used = (usage["OVERALL"] ?: 0.0) + elapsedSec
    var remaining: Double? = if (ov.unlimited) null else max(0.0, ov.effective - used)
    var reason: String? = if (ov.unlimited) null else "limit"
    val lk = lockdownInfo(d.nightLockdown, now)
    if (lk != null) {
        if (lk.activeNow) {
            remaining = 0.0; reason = "lockdown-active"
        } else if (lk.startsAt != null) {
            val inSec = max(0.0, secondsUntil(lk.startsAt, now))
            if (remaining == null || inSec < remaining) { remaining = inSec; reason = "lockdown" }
        }
    }
    val apps = d.limits.keys.filter { it != "OVERALL" }.map { name ->
        val l = computeLimit(name, dayName, d.limits, exc)
        val u = usage[name] ?: 0.0
        val own = if (l.unlimited) null else max(0.0, l.effective - u)
        val capped = when {
            own == null -> remaining
            remaining == null -> own
            else -> min(own, remaining)
        }
        AppSummary(name, u, if (l.unlimited) null else l.effective, capped)
    }.sortedWith(compareBy<AppSummary> { it.remaining ?: Double.MAX_VALUE }.thenBy { it.name.lowercase() })
    return Summary(remaining, reason, used, if (ov.unlimited) null else ov.effective, lk, sameDay, apps)
}

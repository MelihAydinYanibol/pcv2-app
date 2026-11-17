package com.pcv2.app.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val JSON = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = false }
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
val JSON_PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  " }

val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
val DAY_SHORT = mapOf(
    "Monday" to "Mon", "Tuesday" to "Tue", "Wednesday" to "Wed", "Thursday" to "Thu",
    "Friday" to "Fri", "Saturday" to "Sat", "Sunday" to "Sun",
)

// ---------------------------------------------------------------- JSON helpers
fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
fun JsonElement?.asArr(): JsonArray? = this as? JsonArray
fun JsonElement?.isJsonNull(): Boolean = this == null || this is JsonNull

/** Number value of a JSON number (not a string, not a boolean). */
fun JsonElement?.numOrNull(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    if (p.content == "true" || p.content == "false") return null
    return p.content.toDoubleOrNull()
}

fun JsonElement?.strOrNull(): String? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content
}

fun JsonElement?.boolOrFalse(): Boolean {
    val p = this as? JsonPrimitive ?: return false
    if (p is JsonNull) return false
    return when (p.content.lowercase()) {
        "true" -> true
        "false", "", "0" -> false
        else -> p.content.toDoubleOrNull()?.let { it != 0.0 } ?: true
    }
}

fun isJsonNumber(e: JsonElement?): Boolean = e.numOrNull() != null

// ---------------------------------------------------------------- night lockdown
data class TimeWindow(val start: String, val end: String)

data class Schedule(val blocked: List<TimeWindow> = emptyList(), val whitelist: List<TimeWindow> = emptyList()) {
    fun get(type: String) = if (type == "blocked") blocked else whitelist
    fun with(type: String, list: List<TimeWindow>) = if (type == "blocked") copy(blocked = list) else copy(whitelist = list)
    fun toJson(): JsonObject = buildJsonObject {
        put("blocked", buildJsonArray { blocked.forEach { add(buildJsonArray { add(JsonPrimitive(it.start)); add(JsonPrimitive(it.end)) }) } })
        put("whitelist", buildJsonArray { whitelist.forEach { add(buildJsonArray { add(JsonPrimitive(it.start)); add(JsonPrimitive(it.end)) }) } })
    }

    companion object {
        fun parse(e: JsonElement?): Schedule {
            val o = e.asObj() ?: return Schedule()
            fun list(k: String) = o[k].asArr()?.map { w ->
                val a = w.asArr()
                TimeWindow(a?.getOrNull(0).strOrNull() ?: "", a?.getOrNull(1).strOrNull() ?: "")
            } ?: emptyList()
            return Schedule(list("blocked"), list("whitelist"))
        }
    }
}

data class NightLockdown(
    val enabled: Boolean = false,
    val perDay: Boolean = false,
    val allDays: Schedule = Schedule(),
    val days: Map<String, Schedule> = DAYS.associateWith { Schedule() },
) {
    fun day(d: String): Schedule = days[d] ?: Schedule()

    fun toJson(): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("per_day", perDay)
        put("all_days", allDays.toJson())
        put("days", buildJsonObject { DAYS.forEach { put(it, day(it).toJson()) } })
    }

    companion object {
        fun parse(e: JsonElement?): NightLockdown {
            val o = e.asObj() ?: return NightLockdown()
            val daysObj = o["days"].asObj()
            return NightLockdown(
                enabled = o["enabled"].boolOrFalse(),
                perDay = o["per_day"].boolOrFalse(),
                allDays = Schedule.parse(o["all_days"]),
                days = DAYS.associateWith { Schedule.parse(daysObj?.get(it)) },
            )
        }
    }
}

// ---------------------------------------------------------------- queue / exceptions
data class QueueItem(val id: Long, val method: String, val endpoint: String, val data: JsonObject?)

/** One exception transaction: [exception_time, reason]. */
data class ExcEntry(val value: JsonElement, val reason: String?)

/** Mirrors normalizeExcList(): old single [time, reason] format or a list of transactions. */
fun normalizeExcList(e: JsonElement?): List<ExcEntry> {
    val a = e.asArr() ?: return emptyList()
    if (a.size == 2 && isJsonNumber(a[0])) return listOf(ExcEntry(a[0], a[1].strOrNull()))
    return a.mapNotNull { item ->
        val t = item.asArr() ?: return@mapNotNull null
        if (t.isEmpty()) null else ExcEntry(t[0], t.getOrNull(1).strOrNull())
    }
}

fun parseExceptionDay(e: JsonElement?): Map<String, List<ExcEntry>> =
    e.asObj()?.mapValues { normalizeExcList(it.value) } ?: emptyMap()

fun parseUsageDay(e: JsonElement?): Map<String, Double> =
    e.asObj()?.mapNotNull { (k, v) -> v.numOrNull()?.let { k to it } }?.toMap() ?: emptyMap()

// ---------------------------------------------------------------- dashboard batch
data class ServerInfo(
    val primaryApi: String? = null,
    val serverDate: String? = null,
    val lastSynced: String? = null,
    val timestamp: String? = null,
)

/** Parsed GET /api/dashboard/data payload (the "data" object). */
data class Dash(
    val limits: Map<String, Map<String, Long?>>,
    val todayExceptions: Map<String, List<ExcEntry>>,
    val todayUsage: Map<String, Double>,
    val nightLockdown: NightLockdown,
    val nightLockdownRaw: JsonElement?,
    val server: ServerInfo,
    val queue: List<QueueItem>,
    val statusDate: String?,
    val config: JsonElement?,
    val raw: JsonObject,
) {
    val primaryOnline get() = server.primaryApi == "online"

    companion object {
        fun parseLimits(e: JsonElement?): Map<String, Map<String, Long?>> {
            val o = e.asObj() ?: return emptyMap()
            val out = LinkedHashMap<String, Map<String, Long?>>()
            for ((name, v) in o) {
                val days = v.asObj() ?: continue
                out[name] = days.mapValues { (_, s) -> s.numOrNull()?.toLong() }
            }
            return out
        }

        fun parse(d: JsonObject): Dash {
            val server = d["server"].asObj()
            return Dash(
                limits = parseLimits(d["limits"]),
                todayExceptions = parseExceptionDay(d["today_exceptions"]),
                todayUsage = parseUsageDay(d["today_usage"]),
                nightLockdown = NightLockdown.parse(d["night_lockdown"]),
                nightLockdownRaw = d["night_lockdown"],
                server = ServerInfo(
                    primaryApi = server?.get("primary_api").strOrNull(),
                    serverDate = server?.get("server_date").strOrNull(),
                    lastSynced = server?.get("last_synced").strOrNull(),
                    timestamp = server?.get("timestamp").strOrNull(),
                ),
                queue = d["queue"].asArr()?.mapNotNull { q ->
                    val o = q.asObj() ?: return@mapNotNull null
                    QueueItem(
                        id = o["id"].numOrNull()?.toLong() ?: return@mapNotNull null,
                        method = o["method"].strOrNull() ?: "",
                        endpoint = o["endpoint"].strOrNull() ?: "",
                        data = o["data"].asObj(),
                    )
                } ?: emptyList(),
                statusDate = d["status"].asObj()?.get("current_date").strOrNull(),
                config = d["config"],
                raw = d,
            )
        }
    }
}

/** limits[name][day] as JSON for POST/PUT bodies (null = no limit). */
fun limitsToJson(limits: Map<String, Long?>): JsonObject = buildJsonObject {
    DAYS.forEach { d -> val v = limits[d]; if (v == null) put(d, JsonNull) else put(d, v) }
}

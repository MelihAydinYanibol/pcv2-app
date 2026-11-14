package com.pcv2.app.data

import android.content.Context
import com.pcv2.app.core.JSON
import com.pcv2.app.core.asArr
import com.pcv2.app.core.asObj
import com.pcv2.app.core.numOrNull
import com.pcv2.app.core.parseUsageDay
import com.pcv2.app.core.strOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Settings that the web dashboard keeps in localStorage / cookies. */
data class Settings(
    val server: String = "",
    val authMode: String = "password",   // "password" | "token"
    val token: String = "",
    val cookie: String = "",
    val pollSec: Int = 5,
    val bufferSec: Int = 30,
    val theme: String = "auto",           // "auto" | "light" | "dark"
    val lang: String? = null,             // null = follow the phone
) {
    val signedIn get() = server.isNotEmpty() && (cookie.isNotEmpty() || (authMode == "token" && token.isNotEmpty()) || authMode == "none")
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("pcv2", Context.MODE_PRIVATE)

    fun load() = Settings(
        server = sp.getString("server", "") ?: "",
        authMode = sp.getString("authMode", "password") ?: "password",
        token = sp.getString("token", "") ?: "",
        cookie = sp.getString("cookie", "") ?: "",
        pollSec = sp.getInt("pollSec", 5),
        bufferSec = sp.getInt("bufferSec", 30),
        theme = sp.getString("theme", "auto") ?: "auto",
        lang = sp.getString("lang", null),
    )

    fun save(s: Settings) {
        sp.edit()
            .putString("server", s.server)
            .putString("authMode", s.authMode)
            .putString("token", s.token)
            .putString("cookie", s.cookie)
            .putInt("pollSec", s.pollSec)
            .putInt("bufferSec", s.bufferSec)
            .putString("theme", s.theme)
            .putString("lang", s.lang)
            .apply()
    }

    fun getString(key: String): String? = sp.getString(key, null)
    fun putString(key: String, v: String?) = sp.edit().putString(key, v).apply()
}

/** A periodic usage sample (for the "Activity today" timeline). */
data class Sample(val t: Long, val date: String, val overall: Double, val usage: Map<String, Double>)

data class BufferSnapshot(
    val savedAt: Long,
    val source: String,
    val lastLiveAt: Long,
    val data: JsonObject?,
    val usageAll: Map<String, Map<String, Double>>,
)

/**
 * The "browser buffer" of the dashboard, stored in the app's private files:
 * the latest batch payload and usage samples, so the app can still show
 * something while the kid's PC is off or the server is unreachable.
 */
class Buffer(context: Context) {
    private val dir = File(context.filesDir, "buffer").apply { mkdirs() }
    private val snapFile = File(dir, "snapshot.json")
    private val histFile = File(dir, "history.json")

    fun loadSnapshot(): BufferSnapshot? = runCatching {
        if (!snapFile.exists()) return null
        val o = JSON.parseToJsonElement(snapFile.readText()).asObj() ?: return null
        BufferSnapshot(
            savedAt = o["savedAt"].numOrNull()?.toLong() ?: 0,
            source = o["source"].strOrNull() ?: "buffer",
            lastLiveAt = o["lastLiveAt"].numOrNull()?.toLong() ?: 0,
            data = o["data"].asObj(),
            usageAll = o["usageAll"].asObj()?.mapValues { parseUsageDay(it.value) } ?: emptyMap(),
        )
    }.getOrNull()

    fun saveSnapshot(s: BufferSnapshot) {
        val o = buildJsonObject {
            put("savedAt", s.savedAt)
            put("source", s.source)
            put("lastLiveAt", s.lastLiveAt)
            if (s.data != null) put("data", s.data)
            put("usageAll", usageToJson(s.usageAll))
        }
        writeAtomic(snapFile, JSON.encodeToString(JsonElement.serializer(), o))
    }

    fun loadHistory(): List<Sample> = runCatching {
        if (!histFile.exists()) return emptyList()
        JSON.parseToJsonElement(histFile.readText()).asArr()?.mapNotNull { e ->
            val o = e.asObj() ?: return@mapNotNull null
            Sample(
                t = o["t"].numOrNull()?.toLong() ?: return@mapNotNull null,
                date = o["date"].strOrNull() ?: return@mapNotNull null,
                overall = o["overall"].numOrNull() ?: 0.0,
                usage = parseUsageDay(o["usage"]),
            )
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun saveHistory(list: List<Sample>) {
        val arr = buildJsonArray {
            list.forEach { s ->
                add(buildJsonObject {
                    put("t", s.t); put("date", s.date); put("overall", s.overall)
                    put("usage", JsonObject(s.usage.mapValues { JsonPrimitive(it.value) }))
                })
            }
        }
        writeAtomic(histFile, JSON.encodeToString(JsonElement.serializer(), arr))
    }

    fun clear() {
        snapFile.delete()
        histFile.delete()
    }

    fun sizeBytes(): Long = (if (snapFile.exists()) snapFile.length() else 0) + (if (histFile.exists()) histFile.length() else 0)

    /** Everything, for "Download buffer". */
    fun exportJson(): String {
        val snap = runCatching { JSON.parseToJsonElement(snapFile.readText()) }.getOrNull()
        val hist = runCatching { JSON.parseToJsonElement(histFile.readText()) }.getOrNull()
        val o = buildJsonObject {
            if (snap != null) put("buffer", snap)
            if (hist != null) put("history", hist)
        }
        return com.pcv2.app.core.JSON_PRETTY.encodeToString(JsonElement.serializer(), o)
    }

    private fun writeAtomic(f: File, text: String) {
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        fun usageToJson(all: Map<String, Map<String, Double>>) =
            JsonObject(all.mapValues { (_, day) -> JsonObject(day.mapValues { JsonPrimitive(it.value) }) })
    }
}

package com.pcv2.app.data

import com.pcv2.app.core.JSON
import com.pcv2.app.core.asObj
import com.pcv2.app.core.strOrNull
import com.pcv2.app.core.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** The server answered 401 {"auth": "required"} (session expired / signed out elsewhere). */
class AuthRequiredException : IOException(t("Sign-in required"))

/** Network failure or timeout, with a message ready to show. */
class ApiException(message: String) : IOException(message)

data class ApiResult(val status: Int, val json: JsonObject) {
    val ok get() = status in 200..299
    val message: String? get() = json["message"].strOrNull()
    val data: JsonElement? get() = json["data"]
}

sealed interface LoginResult {
    /** cookie is null when the server runs with sign-in disabled or the token is used. */
    data class Success(val cookie: String?) : LoginResult
    data class Failure(val message: String) : LoginResult
}

/** Encodes one path segment like encodeURIComponent(). */
fun seg(v: String): String = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

/**
 * Normalises what the user typed into the server root, e.g.
 * "192.168.1.10:5035/api/" -> "http://192.168.1.10:5035".
 */
fun normalizeServer(input: String): String {
    var s = input.trim()
    if (s.isEmpty()) return s
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
    s = s.trimEnd('/')
    for (suffix in listOf("/api", "/login", "/dashboard", "/widget", "/mock")) {
        if (s.endsWith(suffix, true)) s = s.dropLast(suffix.length)
    }
    return s.trimEnd('/')
}

/**
 * Talks to serverside/secondary_api.py exactly like the dashboard does:
 * the same /api/... endpoints, plus the /login form for the password sign-in.
 */
class ApiClient(base: OkHttpClient? = null) {
    @Volatile var root: String = ""
    @Volatile var token: String? = null       // DASHBOARD_API_TOKEN (Bearer)
    @Volatile var cookie: String? = null      // pcv2_session value
    @Volatile var lang: String = "en"

    private val http: OkHttpClient = base ?: OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun cookieHeader(sessionCookie: String? = cookie): String =
        buildList {
            if (!sessionCookie.isNullOrEmpty()) add("$COOKIE=$sessionCookie")
            add("pcv2_lang=$lang")
        }.joinToString("; ")

    private fun Request.Builder.auth(): Request.Builder {
        header("Cookie", cookieHeader())
        token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        header("Accept", "application/json")
        header("User-Agent", "PCV2-Android")
        return this
    }

    /**
     * One API call (path relative to /api, already URL-encoded).
     * Throws [AuthRequiredException] or [ApiException]; any HTTP status is returned.
     */
    suspend fun call(method: String, path: String, body: JsonElement? = null, timeoutMs: Long = 8000): ApiResult =
        withContext(Dispatchers.IO) {
            val url = "$root/api$path".toHttpUrlOrNull() ?: throw ApiException(t("Cannot reach the server"))
            val reqBody = when {
                body != null -> JSON.encodeToString(JsonElement.serializer(), body).toRequestBody(JSON_TYPE)
                method == "GET" || method == "DELETE" -> null
                else -> ByteArray(0).toRequestBody(null)
            }
            val req = Request.Builder().url(url).method(method, reqBody).auth().build()
            val client = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            val res: Response = try {
                client.newCall(req).execute()
            } catch (e: InterruptedIOException) {
                throw ApiException(t("The server took too long to answer"))
            } catch (e: IOException) {
                throw ApiException(t("Cannot reach the server"))
            }
            res.use { r ->
                val text = try { r.body?.string().orEmpty() } catch (e: IOException) { "" }
                val json = runCatching { JSON.parseToJsonElement(text).asObj() }.getOrNull() ?: JsonObject(emptyMap())
                if (r.code == 401 && json["auth"].strOrNull() == "required") throw AuthRequiredException()
                ApiResult(r.code, json)
            }
        }

    /**
     * Password sign-in through the same form the browser uses: POST /login,
     * then keep the pcv2_session cookie from the 303 redirect.
     */
    suspend fun loginWithPassword(server: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val url = "$server/login".toHttpUrlOrNull() ?: return@withContext LoginResult.Failure(t("Cannot reach the server"))
        val req = Request.Builder().url(url)
            .post(FormBody.Builder().add("username", "Parent").add("password", password).add("next", "/").build())
            .header("Cookie", "pcv2_lang=$lang")
            .header("User-Agent", "PCV2-Android")
            .build()
        val res = try {
            http.newCall(req).execute()
        } catch (e: InterruptedIOException) {
            return@withContext LoginResult.Failure(t("The server took too long to answer"))
        } catch (e: IOException) {
            return@withContext LoginResult.Failure(t("Cannot reach the server") + " (${e.message ?: e.javaClass.simpleName})")
        }
        res.use { r ->
            val sessionCookie = r.headers("Set-Cookie").firstNotNullOfOrNull { h ->
                Regex("(?:^|\\s)$COOKIE=([^;]*)").find(h)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            }
            val html = runCatching { r.body?.string().orEmpty() }.getOrDefault("")
            when {
                r.code in 300..399 -> {
                    // Verify the session (also covers servers running with AUTH_DISABLED)
                    if (verify(server, sessionCookie, null)) LoginResult.Success(sessionCookie)
                    else LoginResult.Failure(t("This does not look like a PCV2 server."))
                }
                r.code == 401 || r.code == 429 -> LoginResult.Failure(
                    Regex("<div class=\"error show\"[^>]*>(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(html)
                        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: if (r.code == 401) t("Wrong password.") else t("Server error {n}", "n" to r.code),
                )
                r.code == 403 -> LoginResult.Failure(t("Server responded with {n}", "n" to r.code))
                else -> LoginResult.Failure(t("This does not look like a PCV2 server."))
            }
        }
    }

    /** Token sign-in: the token itself is the credential, just check it works. */
    suspend fun loginWithToken(server: String, apiToken: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            if (verify(server, null, apiToken)) LoginResult.Success(null)
            else LoginResult.Failure(t("The API token was rejected."))
        } catch (e: IOException) {
            LoginResult.Failure(t("Cannot reach the server") + " (${e.message ?: e.javaClass.simpleName})")
        }
    }

    private fun verify(server: String, sessionCookie: String?, apiToken: String?): Boolean {
        val url = "$server/api/auth/status".toHttpUrlOrNull() ?: return false
        val b = Request.Builder().url(url).header("Cookie", cookieHeader(sessionCookie)).header("Accept", "application/json")
        apiToken?.let { b.header("Authorization", "Bearer $it") }
        http.newCall(b.build()).execute().use { r ->
            if (r.code != 200) return false
            val json = runCatching { JSON.parseToJsonElement(r.body?.string().orEmpty()).asObj() }.getOrNull()
            return json?.get("status").strOrNull() == "success"
        }
    }

    /** Ends the session on the server (best effort). */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val url = "$root/logout".toHttpUrlOrNull() ?: return@withContext
        runCatching {
            http.newCall(Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).header("Cookie", cookieHeader()).build())
                .execute().close()
        }
    }

    companion object {
        const val COOKIE = "pcv2_session"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

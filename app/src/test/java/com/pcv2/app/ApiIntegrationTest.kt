package com.pcv2.app

import com.pcv2.app.core.DAYS
import com.pcv2.app.core.Dash
import com.pcv2.app.core.NightLockdown
import com.pcv2.app.core.Schedule
import com.pcv2.app.core.TimeWindow
import com.pcv2.app.core.asObj
import com.pcv2.app.core.limitsToJson
import com.pcv2.app.core.localDate
import com.pcv2.app.core.parseExceptionDay
import com.pcv2.app.core.strOrNull
import com.pcv2.app.data.ApiClient
import com.pcv2.app.data.AuthRequiredException
import com.pcv2.app.data.LoginResult
import com.pcv2.app.data.seg
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the app's API client against the mock server (mock-server/mock_server.py).
 * Skipped unless PCV2_MOCK_URL is set, e.g.
 *   PCV2_MOCK_URL=http://127.0.0.1:5035 ./gradlew testDebugUnitTest
 * The mock must use the default password "parent1234" and --token test-token.
 */
class ApiIntegrationTest {
    private val url = System.getProperty("pcv2.mockUrl").orEmpty().trimEnd('/')
    private lateinit var api: ApiClient

    private fun control(json: String) {
        OkHttpClient().newCall(
            Request.Builder().url("$url/mock/control").post(json.toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()
    }

    @Before fun setUp() = runBlocking {
        assumeTrue("PCV2_MOCK_URL not set", url.isNotEmpty())
        control("""{"action":"reset"}""")
        control("""{"action":"unlock_login"}""")
        api = ApiClient()
        api.root = url
        val r = api.loginWithPassword(url, "parent1234")
        assertTrue("login: $r", r is LoginResult.Success)
        api.cookie = (r as LoginResult.Success).cookie
        assertNotNull(api.cookie)
    }

    @Test fun wrong_password_shows_server_message() = runBlocking {
        val r = ApiClient().loginWithPassword(url, "nope")
        assertEquals(LoginResult.Failure("Wrong password."), r)
        val tr = ApiClient().apply { lang = "tr" }.loginWithPassword(url, "nope")
        assertEquals(LoginResult.Failure("Yanlış şifre."), tr)
        control("""{"action":"unlock_login"}""")
    }

    @Test fun token_sign_in() = runBlocking {
        assertTrue(ApiClient().loginWithToken(url, "test-token") is LoginResult.Success)
        assertTrue(ApiClient().loginWithToken(url, "bad") is LoginResult.Failure)
        val tokenApi = ApiClient().apply { root = url; token = "test-token" }
        assertEquals(200, tokenApi.call("GET", "/limits").status)
    }

    @Test fun no_password_sign_in_only_when_the_server_allows_it() = runBlocking {
        // Sign-in required: an empty password is refused
        assertEquals(LoginResult.Failure("Enter the password."), ApiClient().loginWithoutPassword(url))
        control("""{"action":"auth_disabled","value":true}""")
        try {
            assertEquals(LoginResult.Success(null), ApiClient().loginWithoutPassword(url))
            // A typed password also works: POST /login just redirects, without a cookie
            assertEquals(LoginResult.Success(null), ApiClient().loginWithPassword(url, "anything"))
            assertEquals(200, ApiClient().apply { root = url }.call("GET", "/dashboard/data").status)
        } finally {
            control("""{"action":"auth_disabled","value":false}""")
        }
    }

    @Test fun dashboard_batch_parses() = runBlocking {
        val r = api.call("GET", "/dashboard/data")
        assertEquals(200, r.status)
        val d = Dash.parse(r.data.asObj()!!)
        assertTrue(d.primaryOnline)
        assertTrue("OVERALL" in d.limits)
        assertEquals(localDate(), d.server.serverDate)
        assertTrue(d.nightLockdown.enabled)
    }

    @Test fun limits_create_edit_delete() = runBlocking {
        val limits = DAYS.associateWith<String, Long?> { 1800L } + ("Sunday" to null)
        val body = buildJsonObject { put("app_name", "My Game.exe"); put("limits", limitsToJson(limits)) }
        assertEquals(200, api.call("POST", "/limits", body).status)
        assertEquals(409, api.call("POST", "/limits", body).status)
        val edit = buildJsonObject { put("limits", limitsToJson(limits + ("Monday" to 60L))) }
        assertEquals(200, api.call("PUT", "/limits/${seg("My Game.exe")}", edit).status)
        val got = api.call("GET", "/limits").data.asObj()!!
        assertEquals("60", got["My Game.exe"]!!.asObj()!!["Monday"].strOrNull())
        assertEquals(200, api.call("DELETE", "/limits/${seg("My Game.exe")}").status)
        assertEquals(404, api.call("DELETE", "/limits/${seg("My Game.exe")}").status)
    }

    @Test fun exceptions_add_set_and_delete() = runBlocking {
        val date = localDate()
        fun body(v: JsonPrimitive) = buildJsonObject { put("date", date); put("app_name", "chrome.exe"); put("exception_time", v); put("reason", "test") }
        assertEquals(200, api.call("POST", "/exceptions", body(JsonPrimitive(900))).status)
        assertEquals(200, api.call("POST", "/exceptions", body(JsonPrimitive("3600"))).status)
        var day = parseExceptionDay(api.call("GET", "/exceptions/$date").data)
        assertEquals(2, day["chrome.exe"]!!.size)
        assertEquals(200, api.call("DELETE", "/exceptions/$date/chrome.exe/0").status)
        day = parseExceptionDay(api.call("GET", "/exceptions/$date").data)
        assertEquals(1, day["chrome.exe"]!!.size)
        assertEquals(200, api.call("DELETE", "/exceptions/$date/chrome.exe").status)
        assertFalse("chrome.exe" in parseExceptionDay(api.call("GET", "/exceptions/$date").data))
    }

    @Test fun night_lockdown_put_and_validation() = runBlocking {
        val nl = NightLockdown(enabled = true, perDay = true, days = DAYS.associateWith { Schedule(blocked = listOf(TimeWindow("21:00", "06:30"))) })
        val r = api.call("PUT", "/nightlockdown", nl.toJson())
        assertEquals(200, r.status)
        assertEquals(nl, NightLockdown.parse(api.call("GET", "/nightlockdown").data))
        val bad = nl.copy(allDays = Schedule(blocked = listOf(TimeWindow("25:00", "07:00"))))
        assertEquals(400, api.call("PUT", "/nightlockdown", bad.toJson()).status)
    }

    @Test fun usage_and_config() = runBlocking {
        val all = api.call("GET", "/usage").data.asObj()!!
        assertTrue(all.size >= 7)
        val cfg = api.call("GET", "/config").data.asObj()!!
        assertTrue("limits" in cfg && "night_lockdown" in cfg)
        assertEquals(200, api.call("POST", "/config", buildJsonObject { put("limits", cfg["limits"]!!) }).status)
    }

    @Test fun offline_queue_cancel_and_sync() = runBlocking {
        api.call("GET", "/dashboard/data")
        control("""{"action":"online","value":false}""")
        try {
            val d = Dash.parse(api.call("GET", "/dashboard/data").data.asObj()!!)
            assertFalse(d.primaryOnline)
            assertTrue(d.limits.isNotEmpty())
            val add = buildJsonObject { put("date", localDate()); put("app_name", "OVERALL"); put("exception_time", 600) }
            assertEquals(202, api.call("POST", "/exceptions", add).status)
            assertEquals(202, api.call("DELETE", "/limits/discord.exe").status)
            val q = Dash.parse(api.call("GET", "/dashboard/data").data.asObj()!!).queue
            assertEquals(2, q.size)
            assertEquals(200, api.call("DELETE", "/server/queue/${q[1].id}").status)
            assertEquals(404, api.call("DELETE", "/server/queue/${q[1].id}").status)
        } finally {
            control("""{"action":"online","value":true}""")
        }
        api.call("POST", "/server/sync")
        val d = Dash.parse(api.call("GET", "/dashboard/data").data.asObj()!!)
        assertTrue(d.queue.isEmpty())
        assertTrue("discord.exe" in d.limits) // the cancelled delete never ran
    }

    @Test(expected = AuthRequiredException::class)
    fun expired_session_raises_auth_required() {
        runBlocking {
            control("""{"action":"expire_sessions"}""")
            api.call("GET", "/dashboard/data")
        }
    }
}

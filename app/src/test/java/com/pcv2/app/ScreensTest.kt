package com.pcv2.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import java.time.Duration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.pcv2.app.core.I18n
import com.pcv2.app.data.ApiClient
import com.pcv2.app.data.LoginResult
import com.pcv2.app.ui.AppViewModel
import com.pcv2.app.ui.LoginScreen
import com.pcv2.app.ui.MainScreen
import com.pcv2.app.ui.PcvTheme
import com.pcv2.app.ui.Sheet
import com.pcv2.app.ui.Source
import com.pcv2.app.ui.Tab
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Drives the real Compose UI against the mock server and saves a screenshot of
 * every screen (light/dark, English/Turkish) to app/build/screenshots.
 * Skipped unless PCV2_MOCK_URL is set (see ApiIntegrationTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp-xhdpi")
class ScreensTest {
    @get:Rule val compose: ComposeContentTestRule = createComposeRule()

    private val url = System.getProperty("pcv2.mockUrl").orEmpty().trimEnd('/')
    private val shotDir = File(System.getProperty("pcv2.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun control(json: String) {
        OkHttpClient().newCall(
            Request.Builder().url("$url/mock/control").post(json.toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()
    }

    @Before fun setUp() {
        assumeTrue("PCV2_MOCK_URL not set", url.isNotEmpty())
        control("""{"action":"reset"}""")
        control("""{"action":"unlock_login"}""")
        ctx.getSharedPreferences("pcv2", Context.MODE_PRIVATE).edit().clear().commit()
        I18n.lang = "en"
    }

    @After fun tearDown() { I18n.lang = "en" }

    private fun signInPrefs(theme: String = "light", lang: String = "en") {
        val cookie = runBlocking { (ApiClient().loginWithPassword(url, "parent1234") as LoginResult.Success).cookie }
        ctx.getSharedPreferences("pcv2", Context.MODE_PRIVATE).edit()
            .putString("server", url).putString("authMode", "password").putString("cookie", cookie)
            .putString("theme", theme).putString("lang", lang).commit()
    }

    /** Lets time pass on the main looper so frames, animations and network callbacks run. */
    private fun pump(ms: Long = 400) {
        var left = ms
        while (left > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            compose.mainClock.advanceTimeBy(16)
            left -= 16
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowViews(): List<View> {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val inst = cls.getMethod("getInstance").invoke(null)
        val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
        return (f.get(inst) as List<View>).toList().filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
    }

    /** Draws every visible window (activity + sheets/dialogs on top) into one PNG. */
    private fun shot(name: String) {
        pump(1200)
        val views = windowViews()
        val base = views.first()
        val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (v in views) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(loc[0].toFloat(), loc[1].toFloat())
            v.draw(canvas)
            canvas.restore()
        }
        FileOutputStream(File(shotDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shootRoot(name: String) = shot(name)
    private fun shootSheet(name: String) = shot(name)

    private var debugVm: AppViewModel? = null

    /** Pumps the main looper (network results resume there) until [cond] holds. */
    private fun waitFor(timeoutMs: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            pump(100)
            if (cond()) return
            Thread.sleep(50)
        }
        val v = debugVm
        throw AssertionError("Timed out; source=${v?.source} data=${v?.data != null} signedIn=${v?.signedIn} error=${v?.lastError} toasts=${v?.toasts?.map { it.title + ": " + it.message }}")
    }

    /** setContent may only be called once per test, so the shown app is swappable state. */
    private var shown by mutableStateOf<Pair<AppViewModel, Boolean>?>(null)
    private var contentSet = false

    private fun show(vm: AppViewModel, dark: Boolean) {
        shown = vm to dark
        debugVm = vm
        if (!contentSet) {
            contentSet = true
            compose.setContent {
                val (v, d) = shown ?: return@setContent
                PcvTheme(d) { if (v.signedIn) MainScreen(v, d) else LoginScreen(v) }
            }
        }
        pump()
    }

    @Test fun sign_in_with_password_then_overview() {
        val vm = AppViewModel(ctx.applicationContext as android.app.Application)
        show(vm, false)
        shootRoot("01_login_en_light")
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement(url)
        fields[1].performTextReplacement("wrong-password")
        compose.onNodeWithText("Sign in").performClick()
        waitFor { compose.onAllNodesWithText("Wrong password.").fetchSemanticsNodes().isNotEmpty() }
        shootRoot("02_login_error")
        fields[1].performTextReplacement("parent1234")
        compose.onNodeWithText("Sign in").performClick()
        waitFor { vm.signedIn && vm.data != null }
        pump()
        compose.onNodeWithText("Computer time left today", ignoreCase = true).assertExists()
        assertEquals(Source.LIVE, vm.source)
        vm.onBackground()
    }

    @Test fun server_without_sign_in_accepts_empty_password_and_sign_out_sticks() {
        control("""{"action":"auth_disabled","value":true}""")
        try {
            val vm = AppViewModel(ctx.applicationContext as android.app.Application)
            show(vm, false)
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement(url)
            compose.onNodeWithText("Sign in").performClick()
            waitFor { vm.signedIn && vm.data != null && vm.source == Source.LIVE }
            assertEquals("none", vm.settings.authMode)
            // Still signed in after a restart
            assertTrue(AppViewModel(ctx.applicationContext as android.app.Application).signedIn)
            vm.tab = Tab.SETTINGS
            shootRoot("60_settings_no_sign_in")
            vm.signOut(wipe = true)
            waitFor { !vm.signedIn }
            vm.onBackground()
            // ...but not after signing out
            assertTrue(!AppViewModel(ctx.applicationContext as android.app.Application).signedIn)
        } finally {
            control("""{"action":"auth_disabled","value":false}""")
        }
    }

    @Test fun every_screen_light_dark_english_turkish() {
        for ((theme, lang) in listOf("light" to "en", "dark" to "en", "light" to "tr", "dark" to "tr")) {
            signInPrefs(theme, lang)
            val vm = AppViewModel(ctx.applicationContext as android.app.Application)
            I18n.lang = lang
            show(vm, theme == "dark")
            vm.onForeground()
            waitFor { vm.data != null && vm.source == Source.LIVE }
            vm.loadUsageAll()
            waitFor { vm.usageAll.size >= 7 }
            val suffix = "${lang}_$theme"
            for (tab in Tab.entries) {
                vm.tab = tab
                if (tab == Tab.EXCEPTIONS) vm.loadExceptions(force = true)
                pump()
                shootRoot("1${tab.ordinal}_${tab.name.lowercase()}_$suffix")
            }
            if (theme == "light" && lang == "en") {
                vm.tab = Tab.OVERVIEW
                vm.sheet = Sheet.Exception("chrome.exe", 900)
                shootSheet("20_sheet_exception_$suffix")
                vm.sheet = Sheet.Limit("discord.exe")
                shootSheet("21_sheet_limit_$suffix")
                vm.sheet = null
                pump()
            }
            vm.onBackground()
        }
    }

    @Test fun offline_pc_shows_cache_banner_and_queues_changes() {
        signInPrefs()
        val vm = AppViewModel(ctx.applicationContext as android.app.Application)
        show(vm, false)
        vm.onForeground()
        waitFor { vm.data != null && vm.source == Source.LIVE }
        control("""{"action":"online","value":false}""")
        try {
            control("""{"action":"queue_samples"}""")
            vm.refresh()
            waitFor { vm.source == Source.CACHE && (vm.data?.queue?.size ?: 0) == 3 }
            compose.onNodeWithText("The kid's computer is turned off or unreachable.").assertExists()
            shootRoot("30_overview_pc_offline")
            vm.tab = Tab.QUEUE
            shootRoot("31_queue_pending")
            vm.tab = Tab.LIMITS
            shootRoot("32_limits_with_queued")
        } finally {
            control("""{"action":"online","value":true}""")
        }
        vm.onBackground()
    }

    @Test fun server_unreachable_falls_back_to_buffer() {
        signInPrefs()
        val vm = AppViewModel(ctx.applicationContext as android.app.Application)
        show(vm, false)
        vm.onForeground()
        waitFor { vm.data != null && vm.source == Source.LIVE }
        vm.onBackground()
        // Point a fresh app at a dead port: it paints from the buffer, then shows the red banner
        ctx.getSharedPreferences("pcv2", Context.MODE_PRIVATE).edit().putString("server", "http://127.0.0.1:9").commit()
        val vm2 = AppViewModel(ctx.applicationContext as android.app.Application)
        assertTrue(vm2.data != null)
        show(vm2, false)
        vm2.onForeground()
        waitFor { vm2.source == Source.BUFFER && !vm2.booting }
        compose.onNodeWithText("Cannot reach the server at http://127.0.0.1:9/api.").assertExists()
        shootRoot("40_server_unreachable")
        vm2.onBackground()
    }

    @Test fun quick_exception_flow_reaches_server() {
        signInPrefs()
        val vm = AppViewModel(ctx.applicationContext as android.app.Application)
        show(vm, false)
        vm.onForeground()
        waitFor { vm.data != null && vm.source == Source.LIVE }
        val before = vm.data!!.todayExceptions["OVERALL"]?.size ?: 0
        compose.onAllNodesWithText("+15m")[0].performClick()
        waitFor { vm.confirmReq != null }
        pump()
        compose.onNodeWithText("Add 15m for Whole computer?").assertExists()
        shootSheet("50_confirm_quick_exception")
        compose.onNodeWithText("Add 15m").performClick()
        pump()
        waitFor { (vm.data?.todayExceptions?.get("OVERALL")?.size ?: 0) == before + 1 && vm.toasts.isNotEmpty() }
        shootRoot("51_toast_saved")
        vm.onBackground()
    }
}

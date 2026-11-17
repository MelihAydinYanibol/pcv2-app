package com.pcv2.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pcv2.app.core.I18n
import com.pcv2.app.data.ApiClient
import com.pcv2.app.data.LoginResult
import com.pcv2.app.widget.TimeWidget
import com.pcv2.app.widget.WidgetState
import com.pcv2.app.widget.WidgetUpdater
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * The home-screen widget, refreshed against the mock server (PCV2_MOCK_URL),
 * with screenshots of each state and size in app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xhdpi")
class WidgetTest {
    private val url = System.getProperty("pcv2.mockUrl").orEmpty().trimEnd('/')
    private val shotDir = File(System.getProperty("pcv2.screenshotDir") ?: "build/screenshots").apply { mkdirs() }
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val mgr get() = shadowOf(AppWidgetManager.getInstance(ctx))

    private fun control(json: String) {
        OkHttpClient().newCall(
            Request.Builder().url("$url/mock/control").post(json.toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()
    }

    @Before fun setUp() {
        assumeTrue("PCV2_MOCK_URL not set", url.isNotEmpty())
        control("""{"action":"reset"}""")
        control("""{"action":"lockdown_off"}""")  // same result whatever time the test runs
        control("""{"action":"unlock_login"}""")
        I18n.lang = "en"
        val cookie = runBlocking { (ApiClient().loginWithPassword(url, "parent1234") as LoginResult.Success).cookie }
        ctx.getSharedPreferences("pcv2", Context.MODE_PRIVATE).edit().clear()
            .putString("server", url).putString("authMode", "password").putString("cookie", cookie).putString("lang", "en").commit()
    }

    private fun refreshedView(): View {
        val id = mgr.createWidget(TimeWidget::class.java, R.layout.widget_time)
        runBlocking { WidgetUpdater.refresh(ctx) }
        return mgr.getViewFor(id)
    }

    private fun text(v: View, id: Int) = (v.findViewById<TextView>(id)).text.toString()

    /** Inflates a widget of the given size (dp) for [st] and saves it as a PNG. */
    private fun shot(st: WidgetState, wDp: Int, hDp: Int, name: String): View {
        val d = ctx.resources.displayMetrics.density
        val host = FrameLayout(ctx)
        val v = WidgetUpdater.build(ctx, st, hDp).apply(ctx, host)
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val pad = (12 * d).toInt()
        val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(if (RuntimeEnvironment.getQualifiers().contains("night")) 0xFF2B2F3A.toInt() else 0xFF7A8BA8.toInt()) // "wallpaper"
        c.translate(pad.toFloat(), pad.toFloat())
        v.draw(c)
        FileOutputStream(File(shotDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return v
    }

    private fun lastState(): WidgetState {
        val f = WidgetUpdater::class.java.getDeclaredField("last").apply { isAccessible = true }
        return f.get(WidgetUpdater) as WidgetState
    }

    @Test fun live_countdown_from_the_server() {
        val v = refreshedView()
        val count = v.findViewById<Chronometer>(R.id.w_count)
        assertEquals(View.VISIBLE, count.visibility)
        assertTrue(count.isCountDown)
        assertTrue("Used 1h 10m" in text(v, R.id.w_sub))
        val st = lastState()
        assertTrue(st.live)
        shot(st, 260, 115, "70_widget_small")
        shot(st, 320, 240, "71_widget_large")
        shot(st, 200, 80, "72_widget_tiny")
        RuntimeEnvironment.setQualifiers("+night")
        shot(st, 320, 230, "73_widget_large_dark")
    }

    @Test fun pc_offline_shows_paused_and_cached_time() {
        // One refresh per widget: Robolectric's RemoteViews.reapply fails on a second update.
        // The mock filled its cache on reset, so the server answers from cache.
        control("""{"action":"online","value":false}""")
        try {
            refreshedView()
            val st = lastState()
            assertTrue(!st.live && !st.unreachable)
            val v = shot(st, 320, 160, "74_widget_pc_offline")
            assertEquals(View.VISIBLE, v.findViewById<View>(R.id.w_big).visibility)
            assertTrue(text(v, R.id.w_sub).startsWith("Paused · PC offline"))
        } finally {
            control("""{"action":"online","value":true}""")
        }
    }

    @Test fun lockdown_active() {
        control("""{"action":"lockdown_now"}""")
        refreshedView()
        val v = shot(lastState(), 320, 160, "75_widget_locked")
        assertEquals("Locked", text(v, R.id.w_big))
    }

    @Test fun time_up() {
        control("""{"action":"time_up"}""")
        control("""{"action":"add_usage","app":"OVERALL","value":120}""")
        refreshedView()
        val v = shot(lastState(), 320, 160, "76_widget_time_up")
        assertEquals("Time is up", text(v, R.id.w_big))
    }

    @Test fun signed_out() {
        ctx.getSharedPreferences("pcv2", Context.MODE_PRIVATE).edit().putString("cookie", "").commit()
        val v = refreshedView()
        assertEquals("Sign-in required", text(v, R.id.w_sub))
    }

    @Test fun server_unreachable() {
        // A server that cannot be reached: falls back to the app's buffer (empty here)
        ctx.getSharedPreferences("pcv2", Context.MODE_PRIVATE).edit().putString("cookie", "x").putString("server", "http://127.0.0.1:9").commit()
        refreshedView()
        val v = shot(lastState(), 260, 115, "77_widget_unreachable")
        assertEquals("Server unreachable", text(v, R.id.w_sub))
    }

    @Test fun turkish() {
        ctx.getSharedPreferences("pcv2", Context.MODE_PRIVATE).edit().putString("lang", "tr").commit()
        refreshedView()
        val v = shot(lastState(), 320, 230, "78_widget_tr")
        assertEquals("Bugün kalan bilgisayar süresi", text(v, R.id.w_label))
        I18n.lang = "en"
    }
}

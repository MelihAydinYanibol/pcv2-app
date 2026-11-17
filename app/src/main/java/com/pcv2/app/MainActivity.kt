package com.pcv2.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import com.pcv2.app.ui.AppViewModel
import com.pcv2.app.ui.LoginScreen
import com.pcv2.app.ui.MainScreen
import com.pcv2.app.ui.PcvTheme
import com.pcv2.app.ui.Sheet

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = when (vm.settings.theme) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            PcvTheme(dark) {
                if (vm.signedIn) MainScreen(vm, dark) else LoginScreen(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The widget's "+" button: open the exception sheet prefilled (nothing changes until confirmed). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_EXCEPTION && vm.signedIn) {
            vm.sheet = Sheet.Exception(intent.getStringExtra(EXTRA_APP) ?: "OVERALL", intent.getLongExtra(EXTRA_SECS, 900L))
        }
    }

    companion object {
        const val ACTION_EXCEPTION = "com.pcv2.app.action.EXCEPTION"
        const val EXTRA_APP = "app"
        const val EXTRA_SECS = "secs"
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}

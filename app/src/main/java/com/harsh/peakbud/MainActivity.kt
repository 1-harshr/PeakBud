package com.harsh.peakbud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.harsh.peakbud.data.settings.AppSettings
import com.harsh.peakbud.data.settings.Preferences
import com.harsh.peakbud.device.DeviceReadings
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.harsh.peakbud.feature.panorama.PeakBudApp
import com.harsh.peakbud.ui.theme.PeakBudTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        var appearanceReady = false
        installSplashScreen().setKeepOnScreenCondition { !appearanceReady }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Release the splash from the Activity coroutine, independently of Compose's first frame.
        lifecycleScope.launch {
            AppSettings(applicationContext).preferences.catch { emit(Preferences()) }.first()
            appearanceReady = true
        }
        setContent { PeakBudRoot() }
    }
}

/** A single appearance owner keeps every screen and sheet in sync with the saved preference. */
@Composable
fun PeakBudRoot(readings: DeviceReadings? = null) {
    val context = LocalContext.current
    val settings = remember(context) { AppSettings(context.applicationContext) }
    val appearance = remember(settings) {
        settings.preferences.map<Preferences, Preferences?> { it }.catch { emit(Preferences()) }
    }
    val preferences by appearance.collectAsStateWithLifecycle(initialValue = null)
    val saved = preferences ?: return
    PeakBudTheme(themeMode = saved.themeMode) { PeakBudApp(readings = readings) }
}

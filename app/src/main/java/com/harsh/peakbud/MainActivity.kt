package com.harsh.peakbud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.harsh.peakbud.data.settings.AppSettings
import com.harsh.peakbud.data.settings.Preferences
import com.harsh.peakbud.device.DeviceReadings
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.harsh.peakbud.feature.panorama.PeakBudApp
import com.harsh.peakbud.ui.theme.PeakBudTheme
import kotlinx.coroutines.delay
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
        setContent {
            // Shown once per launch, not again on rotation.
            var showCredits by rememberSaveable { mutableStateOf(savedInstanceState == null) }
            LaunchedEffect(Unit) { delay(1_200); showCredits = false }
            Box {
                PeakBudRoot()
                AnimatedVisibility(showCredits, exit = fadeOut()) { SplashCredits() }
            }
        }
    }
}

/**
 * Continues the system splash (same background and icon, which the system draws at 240dp) and adds the version and
 * credit line, which the platform splash cannot show.
 */
@Composable
private fun SplashCredits() {
    Box(Modifier.fillMaxSize().background(colorResource(R.color.peakbud_forest)).systemBarsPadding()) {
        Image(painterResource(R.drawable.ic_splash), null, Modifier.size(240.dp).align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.splash_made_by), color = Color.White, fontSize = 14.sp, textAlign = TextAlign.End)
            Text(stringResource(R.string.splash_version, BuildConfig.VERSION_NAME), color = Color.White.copy(alpha = .7f), fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
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

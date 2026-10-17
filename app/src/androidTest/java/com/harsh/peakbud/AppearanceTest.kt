package com.harsh.peakbud

import android.graphics.Bitmap
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.harsh.peakbud.data.settings.*
import com.harsh.peakbud.device.*
import com.harsh.peakbud.feature.panorama.*
import com.harsh.peakbud.ui.text.TrailStrings
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.rules.ExternalResource

/** Preserve real downloaded regions/settings; screenshots are kept in the app cache for visual review. */
class AppearanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var original: Preferences
    private var originalRegion: String? = null
    @get:Rule(order = 0) val setup = object : ExternalResource() {
        override fun before() {
            val context = instrumentation.targetContext
            runBlocking {
                val settings = AppSettings(context)
                original = settings.preferences.first()
                originalRegion = settings.selectedRegion.first()
                settings.update(Preferences(demoMode = true, shareDiagnostics = false))
                settings.selectRegion(null)
            }
            listOf("ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION", "CAMERA").forEach {
                instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.$it").use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { stream -> stream.readBytes() }
                }
            }
        }
        override fun after() = runBlocking {
            AppSettings(instrumentation.targetContext).apply { update(original); selectRegion(originalRegion) }
            Unit
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Before fun stableReadings() = installReadings()
    private fun installReadings() {
        val readings = object : DeviceReadings {
            override fun poses(displayRotation: Int) = emptyFlow<DevicePose>()
            override fun fixes() = emptyFlow<LocationStatus>()
        }
        instrumentation.runOnMainSync { compose.activity.setContent { PeakBudRoot(readings) } }
    }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun tab(id: Int) = compose.onNodeWithContentDescription(text(id)).performClick()
    private fun settings() = compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
    private fun choose(mode: Int) = compose.onNodeWithText(text(mode)).performScrollTo().performClick()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.cacheDir, "design-qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun saved() = runBlocking { AppSettings(instrumentation.targetContext).preferences.first() }

    @Test fun appearanceAppliesAcrossScreensAndSurvivesRecreation() {
        settings()
        choose(R.string.theme_dark)
        compose.onNodeWithText(text(R.string.theme_dark)).assertIsSelected()
        compose.waitUntil(5000) { saved().themeMode == ThemeMode.DARK }
        screenshot("dark-settings")
        Espresso.pressBack()
        compose.waitUntil(15000) { compose.onAllNodesWithText(text(R.string.tracing_the_ridgelines)).fetchSemanticsNodes().isEmpty() }
        screenshot("dark-home")
        tab(R.string.peaks); screenshot("dark-peaks")
        tab(R.string.regions); screenshot("dark-regions")
        tab(R.string.camera); screenshot("dark-camera")
        tab(R.string.home)
        compose.activityRule.scenario.recreate()
        installReadings()
        settings()
        compose.onNodeWithText(text(R.string.theme_dark)).assertIsSelected()
        choose(R.string.theme_light)
        compose.onNodeWithText(text(R.string.theme_light)).assertIsSelected()
        compose.waitUntil(5000) { saved().themeMode == ThemeMode.LIGHT }
        screenshot("light-settings")
        Espresso.pressBack()
        screenshot("light-home")
        tab(R.string.peaks); screenshot("light-peaks")
        tab(R.string.regions); screenshot("light-regions")
        tab(R.string.home)
        settings()
        choose(R.string.theme_system)
        compose.onNodeWithText(text(R.string.theme_system)).assertIsSelected()
        compose.waitUntil(5000) { saved().themeMode == ThemeMode.SYSTEM }
    }

    @Test fun formattedUnitsUseResourceLocaleAndSelectedMeasurementSystem() {
        val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply { setLocale(Locale.FRANCE) }
        val resources = instrumentation.targetContext.createConfigurationContext(configuration).resources
        val strings = TrailStrings(resources)
        val metric = PanoramaState(preferences = Preferences(useFeet = false))
        val imperial = metric.copy(preferences = metric.preferences.copy(useFeet = true))
        assertEquals("1,5 km", distance(1500.0, metric, strings))
        assertEquals("1,0 mi", distance(1609.344, imperial, strings))
        assertEquals("1000 m", height(1000.0, metric, strings))
        assertEquals("3280 ft", height(1000.0, imperial, strings))
        assertEquals("+12°", signedDegrees(12.0, strings))
    }
}

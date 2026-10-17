package com.harsh.peakbud

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import androidx.activity.compose.setContent
import com.harsh.peakbud.device.*
import com.harsh.peakbud.feature.panorama.PeakBudApp
import com.harsh.peakbud.ui.theme.PeakBudTheme
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import org.junit.rules.ExternalResource
import org.junit.Rule
import org.junit.Test

/** Run on a clean emulator; this deliberately does not require network or sensors. */
class PanoramaNavigationTest {
    @get:Rule(order = 0) val locationPermission = object : ExternalResource() {
        override fun before() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val name = instrumentation.targetContext.packageName
            listOf("android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION").forEach { permission ->
                instrumentation.uiAutomation.executeShellCommand("pm grant $name $permission").use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                }
            }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Before fun stableSensorReadings() {
        val readings = object : DeviceReadings {
            override fun poses(displayRotation: Int) = flowOf(DevicePose(90.0, 0.0, true, source = "Test magnetometer"))
            override fun fixes() = emptyFlow<LocationStatus>()
        }
        // These tests cover navigation, not the first-run tutorial: mark it seen, and skip it if it already opened.
        kotlinx.coroutines.runBlocking { com.harsh.peakbud.data.settings.AppSettings(compose.activity).markTutorialSeen() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent { PeakBudTheme { PeakBudApp(readings = readings) } }
        }
        compose.waitForIdle()
        val skip = compose.activity.getString(R.string.tutorial_skip)
        if (compose.onAllNodes(androidx.compose.ui.test.hasText(skip)).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText(skip).performClick()
    }
    private fun tab(id: Int) { compose.onNodeWithContentDescription(compose.activity.getString(id)).performClick() }

    @Test fun homeIsDefaultAndCameraRemainsAvailable() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.home)).assertIsDisplayed()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.choose_spot)).assertIsDisplayed()
        tab(R.string.camera)
        compose.onNodeWithContentDescription("Full screen").assertIsDisplayed()
        tab(R.string.home)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.choose_spot)).assertIsDisplayed()
    }

    @Test fun manualViewpointAndCuratedRegionEntryPointsRemainAvailable() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.choose_spot)).performClick()
        compose.onNodeWithText("Choose a spot").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        tab(R.string.regions)
        compose.onNodeWithText("Search places, countries or ranges").assertIsDisplayed()
        // Search the current small-area catalogue; broad legacy packs are shown only when installed.
        for (name in listOf("Mussoorie · Lal Tibba", "Parasnath · Shikharji", "Poon Hill · Annapurna")) {
            compose.onNode(hasSetTextAction()).performTextReplacement(name)
            Espresso.closeSoftKeyboard()
            compose.onNode(hasText(name) and !hasSetTextAction()).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Import region ZIP").assertDoesNotExist()
        compose.onNodeWithText("Download from URL").assertDoesNotExist()
        Espresso.pressBack()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.choose_spot)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").assertIsDisplayed()
    }
}

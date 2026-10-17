package com.harsh.peakbud

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** Run on a clean emulator; this deliberately does not require network or sensors. */
class PanoramaNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun cameraIsDefaultAndPanoramaRemainsAvailable() {
        compose.onNodeWithText("Live camera").assertIsDisplayed()
        compose.onNodeWithText("Align labels").assertIsDisplayed()
        compose.onNodeWithText("Panorama").performClick()
        compose.onNodeWithText("Viewpoint").assertIsDisplayed()
        compose.onNodeWithText("Live camera").performClick()
        compose.onNodeWithText("Align labels").assertIsDisplayed()
    }

    @Test fun manualDemoAndOfflineRegionEntryPointsRemainAvailable() {
        compose.onNodeWithText("Find your horizon.").assertIsDisplayed()
        compose.onNodeWithText("Peaks").performClick()
        compose.onNodeWithText("Know your mountains.").assertIsDisplayed()
        compose.onNodeWithText("Regions").performClick()
        compose.onNodeWithText("Import region ZIP").assertIsDisplayed()
        compose.onNodeWithText("Download from URL").assertIsDisplayed()
        compose.onNodeWithText("Explore demo").performClick()
        compose.onNodeWithText("Viewpoint").performClick()
        compose.onNodeWithText("Choose your viewpoint").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Trail settings").assertIsDisplayed()
    }
}

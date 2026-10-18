package com.harsh.peakbud

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.harsh.peakbud.core.model.GeoPoint
import com.harsh.peakbud.data.regions.DownloadArea
import com.harsh.peakbud.device.AreaPlace
import com.harsh.peakbud.feature.panorama.DownloadAreaDialog
import com.harsh.peakbud.ui.theme.PeakBudTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DownloadAreaTest {
    @get:Rule val compose = createComposeRule()
    @Test fun searchSelectPreviewAndDownloadDoesNotNeedABundledArea() {
        var requested: DownloadArea? = null
        compose.setContent {
            PeakBudTheme {
                DownloadAreaDialog({}, { requested = it }, null) { query ->
                    assertEquals("Outback", query)
                    listOf(AreaPlace("Outback camp", "Australia", GeoPoint(-20.5, 140.5)))
                }
            }
        }
        compose.onNodeWithText("Download and use").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Outback")
        compose.onNodeWithText("Search places").performClick()
        compose.onNodeWithText("Outback camp").performScrollTo().performClick()
        compose.onNodeWithText("Download and use").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(GeoPoint(-20.5, 140.5), requested!!.point) }
    }
    @Test fun unsupportedSearchResultCannotStartADownload() {
        compose.setContent {
            PeakBudTheme {
                DownloadAreaDialog({}, { fail("Unsupported area must not download") }, null) {
                    listOf(AreaPlace("Polar camp", "", GeoPoint(75.0, 10.0)))
                }
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("Polar camp")
        compose.onNodeWithText("Search places").performClick()
        compose.onNode(hasText("Polar camp") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Download and use").assertIsNotEnabled()
    }
}

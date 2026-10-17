package com.harsh.peakbud.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignContrastTest {
    private fun readable(name: String, foreground: Color, background: Color) {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
        assertTrue("$name text contrast $contrast is below 4.5:1", contrast >= 4.5f)
    }
    @Test fun applicationContentPairsRemainReadableInBothThemes() {
        listOf(TrailLightColors, TrailDarkColors).forEach { c ->
            readable("background", c.onBackground, c.background)
            readable("surface", c.onSurface, c.surface)
            readable("supporting text", c.onSurfaceVariant, c.surfaceContainerHigh)
            readable("primary action", c.onPrimary, c.primary)
            readable("tonal action", c.onPrimaryContainer, c.primaryContainer)
            readable("warning", c.onTertiaryContainer, c.tertiaryContainer)
            readable("error", c.onErrorContainer, c.errorContainer)
        }
    }
    @Test fun panoramaTextRemainsReadableInBothPalettes() {
        listOf(TrailDayScene, TrailNightScene).forEach { c ->
            readable("peak name and footer", c.labelText, c.labelSurface)
            readable("peak detail", c.labelDetail, c.labelSurface)
            listOf(c.skyTop, c.skyMiddle, c.skyBottom).forEach { readable("sky axis", c.axis, it) }
        }
    }
    @Test fun cameraBackingProtectsTextOverSnowAndRock() {
        listOf(Color.White, Color.Black).forEach { image ->
            readable("camera controls", TrailCameraColors.content, TrailCameraColors.scrim.compositeOver(image))
            readable("camera peak details", Color.White.copy(alpha = 220f / 255), TrailCameraColors.labelSurface.compositeOver(image))
        }
        readable("compass warning", TrailCameraColors.content, TrailCameraColors.warningSurface)
    }
}

package com.harsh.peakbud.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.harsh.peakbud.R
import com.harsh.peakbud.core.projection.*
import com.harsh.peakbud.ui.text.rememberTrailStrings
import com.harsh.peakbud.ui.theme.*

/** Requires orientation and preview calibration, but no downloaded terrain or GPS fix. */
@Composable
internal fun CameraHorizon(pose: CameraPose, lens: CameraLens, modifier: Modifier) {
    val strings = rememberTrailStrings()
    val type = peakLabelTypography()
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val line = CameraProjector.horizon(pose, lens, viewport.width, viewport.height)
    Canvas(modifier.clipToBounds().onSizeChanged { viewport = it }.then(if (line == null) Modifier else Modifier.semantics {
        contentDescription = strings(R.string.level_horizon_description)
    })) {
        line?.let { drawLevelHorizon(it, TrailCameraColors.content, TrailCameraColors.scrim, strings(R.string.level_horizon), type.axisPx) }
    }
}

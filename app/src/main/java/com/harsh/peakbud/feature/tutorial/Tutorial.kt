package com.harsh.peakbud.feature.tutorial

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.harsh.peakbud.R
import com.harsh.peakbud.core.alignment.TerrainSkyline
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.terrain.TerrainEngine
import com.harsh.peakbud.data.settings.Preferences
import com.harsh.peakbud.data.terrain.DemoTerrain
import com.harsh.peakbud.feature.panorama.PanoramaCanvas
import com.harsh.peakbud.feature.panorama.PanoramaState
import com.harsh.peakbud.feature.panorama.distance
import com.harsh.peakbud.feature.panorama.height
import com.harsh.peakbud.ui.text.rememberTrailStrings
import com.harsh.peakbud.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Walkthrough opened from Settings. Every illustration is demo material: a rendered "camera" scene and the real skyline
 * renderer fed by the built-in fictional terrain, so nothing depends on the phone's camera, location or downloads.
 */
@Composable
fun TutorialScreen(preferences: Preferences, onClose: () -> Unit) {
    val strings = rememberTrailStrings()
    val scene = rememberDemoScene()
    val pages = listOf(
        R.string.tutorial_camera_title to R.string.tutorial_camera_body,
        R.string.tutorial_align_title to R.string.tutorial_align_body,
        R.string.tutorial_home_title to R.string.tutorial_home_body,
        R.string.tutorial_peaks_title to R.string.tutorial_peaks_body,
        R.string.tutorial_regions_title to R.string.tutorial_regions_body,
        R.string.tutorial_ready_title to R.string.tutorial_ready_body,
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = TrailSpace.lg, vertical = TrailSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(strings(R.string.tutorial), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(strings(R.string.tutorial_skip)) }
                }
                HorizontalPager(pager, Modifier.weight(1f)) { page ->
                    Column(Modifier.fillMaxSize().padding(horizontal = TrailSpace.lg), verticalArrangement = Arrangement.spacedBy(TrailSpace.lg)) {
                        Box(Modifier.fillMaxWidth().weight(1f).clip(MaterialTheme.shapes.large)) {
                            when (page) {
                                0 -> DemoCamera(scene, Modifier.fillMaxSize(), mode = CameraDemo.LABELS)
                                1 -> DemoCamera(scene, Modifier.fillMaxSize(), mode = CameraDemo.ALIGN)
                                2 -> DemoHome(scene, preferences, Modifier.fillMaxSize())
                                3 -> DemoPeaks(scene, preferences, Modifier.fillMaxSize())
                                4 -> DemoRegions(Modifier.fillMaxSize())
                                else -> DemoReady(Modifier.fillMaxSize())
                            }
                        }
                        Text(strings(pages[page].first), style = MaterialTheme.typography.headlineSmall)
                        Text(strings(pages[page].second), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = TrailSpace.sm))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(TrailSpace.lg), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(TrailSize.sidePanel / 3)) {
                        if (pager.currentPage > 0) TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text(strings(R.string.tutorial_back)) }
                    }
                    Row(Modifier.weight(1f).semantics { contentDescription = strings(R.string.tutorial_step, pager.currentPage + 1, pages.size) },
                        horizontalArrangement = Arrangement.Center) {
                        repeat(pages.size) { i ->
                            val active = i == pager.currentPage
                            Box(Modifier.padding(horizontal = TrailSpace.xxs).size(if (active) TrailSpace.lg else TrailSpace.sm, TrailSpace.sm).clip(CircleShape)
                                .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                    Box(Modifier.width(TrailSize.sidePanel / 3), contentAlignment = Alignment.CenterEnd) {
                        val last = pager.currentPage == pages.lastIndex
                        Button(onClick = { if (last) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) {
                            Text(strings(if (last) R.string.tutorial_done else R.string.tutorial_next))
                        }
                    }
                }
            }
        }
    }
}

/** The fictional demo landscape, calculated by the real terrain engine once per tutorial. */
@Composable
private fun rememberDemoScene(): MountainScene? {
    var scene by remember { mutableStateOf<MountainScene?>(null) }
    LaunchedEffect(Unit) {
        scene = withContext(Dispatchers.Default) {
            val terrain = DemoTerrain()
            val ground = (terrain.sample(DemoTerrain.VIEWPOINT) as TerrainSample.Valid).heightM
            TerrainEngine().calculate(Observer(DemoTerrain.VIEWPOINT, ground, datum = VerticalDatum.SYNTHETIC), terrain, DemoTerrain.peaks,
                CalculationSettings(50_000.0, .5))
        }
    }
    return scene
}

internal enum class CameraDemo { LABELS, ALIGN }

/**
 * Rendered stand-in for camera footage: sky, distant range, nearer ridge and haze drawn from the demo skyline, with a
 * gentle hand-held sway. ALIGN mode loops the alignment story: labels start a few degrees off (compass estimate), the
 * skyline is traced, then the labels settle onto the summits with green rings.
 */
/**
 * [storyProgress] drives the alignment story from outside (0..1, held at 1 once aligned) for the Camera tab in Demo
 * mode; null loops it, as in the tutorial. [showPill] draws the tutorial's own status pill.
 */
@Composable
internal fun DemoCamera(scene: MountainScene?, modifier: Modifier, mode: CameraDemo, storyProgress: Float? = null, showPill: Boolean = true) {
    val strings = rememberTrailStrings()
    val sway = rememberInfiniteTransition(label = "sway")
    val swing by sway.animateFloat(-4f, 4f, infiniteRepeatable(tween(7_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "heading")
    val looping by sway.animateFloat(0f, 1f, infiniteRepeatable(tween(7_500, easing = LinearEasing)), label = "story")
    val story = storyProgress ?: looping
    val labelType = peakLabelTypography()
    if (scene == null) { Box(modifier.background(TrailCameraColors.labelSurface)); return }
    val skyline = remember(scene) { TerrainSkyline(scene.horizon) }
    val visible = remember(scene) { scene.peaks.filter { it.visibility != Visibility.OCCLUDED && it.elevationDeg != null } }
    val centre = remember(scene) { visible.maxByOrNull { it.peak.publishedHeightM ?: 0.0 }?.bearingDeg ?: 0.0 }
    // Alignment story phases: compass estimate, tracing the skyline, settling, aligned.
    val aligning = mode == CameraDemo.ALIGN
    val offset = if (!aligning) 0.0 else when {
        story < .3f -> 5.0
        story < .55f -> 5.0
        story < .7f -> 5.0 * (1 - (story - .55f) / .15f)
        else -> 0.0
    }
    val scan = if (aligning && story >= .3f && story < .55f) (story - .3f) / .25f else null
    val aligned = aligning && story >= .7f
    Box(modifier) {
        Canvas(modifier.fillMaxSize().semantics { contentDescription = strings(R.string.tutorial_demo_footage_description) }) {
            val w = size.width; val h = size.height
            if (w <= 0 || h <= 0) return@Canvas
            // A telephoto-like view and a horizon below centre make the range fill the frame like a real photo.
            val hfov = 56.0; val vfov = hfov * h / w; val pitch = 2.5
            val heading = centre + swing
            fun x(bearing: Double) = (w / 2 + Geography.wrap180(bearing - heading) / hfov * w).toFloat()
            fun y(elevation: Double) = (h * .62 - (elevation - pitch) / vfov * h).toFloat()
            drawRect(Brush.verticalGradient(listOf(Color(0xFF5E9FD6), Color(0xFFB9D7EC), Color(0xFFE6EEF0))))
            fun ridge(scale: Double, lift: Double, parallax: Double, top: Color, bottom: Color) {
                val path = Path(); var started = false
                var px = 0f
                while (px <= w + 4) {
                    val bearing = heading + (px / w - .5) * hfov * parallax
                    val e = (skyline.elevation(Geography.wrap360(bearing)) ?: 0.0) * scale + lift
                    if (!started) { path.moveTo(px, y(e)); started = true } else path.lineTo(px, y(e))
                    px += 3f
                }
                path.lineTo(w, h); path.lineTo(0f, h); path.close()
                drawPath(path, Brush.verticalGradient(listOf(top, bottom), startY = h * .25f, endY = h))
            }
            ridge(1.0, 0.0, 1.0, Color(0xFF8FA4B8), Color(0xFF55697A))       // distant range
            ridge(.45, -2.6, 1.6, Color(0xFF4F6B4E), Color(0xFF243A2B))      // nearer forested ridge, moves faster
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .10f), Color.Transparent), startY = h * .4f, endY = h * .62f))
            val horizonY = y(0.0)
            if (horizonY in 0f..h) drawLevelHorizon(
                com.harsh.peakbud.core.projection.ScreenLine(com.harsh.peakbud.core.projection.ScreenPoint(0f, horizonY),
                    com.harsh.peakbud.core.projection.ScreenPoint(w, horizonY)), Color.White, TrailCameraColors.scrim,
                strings(R.string.level_horizon), labelType.axisPx)
            scan?.let { f ->
                var px = 0f
                while (px <= w * f) {
                    val bearing = heading + (px / w - .5) * hfov
                    skyline.elevation(Geography.wrap360(bearing))?.let { drawCircle(TrailCameraColors.uncertain, 2.2f * density, Offset(px, y(it))) }
                    px += 5f
                }
            }
            // Same still pins as the real camera.
            val style = PinStyle(stick = Color.White, dot = Color.White, nameSurface = Color.White, nameText = Color(0xFF1B2B25),
                heightSurface = TrailCameraColors.pinHeight, heightText = Color.White, ring = if (aligned) TrailCameraColors.alignedRing else null)
            val candidates = visible.sortedByDescending { it.peak.publishedHeightM ?: 0.0 }
                .map { PinCandidate(it, x(it.bearingDeg + offset), y(it.elevationDeg!!), it.peak.name, it.peak.publishedHeightM?.let { h -> "${h.toInt()} m" }) }
                .filter { it.x in 0f..w }
            val textSize = labelType.detailPx * 1.05f; val pinTop = TrailSpace.xxl.toPx() * 2
            for (pin in PeakPins.place(this, candidates, textSize, pinTop)) {
                PeakPins.draw(this, pin.x, pin.y, pin.name, pin.height, style, textSize, pinTop)
            }
        }
        if (showPill) DemoBadge(Modifier.align(Alignment.TopStart).padding(TrailSpace.md))
        if (aligning && showPill) Surface(Modifier.align(Alignment.BottomCenter).padding(TrailSpace.lg), shape = CircleShape,
            color = TrailCameraColors.scrim, contentColor = Color.White) {
            Row(Modifier.padding(horizontal = TrailSpace.lg, vertical = TrailSpace.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                when {
                    aligned -> { Box(Modifier.size(TrailSpace.sm).clip(CircleShape).background(TrailCameraColors.alignedRing)); Text(strings(R.string.aligned_with_camera), style = MaterialTheme.typography.labelLarge) }
                    scan != null -> { CircularProgressIndicator(Modifier.size(TrailSize.iconSmall), color = Color.White, strokeWidth = TrailStroke.selected); Text(strings(R.string.aligning), style = MaterialTheme.typography.labelLarge) }
                    else -> Text(strings(R.string.align_with_camera), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun DemoBadge(modifier: Modifier) {
    val strings = rememberTrailStrings()
    Surface(modifier, shape = CircleShape, color = TrailCameraColors.scrim, contentColor = Color.White) {
        Text(strings(R.string.tutorial_demo_footage), Modifier.padding(horizontal = TrailSpace.md, vertical = TrailSpace.xs), style = MaterialTheme.typography.labelMedium)
    }
}

/** The real Home skyline renderer showing the demo landscape, slowly panning. */
@Composable
private fun DemoHome(scene: MountainScene?, preferences: Preferences, modifier: Modifier) {
    val pan = rememberInfiniteTransition(label = "pan")
    val heading by pan.animateFloat(300f, 420f, infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse), label = "heading")
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        if (scene != null) PanoramaCanvas(PanoramaState(scene = scene, initialized = true, heading = Geography.wrap360(heading.toDouble()), fov = 90.0,
            preferences = preferences.copy(demoMode = true)), Modifier.fillMaxSize(), onPan = {}, onPeak = {})
        // The canvas caption already reads "Demo landscape"; a badge here would cover the compass scale.
    }
}

/** Peaks as the Peaks tab lists them, from the demo landscape. */
@Composable
private fun DemoPeaks(scene: MountainScene?, preferences: Preferences, modifier: Modifier) {
    val strings = rememberTrailStrings()
    val state = PanoramaState(preferences = preferences)
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(TrailSpace.md), verticalArrangement = Arrangement.spacedBy(TrailSpace.xxs)) {
        scene?.peaks?.sortedWith(compareBy<PeakView> { it.visibility.ordinal }.thenBy { it.distanceM })?.take(5)?.forEach { peak ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = TrailSpace.lg, vertical = TrailSpace.md), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(peak.peak.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull(peak.peak.publishedHeightM?.let { height(it, state, strings) }, distance(peak.distanceM, state, strings)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val (label, tone) = when (peak.visibility) {
                        Visibility.TERRAIN_VISIBLE -> strings(R.string.in_view) to MaterialTheme.colorScheme.primary
                        Visibility.UNCERTAIN -> strings(R.string.maybe) to MaterialTheme.colorScheme.tertiary
                        else -> strings(R.string.hidden) to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(shape = CircleShape, color = tone.copy(alpha = .12f), contentColor = tone) {
                        Text(label, Modifier.padding(horizontal = TrailSpace.sm, vertical = TrailSpace.xxs), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** An example of the Regions tab: a small download around you, and areas listed closest first. */
@Composable
private fun DemoRegions(modifier: Modifier) {
    val strings = rememberTrailStrings()
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(TrailSpace.md), verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
        Text(strings(R.string.near_you).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DemoRegionCard(strings(R.string.tutorial_example_nearby), strings(R.string.tutorial_example_nearby_detail), highlighted = true)
        Text(strings(R.string.closest_first).uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DemoRegionCard(strings(R.string.tutorial_example_area), strings(R.string.tutorial_example_area_detail), highlighted = false)
    }
}

@Composable
private fun DemoRegionCard(title: String, detail: String, highlighted: Boolean) {
    val strings = rememberTrailStrings()
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(if (highlighted) TrailStroke.selected else TrailStroke.thin,
            if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(TrailSpace.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TrailSpace.xxs)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (highlighted) Button(onClick = {}) { Text(strings(R.string.download_action)) }
            else FilledTonalIconButton(onClick = {}) { Icon(painterResource(R.drawable.ic_download), null) }
        }
    }
}

@Composable
private fun DemoReady(modifier: Modifier) {
    val strings = rememberTrailStrings()
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(TrailSpace.xl), verticalArrangement = Arrangement.spacedBy(TrailSpace.lg, Alignment.CenterVertically)) {
        listOf(R.drawable.ic_photo_camera to R.string.tutorial_tip_nudge, R.drawable.ic_landscape to R.string.tutorial_tip_tap,
            R.drawable.ic_settings to R.string.tutorial_tip_demo).forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrailSpace.md)) {
                Box(Modifier.size(TrailSize.touchTarget).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(strings(text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Start)
            }
        }
    }
}

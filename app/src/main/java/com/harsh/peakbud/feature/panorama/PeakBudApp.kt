package com.harsh.peakbud.feature.panorama

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.harsh.peakbud.R
import com.harsh.peakbud.telemetry.Telemetry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harsh.peakbud.feature.camera.CameraPanel
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.data.regions.RegionPack
import com.harsh.peakbud.data.regions.CatalogRegion
import com.harsh.peakbud.device.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun PeakBudApp(model: PanoramaViewModel = viewModel(), readings: DeviceReadings? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp > configuration.screenHeightDp
    var tab by rememberSaveable { mutableIntStateOf(1) }  // open on Home
    // Tabs: 0 Camera (always full screen), 1 Home (landing: the drawn skyline), 2 Peaks, 3 Regions.
    val cameraTab = tab == 0
    // Tapping the empty camera view hides every control; leaving the tab or pressing back brings them back.
    var immersive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tab) {
        immersive = false
        Telemetry.screen(listOf("camera", "home", "peaks", "regions")[tab])
    }
    BackHandler(enabled = immersive) { immersive = false }
    BackHandler(enabled = !immersive && tab != 1) { tab = 1 }
    var cameraFov by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var askedLocation by rememberSaveable { mutableStateOf(false) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var selectedPeak by remember { mutableStateOf<PeakView?>(null) }
    var attribution by remember { mutableStateOf<String?>(null) }
    var deletePack by remember { mutableStateOf<RegionPack?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val device = remember(context, readings) { readings ?: AndroidDeviceReadings(context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) model.startGps()
        else model.locationDenied()
    }
    LaunchedEffect(state.initialized) {
        if (!state.initialized) return@LaunchedEffect
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.startGps()
        else if (!askedLocation) {
            askedLocation = true
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    @Suppress("DEPRECATION")
    val rotation = if (Build.VERSION.SDK_INT >= 30) context.display?.rotation ?: 0
        else (context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    LaunchedEffect(state.followingGps, lifecycle, tab, device) {
        if (state.followingGps && tab <= 1) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.refreshFixAge()
            launch { device.fixes().collect(model::gps) }
            launch { while (true) { delay(5000); model.refreshFixAge() } }
        }
    }
    LaunchedEffect(state.followingCompass, rotation, lifecycle, tab, device) {
        if (state.followingCompass && tab <= 1) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            device.poses(rotation).collect(model::pose)
        }
    }
    LaunchedEffect(tab, state.activePack?.key, state.initialized) {
        if (cameraTab && state.initialized) {
            model.startCameraTracking()
            if (!state.followingGps && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.startGps()
        }
    }
    LaunchedEffect(state.scene) { selectedPeak = null }
    LaunchedEffect(selectedPeak?.peak?.id) { selectedPeak?.let { Telemetry.event("peak_open", "visibility" to it.visibility.name.lowercase(), "from" to listOf("camera", "home", "peaks", "regions")[tab]) } }
    fun gps() {
        if (state.followingGps) { model.stopGps(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else model.startGps()
    }
    // Light status-bar icons over the camera, dark ones over the paper background.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !cameraTab
        }
    }
    Box(Modifier.fillMaxSize().background(if (cameraTab) Color.Black else MaterialTheme.colorScheme.background)) {
        // Space the floating tab bar occupies above the system navigation bar.
        val barSpace = 84.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (cameraTab) CameraTab(state, model, cameraFov, barSpace, immersive, onImmersive = { immersive = it }, onFov = { cameraFov = it },
            onRegions = { tab = 3 }, onGps = ::gps, onSettings = { settings = true }, onPeak = { selectedPeak = it })
        else Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(bottom = if (tab == 1) barSpace else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
            AppHeader(compact, when (tab) {
                2 -> if (state.noRegion) "Peaks · pick a hiking area to start" else if (state.isDemo) "Peaks · demo landscape" else "Peaks within ${(state.activePack?.radiusM ?: 0.0).toInt() / 1000} km of this spot"
                3 -> "Hiking areas · download once, works offline"
                else -> state.activePack?.name ?: if (state.isDemo) "Demo · made-up landscape" else "Pick a hiking area to start"
            }) { settings = true }
            state.message?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::clearMessage) { Text("Dismiss") }
                    }
                }
            }
            when (tab) {
                1 -> {
                    if (!compact) ObservationStatus(state, false, null, false, ::gps, model::startCameraTracking)
                    BoxWithConstraints(Modifier.weight(1f)) {
                        if (maxWidth > maxHeight) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                PanoramaPanel(state, model, Modifier.weight(1f).fillMaxHeight(), { tab = 3 }) { selectedPeak = it }
                                Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (compact) ObservationStatus(state, false, null, true, ::gps, model::startCameraTracking)
                                    ViewControls(state, model, { manual = true }, ::gps)
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PanoramaPanel(state, model, Modifier.fillMaxWidth().weight(1f), { tab = 3 }) { selectedPeak = it }
                                ViewControls(state, model, { manual = true }, ::gps)
                            }
                        }
                    }
                }
                2 -> PeakList(state, Modifier.weight(1f), barSpace) { selectedPeak = it }
                3 -> Regions(state, Modifier.weight(1f), barSpace, model::downloadRegion,
                    { model.selectRegion(it); tab = 1 }, { deletePack = it }, { pack -> scope.launch { attribution = runCatching { model.attribution(pack) }.getOrDefault("Credits couldn't be loaded.") } }, model::cancelImport,
                    model::discardDownload)
            }
        }
        AnimatedVisibility(!(cameraTab && immersive), Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 }) {
            FloatingTabBar(tab, cameraTab, { tab = it }, Modifier.navigationBarsPadding().padding(bottom = 12.dp))
        }
    }
    if (manual) ViewpointDialog(state, { manual = false }) { point, ground -> model.manualViewpoint(point, ground); manual = false }
    if (settings) SettingsDialog(state, model) { settings = false }
    selectedPeak?.let { peak ->
        AlertDialog(onDismissRequest = { selectedPeak = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(peak.peak.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(peak.peak.publishedHeightM?.let { height(it, state) } ?: "Height unknown", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOfNotNull("${distance(peak.distanceM, state)} away", "${peak.bearingDeg.toInt()}° ${cardinal(peak.bearingDeg)}",
                        peak.elevationDeg?.let { angleText(it) }).forEach {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(it, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                StatusPill(visibilityName(peak.visibility), when (peak.visibility) {
                    Visibility.TERRAIN_VISIBLE -> MaterialTheme.colorScheme.primary; Visibility.UNCERTAIN -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant })
                Text(peak.reason, style = MaterialTheme.typography.bodyMedium)
                Text("${coordinate(peak.peak.point.latitude)}, ${coordinate(peak.peak.point.longitude)}\nSource: ${peak.peak.source}", style = MaterialTheme.typography.bodySmall)
                peak.geometryHeightM?.let { Text("The map puts the ground here at ${height(it, state)}. The official summit height may differ.", style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = { model.pan(Geography.wrap180(peak.bearingDeg - state.heading)); selectedPeak = null; tab = 1 }) { Text("Show on Home") } }, dismissButton = { TextButton(onClick = { selectedPeak = null }) { Text("Close") } })
    }
    attribution?.let { text -> AlertDialog(onDismissRequest = { attribution = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("Map and peak credits") }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { attribution = null }) { Text("Done") } }) }
    deletePack?.let { pack -> AlertDialog(onDismissRequest = { deletePack = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("Delete ${pack.name}?") }, text = { Text("Its maps and peak names will be removed from this phone. You can download it again any time.") }, confirmButton = { TextButton(onClick = { model.deleteRegion(pack); deletePack = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletePack = null }) { Text("Keep") } }) }
}

/** Shown once per launch so people learn that tapping the view hides the controls. */
private var hideHintShown = false

/**
 * Full-screen camera tab. The preview fills the display; controls float above it on translucent pills,
 * following AR conventions (PeakFinder, Star Walk, Apple HIG): status at the top, area and navigation at
 * the bottom, fine adjustment behind the heading chip and by dragging the view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraTab(state: PanoramaState, model: PanoramaViewModel, cameraFov: Pair<Double, Double>?, barSpace: Dp, immersive: Boolean,
                      onImmersive: (Boolean) -> Unit, onFov: (Pair<Double, Double>?) -> Unit, onRegions: () -> Unit, onGps: () -> Unit,
                      onSettings: () -> Unit, onPeak: (PeakView) -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var sheet by rememberSaveable { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(!hideHintShown) }
    // Drag-to-nudge bookkeeping: where the gesture started (for Undo) and the last whole degree (for haptic ticks).
    var dragStart by remember { mutableStateOf<Double?>(null) }
    var shifted by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(showHint) { if (showHint) { kotlinx.coroutines.delay(5000); showHint = false; hideHintShown = true } }
    LaunchedEffect(shifted) { if (shifted != null) { kotlinx.coroutines.delay(4000); shifted = null } }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBar = statusTop + 72.dp
    val showControls = !immersive
    Box(Modifier.fillMaxSize()) {
        CameraPanel(state, Modifier.fillMaxSize(), onRegions, onGps, model::startCameraTracking, onFov, fullScreen = true,
            safeTop = if (showControls) topBar else statusTop, safeBottom = if (showControls) barSpace + 40.dp else 24.dp,
            onBackgroundTap = { onImmersive(!immersive); showHint = false; hideHintShown = true },
            onDragNudge = { degrees ->
                if (dragStart == null) dragStart = state.labelTrim
                val before = kotlin.math.floor(state.labelTrim)
                model.trimCompass(degrees)
                if (kotlin.math.floor(state.labelTrim + degrees) != before) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
            },
            onDragEnd = { dragStart?.let { shifted = it }; dragStart = null },
            onPeak = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick); onPeak(it) })
        AnimatedVisibility(showControls, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Gradient scrims keep white controls legible over snow and sky without boxing in the scene.
                Box(Modifier.fillMaxWidth().height(statusTop + 96.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = .55f), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(barSpace + 96.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f)))))
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        LocationChip(state, Modifier.align(Alignment.CenterStart)) { sheet = true }
                        HeadingChip(state, Modifier.align(Alignment.Center)) { sheet = true }
                        GlassIconButton(R.drawable.ic_settings, "Settings", Modifier.align(Alignment.CenterEnd), onSettings)
                    }
                    if (state.followingCompass && state.cameraPose != null && !state.poseReliable)
                        GlassPill(color = Color(0xE6A45D36)) { Text("Compass unsteady · wave your phone in a figure eight", style = MaterialTheme.typography.labelMedium) }
                    if (showHint) GlassPill { Text("Tap anywhere to hide controls", style = MaterialTheme.typography.labelMedium) }
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = barSpace + 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shifted?.let { start ->
                        GlassPill(Modifier.padding(start = 16.dp, end = 4.dp)) {
                            Text("Labels shifted ${signedDegrees(state.labelTrim)}", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { model.trimCompass(start - state.labelTrim); shifted = null }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Undo") }
                        }
                    }
                    Row(Modifier.clip(CircleShape).clickable(onClick = onRegions).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.activePack?.name ?: if (state.isDemo) "Demo landscape" else "Choose a hiking area", style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(18.dp), tint = Color.White.copy(alpha = .8f))
                    }
                }
            }
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.cameraPose?.heading?.let { "Pointing ${it.toInt().toString().padStart(3, '0')}° ${cardinal(it)}" } ?: "Compass", style = MaterialTheme.typography.titleLarge)
            Text(when {
                !state.followingCompass -> "The compass is off. Turn it on so labels follow where you point."
                !state.poseReliable -> state.compassStatus ?: "The compass is unsteady. Move away from metal and wave your phone in a figure eight."
                else -> "The compass is steady and labels follow where you point."
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel("Line up labels")
            SettingsGroup {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("If labels sit beside the real peaks, shift them here or drag the camera view sideways.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        FilledTonalIconButton(onClick = { model.trimCompass(-1.0); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) },
                            modifier = Modifier.size(48.dp)) { Text("‹ 1°", style = MaterialTheme.typography.labelLarge) }
                        Text(if (state.labelTrim == 0.0) "Not shifted" else signedDegrees(state.labelTrim), style = MaterialTheme.typography.titleMedium)
                        FilledTonalIconButton(onClick = { model.trimCompass(1.0); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) },
                            modifier = Modifier.size(48.dp)) { Text("1° ›", style = MaterialTheme.typography.labelLarge) }
                        TextButton(enabled = state.labelTrim != 0.0, onClick = { model.resetTrim(); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm) }) { Text("Reset") }
                    }
                }
            }
            SectionLabel("Your position")
            ObservationStatus(state, true, cameraFov, false, onGps, model::startCameraTracking, detailsOnly = true)
        }
    }
}

internal fun signedDegrees(value: Double) = "${if (value > 0) "+" else if (value < 0) "−" else ""}${String.format(Locale.US, "%.0f", kotlin.math.abs(value))}°"

/** Top-left: location accuracy at a glance. */
@Composable
private fun LocationChip(state: PanoramaState, modifier: Modifier, onClick: () -> Unit) {
    val text = when {
        !state.followingGps -> "Off"
        state.gpsFresh && state.gpsFix != null -> "${state.gpsFix!!.accuracyM.toInt()} m"
        else -> "Finding…"
    }
    Surface(onClick = onClick, modifier = modifier.height(40.dp).semantics { contentDescription = "Location: ${state.gpsStatus ?: text}. Show details" },
        shape = CircleShape, color = Color.Black.copy(alpha = .55f), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.followingGps && !state.gpsFresh) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(painterResource(R.drawable.ic_my_location), null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Top-centre: heading with a status dot (green steady, amber unsteady, white ring when labels are shifted). */
@Composable
private fun HeadingChip(state: PanoramaState, modifier: Modifier, onClick: () -> Unit) {
    val heading = state.cameraPose?.heading
    val dot = when { !state.followingCompass || heading == null -> Color.White.copy(alpha = .5f); !state.poseReliable -> Color(0xFFF2A65A); else -> Color(0xFF6FD39B) }
    Surface(onClick = onClick, modifier = modifier.height(40.dp).semantics { contentDescription = "Heading ${heading?.toInt() ?: "unknown"} degrees. Compass and label settings" },
        shape = CircleShape, color = Color.Black.copy(alpha = .55f), contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot)
                .then(if (state.labelTrim != 0.0) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier))
            Text(heading?.let { "${it.toInt().toString().padStart(3, '0')}° ${cardinal(it)}" } ?: "Pointing…", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun GlassIconButton(icon: Int, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Color.Black.copy(alpha = .55f), contentColor = Color.White, modifier = modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = description, Modifier.size(20.dp)) }
    }
}

@Composable
private fun GlassPill(modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = .55f), content: @Composable RowScope.() -> Unit) {
    Surface(shape = CircleShape, color = color, contentColor = Color.White) {
        Row(modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun AppHeader(compact: Boolean, context: String, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.size(if (compact) 32.dp else 40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("PeakBud", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(context, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalIconButton(onClick = onSettings, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
        }
    }
}

/** Pill-shaped bar floating above content. The selected tab expands to show its label. */
@Composable
private fun FloatingTabBar(selected: Int, overCamera: Boolean, onSelect: (Int) -> Unit, modifier: Modifier) {
    val tabs = listOf("Camera" to R.drawable.ic_photo_camera, "Home" to R.drawable.ic_home, "Peaks" to R.drawable.ic_landscape, "Regions" to R.drawable.ic_map)
    // Over the camera the bar turns translucent so the scene shows through; elsewhere it is solid forest green.
    Surface(modifier, shape = CircleShape, color = if (overCamera) Color.Black.copy(alpha = .55f) else Color(0xFF16352A), contentColor = Color.White, shadowElevation = if (overCamera) 0.dp else 12.dp) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, (label, icon) -> key(label) {
                val active = index == selected
                Row(Modifier.clip(CircleShape)
                    .background(if (active) (if (overCamera) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer) else Color.Transparent)
                    .clickable { onSelect(index) }
                    .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = active; contentDescription = label }
                    .animateContentSize()
                    .padding(horizontal = if (active) 16.dp else 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp),
                        tint = if (active) (if (overCamera) Color.White else MaterialTheme.colorScheme.onPrimaryContainer) else Color.White.copy(alpha = .72f))
                    // The label is always composed and collapses to zero width when inactive. Adding and removing it
                    // on every switch changed the row's structure mid-animation and crashed Compose on rapid taps.
                    Text(label, Modifier.then(if (active) Modifier.padding(start = 8.dp) else Modifier.width(0.dp)),
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                        color = if (overCamera) Color.White else MaterialTheme.colorScheme.onPrimaryContainer)
                }
            } }
        }
    }
}

@Composable
private fun ObservationStatus(state: PanoramaState, cameraMode: Boolean, cameraFov: Pair<Double, Double>?, compact: Boolean, onGps: () -> Unit, onCompass: () -> Unit, startExpanded: Boolean = false, detailsOnly: Boolean = false) {
    val context = LocalContext.current
    val fix = state.gpsFix
    val heading = if (cameraMode) state.cameraPose?.heading else state.heading
    val bearing = heading?.let { "${it.toInt().toString().padStart(3, '0')}° ${cardinal(it)}" } ?: "---°"
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    val gpsSummary = when {
        !state.followingGps -> "Location off"
        state.gpsStatus != null -> state.gpsStatus
        else -> "Finding your location…"
    }
    val directionSummary = if (state.followingCompass) "Following your phone" else "Drag to turn"
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = if (compact) 2.dp else 6.dp, bottom = if (compact) 2.dp else 6.dp)) {
            if (!detailsOnly) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bearing, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(directionSummary, gpsSummary).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!state.followingGps) TextButton(onClick = onGps, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Locate") }
                if (!state.followingCompass) TextButton(onClick = onCompass, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Compass") }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = if (expanded) "Hide details" else "Show details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.compassStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            AnimatedVisibility(expanded || detailsOnly) {
                Column(Modifier.padding(end = 10.dp, top = 4.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val small = MaterialTheme.typography.bodySmall
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(if (state.followingCompass) "Direction · from your ${state.compassSource ?: "compass"}" else "Direction · compass off, drag to turn", style = small, color = muted)
                    if (fix != null) {
                        Text("Your location · ${coordinate(fix.point.latitude)}, ${coordinate(fix.point.longitude)}", style = small, color = muted)
                        val altitude = fix.seaLevelAltitudeM ?: fix.ellipsoidAltitudeM
                        Text(altitude?.let { "Altitude · ${height(it, state)}${fix.verticalAccuracyM?.let { a -> " (±${kotlin.math.ceil(a.toDouble()).toInt().coerceAtLeast(1)} m)" } ?: ""}" } ?: "Altitude · not available", style = small, color = muted)
                    } else Text("Your location · finding…", style = small, color = muted)
                    if (!state.isDemo) state.scene?.observer?.let { Text("Ground height · ${height(it.groundHeightM, state)}", style = small, color = muted) }
                    if (!cameraMode) Text("Viewing from · ${coordinate(state.point.latitude)}, ${coordinate(state.point.longitude)} (${if (state.isDemo) "demo spot" else if (state.followingGps) "your location" else "chosen spot"})", style = small, color = muted)
                    if (cameraMode) cameraFov?.let { Text("Camera view · ${it.first.toInt()}° wide × ${it.second.toInt()}° tall", style = small, color = muted) }
                    if (state.gpsStatus?.startsWith("Location is off") == true) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }, contentPadding = PaddingValues(0.dp)) { Text("Open location settings") }
                }
            }
        }
    }
}

@Composable
private fun PanoramaPanel(state: PanoramaState, model: PanoramaViewModel, modifier: Modifier, onRegions: () -> Unit, onPeak: (PeakView) -> Unit) {
                    Card(modifier, shape = MaterialTheme.shapes.large) {
                        Box(Modifier.fillMaxSize()) {
                            PanoramaCanvas(state, Modifier.fillMaxSize(), model::pan, onPeak)
                            if (state.calculating) Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(16.dp)) {
                                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    Text("Tracing the ridgelines…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (!state.calculating && state.scene == null) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (state.noRegion) {
                                    Text("Pick a hiking area to see the mountains around you.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = onRegions) { Text("Choose region") }
                                } else Text("This spot is outside the hiking area. Choose a spot inside it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
}

@Composable
private fun ViewControls(state: PanoramaState, model: PanoramaViewModel, onViewpoint: () -> Unit, onGps: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.followingGps, onClick = onGps, label = { Text("My location") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_my_location), null, Modifier.size(18.dp)) })
            FilterChip(selected = state.followingCompass, onClick = model::toggleCompass, label = { Text("Compass") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_explore), null, Modifier.size(18.dp)) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onViewpoint, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Choose spot", maxLines = 1, softWrap = false) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Width", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(state.fov.toFloat(), { model.setFov(it.toDouble()) }, valueRange = 35f..120f, modifier = Modifier.weight(1f))
            Text("${state.fov.toInt()}°", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
        }
        listOfNotNull(
            state.compassStatus,
            state.scene?.horizon?.count { !it.complete }?.takeIf { it > 0 }?.let { "Some directions have no map data, so peaks there may be missing." },
        ).firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun PeakList(state: PanoramaState, modifier: Modifier, bottomSpace: Dp, onPeak: (PeakView) -> Unit) {
    val peaks = state.scene?.peaks.orEmpty().sortedWith(compareBy<PeakView> { when (it.visibility) {
        Visibility.TERRAIN_VISIBLE -> 0; Visibility.UNCERTAIN -> 1; Visibility.OCCLUDED -> 2; Visibility.UNKNOWN -> 3
    } }.thenBy { it.distanceM })
    LazyColumn(modifier, contentPadding = PaddingValues(top = 4.dp, bottom = bottomSpace + 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (peaks.isEmpty()) item {
            EmptyState(if (state.calculating) "Finding nearby peaks…" else if (state.noRegion) "Choose a hiking area in Regions to list its peaks." else "No peaks found from this spot.")
        }
        itemsIndexed(peaks, key = { _, it -> it.peak.id }) { index, peak ->
            val tone = when (peak.visibility) {
                Visibility.TERRAIN_VISIBLE -> MaterialTheme.colorScheme.primary
                Visibility.UNCERTAIN -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            // Rows read as one grouped card: only the first and last get outer corners.
            val shape = RoundedCornerShape(topStart = if (index == 0) 20.dp else 6.dp, topEnd = if (index == 0) 20.dp else 6.dp,
                bottomStart = if (index == peaks.lastIndex) 20.dp else 6.dp, bottomEnd = if (index == peaks.lastIndex) 20.dp else 6.dp)
            Surface(onClick = { onPeak(peak) }, shape = shape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(peak.peak.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(
                            peak.peak.publishedHeightM?.let { height(it, state) } ?: peak.geometryHeightM?.let { "~${height(it, state)}" },
                            distance(peak.distanceM, state),
                            peak.elevationDeg?.let { angleText(it) },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${peak.bearingDeg.toInt()}° ${cardinal(peak.bearingDeg)}", style = MaterialTheme.typography.labelLarge)
                        StatusPill(when (peak.visibility) {
                            Visibility.TERRAIN_VISIBLE -> "In view"; Visibility.UNCERTAIN -> "Maybe"
                            Visibility.OCCLUDED -> "Hidden"; Visibility.UNKNOWN -> "No data"
                        }, tone)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, tone: Color) {
    Surface(shape = CircleShape, color = tone.copy(alpha = .12f), contentColor = tone) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyState(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Regions(state: PanoramaState, modifier: Modifier, bottomSpace: Dp, onDownload: (CatalogRegion) -> Unit, onSelect: (RegionPack?) -> Unit, onDelete: (RegionPack) -> Unit, onAttribution: (RegionPack) -> Unit, onCancel: () -> Unit,
                    onDiscard: (CatalogRegion) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun matches(vararg fields: String?) = words.all { word -> fields.any { it?.lowercase()?.contains(word) == true } }
    fun installedFor(region: CatalogRegion) = state.packs.lastOrNull { it.id == region.id && it.version == region.version }
    val catalog = state.catalog.filter { matches(it.name, it.country, it.description) }
    val (onDevice, available) = catalog.partition { installedFor(it) != null }
    val legacy = state.packs.filter { pack -> state.catalog.none { it.id == pack.id && it.version == pack.version } && matches(pack.name) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape,
            placeholder = { Text("Search places, countries or ranges") },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear search") } },
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = bottomSpace + 8.dp)) {
            state.importing?.let {
                item {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Cancel") }
                            }
                        }
                    }
                }
            }
            if (catalog.isEmpty() && legacy.isEmpty()) item { EmptyState("No hiking areas match \"$query\".") }
            if (onDevice.isNotEmpty() || legacy.isNotEmpty()) item { SectionLabel("Downloaded") }
            items(onDevice, key = { it.id }) { region ->
                val installed = installedFor(region)!!
                val active = installed.key == state.activePack?.key
                RegionCard(region.name, "${region.country} · ${region.description}", "Peaks up to ${region.radiusKm} km away", if (active) "In use" else "Ready",
                    MaterialTheme.colorScheme.primary, highlighted = active,
                    menu = { close ->
                        DropdownMenuItem(text = { Text("Map credits") }, leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) }, onClick = { close(); onAttribution(installed) })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) }, enabled = state.importing == null, onClick = { close(); onDelete(installed) })
                    }) {
                    if (active) FilledTonalButton(onClick = { onSelect(installed) }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Open") }
                    else Button(onClick = { onSelect(installed) }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Use") }
                }
            }
            items(legacy, key = { it.key }) { pack ->
                val active = pack.key == state.activePack?.key
                RegionCard(pack.name, null, null, if (active) "In use" else "Ready", MaterialTheme.colorScheme.primary, highlighted = active,
                    menu = { close ->
                        DropdownMenuItem(text = { Text("Map credits") }, leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) }, onClick = { close(); onAttribution(pack) })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) }, enabled = state.importing == null, onClick = { close(); onDelete(pack) })
                    }) {
                    Button(onClick = { onSelect(pack) }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Use") }
                }
            }
            if (available.isNotEmpty()) item { SectionLabel(if (words.isEmpty()) "Explore ${available.size} areas" else "Results") }
            items(available, key = { it.id }) { region ->
                val saved = state.pendingDownloads.any { transfer -> region.tiles.any { it.url == transfer.address } }
                val downloading = state.downloadingRegionId == region.id
                RegionCard(region.name, "${region.country} · ${region.description}",
                    "${(region.downloadBytes + 1_048_575) / 1_048_576} MB · peaks up to ${region.radiusKm} km away",
                    if (downloading) "Downloading" else if (saved) "Paused" else null, MaterialTheme.colorScheme.tertiary,
                    menu = if (saved && !downloading) { close -> DropdownMenuItem(text = { Text("Discard download") }, leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                        enabled = state.importing == null, onClick = { close(); onDiscard(region) }) } else null) {
                    when {
                        downloading -> CircularProgressIndicator(Modifier.padding(10.dp).size(24.dp), strokeWidth = 2.5.dp)
                        saved -> FilledTonalButton(enabled = state.importing == null, onClick = { onDownload(region) }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Resume") }
                        else -> FilledTonalIconButton(enabled = state.importing == null, onClick = { onDownload(region) }) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = "Download ${region.name}")
                        }
                    }
                }
            }
            if (state.preferences.demoMode) item {
                Surface(onClick = { onSelect(null) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                    border = if (state.isDemo) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(painterResource(R.drawable.ic_explore), null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Demo landscape", style = MaterialTheme.typography.titleSmall)
                            Text("A made-up view for trying the controls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.isDemo) StatusPill("In use", MaterialTheme.colorScheme.primary)
                        else Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionCard(name: String, subtitle: String?, meta: String?, status: String?, statusTone: Color, highlighted: Boolean = false,
                       menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null, action: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (highlighted) 1.5.dp else 1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(start = 16.dp, end = if (menu != null) 4.dp else 12.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (meta != null || status != null) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    status?.let { StatusPill(it, statusTone) }
                    meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.width(10.dp))
            action()
            menu?.let { items ->
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options for $name", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(open, { open = false }, shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surface) { items { open = false } }
                }
            }
        }
    }
}

@Composable
private fun ViewpointDialog(state: PanoramaState, onDismiss: () -> Unit, onApply: (GeoPoint, Double?) -> Unit) {
    var latitude by remember { mutableStateOf(coordinate(state.point.latitude)) }
    var longitude by remember { mutableStateOf(coordinate(state.point.longitude)) }
    var altitude by remember { mutableStateOf(state.groundOverrideM?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose a spot") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Enter coordinates as decimals, like 37.7281. Leave height empty and PeakBud will use the map.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") }, singleLine = true)
            OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") }, singleLine = true)
            OutlinedTextField(altitude, { altitude = it }, label = { Text("Ground height in metres (optional)") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val point = runCatching { GeoPoint(latitude.trim().toDouble(), longitude.trim().toDouble()) }.getOrNull()
        val ground = altitude.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (point == null || altitude.isNotBlank() && (ground == null || !ground.isFinite() || ground !in -500.0..10_000.0)) error = "Check the numbers: latitude −90 to 90, longitude −180 to 180, height −500 to 10,000 m."
        else onApply(point, ground)
    }) { Text("Show view") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(state: PanoramaState, model: PanoramaViewModel, onDismiss: () -> Unit) {
    var phoneHeight by remember { mutableFloatStateOf(state.preferences.phoneHeightM.toFloat()) }
    val prefs = state.preferences
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp))
            SectionLabel("Display")
            SettingsGroup {
                SettingSwitch("Feet and miles", "Show heights and distances in imperial units", prefs.useFeet) { model.updatePreferences(prefs.copy(useFeet = it)) }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingSwitch("Show maybe-visible peaks", "Include peaks that terrain might hide", prefs.showUncertain) { model.updatePreferences(prefs.copy(showUncertain = it)) }
            }
            SectionLabel("Viewing")
            SettingsGroup {
                SettingSlider("Phone height", "${String.format(Locale.US, "%.1f", phoneHeight)} m", phoneHeight, .5f..3f, { phoneHeight = it }) {
                    model.updatePreferences(prefs.copy(phoneHeightM = phoneHeight.toDouble()))
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                SettingSlider("Look up or down", if (state.pitch.toInt() == 0) "Level" else angleText(state.pitch), state.pitch.toFloat(), -20f..20f, { model.setPitch(it.toDouble()) })
            }
            SectionLabel("Demo")
            SettingsGroup {
                SettingSwitch("Demo mode", "Adds a made-up landscape so you can try PeakBud without downloading", prefs.demoMode) { model.updatePreferences(prefs.copy(demoMode = it)) }
            }
            SectionLabel("Privacy")
            SettingsGroup {
                SettingSwitch("Send crash reports and usage", "Helps fix problems. Your location is never sent.", prefs.shareDiagnostics) { model.updatePreferences(prefs.copy(shareDiagnostics = it)) }
            }
            Text("Peak visibility is worked out from maps. Clouds, trees, location errors and nearby metal can change what you actually see.",
                Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) { Column(content = content) }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
private fun SettingSlider(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(current, onChange, valueRange = range, onValueChangeFinished = onDone)
    }
}

internal fun angleText(degrees: Double) = "${String.format(Locale.US, "%.1f", kotlin.math.abs(degrees))}° ${if (degrees >= 0) "up" else "down"}"
internal fun coordinate(value: Double) = String.format(Locale.US, "%.5f", value)
internal fun height(metres: Double, state: PanoramaState) = if (state.preferences.useFeet) "${(metres * 3.28084).toInt()} ft" else "${metres.toInt()} m"
internal fun distance(metres: Double, state: PanoramaState) = if (state.preferences.useFeet) String.format(Locale.US, "%.1f mi", metres / 1609.344) else String.format(Locale.US, "%.1f km", metres / 1000)
internal fun cardinal(degrees: Double) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(Geography.wrap360(degrees + 22.5) / 45).toInt() % 8]
internal fun visibilityName(value: Visibility) = when (value) {
    Visibility.TERRAIN_VISIBLE -> "Should be in view"
    Visibility.OCCLUDED -> "Hidden behind a closer ridge"
    Visibility.UNCERTAIN -> "Might be hidden"
    Visibility.UNKNOWN -> "No map data here"
}

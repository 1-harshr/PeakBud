package com.harsh.peakbud.feature.panorama

import com.harsh.peakbud.ui.text.*
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.harsh.peakbud.data.settings.ThemeMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.harsh.peakbud.R
import com.harsh.peakbud.data.regions.Vicinity
import com.harsh.peakbud.core.projection.CameraPose
import com.harsh.peakbud.feature.camera.AlignmentUi
import com.harsh.peakbud.core.alignment.AlignmentProblem
import com.harsh.peakbud.core.alignment.AlignmentPhase
import com.harsh.peakbud.telemetry.Telemetry
import androidx.compose.ui.text.font.FontWeight
import com.harsh.peakbud.ui.theme.*
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
    val strings = rememberTrailStrings()
    val state by model.state.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp > configuration.screenHeightDp
    var tab by rememberSaveable { mutableIntStateOf(1) }  // open on Home
    // Tabs: 0 Camera (always full screen), 1 Home (landing: the drawn skyline), 2 Peaks, 3 Regions.
    val cameraTab = tab == 0
    // Full screen hides navigation but keeps position metadata and an explicit exit control.
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
    var tutorial by rememberSaveable { mutableStateOf(false) }
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
    // Leaving Demo mode brings the real location back.
    var wasDemo by remember { mutableStateOf(state.isDemo) }
    LaunchedEffect(state.isDemo) {
        if (wasDemo && !state.isDemo && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.startGps()
        wasDemo = state.isDemo
    }
    LaunchedEffect(tab, state.activePack?.key, state.initialized, state.isDemo) {
        if (cameraTab && state.initialized && !state.isDemo) {
            model.startCameraTracking()
            if (!state.followingGps && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.startGps()
        }
    }
    // Partial scenes arrive while distant peaks are checked; only a new viewpoint closes the open peak.
    LaunchedEffect(state.scene?.observer) { selectedPeak = null }
    LaunchedEffect(selectedPeak?.peak?.id) { selectedPeak?.let { Telemetry.event("peak_open", "visibility" to it.visibility.name.lowercase(), "from" to listOf("camera", "home", "peaks", "regions")[tab]) } }
    fun gps() {
        if (state.isDemo) return  // demo mode never uses the real location
        if (state.followingGps) { model.stopGps(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else model.startGps()
    }
    // Light status-bar icons over the camera, dark ones over the paper background.
    val lightChrome = !cameraTab && MaterialTheme.colorScheme.background.luminance() > .5f
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view, cameraTab, immersive) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        if (cameraTab && immersive) {
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    TrailSystemBars(lightChrome)
    Box(Modifier.fillMaxSize().background(if (cameraTab) Color.Black else MaterialTheme.colorScheme.background)) {
        // Space the floating tab bar occupies above the system navigation bar.
        // Portrait: floating bar at the bottom. Landscape: a vertical rail on the left so the scene keeps its height.
        val railSpace = if (compact) TrailSize.tabWidth + TrailSpace.lg + WindowInsets.displayCutout.asPaddingValues().calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr) else TrailSpace.none
        val barSpace = if (compact) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TrailSpace.sm
            else TrailSize.bottomBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (cameraTab) CameraTab(state, model, cameraFov, barSpace, railSpace, compact, immersive, onImmersive = { immersive = it }, onFov = { cameraFov = it },
            onRegions = { tab = 3 }, onGps = ::gps, onSettings = { settings = true }, onPeak = { selectedPeak = it })
        else Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = railSpace).padding(horizontal = TrailSpace.lg).padding(bottom = if (tab == 1 || compact) barSpace else TrailSpace.none),
            verticalArrangement = Arrangement.spacedBy(if (compact) TrailSpace.sm else TrailSpace.md)) {
            AppHeader(compact, when (tab) {
                2 -> if (state.noRegion) strings(R.string.peaks_pick_a_hiking_area_to_start) else if (state.isDemo) strings(R.string.peaks_demo_landscape) else strings(R.string.peaks_within_km_of_this_spot , distance(state.activePack?.radiusM ?: 0.0, state, strings))
                3 -> strings(R.string.hiking_areas_download_once_works_offline)
                else -> currentPlace(state, strings)
            }, contextIcon = if (tab == 1) R.drawable.ic_my_location else null) { settings = true }
            state.message?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(start = TrailSpace.lg, top = TrailSpace.xxs, bottom = TrailSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::clearMessage) { Text(strings(R.string.dismiss)) }
                    }
                }
            }
            when (tab) {
                1 -> {
                    ObservationStatus(state, false, null, compact, ::gps, model::startCameraTracking)
                    BoxWithConstraints(Modifier.weight(1f)) {
                        if (maxWidth > maxHeight) {
                            // Landscape: the skyline takes the full width; the heading card is already in the header row.
                            Column(verticalArrangement = Arrangement.spacedBy(TrailSpace.xs)) {
                                PanoramaPanel(state, model, Modifier.fillMaxWidth().weight(1f), { tab = 3 }, ::gps, { manual = true }) { selectedPeak = it }
                                HomeNotes(state)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                                PanoramaPanel(state, model, Modifier.fillMaxWidth().weight(1f), { tab = 3 }, ::gps, { manual = true }) { selectedPeak = it }
                                HomeNotes(state)
                            }
                        }
                    }
                }
                2 -> PeakList(state, Modifier.weight(1f), barSpace) { selectedPeak = it }
                3 -> {
                    val cell = state.gpsFix?.point?.let { (it.latitude * 100).toInt() to (it.longitude * 100).toInt() }
                    LaunchedEffect(cell, state.catalog.size, state.placeName) { model.planNearby() }
                    Regions(state, Modifier.weight(1f), barSpace, model::downloadRegion,
                    { model.selectRegion(it); tab = 1 }, { deletePack = it }, { pack -> scope.launch { attribution = runCatching { model.attribution(pack) }.getOrDefault(strings(R.string.credits_couldn_t_be_loaded)) } }, model::cancelImport,
                    model::discardDownload)
                }
            }
        }
        if (compact) AnimatedVisibility(!(cameraTab && immersive), Modifier.align(Alignment.CenterStart),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInHorizontally { -it / 2 },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutHorizontally { -it / 2 }) {
            TabRail(tab, cameraTab, { tab = it }, Modifier.displayCutoutPadding().padding(start = TrailSpace.sm))
        } else AnimatedVisibility(!(cameraTab && immersive), Modifier.align(Alignment.BottomCenter),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 }) {
            FloatingTabBar(tab, cameraTab, { tab = it }, Modifier.navigationBarsPadding().padding(bottom = TrailSpace.md))
        }
    }
    if (manual) ViewpointDialog(state, { manual = false }) { point, ground -> model.manualViewpoint(point, ground); manual = false }
    if (settings) SettingsDialog(state, model, onTutorial = { settings = false; tutorial = true }) { settings = false }
    // First launch: show the walkthrough once; finishing or skipping it is saved in DataStore.
    LaunchedEffect(state.tutorialSeen) { if (state.tutorialSeen == false) tutorial = true }
    if (tutorial) com.harsh.peakbud.feature.tutorial.TutorialScreen(state.preferences) { tutorial = false; model.markTutorialSeen() }
    selectedPeak?.let { peak ->
        AlertDialog(onDismissRequest = { selectedPeak = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(peak.peak.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TrailSpace.md)) {
                Text(peak.peak.publishedHeightM?.let { height(it, state, strings) } ?: strings(R.string.height_unknown), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                    listOfNotNull(strings(R.string.away , distance(peak.distanceM, state, strings)), strings(R.string.bearing_with_cardinal , peak.bearingDeg.toInt(), cardinal(peak.bearingDeg, strings)),
                        peak.elevationDeg?.let { angleText(it, strings) }).forEach {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(it, Modifier.padding(horizontal = TrailSpace.md, vertical = TrailSpace.xs), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                StatusPill(visibilityName(peak.visibility, strings), when (peak.visibility) {
                    Visibility.TERRAIN_VISIBLE -> MaterialTheme.colorScheme.primary; Visibility.UNCERTAIN -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant })
                Text(peakReason(peak, strings), style = MaterialTheme.typography.bodyMedium)
                Text(strings(R.string.nsource , coordinate(peak.peak.point.latitude), coordinate(peak.peak.point.longitude), peak.peak.source), style = MaterialTheme.typography.bodySmall)
                peak.geometryHeightM?.let { Text(strings(R.string.the_map_puts_the_ground_here_at_the_official_summit_height_may_differ , height(it, state, strings)), style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = {
            Row {
                TextButton(onClick = { model.focus(peak.peak.id); selectedPeak = null; tab = 0 }) { Text(strings(R.string.find_in_camera)) }
                TextButton(onClick = { model.focus(peak.peak.id); model.pan(Geography.wrap180(peak.bearingDeg - state.heading)); selectedPeak = null; tab = 1 }) { Text(strings(R.string.show_on_home)) }
            }
        }, dismissButton = {
            // The focused peak stays ringed until it is closed here.
            if (state.focusedPeakId == peak.peak.id) TextButton(onClick = { model.focus(null); selectedPeak = null }) { Text(strings(R.string.stop_showing)) }
            else TextButton(onClick = { selectedPeak = null }) { Text(strings(R.string.close)) }
        })
    }
    attribution?.let { text -> AlertDialog(onDismissRequest = { attribution = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(strings(R.string.map_and_peak_credits)) }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { attribution = null }) { Text(strings(R.string.done)) } }) }
    deletePack?.let { pack -> AlertDialog(onDismissRequest = { deletePack = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text(strings(R.string.delete , pack.name)) }, text = { Text(strings(R.string.its_maps_and_peak_names_will_be_removed_from_this_phone_you_can_download_it)) }, confirmButton = { TextButton(onClick = { model.deleteRegion(pack); deletePack = null }) { Text(strings(R.string.delete_2)) } }, dismissButton = { TextButton(onClick = { deletePack = null }) { Text(strings(R.string.keep)) } }) }
}

/**
 * Full-screen camera tab. The preview fills the display; controls float above it on translucent pills,
 * following AR conventions (PeakFinder, Star Walk, Apple HIG): status at the top, area and navigation at
 * the bottom, fine adjustment behind the heading chip and by dragging the view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraTab(state: PanoramaState, model: PanoramaViewModel, cameraFov: Pair<Double, Double>?, barSpace: Dp, railSpace: Dp, landscape: Boolean, immersive: Boolean,
                      onImmersive: (Boolean) -> Unit, onFov: (Pair<Double, Double>?) -> Unit, onRegions: () -> Unit, onGps: () -> Unit,
                      onSettings: () -> Unit, onPeak: (PeakView) -> Unit) {
    val strings = rememberTrailStrings()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var sheet by rememberSaveable { mutableStateOf(false) }
    // Drag-to-nudge bookkeeping: where the gesture started (for Undo) and the last whole degree (for haptic ticks).
    var dragStart by remember { mutableStateOf<Double?>(null) }
    var shifted by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(shifted) { if (shifted != null) { kotlinx.coroutines.delay(4000); shifted = null } }
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBar = statusTop + TrailSize.cameraBarClearance
    val showControls = !immersive
    val realAlignment by model.alignment.ui.collectAsStateWithLifecycle()
    // Demo mode: no camera. Pre-rendered footage of the made-up range, and a scripted "Align with camera".
    val demo = state.isDemo
    val demoStory = remember { androidx.compose.animation.core.Animatable(0f) }
    var demoPhase by remember { mutableStateOf(AlignmentPhase.SENSOR) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(demo) { if (!demo) { demoPhase = AlignmentPhase.SENSOR; demoStory.snapTo(0f) } }
    val alignment = if (demo) AlignmentUi(demoPhase, if (demoPhase == AlignmentPhase.ALIGNED) com.harsh.peakbud.core.alignment.AlignmentCorrection(0.0, 0.0) else null)
        else realAlignment
    val startAlign: () -> Unit = if (demo) ({
        scope.launch {
            demoStory.snapTo(0f); demoPhase = AlignmentPhase.ALIGNING
            demoStory.animateTo(1f, androidx.compose.animation.core.tween(4_500, easing = androidx.compose.animation.core.LinearEasing))
            demoPhase = AlignmentPhase.ALIGNED
        }
    }) else ({ model.startAlignment(cameraFov?.first) })
    val resetAlign: () -> Unit = if (demo) ({ scope.launch { demoStory.snapTo(0f); demoPhase = AlignmentPhase.SENSOR } }) else model::resetAlignment
    // Prepare the terrain prediction in the background once camera geometry and a heading are known, after the peak
    // calculation finishes so the two do not compete for the CPU. Tapping Align earlier builds it immediately.
    val headingBucket = state.cameraPose?.heading?.let { (it / 20).toInt() }
    LaunchedEffect(state.point, state.activePack?.key, cameraFov?.first?.toInt(), headingBucket, state.calculating) {
        if (!state.calculating && !demo) model.prefetchAlignment(cameraFov?.first)
    }
    val pose = if (demo) CameraPose(state.heading, 0.0, 0.0) else alignment.pose(state.cameraPose)
    Box(Modifier.fillMaxSize()) {
        if (demo) com.harsh.peakbud.feature.tutorial.DemoCamera(state.scene, Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onImmersive(!immersive) } },
            com.harsh.peakbud.feature.tutorial.CameraDemo.ALIGN, storyProgress = demoStory.value, showPill = false)
        else CameraPanel(state, Modifier.fillMaxSize(), onRegions, onGps, model::startCameraTracking, onFov, fullScreen = true,
            pose = pose, aligned = alignment.aligned, hiddenPeaks = alignment.hiddenByForeground,
            alignment = model.alignment, rawPose = { model.state.value.cameraPose },
            safeTop = if (showControls) topBar else statusTop + TrailSize.touchTarget,
            safeBottom = (if (showControls) barSpace + (if (landscape) TrailSize.cameraBarClearance else TrailSize.chip) else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) + TrailSize.cameraBarClearance,
            onBackgroundTap = { onImmersive(!immersive) },
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
                Box(Modifier.fillMaxWidth().height(statusTop + TrailSize.cameraScrimDepth).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(TrailCameraColors.scrim, Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(barSpace + TrailSize.cameraScrimDepth).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .5f)))))
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = railSpace).padding(horizontal = TrailSpace.lg, vertical = TrailSpace.sm), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                    Box(Modifier.fillMaxWidth()) {
                        LocationChip(state, Modifier.align(Alignment.CenterStart)) { if (!demo) sheet = true }
                        HeadingChip(state, pose, alignment.aligned, Modifier.align(Alignment.Center)) { sheet = true }
                        GlassIconButton(R.drawable.ic_settings, strings(R.string.settings), Modifier.align(Alignment.CenterEnd), onSettings)
                    }
                    if (state.followingCompass && state.cameraPose != null && !state.poseReliable)
                        GlassPill(color = TrailCameraColors.warningSurface) { Text(strings(R.string.compass_unsteady_wave_your_phone_in_a_figure_eight), style = MaterialTheme.typography.labelMedium) }
                }
                // Landscape: actions sit in the bottom-right corner, clear of the scene's centre and the left rail.
                Column(if (landscape) Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = TrailSpace.lg, bottom = TrailSize.cameraBarClearance + TrailSpace.lg)
                    else Modifier.align(Alignment.BottomCenter).padding(bottom = barSpace + TrailSize.cameraBarClearance + TrailSpace.xs),
                    horizontalAlignment = if (landscape) Alignment.End else Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                    AlignControl(alignment, onAlign = startAlign, onReset = resetAlign)
                    shifted?.let { start ->
                        GlassPill(Modifier.padding(start = TrailSpace.lg, end = TrailSpace.xs)) {
                            Text(strings(R.string.labels_shifted , signedDegrees(state.labelTrim, strings)), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { model.trimCompass(start - state.labelTrim); shifted = null }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(strings(R.string.undo)) }
                        }
                    }
                    Row(Modifier.clip(CircleShape).clickable(onClick = onRegions).padding(horizontal = TrailSpace.md, vertical = TrailSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.activePack?.name ?: if (state.isDemo) strings(R.string.demo_landscape) else strings(R.string.choose_a_hiking_area), style = MaterialTheme.typography.labelLarge, color = Color.White)
                        Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(TrailSize.iconSmall), tint = Color.White.copy(alpha = .8f))
                    }
                }
            }
        }
        // Keep essential metadata visible even when the navigation and adjustment controls are hidden.
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
            .padding(start = if (showControls) railSpace else TrailSpace.none).padding(horizontal = TrailSpace.lg)
            .padding(bottom = if (showControls) barSpace else TrailSpace.lg),
            horizontalArrangement = Arrangement.spacedBy(TrailSpace.sm), verticalAlignment = Alignment.Bottom) {
            CameraPositionOverlay(state, if (landscape) Modifier.widthIn(max = TrailSize.sidePanel) else Modifier.weight(1f))
            if (landscape) Spacer(Modifier.weight(1f))
            if (showControls) GlassIconButton(R.drawable.ic_fullscreen, strings(R.string.enter_full_screen)) {
                onImmersive(true)
            }
        }
        if (immersive) GlassIconButton(R.drawable.ic_fullscreen_exit, strings(R.string.exit_full_screen),
            Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(TrailSpace.lg)) { onImmersive(false) }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.background) {
        TrailSystemBars(MaterialTheme.colorScheme.background.luminance() > .5f)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = TrailSpace.sheet).padding(bottom = TrailSpace.xl), verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
            Text(state.cameraPose?.heading?.let { strings(R.string.pointing , it.toInt().toString().padStart(3, '0'), cardinal(it, strings)) } ?: strings(R.string.compass), style = MaterialTheme.typography.titleLarge)
            Text(when {
                !state.followingCompass -> strings(R.string.the_compass_is_off_turn_it_on_so_labels_follow_where_you_point)
                !state.poseReliable -> state.compassStatus ?: strings(R.string.the_compass_is_unsteady_move_away_from_metal_and_wave_your_phone_in_a_figure)
                else -> strings(R.string.the_compass_is_steady_and_labels_follow_where_you_point)
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionLabel(strings(R.string.line_up_labels))
            SettingsGroup {
                Column(Modifier.padding(TrailSpace.lg), verticalArrangement = Arrangement.spacedBy(TrailSpace.md)) {
                    Text(strings(R.string.if_labels_sit_beside_the_real_peaks_shift_them_here_or_drag_the_camera_view), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        FilledTonalIconButton(onClick = { model.trimCompass(-1.0); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) },
                            modifier = Modifier.size(TrailSize.touchTarget)) { Text(strings(R.string.nudge_left), style = MaterialTheme.typography.labelLarge) }
                        Text(if (state.labelTrim == 0.0) strings(R.string.not_shifted) else signedDegrees(state.labelTrim, strings), style = MaterialTheme.typography.titleMedium)
                        FilledTonalIconButton(onClick = { model.trimCompass(1.0); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) },
                            modifier = Modifier.size(TrailSize.touchTarget)) { Text(strings(R.string.nudge_right), style = MaterialTheme.typography.labelLarge) }
                        TextButton(enabled = state.labelTrim != 0.0, onClick = { model.resetTrim(); haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm) }) { Text(strings(R.string.reset)) }
                    }
                }
            }
            SectionLabel(strings(R.string.your_position))
            ObservationStatus(state, true, cameraFov, false, onGps, model::startCameraTracking, detailsOnly = true)
        }
    }
}

/** Uses only device fixes, never the manual/demo viewpoint, for camera position metadata. */
@Composable
internal fun CameraPositionOverlay(state: PanoramaState, modifier: Modifier = Modifier) {
    val strings = rememberTrailStrings()
    Surface(modifier.semantics { contentDescription = strings(R.string.camera_location_metadata) },
        shape = MaterialTheme.shapes.medium, color = TrailCameraColors.scrim, contentColor = TrailCameraColors.content) {
        Column(Modifier.padding(horizontal = TrailSpace.md, vertical = TrailSpace.sm), verticalArrangement = Arrangement.spacedBy(TrailSpace.xxs)) {
            val fix = state.gpsFix
            if (state.isDemo) {
                // Demo mode never uses the real location.
                Text(strings(R.string.demo_viewpoint), style = MaterialTheme.typography.labelMedium)
            } else if (fix != null) {
                Text(strings(R.string.camera_coordinates, coordinate(fix.point.latitude), coordinate(fix.point.longitude)), style = MaterialTheme.typography.labelMedium)
                val altitude = fix.seaLevelAltitudeM ?: fix.ellipsoidAltitudeM
                val detail = listOfNotNull(
                    if (!state.followingGps || !state.gpsFresh) strings(R.string.camera_last_known_location) else null,
                    altitude?.let { strings(R.string.camera_altitude, height(it, state, strings)) }
                ).joinToString(" · ")
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelMedium)
            } else Text(if (state.followingGps) strings(R.string.camera_coordinates_waiting) else strings(R.string.camera_coordinates_off),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun signedDegrees(value: Double, strings: TrailStrings) = strings(R.string.signed_angle, if (value > 0) "+" else if (value < 0) "−" else "", kotlin.math.abs(value))

/** Top-left: location accuracy at a glance. */
@Composable
private fun LocationChip(state: PanoramaState, modifier: Modifier, onClick: () -> Unit) {
    val strings = rememberTrailStrings()
    val text = when {
        state.isDemo -> strings(R.string.demo)
        !state.followingGps -> strings(R.string.off)
        state.gpsFresh && state.gpsFix != null -> strings(R.string.metres_value , state.gpsFix!!.accuracyM.toInt())
        else -> strings(R.string.finding)
    }
    Surface(onClick = onClick, modifier = modifier.heightIn(min = TrailSize.touchTarget).semantics { contentDescription = strings(R.string.location_show_details , state.gpsStatus ?: text) },
        shape = CircleShape, color = TrailCameraColors.scrim, contentColor = Color.White) {
        Row(Modifier.padding(horizontal = TrailSpace.md), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
            if (!state.isDemo && state.followingGps && !state.gpsFresh) CircularProgressIndicator(Modifier.size(TrailSize.iconSmall), color = Color.White, strokeWidth = TrailStroke.normal)
            else Icon(painterResource(R.drawable.ic_my_location), null, Modifier.size(TrailSize.iconSmall))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Top-centre: heading with a status dot (green steady, amber unsteady, white ring when labels are shifted). */
@Composable
private fun HeadingChip(state: PanoramaState, pose: CameraPose?, aligned: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val strings = rememberTrailStrings()
    val heading = pose?.heading
    val dot = when { aligned -> TrailCameraColors.alignedRing; !state.followingCompass || heading == null -> Color.White.copy(alpha = .5f); !state.poseReliable -> TrailCameraColors.uncertain; else -> TrailCameraColors.steady }
    Surface(onClick = onClick, modifier = modifier.heightIn(min = TrailSize.touchTarget).semantics { contentDescription = strings(R.string.heading_degrees_compass_and_label_settings , heading?.toInt() ?: strings(R.string.unknown)) },
        shape = CircleShape, color = TrailCameraColors.scrim, contentColor = Color.White) {
        Row(Modifier.padding(horizontal = TrailSpace.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
            Box(Modifier.size(TrailSpace.sm).clip(CircleShape).background(dot)
                .then(if (state.labelTrim != 0.0) Modifier.border(TrailStroke.selected, Color.White, CircleShape) else Modifier))
            Text(heading?.let { strings(R.string.bearing_with_cardinal , it.toInt().toString().padStart(3, '0'), cardinal(it, strings)) } ?: strings(R.string.heading_pending), style = MaterialTheme.typography.titleSmall)
        }
    }
}

/**
 * "Align with camera" and its states. Short labels only: Aligning, Aligned, Couldn't match this view, with one reason
 * line and a single action. Manual nudging stays available as the fallback and replaces alignment when used.
 */
@Composable
private fun AlignControl(ui: AlignmentUi, onAlign: () -> Unit, onReset: () -> Unit) {
    val strings = rememberTrailStrings()
    val white = ButtonDefaults.textButtonColors(contentColor = Color.White)
    androidx.compose.animation.AnimatedContent(ui.phase, label = "align") { phase ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TrailSpace.xs)) {
            when (phase) {
                AlignmentPhase.SENSOR -> GlassPill(Modifier.clickable(onClick = onAlign).semantics { role = Role.Button }) {
                    Icon(painterResource(R.drawable.ic_landscape), null, Modifier.size(TrailSize.iconSmall))
                    Spacer(Modifier.width(TrailSpace.sm))
                    Text(strings(R.string.align_with_camera), style = MaterialTheme.typography.labelLarge)
                }
                AlignmentPhase.ALIGNING -> GlassPill(Modifier.padding(end = TrailSpace.xs)) {
                    CircularProgressIndicator(Modifier.size(TrailSize.iconSmall), color = Color.White, strokeWidth = TrailStroke.selected)
                    Spacer(Modifier.width(TrailSpace.sm))
                    Text(strings(if (ui.preparing) R.string.reading_the_terrain else R.string.aligning), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onReset, colors = white) { Text(strings(R.string.cancel)) }
                }
                AlignmentPhase.ALIGNED -> GlassPill(Modifier.padding(end = TrailSpace.xs)) {
                    Box(Modifier.size(TrailSpace.sm).clip(CircleShape).background(TrailCameraColors.alignedRing))
                    Spacer(Modifier.width(TrailSpace.sm))
                    Text(strings(R.string.aligned_with_camera), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onReset, colors = white) { Text(strings(R.string.reset)) }
                }
                AlignmentPhase.FAILED, AlignmentPhase.LOST -> {
                    GlassPill(Modifier.padding(end = TrailSpace.xs), color = TrailCameraColors.warningSurface) {
                        Text(strings(if (phase == AlignmentPhase.LOST) R.string.alignment_lost else R.string.couldnt_match_this_view), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = onAlign, colors = white) { Text(strings(R.string.retry)) }
                    }
                    Text(if (phase == AlignmentPhase.LOST) strings(R.string.alignment_lost_hint) else strings(alignmentProblemText(ui.problem)),
                        style = MaterialTheme.typography.labelMedium, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.widthIn(max = TrailSize.noticeWidth).background(TrailCameraColors.scrim, MaterialTheme.shapes.small).padding(horizontal = TrailSpace.md, vertical = TrailSpace.xs))
                }
            }
            if (ui.aligned && ui.hiddenByForeground.isNotEmpty()) Text(strings(R.string.peaks_hidden_by_foreground, ui.hiddenByForeground.size),
                style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .85f))
        }
    }
}

internal fun alignmentProblemText(problem: AlignmentProblem?): Int = when (problem) {
    AlignmentProblem.NO_CLEAR_SKY -> R.string.align_problem_no_clear_sky
    AlignmentProblem.NOT_ENOUGH_SHAPE -> R.string.align_problem_not_enough_shape
    AlignmentProblem.NO_TERRAIN -> R.string.align_problem_no_terrain
    AlignmentProblem.NO_CLEAR_MATCH -> R.string.align_problem_no_clear_match
    AlignmentProblem.HOLD_UPRIGHT -> R.string.align_problem_hold_upright
    AlignmentProblem.MOVING -> R.string.align_problem_moving
    AlignmentProblem.ROUGH_LOCATION -> R.string.align_problem_rough_location
    AlignmentProblem.NO_CAMERA -> R.string.align_problem_no_camera
    AlignmentProblem.NO_SKYLINE, null -> R.string.align_problem_no_skyline
}

@Composable
private fun GlassIconButton(icon: Int, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = TrailCameraColors.scrim, contentColor = Color.White, modifier = modifier.size(TrailSize.touchTarget)) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), contentDescription = description, Modifier.size(TrailSize.iconMedium)) }
    }
}

@Composable
private fun GlassPill(modifier: Modifier = Modifier, color: Color = TrailCameraColors.scrim, content: @Composable RowScope.() -> Unit) {
    Surface(shape = CircleShape, color = color, contentColor = Color.White) {
        Row(modifier.heightIn(min = TrailSize.readout).padding(horizontal = TrailSpace.lg), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun AppHeader(compact: Boolean, context: String, contextIcon: Int? = null, onSettings: () -> Unit) {
    val strings = rememberTrailStrings()
    Row(Modifier.fillMaxWidth().padding(top = if (compact) TrailSpace.xs else TrailSpace.md, bottom = TrailSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
        // Multicolour artwork: draw as an image, never tinted (a tint flattens it into a solid square).
        Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.size(if (compact) TrailSpace.xxl else TrailSize.chip))
        Spacer(Modifier.width(TrailSpace.md))
        Column(Modifier.weight(1f)) {
            Text(strings(R.string.app_name), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Row(verticalAlignment = Alignment.CenterVertically) {
                contextIcon?.let { Icon(painterResource(it), null, Modifier.padding(end = TrailSpace.xs).size(TrailSize.iconSmall), tint = MaterialTheme.colorScheme.primary) }
                Text(context, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FilledTonalIconButton(onClick = onSettings, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Icon(painterResource(R.drawable.ic_settings), contentDescription = strings(R.string.settings))
        }
    }
}

/**
 * Floating tab bar. A highlight pill springs between tabs; press and slide across the bar to move it with your
 * finger, iOS style, with a tick on each tab you cross. Lifting your finger opens the tab under it.
 * Items are equal width and never change structure, which keeps the slide smooth (and avoids a Compose crash
 * that structural changes caused on rapid switching).
 */
/** Landscape navigation: the same pill style as the bottom bar, stacked vertically on the left edge. */
@Composable
private fun TabRail(selected: Int, overCamera: Boolean, onSelect: (Int) -> Unit, modifier: Modifier) {
    val strings = rememberTrailStrings()
    val tabs = listOf(strings(R.string.camera) to R.drawable.ic_photo_camera, strings(R.string.home) to R.drawable.ic_home, strings(R.string.peaks) to R.drawable.ic_landscape, strings(R.string.regions) to R.drawable.ic_map)
    val pill = if (overCamera) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val onPill = if (overCamera) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge, color = if (overCamera) Color.Black.copy(alpha = .55f) else MaterialTheme.colorScheme.inverseSurface,
        contentColor = Color.White, shadowElevation = if (overCamera) TrailSpace.none else TrailSpace.md) {
        Column(Modifier.padding(TrailSpace.xs), verticalArrangement = Arrangement.spacedBy(TrailSpace.xxs)) {
            tabs.forEachIndexed { index, (label, icon) -> key(label) {
                val active = index == selected
                val tint by androidx.compose.animation.animateColorAsState(if (active) onPill else Color.White.copy(alpha = .72f), label = "rail")
                Column(Modifier.size(TrailSize.tabWidth, TrailSize.tabHeight).clip(MaterialTheme.shapes.large)
                    .background(if (active) pill else Color.Transparent).clickable { onSelect(index) }
                    .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = active; contentDescription = label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(painterResource(icon), null, Modifier.size(TrailSize.icon), tint = tint)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
                }
            } }
        }
    }
}

@Composable
private fun FloatingTabBar(selected: Int, overCamera: Boolean, onSelect: (Int) -> Unit, modifier: Modifier) {
    val strings = rememberTrailStrings()
    val tabs = listOf(strings(R.string.camera) to R.drawable.ic_photo_camera, strings(R.string.home) to R.drawable.ic_home, strings(R.string.peaks) to R.drawable.ic_landscape, strings(R.string.regions) to R.drawable.ic_map)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val windowWidth = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp() }
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelWidth = with(density) { tabs.maxOf { measurer.measure(it.first, MaterialTheme.typography.labelSmall).size.width }.toDp() }
    val itemWidth = minOf(maxOf(TrailSize.tabWidth, labelWidth + TrailSpace.lg), (windowWidth - TrailSpace.xxl) / tabs.size)
    val itemHeight = TrailSize.tabHeight
    val itemPx = with(androidx.compose.ui.platform.LocalDensity.current) { itemWidth.toPx() }
    val maxPx = itemPx * (tabs.size - 1)
    val scope = rememberCoroutineScope()
    val indicator = remember { androidx.compose.animation.core.Animatable(selected * itemPx) }
    val glide = androidx.compose.animation.core.spring<Float>(dampingRatio = .78f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
    var dragging by remember { mutableStateOf(false) }
    var hover by remember { mutableIntStateOf(selected) }
    val currentSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(selected, itemPx) { if (!dragging) { hover = selected; indicator.animateTo(selected * itemPx, glide) } }
    val lift by androidx.compose.animation.core.animateFloatAsState(if (dragging) 1.08f else 1f, label = "lift")
    val pill = if (overCamera) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    val onPill = if (overCamera) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
    // Over the camera the bar turns translucent so the scene shows through; elsewhere it is solid forest green.
    Surface(modifier, shape = CircleShape, color = if (overCamera) TrailCameraColors.scrim else MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = if (overCamera) Color.White else MaterialTheme.colorScheme.onSurface, shadowElevation = if (overCamera) TrailElevation.none else TrailElevation.floating) {
        Box(Modifier.padding(TrailSpace.sm).size(itemWidth * tabs.size, itemHeight)
            .pointerInput(itemPx) {
                fun indexAt(x: Float) = (x / itemPx).toInt().coerceIn(0, tabs.size - 1)
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        dragging = true
                        hover = indexAt(start.x)
                        scope.launch { indicator.animateTo((start.x - itemPx / 2).coerceIn(0f, maxPx), androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessHigh)) }
                    },
                    onDragEnd = {
                        dragging = false
                        val target = hover
                        scope.launch { indicator.animateTo(target * itemPx, glide) }
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                        select(target)
                    },
                    onDragCancel = { dragging = false; hover = currentSelected; scope.launch { indicator.animateTo(currentSelected * itemPx, glide) } },
                ) { change, dx ->
                    change.consume()
                    val x = (indicator.value + dx).coerceIn(0f, maxPx)
                    scope.launch { indicator.snapTo(x) }
                    val under = indexAt(x + itemPx / 2)
                    if (under != hover) { hover = under; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) }
                }
            }) {
            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(indicator.value.roundToInt(), 0) }.size(itemWidth, itemHeight)
                .graphicsLayer { scaleX = lift; scaleY = lift }.clip(CircleShape).background(pill))
            Row {
                tabs.forEachIndexed { index, (label, icon) -> key(label) {
                    val lit = index == (if (dragging) hover else selected)
                    val tint by androidx.compose.animation.animateColorAsState(if (lit) onPill else if (overCamera) Color.White.copy(alpha = .85f) else MaterialTheme.colorScheme.onSurfaceVariant, label = "tint")
                    Column(Modifier.size(itemWidth, itemHeight).clip(CircleShape)
                        .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { onSelect(index) }
                        .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = index == selected; contentDescription = label },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(painterResource(icon), contentDescription = null, Modifier.size(TrailSize.icon), tint = tint)
                        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Medium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } }
            }
        }
    }
}

/** Slider with a round thumb on a thin track, closer to iOS than Material's tall bar thumb. The thumb grows slightly while held. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrailSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier,
                         onValueChangeFinished: (() -> Unit)? = null) {
    val strings = rememberTrailStrings()
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val held by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val grow by androidx.compose.animation.core.animateFloatAsState(if (held || pressed) 1.15f else 1f, label = "thumb")
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    Slider(value, onValueChange, modifier, valueRange = valueRange, onValueChangeFinished = onValueChangeFinished, interactionSource = interaction,
        thumb = {
            Box(Modifier.size(TrailSize.sliderThumb).graphicsLayer { scaleX = grow; scaleY = grow }
                .shadow(TrailElevation.thumb, CircleShape).background(active, CircleShape).border(TrailStroke.hairline, inactive, CircleShape))
        },
        track = { sliderState ->
            Canvas(Modifier.fillMaxWidth().height(TrailSpace.xs)) {
                val span = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                val fraction = if (span > 0f) ((sliderState.value - sliderState.valueRange.start) / span).coerceIn(0f, 1f) else 0f
                val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                drawRoundRect(inactive, cornerRadius = radius)
                drawRoundRect(active, size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height), cornerRadius = radius)
            }
        })
}

@Composable
private fun ObservationStatus(state: PanoramaState, cameraMode: Boolean, cameraFov: Pair<Double, Double>?, compact: Boolean, onGps: () -> Unit, onCompass: () -> Unit, startExpanded: Boolean = false, detailsOnly: Boolean = false) {
    val strings = rememberTrailStrings()
    val context = LocalContext.current
    val fix = state.gpsFix
    val heading = if (cameraMode) state.cameraPose?.heading else state.heading
    val bearing = heading?.let { strings(R.string.bearing_with_cardinal , it.toInt().toString().padStart(3, '0'), cardinal(it, strings)) } ?: strings(R.string.bearing_unknown)
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    val gpsSummary = when {
        state.isDemo -> strings(R.string.demo_viewpoint)
        !state.followingGps -> strings(R.string.location_off)
        state.gpsStatus != null -> state.gpsStatus
        else -> strings(R.string.finding_your_location)
    }
    val directionSummary = if (state.followingCompass) strings(R.string.following_your_phone) else strings(R.string.drag_to_turn)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(start = TrailSpace.lg, end = TrailSpace.xs, top = if (compact) TrailSpace.xxs else TrailSpace.sm, bottom = if (compact) TrailSpace.xxs else TrailSpace.sm)) {
            if (!detailsOnly) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bearing, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(directionSummary, gpsSummary).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (cameraMode && !state.followingGps) TextButton(onClick = onGps, contentPadding = PaddingValues(horizontal = TrailSpace.sm)) { Text(strings(R.string.locate)) }
                if (cameraMode && !state.followingCompass) TextButton(onClick = onCompass, contentPadding = PaddingValues(horizontal = TrailSpace.sm)) { Text(strings(R.string.compass)) }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = if (expanded) strings(R.string.hide_details) else strings(R.string.show_details), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.compassStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            AnimatedVisibility(expanded || detailsOnly) {
                Column(Modifier.padding(end = TrailSpace.md, top = TrailSpace.xs, bottom = TrailSpace.sm), verticalArrangement = Arrangement.spacedBy(TrailSpace.xxs)) {
                    val small = MaterialTheme.typography.bodySmall
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(if (state.followingCompass) strings(R.string.direction_from_your , state.compassSource ?: strings(R.string.compass_2)) else strings(R.string.direction_compass_off_drag_to_turn), style = small, color = muted)
                    if (fix != null) {
                        Text(strings(R.string.your_location , coordinate(fix.point.latitude), coordinate(fix.point.longitude)), style = small, color = muted)
                        val altitude = fix.seaLevelAltitudeM ?: fix.ellipsoidAltitudeM
                        Text(altitude?.let { strings(R.string.altitude , height(it, state, strings), fix.verticalAccuracyM?.let { a -> strings(R.string.vertical_accuracy , kotlin.math.ceil(a.toDouble()).toInt().coerceAtLeast(1)) } ?: "") } ?: strings(R.string.altitude_not_available), style = small, color = muted)
                    } else Text(strings(R.string.your_location_finding), style = small, color = muted)
                    if (!state.isDemo) state.scene?.observer?.let { Text(strings(R.string.ground_height , height(it.groundHeightM, state, strings)), style = small, color = muted) }
                    if (!cameraMode) Text(strings(R.string.viewing_from , coordinate(state.point.latitude), coordinate(state.point.longitude), if (state.isDemo) strings(R.string.demo_spot) else if (state.followingGps) strings(R.string.your_location_2) else strings(R.string.chosen_spot)), style = small, color = muted)
                    if (cameraMode) cameraFov?.let { Text(strings(R.string.camera_view_wide_tall , it.first.toInt(), it.second.toInt()), style = small, color = muted) }
                    if (state.gpsStatus == strings(R.string.location_is_off_turn_it_on_in_android_settings)) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }, contentPadding = PaddingValues(TrailSpace.none)) { Text(strings(R.string.open_location_settings)) }
                }
            }
        }
    }
}

@Composable
private fun PanoramaPanel(state: PanoramaState, model: PanoramaViewModel, modifier: Modifier, onRegions: () -> Unit, onGps: () -> Unit,
                          onViewpoint: () -> Unit, onPeak: (PeakView) -> Unit) {
    val strings = rememberTrailStrings()
    var info by rememberSaveable { mutableStateOf(false) }
    // The canvas is recorded into a layer so Share can save exactly what is on screen.
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Card(modifier, shape = MaterialTheme.shapes.large) {
        Box(Modifier.fillMaxSize()) {
            // Drag turns the view, pinch zooms (replaces the old Width slider).
            PanoramaCanvas(state, Modifier.fillMaxSize().drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }, model::pan, onPeak, onZoom = model::zoomBy,
                compassStartInset = if (state.scene != null) TrailSize.touchTarget else TrailSpace.none)
            // Small, quiet "what am I looking at?" in the top-left corner, beside the direction strip.
            if (state.scene != null) IconButton(onClick = { info = true }, modifier = Modifier.align(Alignment.TopStart).size(TrailSize.touchTarget)) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = strings(R.string.home_info_title),
                    modifier = Modifier.size(TrailSize.iconSmall), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .8f))
            }
            // Map-style round buttons instead of chips: locate me, follow the phone's direction, look from another spot.
            if (state.scene != null || !state.isDemo && !state.noRegion) Column(Modifier.align(Alignment.BottomEnd).padding(end = TrailSpace.sm, bottom = TrailCanvas.footerClearance + TrailSpace.xs),
                verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                if (state.scene != null) MapButton(R.drawable.ic_share, strings(R.string.share_view), active = false) {
                    Telemetry.event("share_view")
                    val caption = listOf(strings(R.string.app_name), currentPlace(state, strings), strings(R.string.bearing_with_cardinal, state.heading.toInt(), cardinal(state.heading, strings)),
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())).joinToString(" · ")
                    scope.launch { runCatching { shareView(context, layer.toImageBitmap(), caption) }.onFailure { Telemetry.error("share view", it) } }
                }
                if (!state.isDemo) MapButton(R.drawable.ic_pin_drop, strings(R.string.choose_spot), active = !state.followingGps && !state.isDemo, onClick = onViewpoint)
                if (!state.isDemo) MapButton(R.drawable.ic_explore, strings(R.string.follow_phone), active = state.followingCompass, onClick = model::toggleCompass)
                if (!state.isDemo) MapButton(R.drawable.ic_my_location, strings(R.string.use_my_location_chip), active = state.followingGps, onClick = onGps)
            }
            if (state.calculating && state.scene == null) Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(TrailSpace.lg), horizontalArrangement = Arrangement.spacedBy(TrailSpace.md), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(TrailSize.icon), strokeWidth = TrailStroke.normal)
                    Text(strings(R.string.tracing_the_ridgelines), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!state.calculating && state.scene == null) Column(Modifier.align(Alignment.Center).padding(TrailSpace.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TrailSpace.md)) {
                if (state.noRegion) {
                    Text(strings(R.string.pick_a_hiking_area_to_see_the_mountains_around_you), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onRegions) { Text(strings(R.string.choose_region)) }
                } else Text(strings(R.string.this_spot_is_outside_the_hiking_area_choose_a_spot_inside_it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (info) HomeInfoDialog { info = false }
}

/** Plain-language key to the Home skyline: what it is and what each part means. */
@Composable
private fun HomeInfoDialog(onDismiss: () -> Unit) {
    val strings = rememberTrailStrings()
    AlertDialog(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        icon = { Icon(painterResource(R.drawable.ic_landscape), null) },
        title = { Text(strings(R.string.home_info_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TrailSpace.md)) {
                Text(strings(R.string.home_info_intro), style = MaterialTheme.typography.bodyMedium)
                listOf(R.string.home_info_strip_title to R.string.home_info_strip, R.string.home_info_lines_title to R.string.home_info_lines,
                    R.string.home_info_labels_title to R.string.home_info_labels, R.string.home_info_controls_title to R.string.home_info_controls).forEach { (title, body) ->
                    Column(verticalArrangement = Arrangement.spacedBy(TrailSpace.xxs)) {
                        Text(strings(title), style = MaterialTheme.typography.titleSmall)
                        Text(strings(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings(R.string.got_it)) } })
}

/** Round floating button in the style of map apps; filled when its mode is on. */
@Composable
private fun MapButton(icon: Int, description: String, active: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, shadowElevation = TrailSpace.xs,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(TrailSize.touchTarget).semantics { contentDescription = description; selected = active }) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(TrailSize.iconMedium)) }
    }
}

/** A single short note under the skyline, only when something needs attention. */
@Composable
private fun HomeNotes(state: PanoramaState) {
    val strings = rememberTrailStrings()
    val now = rememberMinuteClock()
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }  // follows the phone's 12/24-hour setting
    // Terrain-aware: when the sun actually drops behind (or clears) the ridges around this spot, not the flat horizon.
    val sunNote = remember(state.scene, now / 300_000) {
        val scene = state.scene ?: return@remember null
        val skyline = com.harsh.peakbud.core.alignment.TerrainSkyline(scene.horizon)
        com.harsh.peakbud.core.sky.Sun.nextTerrainEvent(scene.observer.point, now, skyline::elevation)?.let { event ->
            val time = timeFormat.format(java.util.Date(event.atMs))
            strings(if (event.kind == com.harsh.peakbud.core.sky.SunEvent.Kind.SET) R.string.sun_sets_behind_ridge else R.string.sun_clears_ridge, time)
        }
    }
    listOfNotNull(
        state.compassStatus,
        state.scene?.horizon?.count { !it.complete }?.takeIf { it > 0 }?.let { strings(R.string.some_directions_have_no_map_data_so_peaks_there_may_be_missing) },
        sunNote,
    ).firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun PeakList(state: PanoramaState, modifier: Modifier, bottomSpace: Dp, onPeak: (PeakView) -> Unit) {
    val strings = rememberTrailStrings()
    val peaks = state.scene?.peaks.orEmpty().sortedWith(compareBy<PeakView> { when (it.visibility) {
        Visibility.TERRAIN_VISIBLE -> 0; Visibility.UNCERTAIN -> 1; Visibility.OCCLUDED -> 2; Visibility.UNKNOWN -> 3
    } }.thenBy { it.distanceM })
    LazyColumn(modifier, contentPadding = PaddingValues(top = TrailSpace.xs, bottom = bottomSpace + TrailSpace.sm), verticalArrangement = Arrangement.spacedBy(TrailSpace.xxs)) {
        if (peaks.isEmpty()) item {
            EmptyState(if (state.calculating && state.scene == null) strings(R.string.finding_nearby_peaks) else if (state.noRegion) strings(R.string.choose_a_hiking_area_in_regions_to_list_its_peaks) else strings(R.string.no_peaks_found_from_this_spot))
        }
        itemsIndexed(peaks, key = { _, it -> it.peak.id }) { index, peak ->
            val tone = when (peak.visibility) {
                Visibility.TERRAIN_VISIBLE -> MaterialTheme.colorScheme.primary
                Visibility.UNCERTAIN -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            // Rows read as one grouped card: only the first and last get outer corners.
            val shape = RoundedCornerShape(topStart = if (index == 0) TrailRadius.large else TrailRadius.small, topEnd = if (index == 0) TrailRadius.large else TrailRadius.small,
                bottomStart = if (index == peaks.lastIndex) TrailRadius.large else TrailRadius.small, bottomEnd = if (index == peaks.lastIndex) TrailRadius.large else TrailRadius.small)
            Surface(onClick = { onPeak(peak) }, shape = shape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = TrailSpace.lg, vertical = TrailSpace.md), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TrailSpace.xxs)) {
                        Text(peak.peak.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(
                            peak.peak.publishedHeightM?.let { height(it, state, strings) } ?: peak.geometryHeightM?.let { strings(R.string.approximate_height, height(it, state, strings)) },
                            distance(peak.distanceM, state, strings),
                            peak.elevationDeg?.let { angleText(it, strings) },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(TrailSpace.md))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(TrailSpace.xs)) {
                        Text(strings(R.string.bearing_with_cardinal , peak.bearingDeg.toInt(), cardinal(peak.bearingDeg, strings)), style = MaterialTheme.typography.labelLarge)
                        StatusPill(when (peak.visibility) {
                            Visibility.TERRAIN_VISIBLE -> strings(R.string.in_view); Visibility.UNCERTAIN -> strings(R.string.maybe)
                            Visibility.OCCLUDED -> strings(R.string.hidden); Visibility.UNKNOWN -> strings(R.string.no_data)
                        }, tone)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, tone: Color) {
    val strings = rememberTrailStrings()
    Surface(shape = CircleShape, color = tone.copy(alpha = .12f), contentColor = tone) {
        Text(text, Modifier.padding(horizontal = TrailSpace.sm, vertical = TrailSpace.xxs), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun EmptyState(text: String) {
    val strings = rememberTrailStrings()
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Text(text, Modifier.padding(TrailSpace.xl), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val strings = rememberTrailStrings()
    Text(text.uppercase(), modifier.padding(start = TrailSpace.xs, top = TrailSpace.sm, bottom = TrailSpace.xxs), style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Regions(state: PanoramaState, modifier: Modifier, bottomSpace: Dp, onDownload: (CatalogRegion) -> Unit, onSelect: (RegionPack?) -> Unit, onDelete: (RegionPack) -> Unit, onAttribution: (RegionPack) -> Unit, onCancel: () -> Unit,
                    onDiscard: (CatalogRegion) -> Unit) {
    val strings = rememberTrailStrings()
    var query by rememberSaveable { mutableStateOf("") }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun matches(vararg fields: String?) = words.all { word -> fields.any { it?.lowercase()?.contains(word) == true } }
    fun installedFor(region: CatalogRegion) = state.packs.lastOrNull { it.id == region.id && it.version == region.version }
    // Only small areas are offered; state-wide packs remain listed only if already installed.
    val catalog = state.catalog.filter { (it.offered || installedFor(it) != null) && matches(it.name, it.country, it.description) }
    val here = state.gpsFix?.point
    // With a location, list areas closest first and show how far each one is.
    val distances = here?.let { point -> Vicinity.suggestions(point, catalog, catalog.size).associate { it.region.id to it.distanceM } }.orEmpty()
    val (onDevice, unsorted) = catalog.partition { installedFor(it) != null }
    val available = if (distances.isEmpty() || words.isNotEmpty()) unsorted else unsorted.sortedBy { distances[it.id] }
    val nearby = state.nearby?.takeIf { plan -> words.isEmpty() && state.packs.none { it.id == plan.id } }
    val legacy = state.packs.filter { pack -> state.catalog.none { it.id == pack.id && it.version == pack.version } && matches(pack.name) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = CircleShape,
            placeholder = { Text(strings(R.string.search_keywords)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), contentDescription = strings(R.string.clear_search)) } },
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TrailSpace.sm), contentPadding = PaddingValues(bottom = bottomSpace + TrailSpace.sm)) {
            state.importing?.let {
                item {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.padding(TrailSpace.lg), verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = TrailSpace.sm)) { Text(strings(R.string.cancel)) }
                            }
                        }
                    }
                }
            }
            if (catalog.isEmpty() && legacy.isEmpty()) item { EmptyState(strings(R.string.no_hiking_areas_match , query)) }
            val nearbyUnavailable = here != null && words.isEmpty() && nearby == null && state.packs.none { it.id.startsWith("nearby-") }
            if (nearby != null || nearbyUnavailable) item { SectionLabel(strings(R.string.near_you)) }
            nearby?.let { plan ->
                item(key = "nearby") {
                    val downloading = state.downloadingRegionId == plan.id
                    val saved = state.pendingDownloads.any { transfer -> plan.tiles.any { it.url == transfer.address } }
                    RegionCard(plan.name, strings(R.string.nearby_area_subtitle),
                        strings(R.string.region_download_storage_range, storageSize(plan.downloadBytes, strings), storageSize(plan.installedBytes, strings), distance(Vicinity.RADIUS_M, state, strings)),
                        if (downloading) strings(R.string.downloading) else if (saved) strings(R.string.paused) else strings(R.string.you_are_here), MaterialTheme.colorScheme.primary, highlighted = true) {
                        when {
                            downloading -> CircularProgressIndicator(Modifier.padding(TrailSpace.md).size(TrailSize.icon), strokeWidth = TrailStroke.progress)
                            else -> Button(enabled = state.importing == null, onClick = { onDownload(plan) }, contentPadding = PaddingValues(horizontal = TrailSpace.lg)) {
                                Text(strings(if (saved) R.string.resume else R.string.download_action))
                            }
                        }
                    }
                }
            }
            if (nearbyUnavailable) item {
                Text(strings(R.string.nearby_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = TrailSpace.xs))
            }
            if (onDevice.isNotEmpty() || legacy.isNotEmpty()) item { SectionLabel(strings(R.string.downloaded)) }
            items(onDevice, key = { it.id }) { region ->
                val installed = installedFor(region)!!
                val active = installed.key == state.activePack?.key
                RegionCard(region.name, strings(R.string.region_subtitle, region.country, region.description), strings(R.string.peaks_up_to_km_away , distance(region.radiusKm * 1000.0, state, strings)), if (active) strings(R.string.in_use) else strings(R.string.ready),
                    MaterialTheme.colorScheme.primary, highlighted = active,
                    menu = { close ->
                        DropdownMenuItem(text = { Text(strings(R.string.map_credits)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) }, onClick = { close(); onAttribution(installed) })
                        DropdownMenuItem(text = { Text(strings(R.string.delete_2)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) }, enabled = state.importing == null, onClick = { close(); onDelete(installed) })
                    }) {
                    if (active) FilledTonalButton(onClick = { onSelect(installed) }, contentPadding = PaddingValues(horizontal = TrailSpace.lg)) { Text(strings(R.string.open)) }
                    else Button(onClick = { onSelect(installed) }, contentPadding = PaddingValues(horizontal = TrailSpace.lg)) { Text(strings(R.string.use)) }
                }
            }
            items(legacy, key = { it.key }) { pack ->
                val active = pack.key == state.activePack?.key
                RegionCard(pack.name, null, null, if (active) strings(R.string.in_use) else strings(R.string.ready), MaterialTheme.colorScheme.primary, highlighted = active,
                    menu = { close ->
                        DropdownMenuItem(text = { Text(strings(R.string.map_credits)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_info), null) }, onClick = { close(); onAttribution(pack) })
                        DropdownMenuItem(text = { Text(strings(R.string.delete_2)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) }, enabled = state.importing == null, onClick = { close(); onDelete(pack) })
                    }) {
                    if (active) FilledTonalButton(onClick = { onSelect(pack) }, contentPadding = PaddingValues(horizontal = TrailSpace.lg)) { Text(strings(R.string.open)) }
                    else Button(onClick = { onSelect(pack) }, contentPadding = PaddingValues(horizontal = TrailSpace.lg)) { Text(strings(R.string.use)) }
                }
            }
            if (available.isNotEmpty()) item { SectionLabel(if (words.isNotEmpty()) strings(R.string.results)
                else if (distances.isNotEmpty()) strings.quantity(R.plurals.explore_area_count, available.size, available.size) + " · " + strings(R.string.closest_first)
                else strings.quantity(R.plurals.explore_area_count, available.size, available.size)) }
            items(available, key = { it.id }) { region ->
                val saved = state.pendingDownloads.any { transfer -> region.tiles.any { it.url == transfer.address } }
                val downloading = state.downloadingRegionId == region.id
                val away = distances[region.id]?.let { if (it < 1.0) strings(R.string.you_are_here) else strings(R.string.distance_away, distance(it, state, strings)) }
                RegionCard(region.name, listOfNotNull(away, strings(R.string.region_subtitle, region.country, region.description)).joinToString(" · "),
                    strings(R.string.region_download_storage_range, storageSize(region.downloadBytes, strings), storageSize(region.installedBytes, strings), distance(region.radiusKm * 1000.0, state, strings)),
                    if (downloading) strings(R.string.downloading) else if (saved) strings(R.string.paused) else null, MaterialTheme.colorScheme.tertiary,
                    menu = if (saved && !downloading) { close -> DropdownMenuItem(text = { Text(strings(R.string.discard_download)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                        enabled = state.importing == null, onClick = { close(); onDiscard(region) }) } else null) {
                    when {
                        downloading -> CircularProgressIndicator(Modifier.padding(TrailSpace.md).size(TrailSize.icon), strokeWidth = TrailStroke.progress)
                        saved -> FilledTonalButton(enabled = state.importing == null, onClick = { onDownload(region) }, contentPadding = PaddingValues(horizontal = TrailSpace.lg)) { Text(strings(R.string.resume)) }
                        else -> FilledTonalIconButton(enabled = state.importing == null, onClick = { onDownload(region) }) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = strings(R.string.download , region.name))
                        }
                    }
                }
            }
            if (state.preferences.demoMode) item {
                Surface(onClick = { onSelect(null) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
                    border = if (state.isDemo) androidx.compose.foundation.BorderStroke(TrailStroke.selected, MaterialTheme.colorScheme.primary) else null, modifier = Modifier.fillMaxWidth().padding(top = TrailSpace.sm)) {
                    Row(Modifier.padding(TrailSpace.lg), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TrailSpace.lg)) {
                        Icon(painterResource(R.drawable.ic_explore), null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(strings(R.string.demo_landscape), style = MaterialTheme.typography.titleSmall)
                            Text(strings(R.string.a_made_up_view_for_trying_the_controls), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.isDemo) StatusPill(strings(R.string.in_use), MaterialTheme.colorScheme.primary)
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
    val strings = rememberTrailStrings()
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (highlighted) TrailStroke.selected else TrailStroke.thin, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(start = TrailSpace.lg, end = if (menu != null) TrailSpace.xs else TrailSpace.md, top = TrailSpace.lg, bottom = TrailSpace.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TrailSpace.xs)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (meta != null || status != null) FlowRow(Modifier.padding(top = TrailSpace.xxs), horizontalArrangement = Arrangement.spacedBy(TrailSpace.sm), verticalArrangement = Arrangement.spacedBy(TrailSpace.xs)) {
                    status?.let { StatusPill(it, statusTone) }
                    meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Spacer(Modifier.width(TrailSpace.md))
            action()
            menu?.let { items ->
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = strings(R.string.more_options_for , name), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(open, { open = false }, shape = MaterialTheme.shapes.medium, containerColor = MaterialTheme.colorScheme.surface) { items { open = false } }
                }
            }
        }
    }
}

@Composable
private fun ViewpointDialog(state: PanoramaState, onDismiss: () -> Unit, onApply: (GeoPoint, Double?) -> Unit) {
    val strings = rememberTrailStrings()
    var latitude by remember { mutableStateOf(coordinate(state.point.latitude)) }
    var longitude by remember { mutableStateOf(coordinate(state.point.longitude)) }
    var altitude by remember { mutableStateOf(state.groundOverrideM?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(strings(R.string.choose_a_spot)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TrailSpace.md)) {
            Text(strings(R.string.enter_coordinates_as_decimals_like_37_7281_leave_height_empty_and_peakbud_wi), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(latitude, { latitude = it }, label = { Text(strings(R.string.latitude)) }, singleLine = true)
            OutlinedTextField(longitude, { longitude = it }, label = { Text(strings(R.string.longitude)) }, singleLine = true)
            OutlinedTextField(altitude, { altitude = it }, label = { Text(strings(R.string.ground_height_in_metres_optional)) }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val point = runCatching { GeoPoint(latitude.trim().toDouble(), longitude.trim().toDouble()) }.getOrNull()
        val ground = altitude.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (point == null || altitude.isNotBlank() && (ground == null || !ground.isFinite() || ground !in -500.0..10_000.0)) error = strings(R.string.check_the_numbers_latitude_90_to_90_longitude_180_to_180_height_500_to_10_00)
        else onApply(point, ground)
    }) { Text(strings(R.string.show_view)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(strings(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(state: PanoramaState, model: PanoramaViewModel, onTutorial: () -> Unit, onDismiss: () -> Unit) {
    val strings = rememberTrailStrings()
    var phoneHeight by remember { mutableFloatStateOf(state.preferences.phoneHeightM.toFloat()) }
    val prefs = state.preferences
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background) {
        TrailSystemBars(MaterialTheme.colorScheme.background.luminance() > .5f)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = TrailSpace.sheet).padding(bottom = TrailSpace.xl), verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
            Text(strings(R.string.settings), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = TrailSpace.xs))
            SectionLabel(strings(R.string.display))
            SettingsGroup {
                Column(Modifier.padding(TrailSpace.card), verticalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                    Text(strings(R.string.appearance), style = MaterialTheme.typography.bodyLarge)
                    Text(strings(R.string.appearance_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column {
                        ThemeMode.entries.forEach { mode ->
                            val title = strings(when (mode) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
                            Row(Modifier.fillMaxWidth().heightIn(min = TrailSize.touchTarget).selectable(
                                selected = prefs.themeMode == mode, role = Role.RadioButton,
                                onClick = { model.updatePreferences(prefs.copy(themeMode = mode)) }), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = prefs.themeMode == mode, onClick = null)
                                Spacer(Modifier.width(TrailSpace.sm))
                                Text(title, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = TrailSpace.lg), color = MaterialTheme.colorScheme.outlineVariant)
                SettingSwitch(strings(R.string.feet_and_miles), strings(R.string.show_heights_and_distances_in_imperial_units), prefs.useFeet) { model.updatePreferences(prefs.copy(useFeet = it)) }
                HorizontalDivider(Modifier.padding(horizontal = TrailSpace.lg), color = MaterialTheme.colorScheme.outlineVariant)
                SettingSwitch(strings(R.string.show_maybe_visible_peaks), strings(R.string.include_peaks_that_terrain_might_hide), prefs.showUncertain) { model.updatePreferences(prefs.copy(showUncertain = it)) }
            }
            SectionLabel(strings(R.string.help))
            SettingsGroup {
                Row(Modifier.fillMaxWidth().clickable(onClick = onTutorial).padding(horizontal = TrailSpace.lg, vertical = TrailSpace.md), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(strings(R.string.tutorial), style = MaterialTheme.typography.bodyLarge)
                        Text(strings(R.string.tutorial_settings_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionLabel(strings(R.string.demo))
            SettingsGroup {
                SettingSwitch(strings(R.string.demo_mode), strings(R.string.adds_a_made_up_landscape_so_you_can_try_peakbud_without_downloading), prefs.demoMode) { model.updatePreferences(prefs.copy(demoMode = it)) }
            }
            SectionLabel(strings(R.string.privacy))
            SettingsGroup {
                SettingSwitch(strings(R.string.send_crash_reports_and_usage), strings(R.string.helps_fix_problems_your_location_is_never_sent), prefs.shareDiagnostics) { model.updatePreferences(prefs.copy(shareDiagnostics = it)) }
            }
            // Rarely needed: folded away so it doesn't sit beside everyday settings.
            var advanced by rememberSaveable { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { advanced = !advanced }.padding(top = TrailSpace.sm),
                verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(strings(R.string.advanced), Modifier.weight(1f))
                Icon(painterResource(if (advanced) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = strings(if (advanced) R.string.hide_details else R.string.show_details), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedVisibility(advanced) {
                SettingsGroup {
                    SettingSlider(strings(R.string.phone_height), strings(R.string.phone_height_value, phoneHeight), phoneHeight, .5f..3f, { phoneHeight = it }) {
                        model.updatePreferences(prefs.copy(phoneHeightM = phoneHeight.toDouble()))
                    }
                    Text(strings(R.string.standing_height_detail), Modifier.padding(start = TrailSpace.lg, end = TrailSpace.lg, bottom = TrailSpace.lg),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionLabel(strings(R.string.about), Modifier.padding(top = TrailSpace.sm))
            SettingsGroup {
                val context = LocalContext.current
                val email = strings(R.string.support_email)
                Column(Modifier.padding(horizontal = TrailSpace.lg, vertical = TrailSpace.lg)) {
                    Text(strings(R.string.developed_by), style = MaterialTheme.typography.bodyLarge)
                    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                    version?.let { Text(strings(R.string.app_version, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(Modifier.padding(horizontal = TrailSpace.lg), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable {
                    val intent = Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:")).apply {
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(email)); putExtra(Intent.EXTRA_SUBJECT, strings(R.string.support_subject))
                    }
                    runCatching { context.startActivity(intent) }
                }.padding(horizontal = TrailSpace.lg, vertical = TrailSpace.md), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(strings(R.string.report_an_issue), style = MaterialTheme.typography.bodyLarge)
                        Text(strings(R.string.report_an_issue_detail, email), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(strings(R.string.peak_visibility_is_worked_out_from_maps_clouds_trees_location_errors_and_nea),
                Modifier.padding(start = TrailSpace.xs, top = TrailSpace.sm), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) { Column(content = content) }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = TrailSpace.lg, vertical = TrailSpace.lg), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(TrailSpace.md))
        Switch(checked, onChange)
    }
}

@Composable
private fun SettingSlider(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = TrailSpace.lg, end = TrailSpace.lg, top = TrailSpace.lg, bottom = TrailSpace.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        TrailSlider(current, onChange, range, onValueChangeFinished = onDone)
    }
}

/** Where the phone is right now: a place name when online, otherwise coordinates. */
internal fun currentPlace(state: PanoramaState, strings: TrailStrings): String {
    if (state.isDemo) return strings(R.string.demo_landscape)
    val fix = state.gpsFix ?: return if (state.followingGps) strings(R.string.finding_your_location) else strings(R.string.location_off)
    val where = state.placeName ?: strings(R.string.place_coordinates, kotlin.math.abs(fix.point.latitude),
        strings(if (fix.point.latitude >= 0) R.string.n else R.string.s), kotlin.math.abs(fix.point.longitude), strings(if (fix.point.longitude >= 0) R.string.e else R.string.w))
    if (!state.followingGps) return strings(R.string.last_known_place, where)
    // Height of the ground the view is worked out from, so the subtitle reads like a trail sign: "Dhanaulti · 2245 m".
    val ground = state.scene?.observer?.takeIf { Geography.distance(it.point, fix.point) < 500 }?.groundHeightM
    return ground?.let { strings(R.string.place_with_height, where, height(it, state, strings)) } ?: where
}
internal fun angleText(degrees: Double, strings: TrailStrings) = strings(if (degrees >= 0) R.string.angle_up else R.string.angle_down, kotlin.math.abs(degrees))
/** Decimal degrees are also editable input; keep an unambiguous decimal point for parsing. */
internal fun coordinate(value: Double) = String.format(Locale.US, "%.5f", value)
internal fun height(metres: Double, state: PanoramaState, strings: TrailStrings) = strings(if (state.preferences.useFeet) R.string.height_feet else R.string.height_metres,
    (if (state.preferences.useFeet) metres * 3.28084 else metres).toInt())
internal fun distance(metres: Double, state: PanoramaState, strings: TrailStrings) = strings(if (state.preferences.useFeet) R.string.distance_miles else R.string.distance_kilometres,
    if (state.preferences.useFeet) metres / 1609.344 else metres / 1000)
internal fun cardinal(degrees: Double, strings: TrailStrings) = listOf(strings(R.string.n), strings(R.string.ne), strings(R.string.e), strings(R.string.se), strings(R.string.s), strings(R.string.sw), strings(R.string.w), strings(R.string.nw))[(Geography.wrap360(degrees + 22.5) / 45).toInt() % 8]
internal fun visibilityName(value: Visibility, strings: TrailStrings) = when (value) {
    Visibility.TERRAIN_VISIBLE -> strings(R.string.should_be_in_view)
    Visibility.OCCLUDED -> strings(R.string.hidden_behind_a_closer_ridge)
    Visibility.UNCERTAIN -> strings(R.string.might_be_hidden)
    Visibility.UNKNOWN -> strings(R.string.no_map_data_here)
}

internal fun peakReason(peak: PeakView, strings: TrailStrings): String = when (peak.reason) {
    PeakReason.NO_SUMMIT_DATA -> strings(R.string.peak_reason_no_summit)
    PeakReason.HEIGHT_MISMATCH -> strings(R.string.peak_reason_height_difference, peak.heightDifferenceM ?: 0)
    PeakReason.VISIBLE -> strings(R.string.peak_reason_visible)
    PeakReason.OCCLUDED -> strings(R.string.peak_reason_occluded)
    PeakReason.BORDERLINE -> strings(R.string.peak_reason_uncertain)
    PeakReason.MISSING_TERRAIN -> strings(R.string.peak_reason_missing_path)
}

internal fun storageSize(bytes: Long, strings: TrailStrings): String = if (bytes >= 1_000_000_000)
    strings(R.string.storage_gb, bytes / 1_000_000_000.0)
else strings(R.string.storage_mb, (bytes + 999_999) / 1_000_000)

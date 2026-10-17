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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harsh.peakbud.feature.camera.CameraPanel
import com.harsh.peakbud.feature.camera.FullScreenCamera
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cameraMode by rememberSaveable { mutableStateOf(true) }
    var fullScreenCamera by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = fullScreenCamera) { fullScreenCamera = false }
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
        if (state.followingGps && tab == 0) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.refreshFixAge()
            launch { device.fixes().collect(model::gps) }
            launch { while (true) { delay(5000); model.refreshFixAge() } }
        }
    }
    LaunchedEffect(state.followingCompass, rotation, lifecycle, tab, device) {
        if (state.followingCompass && tab == 0) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            device.poses(rotation).collect(model::pose)
        }
    }
    LaunchedEffect(cameraMode, tab, state.activePack?.key, state.initialized) {
        if (cameraMode && tab == 0 && state.initialized) {
            model.startCameraTracking()
            if (!state.followingGps && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) model.startGps()
        }
    }
    LaunchedEffect(state.scene) { selectedPeak = null }
    fun gps() {
        if (state.followingGps) { model.stopGps(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        else model.startGps()
    }
    if (fullScreenCamera) {
        FullScreenCamera(state, { fullScreenCamera = false }, { fullScreenCamera = false; tab = 2 }, ::gps,
            model::startCameraTracking, { cameraFov = it }) { selectedPeak = it }
    } else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Space the floating tab bar occupies above the system navigation bar.
        val barSpace = 84.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp).padding(bottom = if (tab == 0) barSpace else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
            AppHeader(compact, when (tab) {
                1 -> if (state.noRegion) "Peaks · pick a hiking area to start" else if (state.isDemo) "Peaks · demo landscape" else "Peaks within ${(state.activePack?.radiusM ?: 0.0).toInt() / 1000} km of this spot"
                2 -> "Hiking areas · download once, works offline"
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
                0 -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                            SegmentedButton(selected = cameraMode, onClick = { cameraMode = true }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                                icon = { SegmentedButtonDefaults.Icon(cameraMode) { Icon(painterResource(R.drawable.ic_photo_camera), null, Modifier.size(18.dp)) } }) { Text("Camera") }
                            SegmentedButton(selected = !cameraMode, onClick = { cameraMode = false }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                                icon = { SegmentedButtonDefaults.Icon(!cameraMode) { Icon(painterResource(R.drawable.ic_landscape), null, Modifier.size(18.dp)) } }) { Text("Panorama") }
                        }
                        if (cameraMode) FilledTonalIconButton(onClick = { fullScreenCamera = true }) { Icon(painterResource(R.drawable.ic_fullscreen), contentDescription = "Full screen") }
                    }
                    if (!compact) ObservationStatus(state, cameraMode, cameraFov, false, ::gps, model::startCameraTracking)
                    if (cameraMode) {
                        if (compact) {
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CameraPanel(state, Modifier.weight(1f).fillMaxHeight().clip(MaterialTheme.shapes.large), { tab = 2 }, ::gps, model::startCameraTracking, { cameraFov = it }) { selectedPeak = it }
                                Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                    ObservationStatus(state, true, cameraFov, true, ::gps, model::startCameraTracking)
                                    AlignmentControls(model)
                                }
                            }
                        } else {
                            CameraPanel(state, Modifier.weight(1f).fillMaxWidth().clip(MaterialTheme.shapes.large), { tab = 2 }, ::gps, model::startCameraTracking, { cameraFov = it }) { selectedPeak = it }
                            AlignmentControls(model)
                        }
                    } else BoxWithConstraints(Modifier.weight(1f)) {
                        if (maxWidth > maxHeight) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                PanoramaPanel(state, model, Modifier.weight(1f).fillMaxHeight(), { tab = 2 }) { selectedPeak = it }
                                Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (compact) ObservationStatus(state, false, null, true, ::gps, model::startCameraTracking)
                                    ViewControls(state, model, { manual = true }, ::gps)
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PanoramaPanel(state, model, Modifier.fillMaxWidth().weight(1f), { tab = 2 }) { selectedPeak = it }
                                ViewControls(state, model, { manual = true }, ::gps)
                            }
                        }
                    }
                }
                1 -> PeakList(state, Modifier.weight(1f), barSpace) { selectedPeak = it }
                2 -> Regions(state, Modifier.weight(1f), barSpace, model::downloadRegion,
                    { model.selectRegion(it); if (it == null) cameraMode = false; tab = 0 }, { deletePack = it }, { pack -> scope.launch { attribution = runCatching { model.attribution(pack) }.getOrDefault("Credits couldn't be loaded.") } }, model::cancelImport,
                    model::discardDownload)
            }
        }
        FloatingTabBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
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
        }, confirmButton = { TextButton(onClick = { model.pan(Geography.wrap180(peak.bearingDeg - state.heading)); selectedPeak = null; fullScreenCamera = false; cameraMode = false; tab = 0 }) { Text("Show in panorama") } }, dismissButton = { TextButton(onClick = { selectedPeak = null }) { Text("Close") } })
    }
    attribution?.let { text -> AlertDialog(onDismissRequest = { attribution = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("Map and peak credits") }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { attribution = null }) { Text("Done") } }) }
    deletePack?.let { pack -> AlertDialog(onDismissRequest = { deletePack = null }, containerColor = MaterialTheme.colorScheme.surface, title = { Text("Delete ${pack.name}?") }, text = { Text("Its maps and peak names will be removed from this phone. You can download it again any time.") }, confirmButton = { TextButton(onClick = { model.deleteRegion(pack); deletePack = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletePack = null }) { Text("Keep") } }) }
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
private fun FloatingTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val tabs = listOf("Camera" to R.drawable.ic_photo_camera, "Peaks" to R.drawable.ic_landscape, "Regions" to R.drawable.ic_map)
    Surface(modifier, shape = CircleShape, color = Color(0xFF16352A), contentColor = Color.White, shadowElevation = 12.dp) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, (label, icon) ->
                val active = index == selected
                Row(Modifier.clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(index) }
                    .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = active; contentDescription = label }
                    .animateContentSize()
                    .padding(horizontal = if (active) 18.dp else 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp),
                        tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer else Color.White.copy(alpha = .72f))
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlignmentControls(model: PanoramaViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Nudge labels", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { model.trimCompass(-1.0) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("← 1°") }
                TextButton(onClick = { model.trimCompass(1.0) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("1° →") }
                TextButton(onClick = model::resetTrim, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun ObservationStatus(state: PanoramaState, cameraMode: Boolean, cameraFov: Pair<Double, Double>?, compact: Boolean, onGps: () -> Unit, onCompass: () -> Unit) {
    val context = LocalContext.current
    val fix = state.gpsFix
    val heading = if (cameraMode) state.cameraPose?.heading else state.heading
    val bearing = heading?.let { "${it.toInt().toString().padStart(3, '0')}° ${cardinal(it)}" } ?: "---°"
    var expanded by rememberSaveable { mutableStateOf(false) }
    val gpsSummary = when {
        !state.followingGps -> "Location off"
        state.gpsStatus != null -> state.gpsStatus
        else -> "Finding your location…"
    }
    val directionSummary = if (state.followingCompass) "Following your phone" else "Drag to turn"
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = if (compact) 2.dp else 6.dp, bottom = if (compact) 2.dp else 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            AnimatedVisibility(expanded) {
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

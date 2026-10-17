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
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
    } else Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            listOf("Camera" to R.drawable.ic_photo_camera, "Peaks" to R.drawable.ic_landscape, "Regions" to R.drawable.ic_map).forEachIndexed { index, (name, icon) ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    icon = { Icon(painterResource(icon), contentDescription = null) }, label = { Text(name) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = if (compact) 0.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(when (tab) { 1 -> "Peaks"; 2 -> "Hiking areas"; else -> "PeakBud" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    if (!compact) Text(when (tab) {
                        1 -> if (state.isDemo) "Fictional peaks · demo" else "Line of sight within ${(state.activePack?.radiusM ?: 0.0).toInt() / 1000} km"
                        2 -> "Download once · works offline on the trail"
                        else -> state.activePack?.name?.let { "$it · offline" } ?: "Explorer demo · fictional terrain"
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { settings = true }) { Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.message?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                CameraPanel(state, Modifier.weight(1f).fillMaxHeight(), { tab = 2 }, ::gps, model::startCameraTracking, { cameraFov = it }) { selectedPeak = it }
                                Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                    ObservationStatus(state, true, cameraFov, true, ::gps, model::startCameraTracking)
                                    AlignmentControls(model)
                                }
                            }
                        } else {
                            CameraPanel(state, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)), { tab = 2 }, ::gps, model::startCameraTracking, { cameraFov = it }) { selectedPeak = it }
                            AlignmentControls(model)
                        }
                    } else BoxWithConstraints(Modifier.weight(1f)) {
                        if (maxWidth > maxHeight) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                PanoramaPanel(state, model, Modifier.weight(1f).fillMaxHeight()) { selectedPeak = it }
                                Column(Modifier.width(270.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                    if (compact) ObservationStatus(state, false, null, true, ::gps, model::startCameraTracking)
                                    ViewControls(state, model, { manual = true }, ::gps)
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PanoramaPanel(state, model, Modifier.fillMaxWidth().weight(1f)) { selectedPeak = it }
                                ViewControls(state, model, { manual = true }, ::gps)
                            }
                        }
                    }
                }
                1 -> PeakList(state, Modifier.weight(1f)) { selectedPeak = it }
                2 -> Regions(state, Modifier.weight(1f), model::downloadRegion,
                    { model.selectRegion(it); if (it == null) cameraMode = false; tab = 0 }, { deletePack = it }, { pack -> scope.launch { attribution = runCatching { model.attribution(pack) }.getOrDefault("Credits unavailable") } }, model::cancelImport,
                    model::discardDownload)
            }
        }
    }
    if (manual) ViewpointDialog(state, { manual = false }) { point, ground -> model.manualViewpoint(point, ground); manual = false }
    if (settings) SettingsDialog(state, model) { settings = false }
    selectedPeak?.let { peak ->
        AlertDialog(onDismissRequest = { selectedPeak = null }, title = { Text(peak.peak.name) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(peak.peak.publishedHeightM?.let { height(it, state) } ?: "Published elevation unavailable", style = MaterialTheme.typography.headlineSmall)
                Text("${distance(peak.distanceM, state)} away · ${peak.bearingDeg.toInt()}° ${cardinal(peak.bearingDeg)}")
                peak.elevationDeg?.let { Text("Elevation angle: ${String.format(Locale.US, "%.1f", it)}° above the horizon") }
                Text(visibilityName(peak.visibility), fontWeight = FontWeight.Bold)
                Text(peak.reason)
                Text("${coordinate(peak.peak.point.latitude)}, ${coordinate(peak.peak.point.longitude)}\nSource: ${peak.peak.source}", style = MaterialTheme.typography.bodySmall)
                peak.geometryHeightM?.let { Text("Terrain height: ${height(it, state)}. Published summit elevation may differ.", style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = { model.pan(Geography.wrap180(peak.bearingDeg - state.heading)); selectedPeak = null; fullScreenCamera = false; cameraMode = false; tab = 0 }) { Text("Look toward peak") } }, dismissButton = { TextButton(onClick = { selectedPeak = null }) { Text("Close") } })
    }
    attribution?.let { text -> AlertDialog(onDismissRequest = { attribution = null }, title = { Text("Data credits") }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { attribution = null }) { Text("Done") } }) }
    deletePack?.let { pack -> AlertDialog(onDismissRequest = { deletePack = null }, title = { Text("Delete ${pack.name}?") }, text = { Text("Its offline terrain and peak catalogue will be removed from this device.") }, confirmButton = { TextButton(onClick = { model.deleteRegion(pack); deletePack = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletePack = null }) { Text("Keep") } }) }
}

@Composable
private fun AlignmentControls(model: PanoramaViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("Align labels", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { model.trimCompass(-1.0) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("−1°") }
        TextButton(onClick = { model.trimCompass(1.0) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("+1°") }
        TextButton(onClick = model::resetTrim, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Reset") }
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
        !state.followingGps -> "GPS off"
        state.gpsStatus != null -> state.gpsStatus
        else -> "Waiting for GPS…"
    }
    val directionSummary = if (state.followingCompass) (if (fix != null || !state.isDemo) "True north" else "Magnetic north") else "Manual direction"
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = if (compact) 2.dp else 6.dp, bottom = if (compact) 2.dp else 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(bearing, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(listOfNotNull(directionSummary, gpsSummary).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!state.followingGps) TextButton(onClick = onGps, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("GPS") }
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
                    Text(if (state.followingCompass) "Direction · ${state.compassSource ?: "waiting for compass"}" else "Direction · compass off, set by hand", style = small, color = muted)
                    if (fix != null) {
                        Text("Position · ${coordinate(fix.point.latitude)}, ${coordinate(fix.point.longitude)}", style = small, color = muted)
                        val altitude = fix.seaLevelAltitudeM ?: fix.ellipsoidAltitudeM
                        Text(altitude?.let { "Altitude · ${height(it, state)} GPS ${if (fix.seaLevelAltitudeM != null) "sea level" else "ellipsoid"}${fix.verticalAccuracyM?.let { a -> " ±${kotlin.math.ceil(a.toDouble()).toInt().coerceAtLeast(1)} m" } ?: ""}" } ?: "Altitude · unavailable", style = small, color = muted)
                    } else Text("Position · waiting for GPS", style = small, color = muted)
                    if (!state.isDemo) state.scene?.observer?.let { Text("Ground · ${height(it.groundHeightM, state)} from terrain model", style = small, color = muted) }
                    if (!cameraMode) Text("Viewpoint · ${coordinate(state.point.latitude)}, ${coordinate(state.point.longitude)} (${if (state.isDemo) "fictional" else if (state.followingGps) "GPS" else "manual"})", style = small, color = muted)
                    if (cameraMode) cameraFov?.let { Text("Camera view · ${it.first.toInt()}° wide × ${it.second.toInt()}° tall", style = small, color = muted) }
                    if (state.gpsStatus?.contains("turned off") == true) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }, contentPadding = PaddingValues(0.dp)) { Text("Open location settings") }
                }
            }
        }
    }
}

@Composable
private fun PanoramaPanel(state: PanoramaState, model: PanoramaViewModel, modifier: Modifier, onPeak: (PeakView) -> Unit) {
                    Card(modifier, shape = RoundedCornerShape(24.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            PanoramaCanvas(state, Modifier.fillMaxSize(), model::pan, onPeak)
                            if (state.calculating) Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(16.dp)) {
                                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    Text("Tracing the ridgelines…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (!state.calculating && state.scene == null) Text("Choose a covered viewpoint to see the skyline", Modifier.align(Alignment.Center).padding(24.dp))
                        }
                    }
}

@Composable
private fun ViewControls(state: PanoramaState, model: PanoramaViewModel, onViewpoint: () -> Unit, onGps: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = state.followingGps, onClick = onGps, label = { Text("GPS") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_my_location), null, Modifier.size(18.dp)) })
            FilterChip(selected = state.followingCompass, onClick = model::toggleCompass, label = { Text("Compass") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_explore), null, Modifier.size(18.dp)) })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onViewpoint, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Viewpoint") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("View", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(state.fov.toFloat(), { model.setFov(it.toDouble()) }, valueRange = 35f..120f, modifier = Modifier.weight(1f))
            Text("${state.fov.toInt()}°", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(36.dp))
        }
        listOfNotNull(
            state.compassStatus,
            state.scene?.horizon?.count { !it.complete }?.takeIf { it > 0 }?.let { "Some directions have missing terrain." },
        ).firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun PeakList(state: PanoramaState, modifier: Modifier, onPeak: (PeakView) -> Unit) {
    val peaks = state.scene?.peaks.orEmpty().sortedWith(compareBy<PeakView> { when (it.visibility) {
        Visibility.TERRAIN_VISIBLE -> 0; Visibility.UNCERTAIN -> 1; Visibility.OCCLUDED -> 2; Visibility.UNKNOWN -> 3
    } }.thenBy { it.distanceM })
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            if (peaks.isEmpty()) item {
                Text(if (state.calculating) "Calculating nearby peaks…" else "No peaks at this viewpoint.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(peaks, key = { _, it -> it.peak.id }) { index, peak ->
                val tone = when (peak.visibility) {
                    Visibility.TERRAIN_VISIBLE -> MaterialTheme.colorScheme.primary
                    Visibility.UNCERTAIN -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(Modifier.fillMaxWidth().clickable { onPeak(peak) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(peak.peak.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(
                            peak.peak.publishedHeightM?.let { height(it, state) } ?: peak.geometryHeightM?.let { "~${height(it, state)}" },
                            distance(peak.distanceM, state),
                            peak.elevationDeg?.let { "${String.format(Locale.US, "%.1f", it)}° up" },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${peak.bearingDeg.toInt()}° ${cardinal(peak.bearingDeg)}", style = MaterialTheme.typography.labelLarge)
                        Text(when (peak.visibility) {
                            Visibility.TERRAIN_VISIBLE -> "Visible"; Visibility.UNCERTAIN -> "Uncertain"
                            Visibility.OCCLUDED -> "Hidden"; Visibility.UNKNOWN -> "No data"
                        }, style = MaterialTheme.typography.labelSmall, color = tone)
                    }
                }
                if (index < peaks.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = .35f))
            }
        }
    }
}

@Composable
private fun Regions(state: PanoramaState, modifier: Modifier, onDownload: (CatalogRegion) -> Unit, onSelect: (RegionPack?) -> Unit, onDelete: (RegionPack) -> Unit, onAttribution: (RegionPack) -> Unit, onCancel: () -> Unit,
                    onDiscard: (CatalogRegion) -> Unit) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        state.importing?.let {
            item {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
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
        items(state.catalog, key = { it.id }) { region ->
            val installed = state.packs.lastOrNull { it.id == region.id && it.version == region.version }
            val saved = state.pendingDownloads.any { transfer -> region.tiles.any { it.url == transfer.address } }
            val active = installed != null && installed.key == state.activePack?.key
            RegionCard(
                name = region.name,
                subtitle = "${region.country} · ${region.description}",
                meta = "${(region.downloadBytes + 1_048_575) / 1_048_576} MB · ${region.radiusKm} km range",
                status = if (installed != null) (if (active) "In use" else "Ready offline") else if (saved) "Paused" else null,
                statusTone = if (installed != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            ) {
                if (installed != null) {
                    if (!active) Button(onClick = { onSelect(installed) }, contentPadding = PaddingValues(horizontal = 18.dp)) { Text("Open") }
                    else OutlinedButton(onClick = { onSelect(installed) }, contentPadding = PaddingValues(horizontal = 18.dp)) { Text("Show") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onAttribution(installed) }) { Icon(painterResource(R.drawable.ic_info), contentDescription = "Data credits", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton(enabled = state.importing == null, onClick = { onDelete(installed) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Delete") }
                } else {
                    Button(enabled = state.importing == null, onClick = { onDownload(region) }, contentPadding = PaddingValues(horizontal = 18.dp)) {
                        Icon(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.downloadingRegionId == region.id) "Downloading…" else if (saved) "Resume" else "Download")
                    }
                    if (saved) TextButton(enabled = state.importing == null, onClick = { onDiscard(region) }) { Text("Discard") }
                }
            }
        }
        // Previously installed custom packs remain usable after upgrading, without exposing import tools.
        items(state.packs.filter { pack -> state.catalog.none { it.id == pack.id && it.version == pack.version } }, key = { it.key }) { pack ->
            RegionCard(name = pack.name, subtitle = null, meta = null, status = "Ready offline", statusTone = MaterialTheme.colorScheme.primary) {
                Button(onClick = { onSelect(pack) }, contentPadding = PaddingValues(horizontal = 18.dp)) { Text("Open") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onAttribution(pack) }) { Icon(painterResource(R.drawable.ic_info), contentDescription = "Data credits", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(enabled = state.importing == null, onClick = { onDelete(pack) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Delete") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onSelect(null) }.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_explore), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("Explorer demo", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("Fictional panorama for trying the controls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (state.isDemo) "In use" else "Try", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun RegionCard(name: String, subtitle: String?, meta: String?, status: String?, statusTone: androidx.compose.ui.graphics.Color, actions: @Composable RowScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = statusTone) }
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    }
}

@Composable
private fun ViewpointDialog(state: PanoramaState, onDismiss: () -> Unit, onApply: (GeoPoint, Double?) -> Unit) {
    var latitude by remember { mutableStateOf(coordinate(state.point.latitude)) }
    var longitude by remember { mutableStateOf(coordinate(state.point.longitude)) }
    var altitude by remember { mutableStateOf(state.groundOverrideM?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose your viewpoint") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Decimal degrees. Leave ground height empty to use the terrain model.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(latitude, { latitude = it }, label = { Text("Latitude") }, singleLine = true)
            OutlinedTextField(longitude, { longitude = it }, label = { Text("Longitude") }, singleLine = true)
            OutlinedTextField(altitude, { altitude = it }, label = { Text("Ground height in metres (optional)") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val point = runCatching { GeoPoint(latitude.trim().toDouble(), longitude.trim().toDouble()) }.getOrNull()
        val ground = altitude.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (point == null || altitude.isNotBlank() && (ground == null || !ground.isFinite() || ground !in -500.0..10_000.0)) error = "Enter valid coordinates and a ground height between −500 and 10,000 m."
        else onApply(point, ground)
    }) { Text("Show panorama") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun SettingsDialog(state: PanoramaState, model: PanoramaViewModel, onDismiss: () -> Unit) {
    var phoneHeight by remember { mutableFloatStateOf(state.preferences.phoneHeightM.toFloat()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Feet and miles", Modifier.weight(1f)); Switch(state.preferences.useFeet, { model.updatePreferences(state.preferences.copy(useFeet = it)) }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Uncertain peak labels", Modifier.weight(1f)); Switch(state.preferences.showUncertain, { model.updatePreferences(state.preferences.copy(showUncertain = it)) }) }
            Text("Phone height: ${String.format(Locale.US, "%.1f", phoneHeight)} m")
            Slider(phoneHeight, { phoneHeight = it }, onValueChangeFinished = { model.updatePreferences(state.preferences.copy(phoneHeightM = phoneHeight.toDouble())) }, valueRange = .5f..3f)
            Text("Vertical view: ${state.pitch.toInt()}°")
            Slider(state.pitch.toFloat(), { model.setPitch(it.toDouble()) }, valueRange = -20f..20f)
            Text("Visibility is an estimate from the terrain model. Clouds, trees, GPS error and compass interference affect what you actually see.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

internal fun coordinate(value: Double) = String.format(Locale.US, "%.5f", value)
internal fun height(metres: Double, state: PanoramaState) = if (state.preferences.useFeet) "${(metres * 3.28084).toInt()} ft" else "${metres.toInt()} m"
internal fun distance(metres: Double, state: PanoramaState) = if (state.preferences.useFeet) String.format(Locale.US, "%.1f mi", metres / 1609.344) else String.format(Locale.US, "%.1f km", metres / 1000)
internal fun cardinal(degrees: Double) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(Geography.wrap360(degrees + 22.5) / 45).toInt() % 8]
internal fun visibilityName(value: Visibility) = when (value) {
    Visibility.TERRAIN_VISIBLE -> "Terrain-visible"
    Visibility.OCCLUDED -> "Hidden by a nearer ridge"
    Visibility.UNCERTAIN -> "Visibility uncertain"
    Visibility.UNKNOWN -> "Terrain data unavailable"
}

package com.harsh.peakbud.feature.panorama

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var cameraMode by rememberSaveable { mutableStateOf(true) }
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
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp, 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Camera", "Peaks", "Regions").forEachIndexed { index, name ->
                    if (tab == index) FilledTonalButton(onClick = { tab = index }) { Text(name) }
                    else TextButton(onClick = { tab = index }) { Text(name) }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PEAKBUD", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    if (!compact) Text(when (tab) { 1 -> "Know your mountains."; 2 -> "Ready for the trail."; else -> "Find your horizon." }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { settings = true }) { Text("Settings") }
            }
            state.message?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = model::clearMessage) { Text("Dismiss") }
                    }
                }
            }
            when (tab) {
                0 -> {
                    if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(state.activePack?.name ?: "Explorer demo · fictional terrain", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        Text(if (state.isDemo) "Demo" else "Offline", style = MaterialTheme.typography.labelMedium)
                    }
                    if (!compact) ObservationStatus(state, cameraMode, cameraFov, false, ::gps, model::startCameraTracking)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = cameraMode, onClick = { cameraMode = true }, label = { Text("Live camera") })
                        FilterChip(selected = !cameraMode, onClick = { cameraMode = false }, label = { Text("Panorama") })
                    }
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
                            CameraPanel(state, Modifier.weight(1f).fillMaxWidth(), { tab = 2 }, ::gps, model::startCameraTracking, { cameraFov = it }) { selectedPeak = it }
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
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        }, confirmButton = { TextButton(onClick = { model.pan(Geography.wrap180(peak.bearingDeg - state.heading)); selectedPeak = null; cameraMode = false; tab = 0 }) { Text("Look toward peak") } }, dismissButton = { TextButton(onClick = { selectedPeak = null }) { Text("Close") } })
    }
    attribution?.let { text -> AlertDialog(onDismissRequest = { attribution = null }, title = { Text("Data credits") }, text = { Text(text, Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { attribution = null }) { Text("Done") } }) }
    deletePack?.let { pack -> AlertDialog(onDismissRequest = { deletePack = null }, title = { Text("Delete ${pack.name}?") }, text = { Text("Its offline terrain and peak catalogue will be removed from this device.") }, confirmButton = { TextButton(onClick = { model.deleteRegion(pack); deletePack = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletePack = null }) { Text("Keep") } }) }
}

@Composable
private fun AlignmentControls(model: PanoramaViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { model.trimCompass(-1.0) }) { Text("−1°") }
        Text("Align labels", style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = model::resetTrim) { Text("Reset") }
        TextButton(onClick = { model.trimCompass(1.0) }) { Text("+1°") }
    }
}

@Composable
private fun ObservationStatus(state: PanoramaState, cameraMode: Boolean, cameraFov: Pair<Double, Double>?, compact: Boolean, onGps: () -> Unit, onCompass: () -> Unit) {
    val context = LocalContext.current
    val fix = state.gpsFix
    val heading = if (cameraMode) state.cameraPose?.heading else state.heading
    val bearing = heading?.let { "${it.toInt().toString().padStart(3, '0')}° ${cardinal(it)}" } ?: "Direction unavailable"
    val fov = cameraFov
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 6.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(bearing, fontWeight = FontWeight.Bold)
                Text(if (cameraMode) fov?.let { "View ${it.first.toInt()}° H × ${it.second.toInt()}° V" } ?: "View angle: waiting for camera" else "Panorama view ${state.fov.toInt()}° H", style = MaterialTheme.typography.labelMedium)
            }
            Text(if (state.followingCompass) "${if (fix != null || !state.isDemo) "True north" else "Magnetic north · GPS needed"} · ${state.compassSource ?: "Waiting for compass"}" else "Manual direction · compass off", style = MaterialTheme.typography.labelSmall)
            if (fix != null) {
                Text("Lat ${coordinate(fix.point.latitude)}  ·  Lon ${coordinate(fix.point.longitude)}", style = MaterialTheme.typography.bodySmall)
                val altitude = fix.seaLevelAltitudeM ?: fix.ellipsoidAltitudeM
                Text(altitude?.let { "Altitude ${height(it, state)} · ${if (fix.seaLevelAltitudeM != null) "GPS sea level" else "GPS ellipsoid"}${fix.verticalAccuracyM?.let { a -> " · ±${kotlin.math.ceil(a.toDouble()).toInt().coerceAtLeast(1)} m vertical" } ?: ""}" } ?: "Altitude unavailable from GPS", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Latitude / longitude: waiting for GPS", style = MaterialTheme.typography.bodySmall)
                Text("Altitude: waiting for GPS", style = MaterialTheme.typography.bodySmall)
            }
            if (!state.isDemo) state.scene?.observer?.let { Text("Ground ${height(it.groundHeightM, state)} · terrain model (EGM96)", style = MaterialTheme.typography.labelSmall) }
            if (!cameraMode && !compact) Text("Viewpoint ${coordinate(state.point.latitude)}, ${coordinate(state.point.longitude)} · ${if (state.isDemo) "fictional demo" else if (state.followingGps) "GPS" else "manual"}", style = MaterialTheme.typography.labelSmall)
            Text(if (!state.followingGps) "GPS off${if (fix != null) " · last known position above" else ""}" else state.gpsStatus ?: "Waiting for GPS…", style = MaterialTheme.typography.labelSmall)
            state.compassStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!state.followingGps) TextButton(onClick = onGps, contentPadding = PaddingValues(4.dp)) { Text("Enable GPS") }
                if (!state.followingCompass) TextButton(onClick = onCompass, contentPadding = PaddingValues(4.dp)) { Text("Enable compass") }
                if (state.gpsStatus?.contains("turned off") == true) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }, contentPadding = PaddingValues(4.dp)) { Text("Location settings") }
                if (!state.followingGps && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, contentPadding = PaddingValues(4.dp)) { Text("Permissions") }
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onViewpoint, modifier = Modifier.weight(1f)) { Text("Viewpoint") }
                        OutlinedButton(onClick = onGps) { Text(if (state.followingGps) "GPS on" else "GPS") }
                        OutlinedButton(onClick = model::toggleCompass) { Text(if (state.followingCompass) "Auto on" else "Compass") }
                    }
                    Text("${coordinate(state.point.latitude)}, ${coordinate(state.point.longitude)} · ${state.scene?.observer?.let { height(it.groundHeightM, state) } ?: "height unavailable"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.gpsStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    state.compassStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                    state.scene?.horizon?.count { !it.complete }?.takeIf { it > 0 }?.let {
                        Text("Some directions have missing terrain. Their skyline and peak visibility are unknown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("View", style = MaterialTheme.typography.labelSmall)
                        Slider(state.fov.toFloat(), { model.setFov(it.toDouble()) }, valueRange = 35f..120f, modifier = Modifier.weight(1f))
                        Text("${state.fov.toInt()}°", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { model.pan(-20.0) }) { Text("← Left") }
                        Text("Drag to explore", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { model.pan(20.0) }) { Text("Right →") }
                    }
    }
}

@Composable
private fun PeakList(state: PanoramaState, modifier: Modifier, onPeak: (PeakView) -> Unit) {
    val peaks = state.scene?.peaks.orEmpty().sortedWith(compareBy<PeakView> { when (it.visibility) {
        Visibility.TERRAIN_VISIBLE -> 0; Visibility.UNCERTAIN -> 1; Visibility.OCCLUDED -> 2; Visibility.UNKNOWN -> 3
    } }.thenBy { it.distanceM })
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text(if (state.isDemo) "Fictional peaks for exploring the engine. Import a region for real mountains." else "Terrain line of sight within ${(state.activePack?.radiusM ?: 0.0).toInt() / 1000} km. Weather and vegetation can still hide summits.", style = MaterialTheme.typography.bodyMedium) }
        if (peaks.isEmpty()) item { Text(if (state.calculating) "Calculating nearby peaks…" else "No peaks available at this viewpoint.", Modifier.padding(vertical = 30.dp)) }
        items(peaks, key = { it.peak.id }) { peak ->
            Card(onClick = { onPeak(peak) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("△", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(peak.peak.name, fontWeight = FontWeight.SemiBold)
                        Text("${peak.peak.publishedHeightM?.let { height(it, state) } ?: "Elevation unknown"} · ${distance(peak.distanceM, state)}", style = MaterialTheme.typography.bodySmall)
                        peak.elevationDeg?.let { Text("Elevation angle: ${String.format(Locale.US, "%.1f", it)}° above the horizon") }
                Text(visibilityName(peak.visibility), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text("${peak.bearingDeg.toInt()}°", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun Regions(state: PanoramaState, modifier: Modifier, onDownload: (CatalogRegion) -> Unit, onSelect: (RegionPack?) -> Unit, onDelete: (RegionPack) -> Unit, onAttribution: (RegionPack) -> Unit, onCancel: () -> Unit,
                    onDiscard: (CatalogRegion) -> Unit) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Text("Choose your hiking area", style = MaterialTheme.typography.titleLarge)
            Text("Download once before your hike. Terrain and peak names work offline after that.", style = MaterialTheme.typography.bodyMedium)
            state.importing?.let {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancel) { Text("Cancel download") }
            }
        }
        items(state.catalog, key = { it.id }) { region ->
            val installed = state.packs.lastOrNull { it.id == region.id && it.version == region.version }
            val saved = state.pendingDownloads.any { transfer -> region.tiles.any { it.url == transfer.address } }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(region.name, style = MaterialTheme.typography.titleMedium)
                    Text("${region.country} · ${region.description}", style = MaterialTheme.typography.bodyMedium)
                    Text("${(region.downloadBytes + 1_048_575) / 1_048_576} MB download · ${region.installedBytes / 1_048_576} MB offline · ${region.radiusKm} km view range", style = MaterialTheme.typography.bodySmall)
                    Text(if (installed != null) "Ready offline" else if (saved) "Download saved · resume anytime" else "Not downloaded", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (installed != null) {
                            Button(onClick = { onSelect(installed) }) { Text("Open") }
                            TextButton(onClick = { onAttribution(installed) }) { Text("Credits") }
                            TextButton(enabled = state.importing == null, onClick = { onDelete(installed) }) { Text("Delete") }
                        } else {
                            Button(enabled = state.importing == null, onClick = { onDownload(region) }) {
                                Text(if (state.downloadingRegionId == region.id) "Downloading…" else if (saved) "Resume" else "Download")
                            }
                            if (saved) TextButton(enabled = state.importing == null, onClick = { onDiscard(region) }) { Text("Discard") }
                        }
                    }
                }
            }
        }
        // Previously installed custom packs remain usable after upgrading, without exposing import tools.
        items(state.packs.filter { pack -> state.catalog.none { it.id == pack.id && it.version == pack.version } }, key = { it.key }) { pack ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    Text("Ready offline", style = MaterialTheme.typography.labelMedium)
                    Row {
                        Button(onClick = { onSelect(pack) }) { Text("Open") }
                        TextButton(onClick = { onAttribution(pack) }) { Text("Credits") }
                        TextButton(enabled = state.importing == null, onClick = { onDelete(pack) }) { Text("Delete") }
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Explorer demo", style = MaterialTheme.typography.titleMedium)
                    Text("Fictional panorama for trying the controls. Camera identification uses a downloaded region.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onSelect(null) }) { Text("Explore demo") }
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Trail settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Feet and miles", Modifier.weight(1f)); Switch(state.preferences.useFeet, { model.updatePreferences(state.preferences.copy(useFeet = it)) }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Uncertain peak labels", Modifier.weight(1f)); Switch(state.preferences.showUncertain, { model.updatePreferences(state.preferences.copy(showUncertain = it)) }) }
            Text("Phone height: ${String.format(Locale.US, "%.1f", phoneHeight)} m")
            Slider(phoneHeight, { phoneHeight = it }, onValueChangeFinished = { model.updatePreferences(state.preferences.copy(phoneHeightM = phoneHeight.toDouble())) }, valueRange = .5f..3f)
            Text("Vertical view: ${state.pitch.toInt()}°")
            Slider(state.pitch.toFloat(), { model.setPitch(it.toDouble()) }, valueRange = -20f..20f)
            Text("Compass alignment", fontWeight = FontWeight.SemiBold)
            Text("Move away from metal. Adjust until the model matches the mountain range.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { model.trimCompass(-1.0) }) { Text("−1°") }
                OutlinedButton(onClick = { model.trimCompass(1.0) }) { Text("+1°") }
                TextButton(onClick = model::resetTrim) { Text("Reset") }
            }
            Text("Terrain visibility is an estimate. Clouds, trees, GPS error and compass interference affect what you see.", style = MaterialTheme.typography.bodySmall)
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

package com.harsh.peakbud.feature.panorama

import com.harsh.peakbud.R
import com.harsh.peakbud.ui.text.TrailStrings
import android.app.Application
import android.hardware.GeomagneticField
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.CameraPose
import com.harsh.peakbud.core.terrain.TerrainEngine
import com.harsh.peakbud.data.peaks.*
import com.harsh.peakbud.data.regions.*
import com.harsh.peakbud.data.settings.*
import com.harsh.peakbud.data.terrain.*
import com.harsh.peakbud.data.download.RegionDownloader
import com.harsh.peakbud.data.download.PendingDownload
import com.harsh.peakbud.device.*
import com.harsh.peakbud.telemetry.Telemetry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PanoramaState(
    val scene: MountainScene? = null,
    val initialized: Boolean = false,
    val calculating: Boolean = false,
    val message: String? = null,
    val packs: List<RegionPack> = emptyList(),
    val catalog: List<CatalogRegion> = emptyList(),
    val downloadingRegionId: String? = null,
    val pendingDownloads: List<PendingDownload> = emptyList(),
    val activePack: RegionPack? = null,
    val point: GeoPoint = DemoTerrain.VIEWPOINT,
    val groundOverrideM: Double? = null,
    val heading: Double = 350.0,
    val pitch: Double = 0.0,
    val fov: Double = 100.0,
    val followingCompass: Boolean = false,
    val followingGps: Boolean = false,
    val gpsStatus: String? = null,
    val gpsFresh: Boolean = false,
    val gpsFix: PositionFix? = null,
    val compassSource: String? = null,
    val cameraPose: CameraPose? = null,
    val poseReliable: Boolean = false,
    val compassStatus: String? = null,
    val importing: String? = null,
    val preferences: Preferences = Preferences(),
    /** Manual label offset in degrees, set with Nudge labels. */
    val labelTrim: Double = 0.0,
    /** Human-readable name of where the phone is, from the geocoder; null offline or before the first fix. */
    val placeName: String? = null,
) {
    /** Fictional terrain is shown only while demo mode is switched on in Settings. */
    val isDemo: Boolean get() = activePack == null && preferences.demoMode
    /** Nothing to show: no region selected and demo mode is off. */
    val noRegion: Boolean get() = activePack == null && !preferences.demoMode
}

private class ObservationMessage(message: String) : IllegalArgumentException(message)

class PanoramaViewModel(application: Application) : AndroidViewModel(application) {
    private val strings = TrailStrings(application.resources)
    private val mutable = MutableStateFlow(PanoramaState())
    val state: StateFlow<PanoramaState> = mutable.asStateFlow()
    private val store = RegionStore(File(application.noBackupFilesDir, "regions"))
    private val database = PeakDatabase.open(application)
    private val settings = AppSettings(application)
    private val downloader = RegionDownloader(File(application.noBackupFilesDir, "downloads"))
    private var calculation: Job? = null
    private var importJob: Job? = null
    private var lastGpsPoint: GeoPoint? = null
    private var gpsAccuracyM = 0.0
    private var yawTrim = 0.0
    private var lastSmoothedHeading: Double? = null
    private var lastFixElapsedMs: Long? = null
    private var declinationKey: Triple<GeoPoint, Int, Long>? = null
    private var declination = 0.0
    private var namedPoint: GeoPoint? = null
    private var namingJob: Job? = null

    init {
        viewModelScope.launch {
            settings.preferences.catch { e -> Telemetry.error("settings read", e); mutable.update { it.copy(message = strings(R.string.settings_read_failed)) }; emit(Preferences()) }
                .collect { value ->
                    val previous = mutable.value.preferences
                    if (!mutable.value.initialized || previous.shareDiagnostics != value.shareDiagnostics) Telemetry.setEnabled(value.shareDiagnostics)
                    Telemetry.key("demo_mode", value.demoMode.toString())
                    mutable.update { it.copy(preferences = value) }
                    if (previous.phoneHeightM != value.phoneHeightM || previous.demoMode != value.demoMode) recalculate()
                }
        }
        viewModelScope.launch {
            val packs = withContext(Dispatchers.IO) { store.installed() }
            val catalog = withContext(Dispatchers.IO) { RegionCatalog.load { application.assets.open(it) } }
            mutable.update { it.copy(packs = packs, catalog = catalog) }
            refreshDownloads()
            val selected = runCatching { settings.selectedRegion.first() }.getOrNull()
            selectRegion(packs.firstOrNull { it.key == selected })
            mutable.update { it.copy(initialized = true) }
        }
    }

    fun recalculate() {
        calculation?.cancel()
        val input = mutable.value
        if (input.noRegion) { mutable.update { it.copy(calculating = false, scene = null, message = null) }; return }
        calculation = viewModelScope.launch {
            mutable.update { it.copy(calculating = true, scene = null, message = null) }
            try {
                // HGT is a bounded file-backed reader. Keep mixed file/CPU work off Main.
                val scene = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    val pack = input.activePack
                    if (pack != null && !pack.observerBounds.contains(input.point)) throw ObservationMessage(strings(R.string.you_re_outside_this_hiking_area_pick_an_area_that_covers_where_you_are_or_ch))
                    if (pack != null) store.verify(pack) { context.ensureActive() }
                    val terrain: Terrain = if (pack == null) DemoTerrain() else HgtTerrain(pack.directory.resolve("terrain"), pack.bounds)
                    terrain.use {
                        val sample = it.sample(input.point) as? TerrainSample.Valid
                            ?: throw ObservationMessage(strings(R.string.there_s_no_map_data_for_this_spot_choose_a_spot_inside_the_hiking_area))
                        val observer = Observer(input.point, input.groundOverrideM ?: sample.heightM,
                            input.preferences.phoneHeightM, it.datum, if (input.followingGps) gpsAccuracyM else 0.0,
                            if (pack == null) 0.0 else 10.0)
                        val peaks = if (pack == null) DemoTerrain.peaks else {
                            if (database.peaks().region(pack.key) == null) {
                                val records = store.peaks(pack)
                                database.withTransaction {
                                    database.peaks().deletePeaks(pack.key)
                                    database.peaks().insert(records.map { p -> PeakRecord(pack.key, p.id, p.name, p.point.latitude, p.point.longitude, p.publishedHeightM, p.source) })
                                    database.peaks().insertRegion(RegionRecord(pack.key, pack.id, pack.name, pack.version))
                                }
                            }
                            database.peaks().candidates(pack.key, pack.bounds.south, pack.bounds.west, pack.bounds.north, pack.bounds.east).map { p -> p.toPeak() }
                        }
                        TerrainEngine().calculate(observer, it, peaks, CalculationSettings(pack?.radiusM ?: 50_000.0)) { context.ensureActive() }
                    }
                }
                ensureActive()
                mutable.update { it.copy(scene = scene, calculating = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                ensureActive() // A cancelled observer/region must not publish a stale failure.
                // Outside-area and no-data cases are expected (require/error); anything else is a bug worth reporting.
                if (error is IllegalArgumentException || error is IllegalStateException) Telemetry.log("view not calculated: ${error.message}")
                else Telemetry.error("calculate view", error)
                mutable.update { it.copy(calculating = false, scene = null, message = (error as? ObservationMessage)?.message ?: strings(R.string.couldn_t_work_out_the_view_from_here_please_try_again)) }
            }
        }
    }

    fun manualViewpoint(point: GeoPoint, groundHeight: Double?) {
        mutable.update { it.copy(point = point, groundOverrideM = groundHeight, followingGps = false, gpsStatus = null) }
        recalculate()
    }
    fun selectRegion(pack: RegionPack?) {
        calculation?.cancel()
        yawTrim = 0.0
        lastSmoothedHeading = null
        lastGpsPoint = null
        Telemetry.event("region_open", "region" to (pack?.id ?: "demo"))
        Telemetry.key("region", pack?.id ?: "none")
        mutable.update { it.copy(activePack = pack, point = pack?.viewpoint ?: DemoTerrain.VIEWPOINT,
            heading = pack?.headingDeg ?: 350.0, groundOverrideM = null, followingGps = false, followingCompass = false,
            gpsStatus = null, gpsFresh = false, cameraPose = null, compassStatus = null, message = null, labelTrim = 0.0) }
        recalculate()
        viewModelScope.launch { runCatching { settings.selectRegion(pack?.key) } }
    }
    fun pan(degrees: Double) {
        mutable.update { it.copy(heading = Geography.wrap360(it.heading + degrees), followingCompass = false) }
    }
    fun setPitch(value: Double) { mutable.update { it.copy(pitch = value.coerceIn(-30.0, 30.0)) } }
    fun setFov(value: Double) { mutable.update { it.copy(fov = value.coerceIn(35.0, 120.0)) } }
    fun toggleCompass() { lastSmoothedHeading = null; mutable.update { it.copy(followingCompass = !it.followingCompass, cameraPose = null, compassStatus = null) } }
    fun trimCompass(degrees: Double) {
        Telemetry.log("label nudge $degrees")
        yawTrim = Geography.wrap180(yawTrim + degrees)
        lastSmoothedHeading = lastSmoothedHeading?.let { Geography.wrap360(it + degrees) }
        mutable.update { it.copy(labelTrim = yawTrim, heading = Geography.wrap360(it.heading + degrees), cameraPose = it.cameraPose?.let { p -> p.copy(heading = Geography.wrap360(p.heading + degrees)) }) }
    }
    fun resetTrim() {
        val old = yawTrim; yawTrim = 0.0
        lastSmoothedHeading = lastSmoothedHeading?.let { Geography.wrap360(it - old) }
        mutable.update { it.copy(labelTrim = 0.0, heading = Geography.wrap360(it.heading - old), cameraPose = it.cameraPose?.let { p -> p.copy(heading = Geography.wrap360(p.heading - old)) }) }
    }
    fun pose(value: DevicePose) {
        if (!mutable.value.followingCompass) return
        if (!value.available) {
            mutable.update { it.copy(followingCompass = false, cameraPose = null, compassStatus = value.message, poseReliable = false) }
            return
        }
        val input = mutable.value
        val ground = input.scene?.observer?.groundHeightM ?: 0.0
        val referencePoint = input.gpsFix?.point ?: input.point
        val key = Triple(referencePoint, (ground / 100).toInt(), System.currentTimeMillis() / 86_400_000)
        if (declinationKey != key) {
            declination = GeomagneticField(referencePoint.latitude.toFloat(), referencePoint.longitude.toFloat(), ground.toFloat(), System.currentTimeMillis()).declination.toDouble()
            declinationKey = key
        }
        val raw = Geography.wrap360(value.magneticHeading + (if (input.isDemo && input.gpsFix == null) 0.0 else declination) + yawTrim)
        val previous = lastSmoothedHeading
        val heading = if (previous == null) raw else Geography.wrap360(previous + Geography.wrap180(raw - previous) * 0.18)
        lastSmoothedHeading = heading
        mutable.update { it.copy(heading = heading, pitch = it.pitch + (value.pitch - it.pitch) * 0.15,
            compassStatus = value.message, poseReliable = value.reliable, compassSource = value.source,
            cameraPose = if (value.cameraUsable) CameraPose(raw, value.pitch, value.roll) else null) }
    }
    fun startCameraTracking() {
        lastSmoothedHeading = null
        mutable.update { it.copy(followingCompass = true, cameraPose = null) }
    }
    fun startGps() {
        lastGpsPoint = null
        lastFixElapsedMs = null
        mutable.update { it.copy(followingGps = true, gpsFresh = false, gpsStatus = strings(R.string.finding_your_location)) }
    }
    fun locationDenied() { mutable.update { it.copy(followingGps = false, message = strings(R.string.peakbud_needs_precise_location_to_line_up_peaks_turn_it_on_in_android_settin)) } }
    fun refreshFixAge() {
        val elapsed = lastFixElapsedMs ?: return
        val age = (android.os.SystemClock.elapsedRealtime() - elapsed) / 1000
        if (age > 30) mutable.update { it.copy(gpsFresh = false, gpsStatus = strings(R.string.location_is_s_old_updating , age)) }
    }
    fun stopGps() { mutable.update { it.copy(followingGps = false) } }
    fun gps(status: LocationStatus) {
        if (!mutable.value.followingGps) return
        when (status) {
            is LocationStatus.Message -> { Telemetry.log("location: ${status.text}"); mutable.update { it.copy(gpsFresh = false, gpsStatus = status.text) } }
            is LocationStatus.Fix -> {
                val fix = status.value
                if (fix.ageSeconds > 30) { mutable.update { it.copy(gpsFresh = false, gpsStatus = strings(R.string.location_is_out_of_date_updating)) }; return }
                gpsAccuracyM = fix.accuracyM.toDouble()
                lastFixElapsedMs = android.os.SystemClock.elapsedRealtime() - fix.ageSeconds * 1000
                mutable.update { it.copy(gpsFix = fix, gpsFresh = true, gpsStatus = strings(R.string.location_accurate_to_m , fix.accuracyM.toInt())) }
                // Look the place name up again only after moving about half a kilometre.
                val named = namedPoint
                if (named == null || Geography.distance(named, fix.point) >= 500.0) {
                    namedPoint = fix.point
                    namingJob?.cancel()
                    namingJob = viewModelScope.launch {
                        val name = PlaceNamer.name(getApplication(), fix.point)
                        if (name == null) namedPoint = null  // offline: try again with the next fix
                        mutable.update { it.copy(placeName = name ?: it.placeName) }
                    }
                }
                if (mutable.value.isDemo) return // Keep fictional terrain at its fictional viewpoint.
                val previous = lastGpsPoint
                if (previous == null || Geography.distance(previous, fix.point) >= maxOf(30.0, gpsAccuracyM)) {
                    lastGpsPoint = fix.point
                    mutable.update { it.copy(point = fix.point, groundOverrideM = null) }
                    recalculate()
                }
            }
        }
    }
    fun updatePreferences(value: Preferences) {
        val old = mutable.value.preferences
        if (old.demoMode != value.demoMode) Telemetry.event("demo_mode", "enabled" to value.demoMode)
        if (old.useFeet != value.useFeet) Telemetry.event("units", "imperial" to value.useFeet)
        if (old.shareDiagnostics && !value.shareDiagnostics) Telemetry.event("diagnostics_off")  // last event before collection stops
        viewModelScope.launch { runCatching { settings.update(value) }.onFailure { e -> Telemetry.error("settings write", e); mutable.update { s -> s.copy(message = strings(R.string.settings_write_failed)) } } }
    }
    fun clearMessage() { mutable.update { it.copy(message = null) } }

    private fun progressText(value: InstallProgress): String = when (value.stage) {
        InstallStage.DOWNLOAD -> strings(R.string.progress_download, value.name, value.receivedBytes / 1_048_576, value.downloadBytes / 1_048_576, value.current, value.total)
        InstallStage.CHECK_TERRAIN -> strings(R.string.progress_check_terrain, value.current, value.total)
        InstallStage.PREPARE -> strings(R.string.progress_prepare, value.name)
        InstallStage.SAVE -> strings(R.string.progress_save)
        InstallStage.CHECK_FILES -> strings(R.string.progress_check_files, value.current, value.total)
    }
    fun downloadRegion(region: CatalogRegion) {
        if (importJob?.isCompleted == false) return
        val trusted = mutable.value.catalog.firstOrNull { it.id == region.id } ?: return
        importJob = viewModelScope.launch {
            mutable.update { it.copy(importing = strings(R.string.starting_download_of , trusted.name), downloadingRegionId = trusted.id, message = null) }
            Telemetry.event("region_download_start", "region" to trusted.id, "size_mb" to trusted.downloadBytes / 1_048_576)
            try {
                val pack = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    CatalogInstaller(File(getApplication<Application>().noBackupFilesDir, "preparing"), store, downloader) {
                        getApplication<Application>().assets.open(it)
                    }.install(trusted, { context.ensureActive() }) { progress -> mutable.update { it.copy(importing = progressText(progress)) } }
                }
                Telemetry.event("region_download_complete", "region" to trusted.id)
                mutable.update { it.copy(packs = (it.packs + pack).sortedBy { p -> p.name }, importing = null, downloadingRegionId = null) }
                refreshDownloads()
                selectRegion(pack)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Telemetry.event("region_download_failed", "region" to trusted.id, "reason" to error.javaClass.simpleName)
                // Network drops and full storage are normal on the trail; only unexpected failures become non-fatal reports.
                if (error !is java.io.IOException) Telemetry.error("download ${trusted.id}", error)
                mutable.update { it.copy(importing = null, downloadingRegionId = null, message = strings(R.string.region_download_failed)) }
                refreshDownloads()
            }
        }
    }
    fun cancelImport() {
        val job = importJob ?: return
        mutable.update { it.copy(importing = strings(R.string.cancelling)) }
        job.invokeOnCompletion {
            mutable.update { it.copy(importing = null, downloadingRegionId = null) }
            viewModelScope.launch { refreshDownloads() }
        }
        job.cancel()
    }
    fun deleteRegion(pack: RegionPack) {
        Telemetry.event("region_delete", "region" to pack.id)
        if (mutable.value.activePack?.key == pack.key) selectRegion(null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.delete(pack)
                    database.withTransaction { database.peaks().deletePeaks(pack.key); database.peaks().deleteRegion(pack.key) }
                }
                mutable.update { it.copy(packs = it.packs.filterNot { p -> p.key == pack.key }) }
            } catch (error: Exception) { Telemetry.error("delete ${pack.id}", error); mutable.update { it.copy(message = strings(R.string.region_delete_failed)) } }
        }
    }
    suspend fun attribution(pack: RegionPack): String = withContext(Dispatchers.IO) { store.attribution(pack) }
    private suspend fun refreshDownloads() {
        val pending = withContext(Dispatchers.IO) { downloader.pending() }
        mutable.update { it.copy(pendingDownloads = pending) }
    }
    fun discardDownload(region: CatalogRegion) {
        if (importJob?.isCompleted == false) return
        importJob = viewModelScope.launch {
            mutable.update { it.copy(importing = strings(R.string.removing_paused_download)) }
            try {
                withContext(Dispatchers.IO) { region.tiles.forEach { downloader.discard(it.url) } }
                refreshDownloads()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Telemetry.error("discard ${region.id}", error); mutable.update { it.copy(message = strings(R.string.download_discard_failed)) } }
            finally { mutable.update { it.copy(importing = null) } }
        }
    }
}

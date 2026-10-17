package com.harsh.peakbud.feature.panorama

import android.app.Application
import android.hardware.GeomagneticField
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.terrain.TerrainEngine
import com.harsh.peakbud.data.peaks.*
import com.harsh.peakbud.data.regions.*
import com.harsh.peakbud.data.settings.*
import com.harsh.peakbud.data.terrain.*
import com.harsh.peakbud.data.download.RegionDownloader
import com.harsh.peakbud.data.download.PendingDownload
import com.harsh.peakbud.device.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class PanoramaState(
    val scene: MountainScene? = null,
    val calculating: Boolean = false,
    val message: String? = null,
    val packs: List<RegionPack> = emptyList(),
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
    val compassStatus: String? = null,
    val importing: String? = null,
    val preferences: Preferences = Preferences(),
) { val isDemo: Boolean get() = activePack == null }

class PanoramaViewModel(application: Application) : AndroidViewModel(application) {
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

    init {
        viewModelScope.launch {
            settings.preferences.catch { mutable.update { it.copy(message = "Settings could not be read; using defaults") }; emit(Preferences()) }
                .collect { value ->
                    val heightChanged = mutable.value.preferences.phoneHeightM != value.phoneHeightM
                    mutable.update { it.copy(preferences = value) }
                    if (heightChanged) recalculate()
                }
        }
        viewModelScope.launch {
            val packs = withContext(Dispatchers.IO) { store.installed() }
            mutable.update { it.copy(packs = packs) }
            refreshDownloads()
            val selected = runCatching { settings.selectedRegion.first() }.getOrNull()
            selectRegion(packs.firstOrNull { it.key == selected })
        }
    }

    fun recalculate() {
        calculation?.cancel()
        val input = mutable.value
        calculation = viewModelScope.launch {
            mutable.update { it.copy(calculating = true, scene = null, message = null) }
            try {
                // HGT is a bounded file-backed reader. Keep mixed file/CPU work off Main.
                val scene = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    val pack = input.activePack
                    require(pack == null || pack.observerBounds.contains(input.point)) { "This viewpoint is outside the installed region. Choose a covered location or import another region." }
                    if (pack != null) store.verify(pack) { context.ensureActive() }
                    val terrain: Terrain = if (pack == null) DemoTerrain() else HgtTerrain(pack.directory.resolve("terrain"), pack.bounds)
                    terrain.use {
                        val sample = it.sample(input.point) as? TerrainSample.Valid
                            ?: error("No usable terrain at this viewpoint. Check the region's coverage.")
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
                mutable.update { it.copy(calculating = false, scene = null, message = error.message ?: "Unable to calculate this panorama") }
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
        mutable.update { it.copy(activePack = pack, point = pack?.viewpoint ?: DemoTerrain.VIEWPOINT,
            heading = pack?.headingDeg ?: 350.0, groundOverrideM = null, followingGps = false, followingCompass = false,
            gpsStatus = null, compassStatus = null, message = null) }
        recalculate()
        viewModelScope.launch { runCatching { settings.selectRegion(pack?.key) } }
    }
    fun pan(degrees: Double) {
        mutable.update { it.copy(heading = Geography.wrap360(it.heading + degrees), followingCompass = false) }
    }
    fun setPitch(value: Double) { mutable.update { it.copy(pitch = value.coerceIn(-30.0, 30.0)) } }
    fun setFov(value: Double) { mutable.update { it.copy(fov = value.coerceIn(35.0, 120.0)) } }
    fun toggleCompass() { lastSmoothedHeading = null; mutable.update { it.copy(followingCompass = !it.followingCompass, compassStatus = null) } }
    fun trimCompass(degrees: Double) {
        yawTrim = Geography.wrap180(yawTrim + degrees)
        lastSmoothedHeading = lastSmoothedHeading?.let { Geography.wrap360(it + degrees) }
        mutable.update { it.copy(heading = Geography.wrap360(it.heading + degrees)) }
    }
    fun resetTrim() {
        val old = yawTrim; yawTrim = 0.0
        lastSmoothedHeading = lastSmoothedHeading?.let { Geography.wrap360(it - old) }
        mutable.update { it.copy(heading = Geography.wrap360(it.heading - old)) }
    }
    fun pose(value: DevicePose) {
        if (!mutable.value.followingCompass) return
        if (!value.available) {
            mutable.update { it.copy(followingCompass = false, compassStatus = value.message) }
            return
        }
        val input = mutable.value
        val ground = input.scene?.observer?.groundHeightM ?: 0.0
        val key = Triple(input.point, (ground / 100).toInt(), System.currentTimeMillis() / 86_400_000)
        if (declinationKey != key) {
            declination = GeomagneticField(input.point.latitude.toFloat(), input.point.longitude.toFloat(), ground.toFloat(), System.currentTimeMillis()).declination.toDouble()
            declinationKey = key
        }
        val raw = Geography.wrap360(value.magneticHeading + declination + yawTrim)
        val previous = lastSmoothedHeading
        val heading = if (previous == null) raw else Geography.wrap360(previous + Geography.wrap180(raw - previous) * 0.18)
        lastSmoothedHeading = heading
        mutable.update { it.copy(heading = heading, pitch = it.pitch + (value.pitch - it.pitch) * 0.15,
            compassStatus = value.message) }
    }
    fun startGps() {
        if (mutable.value.isDemo) { mutable.update { it.copy(message = "Import and select a real terrain region before using GPS. The demo landscape is fictional.") }; return }
        lastGpsPoint = null
        lastFixElapsedMs = null
        mutable.update { it.copy(followingGps = true, gpsStatus = "Waiting for GPS…") }
    }
    fun locationDenied() { mutable.update { it.copy(followingGps = false, message = "Precise location was not granted. Use a manual viewpoint or enable precise location in Android settings.") } }
    fun refreshFixAge() {
        val elapsed = lastFixElapsedMs ?: return
        val age = (android.os.SystemClock.elapsedRealtime() - elapsed) / 1000
        if (age > 30) mutable.update { it.copy(gpsStatus = "GPS fix is ${age}s old; waiting for a fresh position") }
    }
    fun stopGps() { mutable.update { it.copy(followingGps = false) } }
    fun gps(status: LocationStatus) {
        if (!mutable.value.followingGps) return
        when (status) {
            is LocationStatus.Message -> mutable.update { it.copy(gpsStatus = status.text) }
            is LocationStatus.Fix -> {
                val fix = status.value
                if (fix.ageSeconds > 30) { mutable.update { it.copy(gpsStatus = "GPS fix is stale; waiting for a fresh position") }; return }
                gpsAccuracyM = fix.accuracyM.toDouble()
                lastFixElapsedMs = android.os.SystemClock.elapsedRealtime() - fix.ageSeconds * 1000
                mutable.update { it.copy(gpsStatus = "GPS ±${fix.accuracyM.toInt()} m · fresh fix") }
                val previous = lastGpsPoint
                if (previous == null || Geography.distance(previous, fix.point) >= maxOf(30.0, gpsAccuracyM)) {
                    lastGpsPoint = fix.point
                    mutable.update { it.copy(point = fix.point, groundOverrideM = null) }
                    recalculate()
                }
            }
        }
    }
    fun updatePreferences(value: Preferences) { viewModelScope.launch { runCatching { settings.update(value) }.onFailure { mutable.update { s -> s.copy(message = "Could not save settings") } } } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }

    fun importRegion(uri: Uri) {
        if (importJob?.isCompleted == false) return
        importJob = viewModelScope.launch {
            mutable.update { it.copy(importing = "Opening archive…", message = null) }
            try {
                val pack = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                        store.install(stream, { context.ensureActive() }) { progress -> mutable.update { it.copy(importing = progress) } }
                    } ?: error("Unable to open region archive")
                }
                mutable.update { it.copy(packs = (it.packs + pack).sortedBy { p -> p.name }, importing = null) }
                selectRegion(pack)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(importing = null, message = error.message ?: "Region import failed") } }
        }
    }
    fun downloadRegion(address: String) {
        if (importJob?.isCompleted == false) return
        importJob = viewModelScope.launch {
            mutable.update { it.copy(importing = "Connecting to region server…", message = null) }
            try {
                val pack = withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    val file = downloader.download(address, { context.ensureActive() }) { received, total ->
                        mutable.update { it.copy(importing = "Downloading ${received / 1_048_576} MiB${total?.let { count -> " of ${count / 1_048_576} MiB" } ?: ""}…") }
                    }
                    val result = try {
                        file.inputStream().use { stream -> store.install(stream, { context.ensureActive() }) { progress -> mutable.update { it.copy(importing = progress) } } }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { downloader.discard(address); throw error }
                    downloader.discard(address)
                    result
                }
                mutable.update { it.copy(packs = (it.packs + pack).sortedBy { p -> p.name }, importing = null) }
                refreshDownloads()
                selectRegion(pack)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(importing = null, message = "${error.message ?: "Download failed"}. Retry the same URL to resume when supported.") }
                refreshDownloads()
            }
        }
    }
    fun cancelImport() {
        val job = importJob ?: return
        mutable.update { it.copy(importing = "Cancelling…") }
        job.invokeOnCompletion {
            mutable.update { it.copy(importing = null) }
            viewModelScope.launch { refreshDownloads() }
        }
        job.cancel()
    }
    fun deleteRegion(pack: RegionPack) {
        if (mutable.value.activePack?.key == pack.key) selectRegion(null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.delete(pack)
                    database.withTransaction { database.peaks().deletePeaks(pack.key); database.peaks().deleteRegion(pack.key) }
                }
                mutable.update { it.copy(packs = it.packs.filterNot { p -> p.key == pack.key }) }
            } catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "Could not delete region") } }
        }
    }
    suspend fun attribution(pack: RegionPack): String = withContext(Dispatchers.IO) { store.attribution(pack) }
    private suspend fun refreshDownloads() {
        val pending = withContext(Dispatchers.IO) { downloader.pending() }
        mutable.update { it.copy(pendingDownloads = pending) }
    }
    fun discardDownload(address: String) {
        if (importJob?.isCompleted == false) return
        importJob = viewModelScope.launch {
            mutable.update { it.copy(importing = "Removing saved download…") }
            try {
                withContext(Dispatchers.IO) { downloader.discard(address) }
                refreshDownloads()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "Could not discard saved download") } }
            finally { mutable.update { it.copy(importing = null) } }
        }
    }
}

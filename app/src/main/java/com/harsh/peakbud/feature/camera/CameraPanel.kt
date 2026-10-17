package com.harsh.peakbud.feature.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.harsh.peakbud.core.model.*
import com.harsh.peakbud.core.projection.*
import com.harsh.peakbud.feature.panorama.*
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** Camera permission is requested only by the explicit Enable camera action. */
@Composable
fun CameraPanel(state: PanoramaState, modifier: Modifier, onRegions: () -> Unit, onGps: () -> Unit, onTrack: () -> Unit, onFov: (Pair<Double, Double>?) -> Unit, fullScreen: Boolean = false, onPeak: (PeakView) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(granted()) }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it; denied = !it }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permission = granted() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var lens by remember { mutableStateOf<CameraLens?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(lens, viewport) { onFov(lens?.takeIf { viewport.width > 0 && viewport.height > 0 }?.fieldOfView(viewport.width, viewport.height)) }
    DisposableEffect(Unit) { onDispose { onFov(null) } }
    var cameraStatus by remember { mutableStateOf<String?>("Starting camera…") }
    val notice = when {
        !permission -> "Enable the camera to see peak labels over your surroundings."
        cameraStatus != null -> cameraStatus
        state.isDemo -> "Choose a hiking area in Regions and download it to identify real peaks. The panorama demo is fictional."
        !state.followingGps -> "Use your GPS location to place peaks in the camera view."
        !state.gpsFresh -> state.gpsStatus ?: "Waiting for a fresh GPS position…"
        !state.followingCompass -> "Enable direction tracking to align the peak labels."
        state.cameraPose == null -> "Point the back of your phone toward the mountains."
        !state.poseReliable -> state.compassStatus ?: "Calibrate the compass away from metal before aligning peaks."
        state.calculating -> "Checking which peaks are visible…"
        state.scene == null -> "No terrain at this location. Choose a region covering your GPS position."
        lens == null -> "Camera calibration unavailable on this device. Panorama is still available."
        else -> null
    }
    Box(modifier.onSizeChanged { viewport = it }.clipToBounds().background(Color.Black)) {
        if (permission) CameraPreview(Modifier.fillMaxSize(), { lens = it }, { cameraStatus = it })
        if (notice == null) CameraLabels(state, lens!!, Modifier.fillMaxSize(), onPeak)
        if (notice != null) Surface(Modifier.align(Alignment.TopCenter).padding(start = 12.dp, end = 12.dp, top = if (fullScreen) 72.dp else 12.dp, bottom = 12.dp), color = Color(0xDD132A24), contentColor = Color.White) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(notice, style = MaterialTheme.typography.bodySmall)
                when {
                    !permission -> {
                        Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text("Enable camera") }
                        if (denied) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Open app settings") }
                    }
                    state.isDemo -> Button(onClick = onRegions) { Text("Choose region") }
                    !state.followingGps -> Button(onClick = onGps) { Text("Use my location") }
                    !state.followingCompass -> Button(onClick = onTrack) { Text("Track direction") }
                }
            }
        }
        if (notice == null && !fullScreen) Surface(Modifier.align(Alignment.BottomCenter).padding(8.dp), color = Color(0xBB132A24), contentColor = Color.White) {
            Text("${state.gpsStatus.orEmpty()}\nSensor alignment · adjust below if labels drift", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraPreview(modifier: Modifier, onLens: (CameraLens?) -> Unit, onStatus: (String?) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember(context) { PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    } }
    val lensCallback by rememberUpdatedState(onLens)
    val statusCallback by rememberUpdatedState(onStatus)
    var optics by remember { mutableStateOf<Triple<Double, Double, Pair<Float, Float>>?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var focalLength by remember { mutableStateOf<Float?>(null) }
    AndroidView(factory = { view }, modifier = modifier.semantics { contentDescription = if (streaming) "Live rear camera preview" else "Camera preview starting" })
    DisposableEffect(view, owner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var observedCamera: androidx.camera.core.Camera? = null
        var cameraObserver: Observer<androidx.camera.core.CameraState>? = null
        val builder = Preview.Builder().setTargetRotation(view.display?.rotation ?: android.view.Surface.ROTATION_0)
        // Use the focal length actually selected by the camera, rather than guessing
        // from the first entry of a logical multi-camera's supported-lens list.
        androidx.camera.camera2.interop.Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: android.hardware.camera2.CameraCaptureSession, request: android.hardware.camera2.CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                    val focal = result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
                    ContextCompat.getMainExecutor(context).execute { if (!disposed && focal != null) focalLength = focal }
                }
            }
        )
        val preview = builder.build()
        val streamObserver = Observer<PreviewView.StreamState> { value ->
            streaming = value == PreviewView.StreamState.STREAMING
            if (streaming) statusCallback(null)
        }
        view.previewStreamState.observe(owner, streamObserver)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) try {
                provider = future.get()
                check(provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { "No rear camera available. Use Panorama on this device." }
                preview.setSurfaceProvider(view.surfaceProvider)
                val camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                val info = Camera2CameraInfo.from(camera.cameraInfo)
                val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                optics = if (physical != null && pixels != null && active != null && physical.width > 0 && physical.height > 0)
                    Triple(pixels.width.toDouble() / physical.width, pixels.height.toDouble() / physical.height,
                        active.exactCenterX() to active.exactCenterY()) else null
                camera.cameraInfo.cameraState.observe(owner, object : Observer<androidx.camera.core.CameraState> {
                    override fun onChanged(value: androidx.camera.core.CameraState) {
                        if (!disposed && value.error != null) { streaming = false; statusCallback("Camera interrupted. Switch to Panorama and back to retry.") }
                    }
                }.also { cameraObserver = it; observedCamera = camera })
            } catch (error: Exception) {
                statusCallback(error.message ?: "Camera could not start. Use Panorama or check camera access in Android settings.")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            view.previewStreamState.removeObserver(streamObserver)
            cameraObserver?.let { observedCamera?.cameraInfo?.cameraState?.removeObserver(it) }
            cameraObserver = null; observedCamera = null
            provider?.unbind(preview)
            lensCallback(null)
        }
    }
    LaunchedEffect(view, owner, optics, streaming, focalLength) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val o = optics
                val transform = if (streaming) view.sensorToViewTransform else null
                lensCallback(if (o != null && transform != null && focalLength != null && focalLength!! > 0) {
                    // Transform sensor focal vectors to preview pixels. This incorporates
                    // sensor rotation, stream crop, and PreviewView's FILL_CENTER crop.
                    val (cx, cy) = o.third
                    val points = floatArrayOf(cx, cy, cx + (o.first * focalLength!!).toFloat(), cy, cx, cy + (o.second * focalLength!!).toFloat())
                    transform.mapPoints(points)
                    val fx = hypot((points[2] - points[0]).toDouble(), (points[4] - points[0]).toDouble())
                    val fy = hypot((points[3] - points[1]).toDouble(), (points[5] - points[1]).toDouble())
                    if (fx > 0 && fy > 0) CameraLens(fx, fy, points[0].toDouble(), points[1].toDouble()) else null
                } else null)
                delay(100)
            }
        }
    }
}

@Composable
private fun CameraLabels(state: PanoramaState, lens: CameraLens, modifier: Modifier, onPeak: (PeakView) -> Unit) {
    val hits = remember { mutableListOf<Pair<RectF, PeakView>>() }
    val selected by rememberUpdatedState(onPeak)
    Canvas(modifier.semantics { contentDescription = "Live mountain peak labels. Details are also available in Peaks." }
        .pointerInput(Unit) { detectTapGestures { p -> hits.lastOrNull { it.first.contains(p.x, p.y) }?.let { selected(it.second) } } }) {
        hits.clear()
        val pose = state.cameraPose ?: return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12 * density; color = android.graphics.Color.WHITE }
        val occupied = mutableListOf<RectF>()
        val peaks = state.scene?.peaks.orEmpty().filter { it.visibility == Visibility.TERRAIN_VISIBLE || state.preferences.showUncertain && it.visibility == Visibility.UNCERTAIN }
        for (peak in peaks.sortedBy { it.distanceM }) {
            val point = CameraProjector.project(peak.bearingDeg, peak.elevationDeg ?: continue, pose, lens) ?: continue
            if (point.x !in 0f..size.width || point.y !in 0f..size.height) continue
            val label = peak.peak.name + if (peak.visibility == Visibility.UNCERTAIN) " ?" else ""
            val detail = "${peak.peak.publishedHeightM?.let { height(it, state) } ?: peak.geometryHeightM?.let { "~${height(it, state)} terrain" } ?: "Height unknown"} · ${distance(peak.distanceM, state)}"
            val width = maxOf(paint.measureText(label), paint.measureText(detail)).coerceAtMost(size.width - 32 * density) + 16 * density
            val left = (point.x - width / 2).coerceIn(8 * density, maxOf(8 * density, size.width - width - 8 * density))
            val boxHeight = 44 * density
            var rect: RectF? = null
            for (lane in 0..3) {
                val top = (point.y - (65 + lane * 48) * density).coerceAtLeast(8 * density)
                val candidate = RectF(left, top, left + width, top + boxHeight)
                if (candidate.bottom < size.height - 44 * density && occupied.none { RectF.intersects(it, candidate) }) { rect = candidate; break }
            }
            val box = rect ?: continue
            occupied += box
            drawLine(Color.White, Offset(point.x, point.y), Offset(box.centerX(), box.bottom), density)
            drawCircle(Color.White, 4 * density, Offset(point.x, point.y))
            drawRoundRect(Color(0xDD132A24), Offset(box.left, box.top), androidx.compose.ui.geometry.Size(box.width(), box.height()), androidx.compose.ui.geometry.CornerRadius(6 * density))
            val text = android.text.TextUtils.ellipsize(label, android.text.TextPaint(paint), width - 16 * density, android.text.TextUtils.TruncateAt.END).toString()
            drawContext.canvas.nativeCanvas.drawText(text, box.left + 8 * density, box.top + 17 * density, paint)
            paint.textSize = 10 * density
            drawContext.canvas.nativeCanvas.drawText(detail, box.left + 8 * density, box.top + 34 * density, paint)
            paint.textSize = 12 * density
            hits += box to peak
        }
    }
}

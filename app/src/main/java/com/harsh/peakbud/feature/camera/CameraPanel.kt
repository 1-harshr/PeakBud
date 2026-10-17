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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.harsh.peakbud.telemetry.Telemetry
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** Camera permission is requested only by the explicit Enable camera action. */
@Composable
fun CameraPanel(state: PanoramaState, modifier: Modifier, onRegions: () -> Unit, onGps: () -> Unit, onTrack: () -> Unit, onFov: (Pair<Double, Double>?) -> Unit,
                fullScreen: Boolean = false, safeTop: androidx.compose.ui.unit.Dp = 0.dp, safeBottom: androidx.compose.ui.unit.Dp = 0.dp,
                onBackgroundTap: () -> Unit = {}, onDragNudge: (Double) -> Unit = {}, onDragEnd: () -> Unit = {}, onPeak: (PeakView) -> Unit) {
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
        !permission -> "Turn on the camera to see peak names over the view in front of you."
        cameraStatus != null -> cameraStatus
        state.noRegion -> "Download a hiking area to see the names of peaks around you."
        state.isDemo -> "This is a made-up demo landscape. Download a hiking area to name real peaks."
        !state.followingGps -> "Share your location so peaks appear in the right place."
        !state.gpsFresh -> state.gpsStatus ?: "Finding your location…"
        !state.followingCompass -> "Turn on the compass so labels follow where you point."
        state.cameraPose == null -> "Point the back of your phone toward the mountains."
        !state.poseReliable && !fullScreen -> state.compassStatus ?: "Compass is unsteady. Move away from metal and wave your phone in a figure eight."
        state.calculating -> "Finding the peaks you can see…"
        state.scene == null -> "This hiking area doesn't cover where you are. Pick one that does."
        lens == null -> "This phone's camera can't place labels accurately. Use Home instead."
        else -> null
    }
    Box(modifier.onSizeChanged { viewport = it }.clipToBounds().background(Color.Black)) {
        if (permission) CameraPreview(Modifier.fillMaxSize(), { lens = it }, { cameraStatus = it })
        if (notice == null) CameraLabels(state, lens!!, Modifier.fillMaxSize(), safeTop, safeBottom, onBackgroundTap, onDragNudge, onDragEnd, onPeak)
        else if (fullScreen) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onBackgroundTap() } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume() } })  // a swipe is not a tap
        if (notice != null) {
            val action: (@Composable () -> Unit)? = when {
                !permission -> { { Button(onClick = {
                    if (denied) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    else request.launch(Manifest.permission.CAMERA) }) { Text(if (denied) "Open settings" else "Turn on camera") } } }
                cameraStatus != null -> null
                state.isDemo || state.noRegion -> { { Button(onClick = onRegions) { Text("Choose region") } } }
                !state.followingGps -> { { Button(onClick = onGps) { Text("Use my location") } } }
                !state.followingCompass -> { { Button(onClick = onTrack) { Text("Turn on compass") } } }
                else -> null
            }
            // A notice with an action blocks use, so it sits centre stage; progress notes stay small near the top.
            val blocking = action != null
            Surface(Modifier.align(if (blocking && fullScreen) Alignment.Center else Alignment.TopCenter).widthIn(max = 340.dp)
                .padding(start = 16.dp, end = 16.dp, top = if (fullScreen && !blocking) safeTop + 8.dp else 12.dp, bottom = 12.dp),
                shape = RoundedCornerShape(if (blocking) 24.dp else 50.dp),
                color = if (blocking) MaterialTheme.colorScheme.surface.copy(alpha = .94f) else Color.Black.copy(alpha = .55f),
                contentColor = if (blocking) MaterialTheme.colorScheme.onSurface else Color.White) {
                Column(Modifier.padding(horizontal = if (blocking) 20.dp else 16.dp, vertical = if (blocking) 20.dp else 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = if (blocking) Alignment.Start else Alignment.CenterHorizontally) {
                    Text(notice, style = if (blocking) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge)
                    action?.invoke()
                }
            }
        }
        if (notice == null && !fullScreen) Surface(Modifier.align(Alignment.BottomCenter).padding(10.dp), shape = RoundedCornerShape(12.dp), color = Color(0xCC132A24), contentColor = Color.White) {
            Text("Labels off? Use Nudge labels to line them up.", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
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
                check(provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { "This phone has no rear camera. Use Home instead." }
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
                        if (!disposed && value.error != null) { Telemetry.log("camera error ${value.error?.code}"); streaming = false; statusCallback("The camera stopped. Switch to Home and back to restart it.") }
                    }
                }.also { cameraObserver = it; observedCamera = camera })
            } catch (error: Exception) {
                Telemetry.error("camera start", error)
                statusCallback(error.message ?: "Couldn't start the camera. Check camera access in Android settings, or use Home.")
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
private fun CameraLabels(state: PanoramaState, lens: CameraLens, modifier: Modifier, safeTop: androidx.compose.ui.unit.Dp, safeBottom: androidx.compose.ui.unit.Dp,
                         onBackgroundTap: () -> Unit, onDragNudge: (Double) -> Unit, onDragEnd: () -> Unit, onPeak: (PeakView) -> Unit) {
    val hits = remember { mutableListOf<Pair<RectF, PeakView>>() }
    val selected by rememberUpdatedState(onPeak)
    val background by rememberUpdatedState(onBackgroundTap)
    val nudge by rememberUpdatedState(onDragNudge)
    val dragEnd by rememberUpdatedState(onDragEnd)
    Canvas(modifier.semantics { contentDescription = "Live mountain peak labels. Details are also available in Peaks." }
        .pointerInput(Unit) { detectTapGestures { p -> hits.lastOrNull { it.first.contains(p.x, p.y) }?.let { selected(it.second) } ?: background() } }
        // Drag sideways to line labels up with the real ridges; degrees follow the lens's horizontal scale.
        .pointerInput(lens) { detectHorizontalDragGestures(onDragEnd = { dragEnd() }) { change, dx -> change.consume(); nudge(-Math.toDegrees(kotlin.math.atan(dx / lens.fx))) } }) {
        hits.clear()
        val pose = state.cameraPose ?: return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * density; color = android.graphics.Color.WHITE; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setShadowLayer(4 * density, 0f, 1 * density, 0x99000000.toInt()) }
        val occupied = mutableListOf<RectF>()
        val peaks = state.scene?.peaks.orEmpty().filter { it.visibility == Visibility.TERRAIN_VISIBLE || state.preferences.showUncertain && it.visibility == Visibility.UNCERTAIN }
        for (peak in peaks.sortedBy { it.distanceM }) {
            val point = CameraProjector.project(peak.bearingDeg, peak.elevationDeg ?: continue, pose, lens) ?: continue
            // Overlays cover the top and bottom; keep labels and their anchors inside the clear area.
            val clearTop = safeTop.toPx() + 8 * density
            val clearBottom = size.height - safeBottom.toPx()
            if (point.x !in 0f..size.width || point.y !in clearTop..clearBottom) continue
            val label = peak.peak.name + if (peak.visibility == Visibility.UNCERTAIN) " ?" else ""
            val detail = "${peak.peak.publishedHeightM?.let { height(it, state) } ?: peak.geometryHeightM?.let { "~${height(it, state)}" } ?: "Height unknown"} · ${distance(peak.distanceM, state)}"
            val width = maxOf(paint.measureText(label), paint.measureText(detail)).coerceAtMost(size.width - 32 * density) + 16 * density
            val left = (point.x - width / 2).coerceIn(8 * density, maxOf(8 * density, size.width - width - 8 * density))
            val boxHeight = 44 * density
            var rect: RectF? = null
            for (lane in 0..3) {
                val top = (point.y - (65 + lane * 48) * density).coerceAtLeast(clearTop)
                val candidate = RectF(left, top, left + width, top + boxHeight)
                if (candidate.bottom < clearBottom - 8 * density && occupied.none { RectF.intersects(it, candidate) }) { rect = candidate; break }
            }
            val box = rect ?: continue
            occupied += box
            // No box: shadowed text keeps the ridgeline visible. A faint dark leader under the white one keeps it legible on snow.
            drawLine(Color.Black.copy(alpha = .35f), Offset(point.x, point.y), Offset(box.centerX(), box.bottom), 3 * density)
            drawLine(Color.White, Offset(point.x, point.y), Offset(box.centerX(), box.bottom), density)
            drawCircle(Color.Black.copy(alpha = .35f), 6 * density, Offset(point.x, point.y))
            drawCircle(Color.White, 4 * density, Offset(point.x, point.y))
            val text = android.text.TextUtils.ellipsize(label, android.text.TextPaint(paint), width - 16 * density, android.text.TextUtils.TruncateAt.END).toString()
            drawContext.canvas.nativeCanvas.drawText(text, box.left + 8 * density, box.top + 17 * density, paint)
            paint.textSize = 11 * density; paint.alpha = 220
            drawContext.canvas.nativeCanvas.drawText(detail, box.left + 8 * density, box.top + 34 * density, paint)
            paint.textSize = 15 * density; paint.alpha = 255
            hits += box to peak
        }
    }
}

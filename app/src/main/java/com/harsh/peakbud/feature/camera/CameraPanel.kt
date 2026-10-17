package com.harsh.peakbud.feature.camera

import com.harsh.peakbud.ui.text.*
import com.harsh.peakbud.R
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
import com.harsh.peakbud.ui.theme.*
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
                fullScreen: Boolean = false, safeTop: androidx.compose.ui.unit.Dp = TrailSpace.none, safeBottom: androidx.compose.ui.unit.Dp = TrailSpace.none,
                onBackgroundTap: () -> Unit = {}, onDragNudge: (Double) -> Unit = {}, onDragEnd: () -> Unit = {}, onPeak: (PeakView) -> Unit) {
    val strings = rememberTrailStrings()
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
    var cameraStatus by remember { mutableStateOf<String?>(strings(R.string.starting_camera)) }
    val notice = when {
        !permission -> strings(R.string.turn_on_the_camera_to_see_peak_names_over_the_view_in_front_of_you)
        cameraStatus != null -> cameraStatus
        state.noRegion -> strings(R.string.download_a_hiking_area_to_see_the_names_of_peaks_around_you)
        state.isDemo -> strings(R.string.this_is_a_made_up_demo_landscape_download_a_hiking_area_to_name_real_peaks)
        !state.followingGps -> strings(R.string.share_your_location_so_peaks_appear_in_the_right_place)
        !state.gpsFresh -> state.gpsStatus ?: strings(R.string.finding_your_location)
        !state.followingCompass -> strings(R.string.turn_on_the_compass_so_labels_follow_where_you_point)
        state.cameraPose == null -> strings(R.string.point_the_back_of_your_phone_toward_the_mountains)
        !state.poseReliable && !fullScreen -> state.compassStatus ?: strings(R.string.compass_is_unsteady_move_away_from_metal_and_wave_your_phone_in_a_figure_eig)
        state.calculating -> strings(R.string.finding_the_peaks_you_can_see)
        state.scene == null -> strings(R.string.this_hiking_area_doesn_t_cover_where_you_are_pick_one_that_does)
        lens == null -> strings(R.string.this_phone_s_camera_can_t_place_labels_accurately_use_home_instead)
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
                    else request.launch(Manifest.permission.CAMERA) }) { Text(if (denied) strings(R.string.open_settings) else strings(R.string.turn_on_camera)) } } }
                cameraStatus != null -> null
                state.isDemo || state.noRegion -> { { Button(onClick = onRegions) { Text(strings(R.string.choose_region)) } } }
                !state.followingGps -> { { Button(onClick = onGps) { Text(strings(R.string.use_my_location)) } } }
                !state.followingCompass -> { { Button(onClick = onTrack) { Text(strings(R.string.turn_on_compass)) } } }
                else -> null
            }
            // A notice with an action blocks use, so it sits centre stage; progress notes stay small near the top.
            val blocking = action != null
            Surface(Modifier.align(if (blocking && fullScreen) Alignment.Center else Alignment.TopCenter).widthIn(max = TrailSize.noticeWidth)
                .padding(start = TrailSpace.lg, end = TrailSpace.lg, top = if (fullScreen && !blocking) safeTop + TrailSpace.sm else TrailSpace.md, bottom = TrailSpace.md),
                shape = if (blocking) MaterialTheme.shapes.large else androidx.compose.foundation.shape.CircleShape,
                color = if (blocking) MaterialTheme.colorScheme.surface.copy(alpha = .94f) else TrailCameraColors.scrim,
                contentColor = if (blocking) MaterialTheme.colorScheme.onSurface else Color.White) {
                Column(Modifier.padding(horizontal = if (blocking) TrailSpace.sheet else TrailSpace.lg, vertical = if (blocking) TrailSpace.sheet else TrailSpace.md),
                    verticalArrangement = Arrangement.spacedBy(TrailSpace.lg), horizontalAlignment = if (blocking) Alignment.Start else Alignment.CenterHorizontally) {
                    Text(notice, style = if (blocking) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge)
                    action?.invoke()
                }
            }
        }
        if (notice == null && !fullScreen) Surface(Modifier.align(Alignment.BottomCenter).padding(TrailSpace.md), shape = MaterialTheme.shapes.small, color = TrailCameraColors.scrim, contentColor = Color.White) {
            Text(strings(R.string.labels_off_use_nudge_labels_to_line_them_up), Modifier.padding(TrailSpace.sm), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraPreview(modifier: Modifier, onLens: (CameraLens?) -> Unit, onStatus: (String?) -> Unit) {
    val strings = rememberTrailStrings()
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
    AndroidView(factory = { view }, modifier = modifier.semantics { contentDescription = if (streaming) strings(R.string.live_rear_camera_preview) else strings(R.string.camera_preview_starting) })
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
                check(provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { strings(R.string.this_phone_has_no_rear_camera_use_home_instead) }
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
                        if (!disposed && value.error != null) { Telemetry.log("camera error ${value.error?.code}"); streaming = false; statusCallback(strings(R.string.the_camera_stopped_switch_to_home_and_back_to_restart_it)) }
                    }
                }.also { cameraObserver = it; observedCamera = camera })
            } catch (error: Exception) {
                Telemetry.error("camera start", error)
                statusCallback(strings(R.string.couldn_t_start_the_camera_check_camera_access_in_android_settings_or_use_hom))
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
    val strings = rememberTrailStrings()
    val type = peakLabelTypography()
    val hits = remember { mutableListOf<Pair<RectF, PeakView>>() }
    val selected by rememberUpdatedState(onPeak)
    val background by rememberUpdatedState(onBackgroundTap)
    val nudge by rememberUpdatedState(onDragNudge)
    val dragEnd by rememberUpdatedState(onDragEnd)
    Canvas(modifier.semantics { contentDescription = strings(R.string.live_mountain_peak_labels_details_are_also_available_in_peaks) }
        .pointerInput(Unit) { detectTapGestures { p -> hits.lastOrNull { it.first.contains(p.x, p.y) }?.let { selected(it.second) } ?: background() } }
        // Drag sideways to line labels up with the real ridges; degrees follow the lens's horizontal scale.
        .pointerInput(lens) { detectHorizontalDragGestures(onDragEnd = { dragEnd() }) { change, dx -> change.consume(); nudge(-Math.toDegrees(kotlin.math.atan(dx / lens.fx))) } }) {
        hits.clear()
        val pose = state.cameraPose ?: return@Canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = type.titlePx; color = android.graphics.Color.WHITE; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setShadowLayer(TrailCanvas.shadowRadius.toPx(), 0f, TrailStroke.thin.toPx(), 0x99000000.toInt()) }
        val occupied = mutableListOf<RectF>()
        val peaks = state.scene?.peaks.orEmpty().filter { it.visibility == Visibility.TERRAIN_VISIBLE || state.preferences.showUncertain && it.visibility == Visibility.UNCERTAIN }
        for (peak in peaks.inLabelPriority()) {
            val point = CameraProjector.project(peak.bearingDeg, peak.elevationDeg ?: continue, pose, lens) ?: continue
            // Overlays cover the top and bottom; keep labels and their anchors inside the clear area.
            val clearTop = safeTop.toPx() + TrailCanvas.labelMargin.toPx()
            val clearBottom = size.height - safeBottom.toPx()
            if (point.x !in 0f..size.width || point.y !in clearTop..clearBottom) continue
            val label = peak.peak.name + if (peak.visibility == Visibility.UNCERTAIN) " ?" else ""
            val heightText = peak.peak.publishedHeightM?.let { height(it, state, strings) }
                ?: peak.geometryHeightM?.let { strings(R.string.approximate_height, height(it, state, strings)) }
                ?: strings(R.string.height_unknown)
            val detail = strings(R.string.peak_label_detail, heightText, distance(peak.distanceM, state, strings))
            val width = maxOf(paint.measureText(label), paint.measureText(detail)).coerceAtMost(size.width - TrailSpace.xxl.toPx()) + TrailSpace.lg.toPx()
            val left = (point.x - width / 2).coerceIn(TrailCanvas.labelMargin.toPx(), maxOf(TrailCanvas.labelMargin.toPx(), size.width - width - TrailCanvas.labelMargin.toPx()))
            val boxHeight = type.cardHeight
            var rect: RectF? = null
            for (lane in 0..3) {
                val top = (point.y - (TrailCanvas.anchorGap.toPx() + boxHeight + lane * (boxHeight + TrailSpace.sm.toPx()))).coerceAtLeast(clearTop)
                val candidate = RectF(left, top, left + width, top + boxHeight)
                if (candidate.bottom < clearBottom - TrailCanvas.labelMargin.toPx() && occupied.none { RectF.intersects(it, candidate) }) { rect = candidate; break }
            }
            val box = rect ?: continue
            occupied += box
            // A shared dark backing keeps labels legible over both snow and dark rock.
            drawRoundRect(TrailCameraColors.labelSurface, Offset(box.left, box.top),
                androidx.compose.ui.geometry.Size(box.width(), box.height()), androidx.compose.ui.geometry.CornerRadius(TrailCanvas.labelCorner.toPx()))
            drawLine(Color.Black.copy(alpha = .35f), Offset(point.x, point.y), Offset(box.centerX(), box.bottom), TrailStroke.heavy.toPx())
            drawLine(Color.White, Offset(point.x, point.y), Offset(box.centerX(), box.bottom), TrailStroke.thin.toPx())
            drawCircle(Color.Black.copy(alpha = .35f), TrailCanvas.markerHalo.toPx(), Offset(point.x, point.y))
            drawCircle(Color.White, TrailCanvas.marker.toPx(), Offset(point.x, point.y))
            val text = android.text.TextUtils.ellipsize(label, android.text.TextPaint(paint), width - TrailSpace.lg.toPx(), android.text.TextUtils.TruncateAt.END).toString()
            drawContext.canvas.nativeCanvas.drawText(text, box.left + TrailCanvas.labelMargin.toPx(), box.top + type.titleBaseline, paint)
            paint.textSize = type.detailPx; paint.alpha = 220
            val detailText = android.text.TextUtils.ellipsize(detail, android.text.TextPaint(paint), width - TrailSpace.lg.toPx(), android.text.TextUtils.TruncateAt.END).toString()
            drawContext.canvas.nativeCanvas.drawText(detailText, box.left + TrailCanvas.labelMargin.toPx(), box.top + type.detailBaseline, paint)
            paint.textSize = type.titlePx; paint.alpha = 255
            hits += box to peak
        }
    }
}

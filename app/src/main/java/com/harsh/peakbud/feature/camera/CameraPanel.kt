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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.harsh.peakbud.core.geo.Geography
import com.harsh.peakbud.feature.panorama.*
import com.harsh.peakbud.telemetry.Telemetry
import com.harsh.peakbud.core.alignment.UprightFrame
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** Camera permission is requested only by the explicit Enable camera action. */
@Composable
fun CameraPanel(state: PanoramaState, modifier: Modifier, onRegions: () -> Unit, onGps: () -> Unit, onTrack: () -> Unit, onFov: (Pair<Double, Double>?) -> Unit,
                fullScreen: Boolean = false, safeTop: androidx.compose.ui.unit.Dp = TrailSpace.none, safeBottom: androidx.compose.ui.unit.Dp = TrailSpace.none,
                onBackgroundTap: () -> Unit = {}, onDragNudge: (Double) -> Unit = {}, onDragEnd: () -> Unit = {},
                pose: CameraPose? = state.cameraPose, aligned: Boolean = false, hiddenPeaks: Set<String> = emptySet(),
                alignment: CameraAlignmentController? = null, rawPose: () -> CameraPose? = { state.cameraPose }, onPeak: (PeakView) -> Unit) {
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
        pose == null -> strings(R.string.point_the_back_of_your_phone_toward_the_mountains)
        !state.poseReliable && !fullScreen -> state.compassStatus ?: strings(R.string.compass_is_unsteady_move_away_from_metal_and_wave_your_phone_in_a_figure_eig)
        state.calculating && state.scene == null -> strings(R.string.finding_the_peaks_you_can_see)
        state.scene == null -> strings(R.string.this_hiking_area_doesn_t_cover_where_you_are_pick_one_that_does)
        lens == null -> strings(R.string.this_phone_s_camera_can_t_place_labels_accurately_use_home_instead)
        else -> null
    }
    Box(modifier.onSizeChanged { viewport = it }.clipToBounds().background(Color.Black)) {
        if (permission) CameraPreview(Modifier.fillMaxSize(), { lens = it }, { cameraStatus = it }, alignment, rawPose) { state.scene?.peaks.orEmpty() }
        if (permission && cameraStatus == null && state.followingCompass && pose != null && lens != null)
            CameraHorizon(pose, lens!!, Modifier.fillMaxSize())
        if (notice == null) CameraLabels(state, pose!!, aligned, hiddenPeaks, lens!!, Modifier.fillMaxSize(), safeTop, safeBottom, onBackgroundTap, onDragNudge, onDragEnd, onPeak)
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
            // Blocking notices sit centred in the space between the top chips and the bottom controls.
            // Wider in landscape so the message fits in fewer lines within the short height.
            val wide = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
            Surface(Modifier.align(if (blocking && fullScreen) Alignment.Center else Alignment.TopCenter).widthIn(max = if (wide) TrailSize.noticeWidth * 1.6f else TrailSize.noticeWidth)
                .padding(start = TrailSpace.lg, end = TrailSpace.lg,
                    top = if (fullScreen) safeTop + TrailSpace.sm else TrailSpace.md,
                    bottom = if (fullScreen && blocking) safeBottom else TrailSpace.md),
                shape = if (blocking) MaterialTheme.shapes.large else androidx.compose.foundation.shape.CircleShape,
                color = if (blocking) MaterialTheme.colorScheme.surface.copy(alpha = .94f) else TrailCameraColors.scrim,
                contentColor = if (blocking) MaterialTheme.colorScheme.onSurface else Color.White) {
                Column(Modifier.padding(horizontal = if (blocking) TrailSpace.sheet else TrailSpace.lg, vertical = if (blocking) TrailSpace.sheet else TrailSpace.md),
                    verticalArrangement = Arrangement.spacedBy(TrailSpace.lg), horizontalAlignment = if (blocking) Alignment.Start else Alignment.CenterHorizontally) {
                    // Text may shrink-scroll in short landscape windows; the action button must always stay visible.
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
private fun CameraPreview(modifier: Modifier, onLens: (CameraLens?) -> Unit, onStatus: (String?) -> Unit,
                          alignment: CameraAlignmentController?, rawPose: () -> CameraPose?, peaks: () -> List<PeakView>) {
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
    // Read by the analysis thread: preview geometry captured on the main thread.
    val frameGeometry = remember { java.util.concurrent.atomic.AtomicReference<CameraFrameGeometry?>(null) }
    val currentAlignment by rememberUpdatedState(alignment)
    val currentPose by rememberUpdatedState(rawPose)
    val currentPeaks by rememberUpdatedState(peaks)
    AndroidView(factory = { view }, modifier = modifier.semantics { contentDescription = if (streaming) strings(R.string.live_rear_camera_preview) else strings(R.string.camera_preview_starting) })
    DisposableEffect(view, owner) {
        val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
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
                    ContextCompat.getMainExecutor(context).execute { if (!disposed.get() && focal != null) focalLength = focal }
                }
            }
        )
        val preview = builder.build()
        // Frame analysis for "Align with camera": one dedicated worker, latest frame only, small RGBA frames.
        val analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "peakbud-alignment") }
        val analysis = androidx.camera.core.ImageAnalysis.Builder()
            .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(view.display?.rotation ?: android.view.Surface.ROTATION_0)
            .setResolutionSelector(androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(android.util.Size(640, 480),
                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
            .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val controller = currentAlignment
                val geometry = frameGeometry.get()
                if (!disposed.get() && geometry != null && controller != null && controller.wantsFrame()) analyseFrame(image, geometry, controller, currentPose(), currentPeaks())
            } catch (error: Exception) {
                Telemetry.error("alignment frame", error)
            } finally {
                image.close()  // every frame, or CameraX stops delivering them
            }
        }
        val streamObserver = Observer<PreviewView.StreamState> { value ->
            streaming = value == PreviewView.StreamState.STREAMING
            if (streaming) statusCallback(null)
            else {
                frameGeometry.set(null)
                lensCallback(null)
                currentAlignment?.reset()
            }
        }
        view.previewStreamState.observe(owner, streamObserver)
        // LiveData does not deliver IDLE while its owner is stopped. Clear camera-derived state immediately.
        val stopObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                streaming = false
                frameGeometry.set(null)
                lensCallback(null)
                currentAlignment?.reset()
            }
        }
        owner.lifecycle.addObserver(stopObserver)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed.get()) try {
                provider = future.get()
                check(provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { strings(R.string.this_phone_has_no_rear_camera_use_home_instead) }
                preview.setSurfaceProvider(view.surfaceProvider)
                // Preview and analysis share the preview's viewport so they cover the same scene. Mapping still goes
                // through sensor coordinates, so it stays correct even if the viewport is not ready yet.
                val group = androidx.camera.core.UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
                view.viewPort?.let { group.setViewPort(it) }
                val camera = provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group.build())
                val info = Camera2CameraInfo.from(camera.cameraInfo)
                val physical = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pixels = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                optics = if (physical != null && pixels != null && active != null && physical.width > 0 && physical.height > 0)
                    Triple(pixels.width.toDouble() / physical.width, pixels.height.toDouble() / physical.height,
                        active.exactCenterX() to active.exactCenterY()) else null
                camera.cameraInfo.cameraState.observe(owner, object : Observer<androidx.camera.core.CameraState> {
                    override fun onChanged(value: androidx.camera.core.CameraState) {
                        if (!disposed.get() && value.error != null) { Telemetry.log("camera error ${value.error?.code}"); streaming = false; statusCallback(strings(R.string.the_camera_stopped_switch_to_home_and_back_to_restart_it)) }
                    }
                }.also { cameraObserver = it; observedCamera = camera })
            } catch (error: Exception) {
                Telemetry.error("camera start", error)
                statusCallback(strings(R.string.couldn_t_start_the_camera_check_camera_access_in_android_settings_or_use_hom))
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed.set(true)
            view.previewStreamState.removeObserver(streamObserver)
            owner.lifecycle.removeObserver(stopObserver)
            cameraObserver?.let { observedCamera?.cameraInfo?.cameraState?.removeObserver(it) }
            cameraObserver = null; observedCamera = null
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            analysisExecutor.shutdown()
            alignment?.reset()
            frameGeometry.set(null)
            lensCallback(null)
        }
    }
    LaunchedEffect(view, owner, optics, streaming, focalLength) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val o = optics
                val transform = if (streaming) view.sensorToViewTransform else null
                val lens = if (o != null && transform != null && focalLength != null && focalLength!! > 0) {
                    // Transform sensor focal vectors to preview pixels. This incorporates
                    // sensor rotation, stream crop, and PreviewView's FILL_CENTER crop.
                    val (cx, cy) = o.third
                    val points = floatArrayOf(cx, cy, cx + (o.first * focalLength!!).toFloat(), cy, cx, cy + (o.second * focalLength!!).toFloat())
                    transform.mapPoints(points)
                    val fx = hypot((points[2] - points[0]).toDouble(), (points[4] - points[0]).toDouble())
                    val fy = hypot((points[3] - points[1]).toDouble(), (points[5] - points[1]).toDouble())
                    if (fx > 0 && fy > 0) CameraLens(fx, fy, points[0].toDouble(), points[1].toDouble()) else null
                } else null
                frameGeometry.set(if (lens != null && transform != null && view.width > 0 && view.height > 0)
                    CameraFrameGeometry(lens, view.width, view.height, transform) else null)
                lensCallback(lens)
                delay(100)
            }
        }
    }
}

@Composable
private fun CameraLabels(state: PanoramaState, pose: CameraPose, aligned: Boolean, hiddenPeaks: Set<String>, lens: CameraLens, modifier: Modifier, safeTop: androidx.compose.ui.unit.Dp, safeBottom: androidx.compose.ui.unit.Dp,
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
        if (size.width <= TrailSpace.lg.toPx() * 2 || size.height <= 0) return@Canvas
        val peaks = state.scene?.peaks.orEmpty().toLabel(state.preferences.showUncertain, state.focusedPeakId)
        // Overlays cover the top and bottom; dots stay inside the clear area.
        val clearTop = safeTop.toPx() + TrailCanvas.labelMargin.toPx()
        val clearBottom = size.height - safeBottom.toPx()
        val candidates = peaks.mapNotNull { peak ->
            if (peak.peak.id in hiddenPeaks) return@mapNotNull null  // the camera shows something nearer covering this summit
            val point = CameraProjector.project(peak.bearingDeg, peak.elevationDeg ?: return@mapNotNull null, pose, lens) ?: return@mapNotNull null
            if (point.x !in 0f..size.width || point.y !in clearTop..clearBottom) return@mapNotNull null
            PinCandidate(peak, point.x, point.y, pinName(peak, strings),
                peak.peak.publishedHeightM?.let { height(it, state, strings) } ?: peak.geometryHeightM?.let { strings(R.string.approximate_height, height(it, state, strings)) })
        }
        // Still pins: dot on the summit, vertical stick, tilted name/height pill. Camera-aligned pins get a green ring.
        val style = PinStyle(stick = Color.White, dot = Color.White, nameSurface = Color.White, nameText = Color(0xFF1B2B25),
            heightSurface = TrailCameraColors.pinHeight, heightText = Color.White, ring = if (aligned) TrailCameraColors.alignedRing else null)
        val textSize = type.detailPx * 1.05f
        val focusStyle = style.copy(ring = TrailCameraColors.uncertain)
        for (pin in PeakPins.place(this, candidates, textSize, clearTop)) {
            hits += PeakPins.draw(this, pin.x, pin.y, pin.name, pin.height, if (pin.item.peak.id == state.focusedPeakId) focusStyle else style, textSize, clearTop) to pin.item
        }
        // The focused peak is out of frame: an arrow at the edge shows which way to turn.
        state.focusedPeak?.takeIf { f -> candidates.none { it.item.peak.id == f.peak.id } }?.let { focus ->
            val turnRight = Geography.wrap180(focus.bearingDeg - pose.heading) > 0
            val y = (clearTop + clearBottom) / 2
            val radius = TrailSpace.lg.toPx()
            val cx = if (turnRight) size.width - TrailSpace.md.toPx() - radius else TrailSpace.md.toPx() + radius
            drawCircle(TrailCameraColors.scrim, radius, Offset(cx, y))
            val dir = if (turnRight) 1f else -1f
            val arm = radius * .45f
            drawLine(Color.White, Offset(cx - dir * arm * .5f, y - arm), Offset(cx + dir * arm * .5f, y), TrailSpace.xxs.toPx() * 1.5f, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color.White, Offset(cx + dir * arm * .5f, y), Offset(cx - dir * arm * .5f, y + arm), TrailSpace.xxs.toPx() * 1.5f, androidx.compose.ui.graphics.StrokeCap.Round)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; this.textSize = type.detailPx; setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK) }
            val label = strings(R.string.turn_to_peak, focus.peak.name, kotlin.math.abs(Geography.wrap180(focus.bearingDeg - pose.heading)).toInt())
            val tw = paint.measureText(label)
            val tx = if (turnRight) cx - radius - TrailSpace.sm.toPx() - tw else cx + radius + TrailSpace.sm.toPx()
            drawContext.canvas.nativeCanvas.drawText(label, tx, y - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }
}


/** Downsample the frame upright, then map boundary points to preview pixels through sensor coordinates. */
private fun analyseFrame(image: androidx.camera.core.ImageProxy, geometry: CameraFrameGeometry, controller: CameraAlignmentController,
                         pose: CameraPose?, peaks: List<PeakView>) {
    val width = geometry.width; val height = geometry.height
    val toPreview = geometry.bufferToView(image.imageInfo.sensorToBufferTransformMatrix) ?: return
    val plane = image.planes[0]
    val buffer = plane.buffer; val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
    val crop = image.cropRect
    val frame = UprightFrame.sample(crop.left, crop.top, crop.width(), crop.height(), image.imageInfo.rotationDegrees, 320) { x, y ->
        val offset = y * rowStride + x * pixelStride
        (0xff shl 24) or ((buffer.get(offset).toInt() and 255) shl 16) or ((buffer.get(offset + 1).toInt() and 255) shl 8) or (buffer.get(offset + 2).toInt() and 255)
    }
    val point = FloatArray(2)
    controller.process(frame, { bx, by ->
        point[0] = bx.toFloat(); point[1] = by.toFloat()
        toPreview.mapPoints(point)
        if (point[0] in 0f..width.toFloat() && point[1] in 0f..height.toFloat()) point[0].toDouble() to point[1].toDouble() else null
    }, geometry.lens, pose, peaks)
}

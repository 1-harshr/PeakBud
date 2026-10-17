package com.harsh.peakbud.feature.camera

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.harsh.peakbud.core.model.PeakView
import com.harsh.peakbud.feature.panorama.*

@Composable
fun FullScreenCamera(state: PanoramaState, onExit: () -> Unit, onRegions: () -> Unit, onGps: () -> Unit,
                     onTrack: () -> Unit, onFov: (Pair<Double, Double>?) -> Unit, onPeak: (PeakView) -> Unit) {
    val context = LocalContext.current
    val window = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
    }
    DisposableEffect(window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            previousBehavior?.let { controller?.systemBarsBehavior = it }
        }
    }
    var fov by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    Box(Modifier.fillMaxSize()) {
        CameraPanel(state, Modifier.fillMaxSize(), onRegions, onGps, onTrack,
            { fov = it; onFov(it) }, fullScreen = true, onPeak = onPeak)
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            FilledTonalButton(onClick = onExit, modifier = Modifier.align(Alignment.TopEnd),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xDD132A24), contentColor = Color.White)) {
                Text("Exit full screen")
            }
            Surface(Modifier.align(Alignment.BottomStart).widthIn(max = 420.dp)
                .semantics { contentDescription = "Camera location metadata" },
                shape = RoundedCornerShape(12.dp), color = Color(0xBB132A24), contentColor = Color.White) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val bearing = state.cameraPose?.heading
                    val reference = if (state.gpsFix != null || !state.isDemo) "true north" else "magnetic north"
                    Text(bearing?.let { "${it.toInt().toString().padStart(3, '0')}° ${cardinal(it)} · $reference" } ?: "Direction unavailable",
                        style = MaterialTheme.typography.labelLarge)
                    val fix = state.gpsFix
                    Text(fix?.let { "Lat ${coordinate(it.point.latitude)} · Lon ${coordinate(it.point.longitude)}" } ?: "Lat / Lon · waiting for GPS",
                        style = MaterialTheme.typography.bodySmall)
                    val altitude = fix?.seaLevelAltitudeM ?: fix?.ellipsoidAltitudeM
                    Text(altitude?.let { "Altitude ${height(it, state)} · GPS ${if (fix?.seaLevelAltitudeM != null) "sea level" else "ellipsoid"}" } ?: "Altitude · waiting for GPS",
                        style = MaterialTheme.typography.bodySmall)
                    fov?.let { Text("View ${it.first.toInt()}° H × ${it.second.toInt()}° V", style = MaterialTheme.typography.labelSmall) }
                    Text(if (state.followingGps) state.gpsStatus ?: "Waiting for GPS…" else if (fix != null) "GPS off · last known position" else "GPS off", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

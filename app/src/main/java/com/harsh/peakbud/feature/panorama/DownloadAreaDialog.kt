package com.harsh.peakbud.feature.panorama

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import com.harsh.peakbud.R
import com.harsh.peakbud.core.model.GeoPoint
import com.harsh.peakbud.data.regions.DownloadArea
import com.harsh.peakbud.device.AreaPlace
import com.harsh.peakbud.device.PlaceSearch
import com.harsh.peakbud.ui.text.rememberTrailStrings
import com.harsh.peakbud.ui.theme.TrailSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DownloadAreaDialog(onDismiss: () -> Unit, onDownload: (DownloadArea) -> Unit, currentPoint: GeoPoint?,
                                search: (suspend (String) -> List<AreaPlace>)? = null) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val strings = rememberTrailStrings()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<AreaPlace>()) }
    var selected by remember { mutableStateOf<DownloadArea?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun choose(place: AreaPlace) {
        selected = runCatching { DownloadArea.plan(place.point, place.name) }.getOrNull()
        message = if (selected == null) strings(R.string.area_outside_support) else null
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(strings(R.string.download_an_area)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(TrailSpace.md)) {
            Text(strings(R.string.area_search_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(query, { query = it; selected = null; results = emptyList(); message = null }, singleLine = true,
                enabled = !loading, label = { Text(strings(R.string.area_place_query)) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(TrailSpace.sm)) {
                Button(enabled = query.isNotBlank() && !loading, onClick = {
                    focus.clearFocus()
                    scope.launch {
                        loading = true; message = null; selected = null
                        try {
                            results = search?.invoke(query.trim()) ?: PlaceSearch.find(context, query.trim())
                            if (results.isEmpty()) message = strings(R.string.area_search_empty)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = strings(R.string.area_search_failed) }
                        finally { loading = false }
                    }
                }) { Text(strings(R.string.area_search_action)) }
                if (currentPoint != null) TextButton(enabled = !loading, onClick = {
                    focus.clearFocus()
                    results = emptyList(); choose(AreaPlace(strings(R.string.area_at_my_location), "", currentPoint))
                }) { Text(strings(R.string.area_use_location)) }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            results.forEach { place ->
                OutlinedButton(onClick = { choose(place) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(place.name)
                        if (place.detail.isNotBlank()) Text(place.detail, style = MaterialTheme.typography.bodySmall)
                        Text("%.4f, %.4f".format(java.util.Locale.US, place.point.latitude, place.point.longitude), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            selected?.let { area ->
                HorizontalDivider()
                Text(area.name, style = MaterialTheme.typography.titleSmall)
                Text(strings(R.string.area_preview, area.tiles.size, (area.installedBytes + 1_048_575) / 1_048_576), style = MaterialTheme.typography.bodyMedium)
                Text(strings(R.string.area_sources_notice), style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = {
        TextButton(enabled = selected != null && !loading, onClick = { selected?.let(onDownload) }) { Text(strings(R.string.area_download_use)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(strings(R.string.cancel)) } })
}

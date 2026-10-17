package com.harsh.peakbud.ui.text

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources

/** Capture resources in composition; callbacks and Canvas drawing can resolve text without composable calls. */
class TrailStrings(private val resources: Resources) {
    operator fun invoke(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
    fun quantity(@PluralsRes id: Int, count: Int, vararg args: Any): String = resources.getQuantityString(id, count, *args)
}
@Composable
fun rememberTrailStrings(): TrailStrings {
    val resources = LocalResources.current
    return remember(resources) { TrailStrings(resources) }
}

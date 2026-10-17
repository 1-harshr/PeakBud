package com.harsh.peakbud.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Refresh on theme changes and when a dialog returns focus to the Activity window. */
@Composable
fun TrailSystemBars(lightBackground: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.activity()?.window ?: return
    fun applyAppearance() {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightBackground
            isAppearanceLightNavigationBars = lightBackground
        }
    }
    SideEffect { applyAppearance() }
    DisposableEffect(view, window, lightBackground) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) applyAppearance() }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }
}

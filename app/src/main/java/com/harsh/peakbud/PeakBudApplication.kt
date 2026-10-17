package com.harsh.peakbud

import android.app.Application
import androidx.compose.runtime.Composer
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import com.harsh.peakbud.telemetry.Telemetry

class PeakBudApplication : Application() {
    @OptIn(ExperimentalComposeRuntimeApi::class)
    override fun onCreate() {
        super.onCreate()
        // Debug builds name the failing composable in crash traces; too costly for release.
        if (BuildConfig.DEBUG) Composer.setDiagnosticStackTraceEnabled(true)
        Telemetry.init(this)
    }
}

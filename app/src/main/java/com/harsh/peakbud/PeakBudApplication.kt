package com.harsh.peakbud

import android.app.Application
import com.harsh.peakbud.telemetry.Telemetry

class PeakBudApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
    }
}

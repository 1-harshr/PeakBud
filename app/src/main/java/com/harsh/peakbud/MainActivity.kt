package com.harsh.peakbud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.harsh.peakbud.feature.panorama.PeakBudApp
import com.harsh.peakbud.ui.theme.PeakBudTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PeakBudTheme { PeakBudApp() } }
    }
}

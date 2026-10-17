package com.harsh.peakbud.data.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.preferences by preferencesDataStore("peakbud")
data class Preferences(val useFeet: Boolean = false, val showUncertain: Boolean = true, val phoneHeightM: Double = 1.6, val demoMode: Boolean = false, val shareDiagnostics: Boolean = true)
class AppSettings(context: Context) {
    private val store = context.preferences
    val preferences = store.data.map { Preferences(it[FEET] ?: false, it[UNCERTAIN] ?: true, it[HEIGHT]?.takeIf { h -> h.isFinite() && h in .5..3.0 } ?: 1.6, it[DEMO] ?: false, it[DIAGNOSTICS] ?: true) }
    val selectedRegion = store.data.map { it[REGION] }
    suspend fun selectRegion(key: String?) = store.edit { if (key == null) it.remove(REGION) else it[REGION] = key }
    suspend fun update(value: Preferences) = store.edit {
        it[FEET] = value.useFeet; it[UNCERTAIN] = value.showUncertain; it[HEIGHT] = value.phoneHeightM; it[DEMO] = value.demoMode; it[DIAGNOSTICS] = value.shareDiagnostics
    }
    companion object {
        private val FEET = booleanPreferencesKey("feet")
        private val UNCERTAIN = booleanPreferencesKey("uncertain")
        private val HEIGHT = doublePreferencesKey("phone-height")
        private val REGION = stringPreferencesKey("selected-region")
        private val DEMO = booleanPreferencesKey("demo-mode")
        private val DIAGNOSTICS = booleanPreferencesKey("share-diagnostics")
    }
}

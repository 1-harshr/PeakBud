package com.harsh.peakbud.telemetry

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.harsh.peakbud.BuildConfig

/**
 * One place for logging, usage events and crash reporting.
 *
 * Everything goes to Logcat in debug builds. When Firebase is configured (app/google-services.json present),
 * breadcrumbs and non-fatal errors go to Crashlytics and events go to Analytics, but only while the user's
 * "Send crash reports and usage" setting is on. Never pass coordinates or other personal data here.
 */
object Telemetry {
    private const val TAG = "PeakBud"
    @Volatile private var analytics: FirebaseAnalytics? = null
    @Volatile private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        val app = runCatching { FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context) }.getOrNull()
        if (app == null) { Log.i(TAG, "Firebase is not configured; logging to Logcat only"); return }
        analytics = FirebaseAnalytics.getInstance(context)
        crashlytics = FirebaseCrashlytics.getInstance().apply {
            setCustomKey("build_type", BuildConfig.BUILD_TYPE)
            setCustomKey("version", BuildConfig.VERSION_NAME)
        }
    }

    /** Follows the user's consent. Firebase persists the choice, so crashes early in the next launch are covered too. */
    fun setEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
        log("diagnostics ${if (enabled) "on" else "off"}")
    }

    /** Breadcrumb attached to the next crash or non-fatal report. */
    fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
        crashlytics?.log(message)
    }

    /** Usage event. Names and keys: lowercase snake_case, at most 40 characters. */
    fun event(name: String, vararg params: Pair<String, Any?>) {
        log("event $name ${params.joinToString { "${it.first}=${it.second}" }}")
        val bundle = Bundle().apply {
            params.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is Int -> putLong(key, value.toLong())
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    is Float -> putDouble(key, value.toDouble())
                    is Boolean -> putString(key, value.toString())
                    else -> putString(key, value.toString().take(100))
                }
            }
        }
        analytics?.logEvent(name, bundle)
    }

    fun screen(name: String) = event(FirebaseAnalytics.Event.SCREEN_VIEW, FirebaseAnalytics.Param.SCREEN_NAME to name)

    /** Something went wrong but the app recovered. Shows in Crashlytics as a non-fatal issue. */
    fun error(where: String, error: Throwable) {
        Log.w(TAG, where, error)
        crashlytics?.log(where)
        crashlytics?.recordException(error)
    }

    fun key(name: String, value: String) { crashlytics?.setCustomKey(name, value) }
}

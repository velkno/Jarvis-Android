package com.jarvis.agent.control

import android.content.Context

/** All UI-only preferences remain on the device; no additional overlay permission is needed. */
object OverlaySettings {
    private const val FILE = "jarvis_overlay"
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun bubbleEnabled(context: Context) = prefs(context).getBoolean("bubble", true)
    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("bubble", enabled).apply()
    }

    fun effectsEnabled(context: Context) = prefs(context).getBoolean("effects", true)
    fun setEffectsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("effects", enabled).apply()
    }

    // Vision is OFF by default: enabling it sends on-demand screenshots to the selected AI API.
    fun visionEnabled(context: Context) = prefs(context).getBoolean("vision", false)
    fun setVisionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("vision", enabled).apply()
    }

    fun speechEnabled(context: Context) = prefs(context).getBoolean("speech", true)
    fun setSpeechEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("speech", enabled).apply()
    }

    // An opt-in, read-only diagnostic tool on rooted phones. Off by default.
    fun rootDiagnosticsEnabled(context: Context) = prefs(context).getBoolean("root_diagnostics", false)
    fun setRootDiagnosticsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("root_diagnostics", enabled).apply()
    }

    fun bubbleY(context: Context) = prefs(context).getInt("bubble_y", 170)
    fun setBubbleY(context: Context, y: Int) {
        prefs(context).edit().putInt("bubble_y", y).apply()
    }
}

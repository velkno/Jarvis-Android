package com.jarvis.agent.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.jarvis.agent.control.JarvisAccessibilityService

object Permissions {

    /** هل خدمة الوصول مفعّلة في إعدادات النظام؟ */
    fun accessibilityEnabled(ctx: Context): Boolean {
        if (JarvisAccessibilityService.instance != null) return true
        val expected = ComponentName(ctx, JarvisAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun micGranted(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
}

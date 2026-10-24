package com.jarvis.agent.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.jarvis.agent.control.AppLauncher
import com.jarvis.agent.control.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class FastResult(val success: Boolean, val message: String)

/**
 * No LLM or network call for exact, harmless navigation intents.
 * Unknown/ambiguous apps return null and the normal AI agent takes over.
 */
object FastActionExecutor {
    private val aliases = mapOf(
        "واتساب" to "com.whatsapp",
        "الواتساب" to "com.whatsapp",
        "واتس" to "com.whatsapp",
        "whatsapp" to "com.whatsapp",
        "واتساببزنس" to "com.whatsapp.w4b",
        "whatsappbusiness" to "com.whatsapp.w4b",
        "يوتيوب" to "com.google.android.youtube",
        "اليوتيوب" to "com.google.android.youtube",
        "youtube" to "com.google.android.youtube",
        "كروم" to "com.android.chrome",
        "chrome" to "com.android.chrome",
        "googlechrome" to "com.android.chrome",
        "تيليجرام" to "org.telegram.messenger",
        "تليجرام" to "org.telegram.messenger",
        "telegram" to "org.telegram.messenger",
        "انستغرام" to "com.instagram.android",
        "إنستغرام" to "com.instagram.android",
        "instagram" to "com.instagram.android",
        "تيكتوك" to "com.zhiliaoapp.musically",
        "tiktok" to "com.zhiliaoapp.musically",
        "فيسبوك" to "com.facebook.katana",
        "facebook" to "com.facebook.katana",
        "جيميل" to "com.google.android.gm",
        "gmail" to "com.google.android.gm",
        "خرائطجوجل" to "com.google.android.apps.maps",
        "googlemaps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "سبوتيفاي" to "com.spotify.music",
        "spotify" to "com.spotify.music"
    )

    private fun normalized(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("[\\s_.-]+"), "")

    suspend fun execute(context: Context, intent: FastIntent): FastResult? =
        withContext(Dispatchers.Main.immediate) {
            when (intent) {
                FastIntent.Back -> {
                    val ok = JarvisAccessibilityService.instance?.pressBack() == true
                    FastResult(ok, if (ok) "تم الرجوع" else "فعّل خدمة الوصول للرجوع")
                }
                FastIntent.Home -> {
                    val ok = JarvisAccessibilityService.instance?.pressHome() == true
                    FastResult(ok, if (ok) "تم فتح الشاشة الرئيسية" else "فعّل خدمة الوصول")
                }
                FastIntent.OpenSettings -> {
                    val ctx = JarvisAccessibilityService.instance ?: context
                    val ok = runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.isSuccess
                    FastResult(ok, if (ok) "تم فتح الإعدادات" else "تعذّر فتح الإعدادات")
                }
                is FastIntent.OpenApp -> {
                    val launcher = AppLauncher(context)
                    val key = normalized(intent.appName)
                    // Match known packages OR the complete visible installed-app name.
                    // Never use partial fuzzy matches in the fast route.
                    val packageName = aliases[key]
                        ?: withContext(Dispatchers.Default) {
                            launcher.list().firstOrNull { normalized(it.label) == key }?.pkg
                        }
                        ?: if (intent.appName.contains('.') &&
                            intent.appName.matches(Regex("[a-zA-Z0-9_.]+"))) intent.appName
                        else null

                    if (packageName == null) return@withContext null
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                        ?: return@withContext null
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    val ctx = JarvisAccessibilityService.instance ?: context
                    val ok = runCatching { ctx.startActivity(launchIntent) }.isSuccess
                    FastResult(
                        ok,
                        if (ok) "تم فتح " + intent.appName else "تعذّر فتح " + intent.appName
                    )
                }
            }
        }
}

package com.jarvis.agent.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** فتح التطبيقات وعرضها عبر PackageManager — لا يحتاج روت. */
class AppLauncher(private val context: Context) {

    data class App(val label: String, val pkg: String)

    companion object {
        @Volatile private var cachedApps: List<App>? = null
        @Volatile private var cacheTimeMs: Long = 0L
    }

    /** Cache installed app enumeration for 30s across launcher instances. */
    @Synchronized
    fun list(): List<App> {
        val now = android.os.SystemClock.elapsedRealtime()
        cachedApps?.let { if (now - cacheTimeMs < 30_000L) return it }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val result = pm.queryIntentActivities(intent, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
        cachedApps = result
        cacheTimeMs = now
        return result
    }

    /** يجد تطبيقاً بالحزمة أو بالاسم (مطابقة تامة ثم جزئية). */
    fun find(query: String): App? {
        val q = query.trim().lowercase()
        val all = list()
        return all.firstOrNull { it.pkg.lowercase() == q }
            ?: all.firstOrNull { it.label.lowercase() == q }
            ?: all.firstOrNull { it.label.lowercase().contains(q) || q.contains(it.label.lowercase()) }
            ?: all.firstOrNull { it.pkg.lowercase().contains(q) }
    }

    /** يفتح التطبيق ويعيد رسالة نتيجة. */
    fun launch(launcher: Context, query: String): String {
        val app = find(query) ?: return "لم أجد تطبيقاً باسم «$query». استخدم list_apps."
        val intent = context.packageManager.getLaunchIntentForPackage(app.pkg)
            ?: return "التطبيق ${app.label} لا يمكن فتحه مباشرة."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            launcher.startActivity(intent)
            "تم فتح ${app.label} (${app.pkg})"
        } catch (e: Exception) {
            "فشل فتح ${app.label}: ${e.message}"
        }
    }
}

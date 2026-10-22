package com.jarvis.agent.control

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * تنفيذ أوامر shell بصلاحيات الروت عبر libsu.
 * يمنح الوكيل تحكّماً مطلقاً: فتح أي تطبيق، input events، إعدادات النظام، إلخ.
 */
object RootShell {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20)
        )
    }

    /** هل الروت متاح وممنوح؟ */
    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    /** ينفّذ أمراً ويعيد الناتج (stdout + stderr مدمجَين). */
    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        val res = Shell.cmd(command).exec()
        ShellResult(
            success = res.isSuccess,
            code = res.code,
            out = res.out.joinToString("\n"),
            err = res.err.joinToString("\n")
        )
    }

    // ===== أوامر جاهزة شائعة =====

    suspend fun tap(x: Int, y: Int) = exec("input tap $x $y")

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 300) =
        exec("input swipe $x1 $y1 $x2 $y2 $ms")

    suspend fun inputText(text: String): ShellResult {
        val escaped = text.replace(" ", "%s").replace("'", "\\'")
        return exec("input text '$escaped'")
    }

    suspend fun keyEvent(code: Int) = exec("input keyevent $code")

    /** فتح تطبيق عبر اسم الحزمة. */
    suspend fun launchApp(packageName: String) =
        exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")

    /** قائمة التطبيقات المثبتة (حزمة لكل سطر). */
    suspend fun listPackages(): List<String> =
        exec("pm list packages -3").out.lines()
            .mapNotNull { it.removePrefix("package:").trim().takeIf(String::isNotEmpty) }

    /** لقطة شاشة إلى مسار. */
    suspend fun screenshot(path: String) = exec("screencap -p $path")
}

data class ShellResult(
    val success: Boolean,
    val code: Int,
    val out: String,
    val err: String
) {
    fun asText(): String = buildString {
        append("exit=$code")
        if (out.isNotBlank()) append("\n$out")
        if (err.isNotBlank()) append("\n[err] $err")
    }
}

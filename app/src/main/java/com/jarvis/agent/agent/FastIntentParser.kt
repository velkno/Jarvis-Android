package com.jarvis.agent.agent

/**
 * Conservative offline intent recognition.
 *
 * Only an entire, unambiguous navigation command is eligible. The parser never
 * interprets compound commands as an instruction to open the first app: those
 * belong to the AI planner so that the full requested task is preserved.
 */
sealed interface FastIntent {
    data class OpenApp(val appName: String) : FastIntent
    data object Home : FastIntent
    data object Back : FastIntent
    data object OpenSettings : FastIntent
}

object FastIntentParser {
    private val open = Regex(
        "^(?:افتح|فتح|شغل|شغّل|open|launch|start)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val safeName = Regex("^[\\p{L}\\p{N}_ .+\\-]+$")
    private val compound = Regex(
        "(?:\\s+(?:و(?:افتح|ارسل|أرسل|اكتب|ثم|بعدها)|ثم|بعدها|and|then|send|write)\\b|[،,;:])",
        RegexOption.IGNORE_CASE
    )

    fun parse(input: String): FastIntent? {
        val command = input.trim().trimEnd('.', '!', '؟', '?').trim()
        if (command.isBlank() || command.length > 90 || '\n' in command || '\r' in command) return null
        when (command.lowercase()) {
            "ارجع", "رجوع", "العودة", "back", "go back" -> return FastIntent.Back
            "الرئيسية", "الشاشة الرئيسية", "اذهب للرئيسية", "اذهب إلى الرئيسية",
            "home", "go home" -> return FastIntent.Home
            "الإعدادات", "الاعدادات", "افتح الإعدادات", "افتح الاعدادات",
            "open settings" -> return FastIntent.OpenSettings
        }
        val raw = open.matchEntire(command)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val app = raw.removePrefix("تطبيق ").removePrefix("application ").trim()
        if (app.length !in 2..64 || !safeName.matches(app) || compound.containsMatchIn(app)) return null
        // A conjunction directly adjacent to the second verb may have no
        // word boundary in Arabic (e.g. واتساب وارسل), so reject explicitly.
        if (listOf(" وارسل", " وأرسل", " واكتب", " وافتح", " وشغل", " وابعث",
                    " وقل", " وابحث", " وانقر", " واضغط", " واطبع").any { app.contains(it) }) return null
        if (listOf(" and ", " then ", " send ", " search ", " message ", " to ").any {
                app.lowercase().contains(it)
            }) return null
        return FastIntent.OpenApp(app)
    }
}

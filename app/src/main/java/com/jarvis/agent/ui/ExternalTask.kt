package com.jarvis.agent.ui

import android.content.Intent

/**
 * Accepts a user-shared command or an explicit jarvis://run?task=... link.
 *
 * This is only a hand-off into the UI, NOT a remote-control endpoint:
 * MainActivity always shows a confirmation dialog before AgentSession.send.
 * Never add auto-execution here: external apps and websites are untrusted.
 */
internal object ExternalTask {
    private const val MAX_LENGTH = 500

    fun from(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type != "text/plain") return null
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            }
            Intent.ACTION_PROCESS_TEXT -> {
                if (intent.type != "text/plain") return null
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return null
                if (!uri.scheme.equals("jarvis", ignoreCase = true) ||
                    !uri.host.equals("run", ignoreCase = true)) return null
                uri.getQueryParameter("task")
            }
            else -> null
        } ?: return null

        val task = raw.trim()
        if (task.isEmpty() || task.length > MAX_LENGTH) return null
        // Reject control characters. Tabs/newlines are allowed for multi-line tasks.
        if (task.any { it.code < 32 && it != '\n' && it != '\t' }) return null
        return task
    }
}

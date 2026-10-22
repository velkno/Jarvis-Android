# Privacy & permissions

JARVIS uses powerful Android permissions because it acts on your device at your request. Read this before granting access.

## What it can observe

- **Accessibility Service:** reads text, element boundaries and current interface state, and performs user-requested gestures/actions.
- **Vision/SC (opt-in, Android 11+):** requests an on-demand screenshot from Android's accessibility screenshot API; the screenshot is compressed in memory and sent with the task to your selected AI provider. Protected system windows may remain blank or inaccessible.
- **Microphone (optional):** speech input uses Android's speech recognition services. Exact processing and network behavior may depend on the installed recognition service.
- **Installed apps:** package queries are used to find and open apps.

## Network transfer

The active AI provider (OpenAI, Anthropic or Gemini, configured by you) receives the prompts, screen context and optional screenshot payloads needed to answer the current task. API keys are intended to be stored encrypted on the device. There is no project-hosted relay server in this implementation. Provider retention, usage and fees depend on the provider and your contract with it.

**Important:** Avoid letting the agent read highly sensitive information. Screenshots can include personal data even if password fields are masked in Accessibility text. The app cannot guarantee that sensitive third-party content will be redacted from images.

## Controls and limitations

- Vision/SC is off unless enabled by the user.
- The Stop button ends the running agent loop; Android Accessibility can be disabled in system settings.
- Screenshot images are intended to be held in memory per request, not intentionally stored as files by this application. This does **not** control how remote model providers handle uploaded images.
- There is no built-in stealth monitoring or automatic capture of a user's screen without a task.
- Root diagnostics require separate opt-in and support only pre-approved read-only commands.

## Best practices

Use restricted API keys, watch API spending, review provider privacy terms, and test with a secondary device or non-sensitive apps before relying on the agent.

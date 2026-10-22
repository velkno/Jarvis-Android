# Development guide

## Requirements

- JDK 17
- Android SDK 34 / build-tools 34.0.0
- Android Studio recommended (or command line Gradle wrapper)
- Android device running API 26+ for manual functional tests

## Building

```bash
./gradlew --no-daemon assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. Debug signing is intended for testing; maintain your own secure signing key for production releases, and never commit keys or passwords.

## High-level architecture

- `AgentLoop.kt` / `AgentSession.kt`: maintains one task loop and tool-calling state.
- `LlmProvider` and adapters: OpenAI, Anthropic Claude, and Gemini request/response translation.
- `JarvisAccessibilityService`: observes accessibility elements and drives interactions.
- `DeviceTools`: exposes explicit capabilities to LLM tools.
- `OverlaySettings` / `AgentOverlay`: optional floating UI, cursor and activity feedback.
- `KeyStore.kt`: encrypted API key handling.

## Testing checklist

Test Android 8, 11, 13+ where possible; run without root; test each provider with a known vision-capable model; grant/revoke Accessibility mid-task; test Stop; turn overlays off; ensure secure windows are inaccessible; validate password text masking; watch for sensitive contents in logs; check errors and provider quota/rate limits. Manual device testing remains required.

## CI

The `Build Jarvis APK` GitHub Actions workflow compiles debug builds and uploads a ZIP artifact. For public end-user downloads, publish a separately reviewed GitHub Release asset rather than treating debug CI outputs as production packages.

## Publish checklist

Ensure source is reviewed for secrets and third-party rights, choose an explicit license, review app permissions and privacy description, test installation, and attach a signed and verified APK to a GitHub release.

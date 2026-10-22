# JARVIS Android v1.4

Agent-controlled Android app supporting OpenAI, Anthropic Claude and Google Gemini.

## Included
- Vision/SC: on-demand screenshot interpretation on Android 11+ (opt-in).
- Accessibility: screen reading, click by index/text, tap, double-tap, long-press, drag/drop, pinch, swipes, native scroll, text replace/append/clear.
- Device tools: apps, web URLs, selected system settings, status, notifications, quick settings, clipboard write/paste, media and volume.
- Blue pointer, glowing screen edges, floating chat bubble, stop button.
- Root diagnostics: optional and restricted to read-only commands.

## Build
The original source archive is `Jarvis-v1.2-build-ready-source.zip`. The updated source is applied from the ordered patches in `patches/advanced-v1.4.part01` through `part07`, then `patches/advanced-v1.4-nullability.patch`, by `.github/workflows/build-apk.yml`.

GitHub Actions builds `assembleDebug` and publishes `Jarvis-v1.4-Advanced-APK` as a download artifact. The debug APK is Android 8.0+.

**Security:** Screenshots only on request when Vision/SC is enabled. Screen password fields are masked. Actions require a user-initiated task, and protected windows/security controls are not bypassed. Irreversible actions should be confirmed in chat; that rule is guidance, not an OS guarantee.

**Limitations:** CI compilation was verified; device-specific behavior still needs testing on a real handset. APK upgrades signed using different signing keys may require backup and reinstall.

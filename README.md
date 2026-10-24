<div align="center">

# JARVIS for Android

**Your AI assistant, now with hands on the screen.**

A user-controlled Android agent that can **see the current screen**, decide what to do, and interact with apps using Android Accessibility. Give it a task by text or voice, watch its steps, and stop at any time.

[![Build Android APK](https://github.com/velkno/Jarvis-Android./actions/workflows/build-apk.yml/badge.svg)](https://github.com/velkno/Jarvis-Android./actions/workflows/build-apk.yml)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Status](https://img.shields.io/badge/Status-Experimental-orange)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**OpenAI · Anthropic Claude · Google Gemini**

[Features](#features) · [Install](#install) · [Quick start](#quick-start) · [Build](#build-from-source) · [Arabic / العربية](#بالعربية) · [Contributing](CONTRIBUTING.md)

</div>

> **Status: experimental.** The debug APK has been compiled successfully in GitHub Actions. It has **not been independently validated on physical devices**, and individual accessibility/vision features depend on Android version, permissions and the selected model. Please report bugs.

## Why JARVIS?

Most chatbots give instructions. JARVIS tries to **carry out** user-requested phone tasks itself:

1. **Observe:** read the accessibility tree and, when explicitly enabled, capture a screenshot.
2. **Reason:** send the task and relevant screen context to your chosen AI model.
3. **Act:** click, type, scroll, navigate or open an app.
4. **Verify:** inspect the new screen and continue until finished, stopped, or blocked.

No root access is required for normal touch and navigation tools.

## Features

| Area | What is available |
|---|---|
| **Vision/SC** | On-demand screenshot understanding on Android **11+**, using an accessibility screenshot API. Opt-in; protected windows cannot be captured. |
| **Accessibility control** | Read UI hierarchy, tap by position or element, double tap, long-press, drag, swipe, scroll and pinch. |
| **Input & navigation** | Enter, replace or clear text; Home, Back, Recents, Notifications and Quick Settings. |
| **App actions** | List/open installed apps, open URLs, navigate to selected settings screens. |
| **AI providers** | Configure your own API key for OpenAI, Anthropic Claude or Google Gemini. Choose a compatible model. |
| **Voice & display** | Speech input, spoken replies, an optional floating chat bubble, blue pointer and blue edge effects. |
| **ChatGPT hand-off (v1.5)** | Share or select text in another app and explicitly approve the command in JARVIS. [Setup & security](docs/CHATGPT_HANDOFF.md). This does **not** provide silent phone control from a ChatGPT conversation. |
| **Control & safety** | Stop button, user-initiated sessions, masked password fields in accessibility data, optional restricted read-only root diagnostics. |

**Limitations:** JARVIS does not bypass Android lock screens, protected app windows or security restrictions. Sensitive or irreversible actions should always be checked by the user; model confirmations are **not an OS-level enforcement guarantee**. Some devices/manufacturers block or throttle overlays and accessibility tools. The app is **not** an always-on screen broadcaster.

## Install

1. Open the repository **[Actions](../../actions/workflows/build-apk.yml)** tab and select the latest **successful** “Build Jarvis APK” run.
2. Download the **Jarvis-v1.8-Turbo-APK** artifact and extract `app-debug.apk`. Artifacts may require a GitHub sign-in. The maintainer can publish an explicitly labelled debug [prerelease](../../actions/workflows/release-experimental.yml) with a direct APK asset and SHA-256 checksum after review; inspect [Releases](../../releases) when available.
3. On Android, open the APK and allow installation from your trusted file manager when prompted.
4. If Android blocks enabling Accessibility for a sideloaded app, check the system's restricted-settings prompt and decide whether to grant access. This permission lets the app observe and operate your screen.

**Note on updates:** CI builds use debug signing. Different debug signing keys might prevent an APK from updating an older version. Back up your configuration before uninstalling; uninstalling may erase API settings.

## JARVIS v1.8: Turbo responsiveness

Version 1.8 keeps the v1.7 local/hybrid command engine and improves responsiveness:

- **No extra AI round trip for an initial UI scan:** for user-requested screen tasks, capture the current Accessibility tree locally and include a bounded snapshot in the first model request. No automatic screenshot is taken.
- **Stop really cancels in-flight LLM network requests:** providers use cancellable OkHttp async calls instead of blocking network calls.
- **No blind 1.2-second app-launch sleep:** inspect the UI after transitions instead.
- **Faster target detection:** `wait_for` rechecks the Accessibility tree about every 120 ms rather than every 350 ms.
- **Connection reuse:** shared HTTP/2-capable client and pooled TLS connections.
- **Smaller Vision uploads:** on-demand JPEG screenshots are reduced to a maximum edge of 1600 px at 72% compression quality, while the Accessibility text remains available.
- **Cached application lookups:** Android app enumeration is reused for up to 30 seconds; expensive enumerations are no longer run on the main thread by the local fast route.

The app remains experimental. No specific latency has been guaranteed or independently benchmarked on physical devices. Local single-action tasks can avoid the cloud; multi-step AI tasks still depend on network and model latency. The private-repository GitHub bridge is separate and still uses polling plus explicit on-device approval.

## JARVIS v1.7: Hybrid speed + latest AI models

Local one-step commands (e.g. **افتح واتساب**) bypass model/network calls; compound actions still use full AI planning and screen controls. The remote private GitHub bridge polls every ~8 seconds (instead of 30) with connection reuse, and tasks display elapsed execution time. GPT-6 models use the Responses API for proper tool calling. See [Speed & intelligence](docs/SPEED_INTELLIGENCE_V17.md).

## Receive private remote requests from ChatGPT (v1.6)

New: opt-in remote commands through **GitHub Issues in a private repository**. GitHub-integrated ChatGPT sessions can create a time-limited request for this phone. The phone checks for it, notifies you, and **requires your on-device approval** before doing anything. It then uses JARVIS's existing phone-control tools. No silent control, no public command queue, and no raw screenshot upload to GitHub. See **[Private remote bridge: full setup](docs/PRIVATE_REMOTE_BRIDGE.md)**.

## Send commands from ChatGPT (v1.5)

After configuring the API key and Accessibility service, select the command text in ChatGPT and use Android's **Share** or **selected text** action to send it to JARVIS (availability depends on the ChatGPT app). JARVIS displays a confirmation dialog before running anything. See [ChatGPT hand-off instructions](docs/CHATGPT_HANDOFF.md). **This is user-approved hand-off, not autonomous remote control.**

## Quick start

1. Open JARVIS → **API / Keys**. Pick OpenAI, Anthropic or Gemini; paste **your own** key and select a model that supports tool calling and, for Vision, image input.
2. In **Permissions**, enable the **JARVIS Accessibility service**.
3. Optionally enable **Vision/SC** (Android 11+) for image understanding, the floating bubble, pointer and visual effects.
4. Type or speak a task. Example: **“Open YouTube and search for easy Moroccan tajine recipes.”**
5. Review the tool steps, and hit **Stop** to halt the task.

**Privacy warning:** With Vision/SC enabled, screenshots and/or accessibility content can be sent to **the AI provider you select** for processing. This may include personal content shown on your screen. Disable Vision/SC on sensitive apps and avoid handling passwords, payments or private conversations until you have reviewed the behavior yourself. Provider API fees may apply. Read [Privacy & permissions](docs/PRIVACY.md).

## Build from source

You need Android Studio or JDK 17, Android SDK 34, and internet access for dependencies.

```bash
git clone https://github.com/velkno/Jarvis-Android..git
cd Jarvis-Android.
./gradlew assembleDebug
```

The unsigned-for-release **debug** APK will be at `app/build/outputs/apk/debug/app-debug.apk`. No production signing key is included.

Use the **[Build Jarvis APK](.github/workflows/build-apk.yml)** workflow in GitHub Actions to build without installing Android Studio.

## Project layout

```text
app/src/main/java/com/jarvis/agent/
  ai/         OpenAI, Anthropic and Gemini integrations
  agent/      Tool-calling agent loop and session
  control/    Accessibility service, gestures, overlays, tools
  data/       Encrypted API key storage
  ui/         Jetpack Compose interface
  voice/      Speech recognition and text-to-speech
app/src/main/res/      Android resources and accessibility config
.github/workflows/     Android build automation
```

For a detailed walkthrough, see [Development guide](docs/DEVELOPMENT.md), [Changelog](CHANGELOG.md), and the [experimental GitHub Release workflow](.github/workflows/release-experimental.yml).

## Contributing & feedback

Bug reports, feature proposals, documentation, translations and pull requests are welcome. Check [Contributing](CONTRIBUTING.md) and use **Issues** for bug reports. Please do not post API keys, private screenshots or personal data in public issues. Report vulnerabilities privately using [Security policy](SECURITY.md).

## License and reuse

JARVIS's original project code is distributed under the **[MIT License](LICENSE)** (Copyright © 2026 velkno). You may use, copy, modify, fork, redistribute, and include it in commercial applications, provided you retain the copyright and license notices. You **do not need to ask for permission**.

This license does not override licenses belonging to third-party libraries, artwork, fonts, trademarks, or other content; please respect their separate terms. API services (OpenAI, Claude, and Gemini) require their own accounts, terms, and API keys.

**Help JARVIS evolve:** use the GitHub **Fork** button, make your change on a branch, and open a **Pull Request** with test results. See [Contributing](CONTRIBUTING.md). Fork authors are welcome to keep independent versions, but sharing improvements upstream benefits everybody.

## بالعربية

**جارفيس** هو مساعد ذكي للأندرويد كيتحكم فالهاتف بتعليماتك: كيقرأ عناصر الشاشة، ويقدر يشوف لقطة شاشة عند تفعيل **Vision/SC**، ومن بعد ينقر ويكتب ويسكرول ويفتح التطبيقات. كيدعم **OpenAI وClaude وGemini** بمفتاح API ديالك، وفيه **فقاعة عائمة**، **مؤشر أزرق**، **مؤثرات مضيئة** وزر للإيقاف.

- **أندرويد 8 فما فوق**؛ الرؤية بالصور كتحتاج **أندرويد 11 فما فوق**.
- **الروت اختياري** وماشي ضروري للاستعمال العادي.
- الصور ومعلومات الشاشة يمكن تمشي لمزود الذكاء الاصطناعي اللي اخترتيه؛ ما تفعّلش الرؤية فالتطبيقات الحساسة.
- هادي نسخة **تجريبية**، والبناء نجح ولكن خاصها تجارب على هواتف حقيقية.
- المشروع متاح لإعادة الاستخدام والتعديل والتوزيع، وحتى الاستعمال التجاري، بموجب [رخصة MIT](LICENSE) مع الاحتفاظ بإشعار الحقوق والرخصة.

---

*JARVIS is an independent Android project. It is not affiliated with OpenAI, Anthropic or Google.*

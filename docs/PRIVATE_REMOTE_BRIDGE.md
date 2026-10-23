# JARVIS v1.6: Private GitHub remote bridge

## What is included

An actual phone-side, opt-in incoming-command bridge. A ChatGPT session **with permission to create GitHub Issues in your chosen private repository** can send a time-limited request. Android polls the private GitHub repository through an authenticated API call while the user-enabled JARVIS Accessibility service is active. JARVIS displays a local notification and a confirmation dialog with the **exact task**. Nothing executes until the owner taps **موافقة وتشغيل**.

Existing JARVIS AI functions (open apps, navigation, touch, text, screenshots with opt-in Vision/SC, voice, overlays, etc.) remain available after approval, subject to Android permissions, device security and the chosen AI provider. Root is not required for normal interaction.

This is not an OpenAI-provided phone tool. There is no unprompted execution from ChatGPT, no continuously connected ChatGPT integration unless GitHub connector can access the private command repository, and no way to bypass Android app restrictions.

## Setup (required)

1. Create a separate **private** GitHub repository, for example `YOUR_ACCOUNT/jarvis-private-commands`. In Settings ensure **Issues** is enabled. **Never use the public Jarvis-Android source repository as the command queue.**
2. On GitHub create a **fine-grained Personal Access Token** limited to that one private repository. Repository permissions: Issues **Read & Write** and Metadata read. Grant the minimum expiration required. Do not paste the token into ChatGPT, screenshots or public issues.
3. Install the JARVIS 1.6 APK built from the branch after CI passes, and enable **Accessibility** in the JARVIS Permissions tab. Configure an AI provider and test its API key.
4. On JARVIS open **الربط** tab. Enter the private `owner/repo`, the **trusted sender GitHub username** (usually YOUR_ACCOUNT, or the actual authenticated actor of GitHub connector), and the token. Tap **اختبار وحفظ الاتصال**. The app refuses public repos and does not persist invalid settings.
5. Switch on **تفعيل استقبال الأوامر**. Enable notification permission on Android 13+ when prompted. Keep JARVIS Accessibility service running; HyperOS battery restrictions may affect polling.
6. Copy only the displayed `Device ID` and the private repository name into the ChatGPT conversation. Do **not** share the PAT. Ensure the GitHub connector has been authorized to that private repository.

## How to request a command

In a ChatGPT conversation that has the GitHub connector installed, ask it to create **one** issue in the private command repository with the exact title:

`[jarvis-remote-v1]`

And a JSON body like the following (replace the device ID and expiry):

```json
{
  "protocol": "jarvis-remote-v1",
  "device_id": "COPY-DEVICE-ID-FROM-PHONE",
  "task": "افتح واتساب",
  "expires_at": "2026-10-23T21:30:00Z"
}
```

**Important:** `expires_at` must be a fresh **UTC** time after now, no more than 15 minutes after the issue's creation. Use current UTC rather than copying the example timestamp. The issue's author must match the trusted username configured on JARVIS. The app polls every 8 seconds while its Accessibility service is alive, subject to Android power management and internet access. The first valid request is shown for review, with a high-priority local notification when allowed.

Tap **موافقة وتشغيل** in JARVIS. To decline tap **رفض**. Once approved, the task runs through the existing AgentSession and controls. The app posts a **status-only** comment, `JARVIS_REMOTE_RESULT_V1`, on the corresponding private issue after completion or failure, if the token permits Issue comments. Detailed local execution logs stay on the device.

The ChatGPT GitHub connector can read that status comment in a subsequent interaction. It does not have access to screenshots or passwords through this bridge.

## Security design

- Strictly private GitHub repository enforced on Android; no fallback to public repositories.
- Fine-grained PAT kept in Android encrypted preferences, not in repository source, logs or this chat.
- Trusted GitHub issue author, UUID device target, TTL <= 15 min, fixed title and JSON protocol.
- Commands are manually approved one at a time on the phone before invoking Accessibility actions.
- Handled issue numbers retained to avoid replay; unexpected/disallowed/old requests ignored.
- Visible opt-in switch; service only polls when Accessibility service is active.
- Stop ongoing tasks using the existing red **إيقاف** button.
- Remote GitHub comments contain only execution status. Screenshots/UI dumps and logs remain local (subject to processing by the selected AI provider when running the task).
- This experimental implementation has no cryptographic end-to-end signing beyond GitHub authentication. Account/PAT compromise can submit unwanted **requests**, but the app still requires on-device approval. Protect your GitHub account and token.

## Troubleshooting

- No incoming command: verify private repo, GitHub login match, device ID exact, `expires_at` within validity, Accessibility on, app's bridge on, network, and battery restrictions.
- Token errors: check private repository access and Issues Read & Write on the fine-grained PAT.
- Android notification is absent: open JARVIS and grant the notifications permission; approval dialog is available in-app.
- GitHub connector cannot create an issue: authorize the connector to the private repo, or enter a sample issue yourself.
- Cannot control protected screens: Android safety barriers are intentional.

# JARVIS v1.7: Speed + Intelligence

## What changed

**Hybrid command execution:** Short, exact commands such as `افتح واتساب`, `open YouTube`, `ارجع`, `home`, and `افتح الإعدادات` can run fully **on the phone**, without a model request. This saves the model round trip. The fast executor matches a known application package or the **exact installed app name**. If it cannot match the app, it routes the request to the chosen AI provider instead. Compound commands such as `افتح واتساب وأرسل رسالة` are **never truncated** to a local one-step command.

**A full reasoning mode** handles multi-step tasks. Phone tools and optional visual analysis remain available; a text-only `read_screen(include_image=false)` can skip an unnecessary screenshot when labels are sufficient. When a new task begins, model history is reduced to a short reference to the previous request, rather than replaying an entire session. Long current tasks keep the newest complete tool rounds, preserving valid tool call IDs.

**Modern models:** OpenAI `gpt-6.1-sol`, `gpt-6-astra`, and `gpt-6-luna` are offered via the **Responses API**, which is required for tool calling on the stronger GPT-6 family. Existing `gpt-4o` setups continue to use Chat Completions. Gemini includes `gemini-3.8-flash` and `gemini-4-argon`. Not all accounts have API access to all models, and charges depend on the AI provider. OpenAI GPT-6 requests use `store=false`; reasoning items needed for tool use are replayed with encrypted content.

**Remote bridge response:** The private GitHub relay now checks every **8 seconds** while the user-enabled Accessibility service runs, instead of 30 seconds. A single verified private-repo connection and pooled HTTP client remove redundant setup on every poll. Errors back off to 60 seconds. This is still **polling**, not a zero-latency push/WebSocket connection. On Android, system battery optimization/network restrictions can cause additional delays.

**Visible performance:** JARVIS displays elapsed local task time in milliseconds, indicating whether Fast Engine or Reasoning Engine ran. This time excludes remote relay waiting and user approval.

## Setup

Install the tested build; use JARVIS **المفاتيح** to select a model, provide your own AI API key, and use **اختبار الاتصال**. For pure Fast Engine actions, an AI API key is not required.

To receive a task from this ChatGPT session via the private GitHub relay, the user must separately configure the private repository, trusted GitHub sender, and fine-grained token in the phone's **الربط** tab, as detailed in [PRIVATE_REMOTE_BRIDGE.md](PRIVATE_REMOTE_BRIDGE.md). Each remote task must still be confirmed on the device. JARVIS never automatically reads and controls the phone solely because a ChatGPT message appeared.

## Reliability

- Fast Engine covers a deliberately narrow command grammar to avoid doing the wrong thing quickly.
- Multi-step tasks, unknown apps, and complex navigation use the AI with Accessibility.
- Images leave the phone only when Vision/SC is enabled and the model requests a screenshot (or uses the default screen image behavior).
- Real device speed depends on CPU, network conditions, model response time and API availability.
- Unit tests cover direct command classification and safeguards against accidentally truncating compound commands.
- Real-phone benchmark and user acceptance tests are still required for confident performance numbers. No guaranteed sub-second response time.

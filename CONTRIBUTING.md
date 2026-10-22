# Contributing to JARVIS

Thanks for helping improve an experimental Android AI agent. Please keep changes narrow, tested, and respectful of user consent.

## Workflow

1. Search open issues before filing a new one.
2. For larger changes, open an issue describing the user-visible behavior, Android versions affected, and the new permissions (if any).
3. Create a focused branch and submit a pull request with steps to test.
4. Build with `./gradlew assembleDebug` and state whether you tested on a real device.
5. Never commit API keys, credentials, personally identifying screenshots, or device logs containing personal data.

## Safety requirements

- Never add covert background recording or actions without user initiation.
- Do not bypass Android protections or protected app windows.
- New invasive tools should default to **off**, be explained in UI, and require explicit user consent.
- Avoid logging screenshot contents, clipboard contents, tokens or conversations.
- Keep an immediate Stop path and review external side-effects (messages, payments, deletes).

## Bug reports

Include Android version, device model, JARVIS version, provider/model, a minimal reproduction, expected vs actual behavior, and redacted logs. Do not include live API keys.

## Pull requests

Explain your changes, add tests when reasonable, and update README/CHANGELOG if behavior changes.

## License and rights

By submitting a pull request, you agree that your original contribution is submitted under the repository's [MIT License](LICENSE). Please contribute only code, documentation, or assets that you have the right to share under those terms, and disclose any third-party material and its license.

You are welcome to fork JARVIS, maintain an independent variant, or reuse the code in another project. Keeping copyright and license notices is required. Upstream contributions are appreciated but not mandatory.

# Security policy

JARVIS is an experimental app with **Accessibility** privileges and optional screenshot transmission to an external model provider. Treat it as security-sensitive software.

## Reporting vulnerabilities

**Do not create a public issue** for exploitable vulnerabilities, exposed API keys, or private user data.

Use GitHub's **Report a vulnerability** / private vulnerability reporting option on this repository, if enabled, or contact the maintainer privately through GitHub. Include reproduction steps without posting secrets.

## Operational recommendations

- Download APKs only from the maintainer's GitHub repository.
- Verify the origin of each build. Debug APKs are for testing, not production trust guarantees.
- Enable Vision/SC only when you understand what content can be sent to your chosen provider.
- Do not give the app root access unless you need its restricted diagnostics.
- Stop or disable the Accessibility service when it is not needed.
- API keys should be restricted, rotated if leaked, and not shared in issue attachments.

## Supported versions

Security fixes are currently planned for the latest version only. No response-time guarantee is offered.

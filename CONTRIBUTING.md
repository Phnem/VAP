# Contributing to VAP

Report bugs and suggest features through [GitHub Issues](https://github.com/Phnem/VAP/issues). Code contributions are welcome as pull requests.

## Reporting a bug

Include the VAP version and variant (`foss` or `cast`), device model, Android version, reproduction steps, and expected and actual behavior. For playback issues, include the container and codecs, plus a short sample you have permission to share. For network sources, include the protocol and server version. Remove passwords, tokens, and personal information from logs.

## Contributing code

1. Set up the environment described in the [README](README.md#build-from-source).
2. Create a branch and keep the change focused on one task.
3. Add appropriate checks for logic changes, update both UI translations when changing strings, and preserve license notices.
4. Run JVM tests and lint for both variants. Test playback and JNI changes on a test device, and describe what you verified in the PR.
5. Explain the problem, resulting behavior, and validation in your PR. Include screenshots for UI changes.

Documentation-only changes do not require an APK build. Keep generated files, signing keys, credentials, model weights, and personal media out of Git.

The project's own code is licensed under MIT. Preserve the separate licenses of third-party components.

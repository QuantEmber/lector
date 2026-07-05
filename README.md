# Lector

**Read any text, PDF, or EPUB aloud — free, private, offline.**

Lector reads your documents aloud using your device's own text-to-speech voices. Open
a TXT, Markdown, EPUB, or PDF — a single file or a whole folder — or paste text, or
share to Lector from any app. It turns the text into a clean, paged reader and reads
it to you, highlighting the current sentence as it goes.

## Features
- Reads TXT, Markdown, EPUB, PDF, and other text files
- A real library: scan a folder and Lector remembers your books
- Picks up where you left off in every document
- Page view with a slider to move through a whole book
- Adjustable speed, sleep timer, background-friendly controls

## Private by design
No ads, no accounts, no trackers, **no network access, and no permissions at all.**
Your text never leaves your device. Reading aloud uses the text-to-speech engine
already on your phone. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

## Build
```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # release (unsigned unless a keystore is configured)
```
Requires the Android SDK (set `sdk.dir` in a local `local.properties`, or `ANDROID_HOME`).
Standard Gradle + Kotlin + Jetpack Compose; no proprietary dependencies.

- Language: Kotlin · UI: Jetpack Compose · min SDK 26 · target SDK 35
- The only third-party runtime dependency is [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
  (Apache-2.0), for PDF text extraction.

## License
[GNU GPL v3.0](LICENSE). Contributions welcome.

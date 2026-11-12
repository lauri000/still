# Still

A native PDF and EPUB speed reader for **Android**. Discover public-domain ebooks, or import a PDF or DRM-free EPUB, then read in a centered single line or a manually scrollable passage. Tap a passage word to continue in single-line mode, with your position saved across views.

## Android app

| Platform | Source | Build and run |
| --- | --- | --- |
| Android | [`apps/android/`](apps/android/) — Kotlin, Jetpack Compose, PDFium | [Android guide](apps/android/README.md) |

The app uses Kotlin and Jetpack Compose, with PDFium for PDF extraction and previews. The [portable book format and compatibility fixtures](shared/FORMAT.md) preserve existing backups, including those exported by the retired iOS and macOS apps.

## Repository layout

```text
apps/
  android/              Android Studio / Gradle project
shared/
  FORMAT.md             Portable book format contract
  fixtures/             Import, reader, and legacy-backup fixtures
  resources/            Offline PDF repair vocabulary and license

docs/                   Reading guide and screenshots
scripts/                Repository maintenance and verification tools
```

[Discover public-domain ebooks](docs/discovery.md) · [EPUB support and test fixtures](docs/epub.md) · [Reading, corrections, storage, and extraction limits](docs/reading.md) · [PDF repair and evaluation](docs/pdf-repair.md) · [Compatibility checks](shared/FORMAT.md#verification)

Books stay on the device. Backups can be exported and imported between devices; accounts, server components, automatic sync, and OCR are not included.

Licensed under [MIT](LICENSE). Third-party components retain their own licenses.
See [Android releases and Zapstore publishing](docs/publishing.md) for release
builds, signing-key backup, and local Nostr signing.

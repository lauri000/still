# Still

A native PDF speed reader for **iPad, macOS, and Android**. Import a PDF, choose a starting page, then read one centered word at a time with surrounding text, swipe navigation, and saved progress.

## Apps

| Platform | Source | Build and run |
| --- | --- | --- |
| Android | [`apps/android/`](apps/android/) — Kotlin, Jetpack Compose, PDFium | [Android guide](apps/android/README.md) |
| iOS / iPadOS | [`apps/apple/ios/`](apps/apple/ios/) — iOS configuration and UIKit bridge | [Apple guide](apps/apple/README.md) |
| macOS | [`apps/apple/macos/`](apps/apple/macos/) — macOS configuration, entitlements, and AppKit bridge | [Apple guide](apps/apple/README.md) |

The Apple targets share SwiftUI/PDFKit code in `apps/apple/shared/` and one Xcode project. Android has its own native implementation. Across Apple and Android, the [portable book format and compatibility fixtures](shared/FORMAT.md), repair rules, and offline vocabulary are shared; there is no Rust core.

## Repository layout

```text
apps/
  android/              Android Studio / Gradle project
  apple/
    ios/                iOS build settings and platform code
    macos/              macOS build settings and platform code
    shared/             Swift source and Apple resources
    tests/              Apple unit and UI tests
    Still.xcodeproj     Generated project for both Apple targets
    project.yml         XcodeGen specification
shared/
  FORMAT.md             Portable book format contract
  fixtures/             Cross-platform compatibility fixtures
  resources/            Offline PDF repair vocabulary and license

docs/                   Reading guide and screenshots
scripts/                Repository maintenance and verification tools
```

[Reading, corrections, storage, and extraction limits](docs/reading.md) · [PDF repair and evaluation](docs/pdf-repair.md) · [Compatibility checks](shared/FORMAT.md#verify-both-implementations)

Books stay on the device. Backups can be exported and imported across platforms; accounts, server components, automatic sync, and OCR are not included.

Licensed under [MIT](LICENSE). Third-party components retain their own licenses.
See [Android releases and Zapstore publishing](docs/publishing.md) for release
builds, signing-key backup, and local Nostr signing.

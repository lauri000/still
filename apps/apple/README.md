# Still for iOS, iPadOS, and macOS

The two native Apple targets share SwiftUI views, PDFKit services, reader logic, and resources. Each target has its own platform directory and build settings. There are no third-party Apple dependencies.

## Build and run

Open [`Still.xcodeproj`](Still.xcodeproj) in Xcode 26 or later.

- **Mac:** select the `StillMac` scheme and **My Mac**, then Run.
- **iPad simulator:** select the `Still` scheme and an iPad simulator, then Run.
- **Your iPad or iPhone:** select `Still`, choose your connected device, and set your development team under Signing & Capabilities. Xcode handles provisioning. This repository does not include a signing identity.

Minimum deployment targets are iOS/iPadOS 17 and macOS 14. The iOS target supports both iPad and iPhone.

Platform bundle IDs, versions, and signing-related settings live in [`ios/Config.xcconfig`](ios/Config.xcconfig) and [`macos/Config.xcconfig`](macos/Config.xcconfig). macOS sandbox entitlements live beside its configuration. Target membership and schemes are defined in [`project.yml`](project.yml).

The generated Xcode project is checked in. After changing target membership or the project specification, regenerate it from the repository root:

```sh
xcodegen generate --spec apps/apple/project.yml
```

## Source layout

- `ios/`: iOS build settings and the UIKit PDF view wrapper.
- `macos/`: macOS build settings, entitlements, and the AppKit PDF view wrapper.
- `shared/Core/`: book model, tokenizer, and playback engine.
- `shared/Services/`: PDF import, persistence, library state, and generated sample PDF.
- `shared/Views/`: library, page selection, reader, editing tools, and PDF coordination.
- `shared/Resources/`: shared Apple assets.
- `shared/StillApp.swift`: common application entry point and library initialization.
- `tests/`: `StillTests` and `StillUITests`.

Passage view uses equal-sized words, a dark centered active word, and muted surrounding rows. Full rows are justified to align their edges, while short paragraph endings keep normal spacing. The single-line reader retains its larger active word.

See the [reading guide](../../docs/reading.md) and [portable book contract](../../shared/FORMAT.md) for behavior shared with Android.

## Verify

Run from the repository root:

```sh
xcodebuild -project apps/apple/Still.xcodeproj -scheme StillMac \
  -derivedDataPath DerivedData/Mac CODE_SIGNING_ALLOWED=NO build

xcodebuild -project apps/apple/Still.xcodeproj -scheme Still \
  -destination 'platform=iOS Simulator,name=Still iPad' \
  -derivedDataPath DerivedData/iOS CODE_SIGNING_ALLOWED=NO test
```

Use an available iPad simulator name if `Still iPad` is not installed locally. `StillTests` covers real PDF extraction, bookmarks, Unicode positions, trims, corrections, persistence, backups, and playback/scrubbing. `StillUITests` covers the import picker, starting-page selection, exact resume, surrounding words, swipe/tap navigation, and optional corrections. UI tests use an isolated temporary library.

The [Swift/Kotlin compatibility checks](../../shared/FORMAT.md#verify-both-implementations) also compile the production Swift model and sample PDF generator directly.

## Current verification

Verified on 16 October 2026 with Xcode 26.3: the macOS target builds, and all eight iPad UI scenarios pass. The centered-passage test also passes on the iPhone 17 simulator, including portrait/landscape centering and visible rows above and below the active word. Tests cover mutually exclusive reading views, row-word selection, swiping, focus-letter alignment, and hold-to-read. Previews are in [`docs/screenshots/centered-passage/`](../../docs/screenshots/centered-passage/). The native core's 38 unit/integration tests passed in the preceding hold-to-read release.

macOS UI interaction was not manually verified because desktop Computer Use permission was unavailable. Physical Apple devices and developer signing were not exercised.

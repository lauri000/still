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

The compact header holds page navigation and progress; text fills the remaining space. On iPhone and iPad, hold the invisible bottom-right quarter to read and drag horizontally to adjust speed. Start elsewhere to scrub; each gesture keeps its starting role until release. Tapping words no longer jumps. On Mac, header buttons handle playback, stepping, and speed (±10 WPM), with Space and arrow keys retained. WPM appears only after a speed adjustment and fades when adjustments stop.

Passage view uses equal-sized words by default, a dark centered active word, and muted surrounding rows. Full rows are justified to align their edges, while short paragraph endings keep normal spacing. The saved **Active word size** slider in reading options adjusts the active word in both views from 100% to 300% of the surrounding text size. The default is 100%, and long words still shrink to fit. Reading options include a persistent **Animated scrolling** toggle, off by default, for both layouts. It moves surrounding words while keeping the active word or focus letter centered, settles immediately when paused or navigated, and respects Reduce Motion.

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

Use an available iPad simulator name if `Still iPad` is not installed locally. `StillTests` covers real PDF extraction, bookmarks, Unicode positions, trims, corrections, persistence, backups, and playback/scrubbing. `StillUITests` covers the import picker, starting-page selection, exact resume, surrounding words, swipe navigation and fixed gesture zones, and optional corrections. UI tests use an isolated temporary library.

The [Swift/Kotlin compatibility checks](../../shared/FORMAT.md#verify-both-implementations) also compile the production Swift model and sample PDF generator directly.

## Current verification

Verified on 16 October 2026 with Xcode 26.3: 43 unit/layout tests and all 10 iPad UI tests pass. Four focused iPhone scenarios also pass, covering the compact header, gesture zones, speed changes, passage layout, and saved word size in portrait and landscape. The macOS target builds, and an isolated native view preview confirms the header and reading layout. Physical Apple devices and developer signing were not exercised.

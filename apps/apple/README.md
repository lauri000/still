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

Passage view keeps words in stable, left-aligned rows. The highlight advances through them while the whole column scrolls vertically, keeping the active row near the middle. Motion follows exact word timing and natural pauses; release freezes the column and resuming continues from that point. The saved size slider ranges from 100% to 300%: **Active word size** adjusts the highlighted word in single-line view, while **Passage text size** scales the whole passage uniformly with natural word spacing. Selection never shifts surrounding words. Long words still shrink to fit. **Animated scrolling** remains an optional single-line transition, off by default. Passage motion is always continuous unless Reduce Motion is enabled, which holds the active row still and advances only at row boundaries. **Focus letter** remains centered in single-line view and highlights the letter in place in passage view.

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

The scrolling-passage update was checked on 17 October 2026: all 47 unit/layout tests and 10 iPad UI scenarios passed, including a targeted rerun after correcting the starting-page test. The current-main macOS build was launched and visually checked.

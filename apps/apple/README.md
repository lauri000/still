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

Passage view shows plain text in stable, left-aligned rows while the column scrolls vertically. It has no highlighted word or focus letter. Nearby text is laid out on demand instead of preparing the whole book. The same hold, speed, and scrub controls remain available; pausing freezes the column, and resuming continues from that position. Body words receive equal time at the selected WPM; reliably matched chapter endings get a brief pause. System reduced-motion settings advance stationary rows at row boundaries.

The reading-options button appears only in single-line mode. **Active word size** (100%–300%), **Focus letter**, **Animated scrolling**, **Natural pauses**, and **Pause at unchecked pages** apply only to that mode. Passage view uses its own regular text size and reads unchecked text, skipping empty pages. Switching modes preserves the position, WPM, and saved single-line preferences. Backgrounding or leaving either view pauses and saves progress.

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

Plain-passage verification on 19 October 2026: all 52 unit tests and 10 iPhone UI scenarios pass, including independent single-line preferences, plain scrolling rows, rotation, gestures, and saved positions. The macOS target builds. Large-book layout tests verify matching rows across nearby-page windows.

PDF contents navigation: the starting-page chooser reads the original PDF's embedded
outline, preserving source order, nested groups, and duplicate destinations. Wide
screens show a collapsible sidebar; phones use a Contents sheet. Browsing only
changes the preview until Read is chosen. See [the specification](../../docs/pdf-outline.md).

Book typography and reader Contents: matched headings use larger text in multiline
and appear together in single-line reading. Tap the chapter/page header to browse
Contents. Exact matches seek the heading; other entries navigate by page. See
[reading](../../docs/reading.md#book-typography-and-contents) for details.

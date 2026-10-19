# Still for Android

Native Kotlin and Jetpack Compose, with on-device PDFium extraction and preview through PdfiumAndroidKt. The Apple app remains SwiftUI/PDFKit. There is no Rust core, account, network permission, or server dependency.

## Build and run

Open this `apps/android/` directory in Android Studio, allow Gradle sync, and run `app` on a device or emulator. Use JDK 17 or newer (Android Studio's bundled JDK works), Android SDK Platform 37, and Build Tools 36. Gradle can install the required SDK components after their licenses have been accepted.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk" # macOS default; adjust on other systems
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android version: **Android 9 / API 28**. The app currently targets API 36 and compiles against API 37 to satisfy the PDF library's build requirement. The universal debug APK includes ARM and x86 native PDF libraries; it is for local installation, not a signed Play Store release.

For signed APKs and Zapstore distribution, follow the [release guide](../../docs/publishing.md).
Release keys stay outside the repository; the release helper builds a committed source snapshot.

## Reading

Import a PDF, choose the starting page using the PDF preview, and tap **Read**. The compact header contains page navigation and progress; text fills the remaining screen in portrait and landscape. Hold anywhere in the invisible bottom-right quarter of the reading area to begin; release to pause. While holding, move right to increase the current speed or left to decrease it. Each touch starts at the saved speed, from 60 to 900 WPM. A floating WPM label appears only when speed changes and fades after adjustments stop.

Start a horizontal swipe anywhere outside that corner to move forward/backward through the text. The starting zone determines the entire gesture: crossing into or out of the corner never switches between scrubbing and speed control. Additional fingers cannot take over. Scrubbing pauses playback without changing speed; hold the corner again to resume from the selected word. Tapping words does not jump to them. Screen readers retain start/pause, speed adjustments, and next/previous word actions.

Passage view shows plain text in stable, left-aligned rows while the column scrolls vertically. It has no highlighted word or focus letter. Nearby text is laid out on demand instead of preparing the whole book. The same hold, speed, and scrub controls remain available; pausing freezes the column, and resuming continues from that position. Each word receives equal time at the selected WPM. System reduced-motion settings advance stationary rows at row boundaries.

The reading-options button appears only in single-line mode. **Active word size** (100%–300%), **Focus letter**, **Animated scrolling**, **Natural pauses**, and **Pause at unchecked pages** apply only to that mode. Passage view uses its own regular text size and reads unchecked text, skipping empty pages. Switching modes preserves the position, WPM, and saved single-line preferences. Backgrounding or leaving either view pauses and saves progress.

The book's ellipsis menu contains corrections, page inclusion/review, chapters, a starting-word selector with **Restore beginning**, title/author editing, portable backup export, and deletion. Original PDFs and extracted text remain intact.

## Storage and compatibility

Books live in app-private `files/Still/<UUID>/`. Writes use a serial queue and atomic file replacement. Text/metadata edits are debounced for 500 ms and flushed on navigation/backgrounding. Reader positions checkpoint every 20 words and on pause, closing, or ending a swipe. Imports commit only after extraction succeeds; duplicate backup imports become separate library copies.

The existing Apple JSON backup format is preserved, including Foundation date encoding, base64 PDF data, UTF-16 positions, page IDs, corrections, and beginning trims. See [the shared format contract](../../shared/FORMAT.md). Export and import the backup to move a book between Android, iPad, and macOS. There is no automatic sync. Android cloud backup is disabled; use explicit book exports.

## Extraction limits

- Selectable-text PDFs only; no OCR. Import-time repair uses glyph geometry and an offline vocabulary to fix ligatures, wrapped words, and supported spacing errors. Recognized recurring margin page numbers/headings are removed.
- Source extraction order is preserved. Complex columns, footnotes, unusual fonts, and ambiguous compounds can still require corrections. Existing books have an explicit **Improve text** command with session undo. See [the repair evaluation](../../docs/pdf-repair.md).
- Embedded bookmarks are imported with their hierarchy and page destinations. New Android imports use sequential page labels because the PDF binding does not expose custom page labels. Custom labels in Apple backups are retained.
- Password-protected PDFs need to be unlocked before import. The Android binding does not expose PDF copy-permission flags; it does not reproduce PDFKit's separate copy-restriction check.
- PDFs are limited to 250 MB. Reading a large JSON backup also needs memory for its base64 string; memory failures report an error and preserve existing books.
- Different PDF engines can produce different text. Moving a stored book via backup retains its exact text and positions; independently importing the PDF again creates a new book.
- Word segmentation is based on whitespace. Languages without word-separating spaces need a future language-aware tokenizer.

## Verification

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

JVM tests cover shared Swift tokenizer fixtures, Apple backup decoding, Kotlin export/Swift import, trims and exclusions, Unicode offsets, edits, timing, strict review, scrub interruption/checkpoints, malformed data, atomic persistence, and duplicate imports. Device tests exercise real PDF extraction/preview, Android Unicode behavior, the import-to-reading flow, swiping, gesture-zone ownership, corrections, and saved progress. They use isolated test libraries.

Verified on 16 October 2026:

- Debug APK builds; 16 JVM tests and all 10 Android device tests pass on the Android 16 / API 36 emulator, including relative speed changes, release/cancel, dragging outside the corner, and speed retention.
- The Swift checker passes 6 tokenizer fixtures and reads the Android-exported portable backup.
- Android lint reports zero errors (16 warnings, primarily dependency/SDK update notices).
- Manual import through the system file picker, portrait/landscape reader layouts, and selected-word retention across rotation were checked. Screenshots are in [`docs/screenshots/android/`](../../docs/screenshots/android/).
- The new corner controls were inspected in portrait and landscape; previews are in [`docs/screenshots/hold-to-read/`](../../docs/screenshots/hold-to-read/).
- The centered-passage update passes all 10 device tests, including fixed word/focus-letter alignment, exclusive views, row selection, and book boundaries. Portrait and compact landscape previews are in [`docs/screenshots/centered-passage/`](../../docs/screenshots/centered-passage/).
- Physical Android devices and Play Store distribution have not been exercised.

Animated-scrolling verification on 16 October 2026: all 20 JVM tests and 11 emulator tests pass, including sampled intermediate frames in both layouts, fixed centering, immediate pause, disabled animation scale, saved settings, resume, and the hold/speed gesture. Lint reports zero errors and 17 warnings (dependency/SDK notices and existing API-style suggestions, including the new preference setter).

Full-screen reader verification for 0.1.5: all 20 JVM tests and 17 device tests pass. Five focused checks also pass in phone landscape, covering the compact header, gesture-zone ownership, WPM visibility, text sizing, and animation. Rotation and backgrounding cancel a held gesture and preserve the selected word. Lint reports zero errors and 18 existing dependency/SDK/API-style warnings. Current screenshots are in [`docs/screenshots/full-screen-reader/`](../../docs/screenshots/full-screen-reader/).

PDFium, PdfiumAndroidKt, PDFBox-Android, and Bouncy Castle license texts are included in `app/src/main/assets/licenses/` and packaged with the APK.

The scrolling-passage update was checked on 17 October 2026: all 24 unit tests and 17 emulator scenarios passed (the review-prompt scenario needed an isolated rerun). The passage test also verifies continuous vertical movement between words, stable horizontal positions, and freezing on release. Debug build and lint completed with no lint errors; existing warnings remain.

The bundled SCOWL vocabulary and its full license are in `shared/resources/` and packaged as assets on Android and resources on Apple.

Plain-passage verification on 19 October 2026: all 31 JVM tests and 17 emulator scenarios pass; lint has zero errors and 18 existing warnings. An opt-in test with the local 284-page, 104,789-word 1984 PDF opened multiline in 160–230 ms across the former size/focus/natural-pause combinations, compared with about 5.8 seconds with natural pauses in 0.1.8. The PDF is not bundled or committed. Multiline now lays out nearby text and does not calculate natural-pause durations for the book. Physical GrapheneOS testing remains outstanding.

PDF contents navigation: the starting-page chooser reads the original PDF's embedded
outline with PDFBox-Android, preserving source order, nested groups, and duplicate
destinations. PDFium continues to extract text and render pages. Wide
screens show a collapsible sidebar; phones use a Contents sheet. Browsing only
changes the preview until Read is chosen. See [the specification](../../docs/pdf-outline.md).

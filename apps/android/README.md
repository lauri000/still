# Still for Android

**Discover** sits beside **Import file** in the library. Browse Project Gutenberg, search by title or author, filter languages, and download individual EPUBs for offline reading. See [discovery](../../docs/discovery.md).

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

Import a PDF or EPUB, choose the starting page or section using the preview, and tap **Read**. The compact header contains page navigation and progress; text fills the remaining screen in portrait and landscape. Hold anywhere in the invisible bottom-right quarter of the reading area to begin; release to pause. While holding, move right to increase the current speed or left to decrease it. Each touch starts at the saved speed, from 60 to 900 WPM. A floating WPM label appears only when speed changes and fades after adjustments stop.

Start a horizontal swipe anywhere outside that corner to move forward/backward through the text. The starting zone determines the entire gesture: crossing into or out of the corner never switches between scrubbing and speed control. Additional fingers cannot take over. Scrubbing pauses playback without changing speed; hold the corner again to resume from the selected word. Tapping words in single-line mode does not jump to them. Screen readers retain start/pause, speed adjustments, and next/previous word actions.

Passage view is a native, manually scrollable column of plain, left-aligned text. Tap or click any word to open single-line mode paused at that exact word. Switching back brings the same word into view; scrolling updates the saved place from the top visible row. Paragraph blocks are laid out lazily, without measuring the whole book. There is no hold zone, speed gesture, word highlight, or playback timer in passage view. Matched headings keep their larger typography.

The reading-options button appears only in single-line mode. **Active word size** (100%–300%), **Focus letter**, **Animated scrolling**, **Natural pauses**, and **Pause at unchecked pages** apply only to that mode. Passage view uses its own regular text size and reads unchecked text, skipping empty pages. Switching modes preserves the position, WPM, and saved single-line preferences. Backgrounding or leaving either view pauses and saves progress.

The book's ellipsis menu contains corrections, page inclusion/review, chapters, a starting-word selector with **Restore beginning**, title/author editing, portable backup export, and deletion. Original PDFs and extracted text remain intact.

## Code ownership

`ReaderScreen` owns controls and saved view selection. Its `WordRibbon` and `WordMotion` are single-line only: the ribbon lays out one horizontal neighborhood, and motion interpolates horizontal positions. `PassageView` owns native scrolling and exact-word hit testing; `PassageLayout` contains geometry and source-token ranges, without duplicate display text. Both views share `ReadingSession` for the reading position, while only single-line mode can start its clock.

`LibraryViewModel` owns one cancellable preparation job for imports and text repair. Both use the same progress/error cleanup and await the existing serial repository queue for atomic commits. Cancellation stays outside a commit once it begins. `PdfImporter` owns PDFium document/descriptor lifetimes through one helper; per-page text and geometry resources remain scoped to their use. PDF backups and persisted preference keys remain compatible; EPUB backups add an original source payload. See [EPUB support](../../docs/epub.md).

## Storage and compatibility

Books live in app-private `files/Still/<UUID>/`. Writes use a serial queue and atomic file replacement. Text/metadata edits are debounced for 500 ms and flushed on navigation/backgrounding. Reader positions checkpoint every 20 words and on pause, closing, or ending a swipe. Imports commit only after extraction succeeds; duplicate backup imports become separate library copies.

The existing Apple JSON backup format is preserved, including Foundation date encoding, base64 PDF data, UTF-16 positions, page IDs, corrections, and beginning trims. See [the shared format contract](../../shared/FORMAT.md). Export and import the backup to move a book between Android, iPad, and macOS. There is no automatic sync. Android cloud backup is disabled; use explicit book exports.

## Extraction limits

- Selectable-text PDFs only; no OCR. Import-time repair uses glyph geometry and an offline vocabulary to fix ligatures, wrapped words, and supported spacing errors. Recognized recurring margin page numbers/headings are removed.
- Source extraction order is preserved. Complex columns, footnotes, unusual fonts, and ambiguous compounds can still require corrections. Existing books have an explicit **Reprocess book** command with session undo. See [the repair evaluation](../../docs/pdf-repair.md).
- Embedded bookmarks are imported with their hierarchy and page destinations. New Android imports use sequential page labels because the PDF binding does not expose custom page labels. Custom labels in Apple backups are retained.
- Password-protected PDFs need to be unlocked before import. The Android binding does not expose PDF copy-permission flags; it does not reproduce PDFKit's separate copy-restriction check.
- PDFs are limited to 250 MB. Reading a large JSON backup also needs memory for its base64 string; memory failures report an error and preserve existing books.
- Different PDF engines can produce different text. Moving a stored book via backup retains its exact text and positions; independently importing the PDF again creates a new book.
- Word segmentation is based on whitespace. Languages without word-separating spaces need a future language-aware tokenizer.

## Verification

Python 3 generates the small EPUB fixtures before tests. Optional real books are downloaded separately; see [EPUB fixtures](../../docs/epub.md).

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

Book typography and reader Contents: matched headings use larger text in multiline
and appear together in single-line reading. Tap the chapter/page header to browse
Contents. Exact matches seek the heading; other entries navigate by page. See
[reading](../../docs/reading.md#book-typography-and-contents) for details.

Manual-passage verification (19 October 2026): 39 JVM tests and all 22 Android reader UI scenarios pass; lint has zero errors. The new scenario checks native scrolling, tapping an exact word, repeated mode switches, reopening, and preservation of single-line options. The private 104,789-word 1984 fixture opens in every tested 100%/125%, focus-letter, and natural-pause combination. Debug emulator timings were 0.46–1.24 seconds while Apple builds/tests also ran; these are not physical-device benchmarks. No private PDF or text is bundled.


Android passage scrolling now uses one text/tap target per row. Word ranges map a tap to the source token, and accessibility actions expose individual words. This avoids a separate Compose link layout for every word; isolating row composition also keeps cursor updates from rebuilding unaffected rows. Native fling behavior and reading-position tracking are unchanged.

Scroll profiling on 19 October 2026 used the same 12 wall-clock swipes through a private 284-page fixture in the dedicated Android 16 emulator. In the controlled original/improved debug-build pair, the 95th-percentile CPU frame-path duration fell from 11.5 to 6.2 ms, and the 99th percentile from 113.4 to 41.2 ms. Both traversed 464 words. These are comparative emulator measurements, not a physical-device benchmark; no private text is bundled or logged.

The opt-in `PassagePerformanceTests#scrollFrameTimings` probe reads a local `Book` JSON from `files/passage-evaluation/scroll-book.json` in the isolated `-PpdfEvaluationBuild` app. Run it with instrumentation argument `scrollEvaluation=true`. It reports frame percentiles from `Window` frame metrics, including a CPU-path sum of unknown delay, input, animation, layout, draw, sync, and command issue durations. Install test APKs directly when using this fixture so Gradle's connected-test cleanup does not remove it.

Scroll-renderer verification: 39 JVM tests and all 26 Android reader/scroll UI scenarios pass, including live-drag momentum, paragraph crossings, exact word taps, ignored whitespace, accessibility word actions, mode switches, rotation, and reopening. Release compilation succeeds; debug lint reports zero errors and the existing 21 warnings.

Architecture-cleanup verification (19 October 2026): 39 JVM tests and 29 Android device scenarios pass, including import/repair cancellation, failed-import recovery, single-line animation, focus alignment, native fling, and exact word selection. Debug builds succeed; lint reports zero errors and the existing 21 warnings. Shared backup/Unicode/PDF-repair contracts also pass.

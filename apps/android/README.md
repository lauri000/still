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

## Reading

Import a PDF, choose the starting page using the PDF preview, and tap **Read**. Press Play to begin. The selected word stays centered, with surrounding words visible. Swipe left/right to move forward/backward, or tap a nearby word. Swiping pauses playback; press Play to resume from the selected word. Speed ranges from 60 to 900 WPM.

The text icon opens a larger nearby passage. Reading options contain the saved **Focus letter** toggle (off by default), natural pauses, and optional strict page review. Focus letter highlights one character and holds it at the center as words advance. The screen stays awake while playing; backgrounding, leaving the reader, and interrupted swipes pause and save the selected position. Reopening a book offers **Continue reading**. Explicitly choosing another starting page resets its beginning boundary.

The book's ellipsis menu contains corrections, page inclusion/review, chapters, a starting-word selector with **Restore beginning**, title/author editing, portable backup export, and deletion. Original PDFs and extracted text remain intact.

## Storage and compatibility

Books live in app-private `files/Still/<UUID>/`. Writes use a serial queue and atomic file replacement. Text/metadata edits are debounced for 500 ms and flushed on navigation/backgrounding. Reader positions checkpoint every 20 words and on pause, closing, or ending a swipe. Imports commit only after extraction succeeds; duplicate backup imports become separate library copies.

The existing Apple JSON backup format is preserved, including Foundation date encoding, base64 PDF data, UTF-16 positions, page IDs, corrections, and beginning trims. See [the shared format contract](../../shared/FORMAT.md). Export and import the backup to move a book between Android, iPad, and macOS. There is no automatic sync. Android cloud backup is disabled; use explicit book exports.

## Extraction limits

- Selectable-text PDFs only; no OCR or automatic header/footer removal.
- Source extraction order is preserved. Complex columns, footnotes, ligatures, and hyphenated line breaks can require corrections.
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

JVM tests cover shared Swift tokenizer fixtures, Apple backup decoding, Kotlin export/Swift import, trims and exclusions, Unicode offsets, edits, timing, strict review, scrub interruption/checkpoints, malformed data, atomic persistence, and duplicate imports. Device tests exercise real PDF extraction/preview, Android Unicode behavior, the import-to-reading flow, swiping, tap selection, corrections, and saved progress. They use isolated test libraries.

Verified on 16 October 2026:

- Debug APK builds; 15 JVM tests and all 7 Android device tests pass on the Android 16 / API 36 emulator.
- The Swift checker passes 6 tokenizer fixtures and reads the Android-exported portable backup.
- Android lint reports zero errors (16 warnings, primarily dependency/SDK update notices).
- Manual import through the system file picker, portrait/landscape reader layouts, and selected-word retention across rotation were checked. Screenshots are in [`docs/screenshots/android/`](../../docs/screenshots/android/).
- Physical Android devices, release signing, and Play Store distribution have not been exercised.

PDFium and PdfiumAndroidKt license texts are included in `app/src/main/assets/licenses/` and packaged with the APK.

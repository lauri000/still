# PDF text repair

Still repairs native PDF text at import time on Android, iPad/iPhone, and macOS. It uses PDFium or PDFKit plus deterministic rules; there is no OCR, model download, network request, or inference during playback.

## How it works

1. Retain the original text and collect selective glyph geometry. Android checks that PDFium character indices agree with UTF-16 source offsets. Apple uses PDF selections because characterBounds(at:) can disagree with page.string around ligatures and newlines.
2. Expand presentation ligatures, remove soft hyphens, normalize line separators, and interpret PDFium's discretionary-hyphen marker. Geometry distinguishes invented spaces following malformed ligature advances from actual word boundaries.
3. Learn vocabulary from the document, excluding wrapped fragments. Use a bundled 79,669-word SCOWL vocabulary only when common English words supply evidence that the document is English. Preserve recognized hyphenated compounds; join wrapped continuations, including words spanning pages or several lines.
4. Repair other missing/extra spaces only when the document supports the resulting words and native geometry supports the boundary. Decorative initials have a separate size/alignment check. Unknown long tokens are not exhaustively segmented.
5. Remove recognized recurring numeric margin furniture. Preserve paragraphs, content order, punctuation, and original extraction. Track UTF-16 provenance through each repair to relocate saved positions.

Native pages are opened selectively for candidate boundaries; Android holds only one such page at a time. Dictionary loading is cached. Cancellation is checked between pages and repair passes. Shared fixtures cover Unicode, genuine compounds, paragraph breaks, cross-page continuations, disabled pages, furniture, geometry gating, and offset relocation.

This is formatting repair, not spelling correction. It does not replace unfamiliar names or rewrite prose. The dictionary is approximately 745 KiB uncompressed; source, regeneration script, and complete license are included.

## Existing books

New imports receive version 1 automatically. Existing libraries and portable backups remain unchanged when opened. **Improve text** upgrades included pages without manual edits; an immediate **Undo text improvement** restores the previous book. Undo lasts for the session and is invalidated by another mutation of that book. Page IDs, metadata, chapters, exclusions, and the immutable PDF are retained. See [the format contract](../shared/FORMAT.md).

## Evaluation

The supplied Hume, Charisma, and Orwell PDFs contain 302, 260, and 284 pages respectively: **846 pages**. They were processed through both production native importers. Private PDFs, extracted text, and glyph dumps are outside the repository; the committed fixtures contain synthetic text.

The targeted defects included PDFium's embedded discretionary-hyphen markers, malformed fi spacing, missing spaces between complete words, and detached decorative initials. The evaluation compares original/repaired text, native engines, and visual PDF samples. Engine agreement is a diagnostic, **not a ground-truth accuracy score**. This is not a manual proofreading of every word.

Measured on 17 October 2026, using Android 16 / API 36 in an ARM emulator and PDFKit on this Mac. These are median native extraction + cleanup times over three runs, excluding file-picker copying and library saving. Output was identical between runs. Emulator timings do not establish physical-phone performance.

| Book | Pages | Android | Apple / macOS | Targeted results |
| --- | ---: | ---: | ---: | --- |
| Hume Enquiry | 302 | 3.40 s | 2.07 s | 881 embedded hyphen markers resolved; missing word boundary and detached initials repaired |
| The Charisma Myth | 260 | 2.71 s | 1.31 s | All 48 detected wrapped compounds retained their genuine hyphens |
| Orwell 1984 | 284 | 2.71 s | 1.74 s | 742 detected fi-spacing defects removed; 867 embedded hyphen markers resolved |

The Hume fused-word example changes from abstractreasonings to abstract reasonings; the Orwell overlapping-space example changes from curr ent to current. Apple's decorative-initial examples change from Iwas/Ineed to I was/I need. These checks cover known defects; they are not a claim of zero remaining errors.

Validation: 21 Android JVM tests, nine Android device tests (the opt-in corpus harness is skipped in the ordinary run), 40 iPad tests, 12 shared repair fixtures plus geometry/cancellation/Unicode contracts, and portable backup compatibility checks passed. Android lint has no errors; existing dependency/SDK warnings remain. The macOS app builds successfully. The supplied-book evaluation was also run separately through the Android instrumentation harness and the macOS production importer.

Remaining limitations include column/footnote reading order, superscript footnote attachment, headers outside the recognized margin patterns, and novel compounds whose intended hyphenation is ambiguous. A reader can still use the PDF preview and page editor for these. Local AI could later propose corrections for uncertain spans, but automatic model rewriting would require its own accuracy, latency, and memory evaluation before replacing this predictable import path.

## Reproduce on Apple

Run from the repository root on a Mac with Xcode. Choose an output directory outside the checkout because JSON contains complete private book text.

~~~sh
xcrun swiftc -O \
  apps/apple/shared/Core/Book.swift \
  apps/apple/shared/Core/PDFTextRepair.swift \
  apps/apple/shared/Services/PDFImporter.swift \
  apps/apple/shared/Services/PDFTextGeometry.swift \
  apps/apple/shared/Services/PDFVocabulary.swift \
  scripts/evaluate-pdfs.swift -o /tmp/evaluate-pdfs
/tmp/evaluate-pdfs /tmp/pdf-results /path/to/book.pdf
~~~

The JSON stores elapsed extraction/repair seconds, raw text, and repaired text per physical page. The original PDF is not modified.

## Reproduce on Android

Use an isolated emulator/device. The pdfEvaluationBuild Gradle property installs a separate application ID so evaluation does not replace an existing reader or library. Set ANDROID_SERIAL when multiple devices are connected.

~~~sh
cd apps/android
./gradlew -PpdfEvaluationBuild :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell run-as com.stillreader.app.pdfevaluation mkdir -p files/pdf-evaluation
adb shell 'run-as com.stillreader.app.pdfevaluation sh -c "cat > files/pdf-evaluation/book.pdf"' < /path/to/book.pdf
adb shell am instrument -w \
  -e class com.stillreader.app.PdfEvaluationTests -e pdfEvaluation true \
  com.stillreader.app.pdfevaluation.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as com.stillreader.app.pdfevaluation \
  cat files/pdf-evaluation/book.pdf.json > /tmp/book.android-repaired.json
~~~

The harness processes all PDFs in that evaluation directory. It is skipped in ordinary device test runs. Optional -e pdfGeometry true writes large per-character geometry diagnostics, also containing private text.

Compare one pair of native outputs from the repository root:

~~~sh
python3 scripts/compare-pdf-extraction.py \
  /tmp/pdf-results/book.pdf.apple-repaired.json /tmp/book.android-repaired.json
~~~

The --details option prints differing tokens. Counts of fi followed by a space are candidates, not universally errors; hyphen counts alone cannot establish correctness. Run timing measurements separately from builds/tests, repeat them, and distinguish emulator timings from physical-device performance.

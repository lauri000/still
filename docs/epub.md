# EPUB import

Still imports DRM-free, reflowable EPUB 2 and EPUB 3 on Android, iOS/iPadOS, and macOS. Choose **Import book** and select an `.epub`. The contents picker follows the EPUB's navigation and opens exact chapter anchors, including several chapters within one section. Both reader modes, starting-word trims, corrections, backup export/import, and saved positions work with the prepared text.

The importer follows the declared linear spine, extracts XHTML paragraphs and headings, preserves verse line breaks and inline word boundaries, and resolves relative paths and Unicode fragments. EPUB 3 navigation is preferred, with EPUB 2 NCX as fallback; without either, headings provide a contents list. EPUB source files remain internal text units; one may contain several chapters. The reader and library show chapter names and word-based progress through the retained book text. Contents entries show their percentage into the book, and **Go to progress** replaces navigation by internal file number. Text tools describe the chapter range in each editable unit. Books without chapter metadata still support progress navigation.

Conversion runs once at import. The saved reader data includes word ranges, semantic headings and exact chapter destinations. Continue reading loads that prepared output without opening the archive. The original EPUB is kept unchanged for **Reprocess book**, and travels with a portable backup. See [the format contract](../shared/FORMAT.md).

This is a text reader: original CSS layout, images, tables as visual grids, interactive notes, audio and scripted content are not reproduced. Nonlinear appendix/resources are outside the continuous reading sequence. Fixed-layout, encrypted reading content, and image-only books report a clear unsupported-source error. Font obfuscation is allowed because embedded fonts are not used. Word segmentation remains whitespace-based; the multilingual fixtures check text and anchor preservation, not Japanese word segmentation.

Archives are read without extracting files to disk or executing HTML. External XML DTDs are not fetched, custom entities are rejected, and standard XHTML named entities use the bundled `shared/resources/xhtml-entities.json` table (HTML 4 entity names/code points from Python's standard `html.entities.name2codepoint`). Paths stay within the archive. Import limits are 250 MB compressed, 512 MB declared expanded size, 10,000 entries, 16 MB per XML document, and 10 million UTF-16 text units. Cancellation or failure leaves the library unchanged.

Android uses the platform ZIP/XML readers. Apple uses Foundation XMLParser and pinned ZIPFoundation 0.9.20; its MIT license is included in the Apple resources. Both implementations produce the same portable data.

## Fixtures without committed books

**No EPUB binaries are stored in Git**, including synthetic fixtures. Generate the small, project-owned test archives locally:

```sh
python3 scripts/epub-fixtures.py
```

Android test tasks and the Xcode unit-test target run this generator automatically. It covers out-of-order ZIP entries, linear spine order, EPUB 2 NCX, EPUB 3 navigation, Unicode paths/anchors, repeated headings, inline text, standard entities, hidden content, and malformed/encrypted/fixed/image-only inputs. No network is needed for these tests.

The optional real-book corpus is described in [`manifest.json`](../shared/fixtures/epub/manifest.json): original download and source URLs, SHA-256 hashes, sizes, rights notices, and observed features. To download it:

```sh
python3 scripts/download-epub-fixtures.py
```

The script verifies existing downloads, checks new downloads against the manifest, and refuses changed editions. W3C sample release URLs are pinned to 20230704. Gutenberg download URLs can change content; review the replacement before updating a hash. Native corpus tests skip when the files are absent. Builds never fetch books automatically. Downloaded books retain their own licenses and notices; they are not relicensed under Still's MIT license.

| Source | Purpose |
| --- | --- |
| Gutenberg Hume, EPUB 2 and 3 | NCX/navigation, long sections, multiple destinations in one file |
| Gutenberg Alice | Dialogue, inline emphasis, verse breaks |
| W3C Moby-Dick | Large spine, navigation without NCX, many chapters |
| W3C Children's Literature | Nested contents, pagebreak markers, navigation in the spine |
| W3C Internal Links | Japanese text and non-ASCII anchors |
| W3C Israel Sailing | Hebrew logical text order and RTL source |
| W3C Haruko | Clear fixed-layout rejection |

## Cross-platform check

Run the native unit tests, then build the macOS target and link the contract checker against its ZIP dependency. From the repository root:

```sh
(cd apps/android && ./gradlew :app:testDebugUnitTest)
xcodebuild -project apps/apple/Still.xcodeproj -scheme StillMac \
  -derivedDataPath /tmp/still-epub-mac CODE_SIGNING_ALLOWED=NO build
xcrun swiftc -I /tmp/still-epub-mac/Build/Products/Debug \
  /tmp/still-epub-mac/Build/Products/Debug/ZIPFoundation.o \
  apps/apple/shared/Core/{Book,ReaderPipeline,ReadingStructure,PassageLayout}.swift \
  apps/apple/shared/Services/{ImportedBook,EPUBImporter}.swift \
  scripts/epub-contracts.swift -o /tmp/still-epub-contracts
/tmp/still-epub-contracts
(cd apps/android && ./gradlew :app:testDebugUnitTest --tests '*EpubImporterTests' --rerun-tasks)
```

The checker compares native imports' text, section order, metadata, word indexes, semantic heading spans, and chapter offsets. It decodes the Android EPUB backup in Swift and emits a Swift backup for the final Android test to decode. Generated outputs stay under the ignored Android build directory.

## Verification (20 October 2026)

- 55 Android JVM tests and 80 Apple unit/layout tests pass, including EPUB 2/3, Unicode anchors, repeated headings, prepared persistence, original-byte backups, correction preservation, cancellation, and unsupported input errors.
- The seven supported downloaded books produce identical text, word indexes, heading spans and chapter destinations in Swift and Kotlin. EPUB backup contracts pass in both directions, alongside the existing PDF/tokenizer/prepared-reader contracts.
- The Android device suite passed 30 scenarios; five wide-screen/private-fixture performance probes were skipped on the phone emulator. Final focused EPUB tests pass for chapter selection, both reader modes, resume, backup reimport, and starting-word selection 10,000 words into Hume. Lint reports zero errors and 21 existing warnings.
- All 12 existing iPhone UI scenarios were exercised. After updating the empty-library assertion to “No books yet,” that scenario and the affected contents/starting-word scenarios pass in the final targeted run. The final macOS target builds successfully.

These are simulator/emulator checks, not physical-device benchmarks. Fixed-layout and image-only reading remain outside this release's scope.

## Review fixes

EPUB pipeline version 2 preserves hard line breaks through the scrolling layout and stores chapter source anchors for restoration. PDF pipeline version 1 remains unchanged. Restoring a section recovers its text, headings, and imported chapter destinations together, including repeated chapter names, while keeping manual chapter additions and removals. Text corrections no longer mark navigation as customized.

Text edits calculate one offset mapping for all headings and chapters. Reader preparation is debounced off the UI thread; ordered background saves reject stale results in memory, and reading/export/reprocessing wait for pending edits. Regression cases include rapid edits, immediate reading, reversing an edit back to the original text, and restoring damaged structure.

The review fixes pass 59 Android JVM tests and 85 Apple unit/layout tests, five focused Android device scenarios, and three iPhone UI scenarios covering editing, resume and passage scrolling. The macOS build and seven-book native compatibility checks pass. In an optimized Swift probe on the development Mac, relocating anchors for one character inserted into Hume's largest EPUB section fell from about 217 ms to 7 ms; the roughly 72 ms reader preparation now runs in the background. This is not a phone benchmark.

## Em-dash word boundaries (23 October 2026)

Pipeline 3 separates unspaced em-dash words while keeping original text and UTF-16 anchors. Valid pipeline 2 EPUBs upgrade their saved indexes automatically and atomically on library load; backup imports also upgrade. Neither action re-extracts or replaces the original EPUB. The same index-only upgrade covers pipeline 1 PDFs. Passage typography, semantic headings, trims and chapter destinations use the same source positions.

A Gutenberg Moby-Dick imported by 0.1.22 was reopened by both native implementations: all 976 tokens containing letter–em-dash–letter sequences were split. Native output agrees on every word index, heading span, chapter anchor and saved position; text and original bytes are unchanged, and a second reopen leaves the saved index untouched.

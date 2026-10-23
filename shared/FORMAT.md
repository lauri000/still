# Still portable book format

Swift and Kotlin implement the reader independently. They share this contract and the fixtures in `fixtures/`; there is no Rust or other shared executable core.

A library book has a UUID directory containing `book.json` and an immutable original: `original.pdf` or `original.epub`. PDF backups use the existing JSON envelope: `{"format":"still-book","version":1,"book":{...},"pdfData":"<base64>"}`. Transfer the whole backup, rather than extracting the PDF again on the receiving device.

The book object preserves these keys:

| Key | Representation |
| --- | --- |
| `schemaVersion` | Integer `2` for prepared PDFs, `3` for EPUBs; legacy `1` accepted |
| `id` | UUID string, exported in uppercase |
| `title`, `author`, `originalFilename` | Strings |
| `importedAt` | Floating-point seconds since **2001-01-01 00:00:00 UTC**, matching Foundation's default `Date` encoding |
| `sourceFormat` | `"epub"` or `"pdf"`; missing means PDF |
| `pages` | Ordered reader units: PDF pages or EPUB text sections; the wire key stays compatible |
| `chapters` | Chapter records with zero-based `pageIndex`, nesting `level`, and optional exact `utf16Offset` |
| `position` | Optional `{pageID, utf16Offset, finished}` |
| `readingStart` | Optional position marking a reversible beginning trim |
| `isSample` | Boolean; export even when false |

Both formats now use `pipelineVersion: 3`. PDF books retain schema 2 and backup envelope 1; EPUB books retain schema 3 and backup envelope 2, including explicit line breaks and stable chapter source anchors. Valid PDF pipeline 1 and EPUB pipeline 2 indexes automatically upgrade from stored text during library loading or backup import. This one-time upgrade rebuilds word indexes and heading matches, preserves text, corrections, IDs, review/inclusion choices, chapters and UTF-16 positions, and atomically saves the result. It never re-extracts or replaces the original file. Other legacy, future or invalid output still requires explicit **Reprocess book**. Both platforms continue accepting legacy schema 1.

A prepared page stores its stable `id`, one-based `number`, display `label`, final editable `text`, `isReviewed`, `isIncluded`, `edited`, and `wordIndex`. The compact index is a flat array of UTF-16 `(offset, length, break)` triples: 0 means no forced break, 1 ends a paragraph, and 2 ends a line within an EPUB paragraph. PDF output only uses 0/1; extracted PDF line wraps still reflow. Conversion, a text edit, or the versioned index upgrade analyzes word boundaries. Loading materializes runtime words from these saved ranges. The book stores `headingMatches` as `{title, pageIndex, level, start, end}` records; titles are normalized lookup keys and spans are UTF-16, end-exclusive. An empty list is a valid prepared result.

Raw extraction, repair baselines, geometry, and provenance are pipeline intermediates and are not stored in new output. The original source is retained unchanged. Restoring extracted text reads the source on demand. Legacy `extractedText`, `processedText`, and `textProcessingVersion` are decoded only to preserve existing corrections during reprocessing.

**Reprocess book** reruns extraction, repair, word indexing, and heading matching from the retained original, then atomically replaces the saved output. Manual page text is supplied as protected pipeline input, so cross-page repair cannot consume words from a corrected page. Book identity, title/author, inclusion choices, and stable page IDs survive. Review resets only where text changes. Positions are relocated into the resulting text. Cancel/failure leaves the prior book intact; Undo reprocessing restores it during the current session. Imported chapters refresh with the pipeline unless the chapter list was customized; legacy chapter lists are conservatively preserved. `chaptersCustomized` records this user choice separately from derived output.

Bump the affected source's `pipelineVersion` whenever extraction, repair, tokenization, or heading recognition changes. Incompatible extraction/repair output is reprocessed from the original. A known word-index-only upgrade can rebuild derived indexes from unchanged stored text, as pipeline 3 does. Bounds-invalid indexes require reprocessing too. Text edits rebuild affected word indexes and heading matches; title/chapter edits refresh heading recognition. Editor changes are debounced and prepared on a background worker, with ordered saves and snapshot checks to prevent stale results replacing later edits. Opening the reader, exporting, and reprocessing await pending preparations. Trims and page inclusion only select from the existing prepared output. Progress, speed, and display changes do not run the pipeline.

Runtime word/paragraph/heading projections are built off the UI thread when a book is selected/opened and reused across reader sessions. Normal library loading does not eagerly build them for every book; a one-time index upgrade also prepares the new projection. Actual text measurement remains lazy and depends on the viewport/font size.

Positions use **UTF-16 code-unit offsets into the stored page text**. They are not Unicode scalar, grapheme, UTF-8 byte, or global word indices. Emoji and combining characters are covered by the fixtures. A saved offset resolves to the nearest preceding token on that page, clamped to the available text. Edits to an earlier page do not move a later page's position.

`readingStart` is separate from page inclusion and does not delete content. Editing its page relocates the marker using the common UTF-16 prefix/suffix. Restoring the beginning preserves manually excluded pages. Unchecked text can play immediately by default; strict review is a local preference, not a book mutation.

Tokenization splits at whitespace and at em-dash runs between words, retaining each dash run on the preceding word for natural pauses. Leading/trailing punctuation, ordinary hyphenated compounds, apostrophes and en-dash numeric ranges remain intact. Contiguous UTF-16 spans rejoin without an inserted space in passage mode and heading matching; a real source gap remains a space. Only the final split word inherits a paragraph or hard-line break. Paragraph gaps recognize two LF or two CRLF separators. At 60–900 WPM, optional natural pauses use the largest of: sentence punctuation 1.9×, clause punctuation 1.35×, words over 12 grapheme clusters 1.25×, and paragraph endings 2.1×. The selected word receives a full interval when playback resumes. A horizontal drag pauses playback, previews relative word changes, and checkpoints on release or interruption.

Unknown JSON keys can be ignored. Missing optional positions decode as null. Future schema/backup versions are rejected rather than silently interpreted. Validation must reject invalid UUIDs, duplicate page IDs, and chapter destinations outside the book. Do not change the timestamp epoch, text normalization, or offset units without a versioned migration.

## Catalog metadata

Optional book fields `catalogID`, `contentHash` (SHA-256 of the original EPUB), `epubIdentifier`, `languageCodes`, `synopsis`, `sourceURL`, and `coverData` (base64 image, at most 2 MiB) preserve discovery metadata. They do not change the book schema or backup envelope. Missing fields decode on existing libraries. Both platforms deduplicate ordinary EPUB imports by identity/hash while explicit backup imports retain the ability to restore another copy.

## EPUB sections and backups

`sourceFormat: "epub"` requires schema 3 so older apps reject it clearly. Each record under the legacy `pages` key is a nonempty, linear spine XHTML section. Its `sourceHref` is the normalized archive-relative content path. Its optional `headings` list holds explicit XHTML heading spans `{start, end, level}` (UTF-16, end-exclusive, levels 0–5). Those semantic spans feed prepared `headingMatches`; EPUB headings do not depend on TOC labels matching the text. Chapters resolve EPUB 3 navigation or EPUB 2 NCX links into `pageIndex` plus `utf16Offset`, retaining distinct anchors within a section and repeated titles. `number` and `label` enumerate retained sections, not print page numbers.

Imported chapters also retain an optional `sourceAnchor`: the resolved content path and fragment, or a heading ordinal for heading-derived navigation. Restoring an EPUB section replaces text, word indexes and heading spans together, and clears its edited flag. It restores imported chapter destinations using these source anchors while preserving manual entries and removals in a customized chapter list. Only explicit chapter editing sets `chaptersCustomized`; automatic offset relocation during text correction does not.

EPUB backups use `{"format":"still-book","version":2,"book":{...},"sourceData":"<base64 original EPUB>"}`. Version 2 requires an EPUB book; PDF backups retain version 1 and `pdfData`. Normal reopening uses prepared data. Backup import validates the original archive but does not extract or analyze its XHTML again. Book IDs, source paths, exact positions, trims, corrections and headings travel with prepared data. The original is retained for explicit reprocessing. Reprocessing currently requires the same ordered section paths; if a future converter changes section boundaries, it fails without replacing the saved book and offers a separate import.

[Supported EPUB content, fixtures, and native compatibility checks](../docs/epub.md).

## Verify both implementations

From the repository root:

```sh
xcrun swiftc apps/apple/shared/Core/{Book,ReaderPipeline,ReadingStructure,PassageLayout}.swift apps/apple/shared/Services/SampleBook.swift \
  scripts/contract-fixtures.swift -o /tmp/still-contract-fixtures
/tmp/still-contract-fixtures

xcrun swiftc apps/apple/shared/Core/{Book,ReaderPipeline,ReadingStructure,PassageLayout,PDFTextRepair}.swift scripts/pdf-repair-contracts.swift -o /tmp/still-pdf-repair-contracts
/tmp/still-pdf-repair-contracts

cd apps/android
./gradlew :app:testDebugUnitTest
cd ../..
/tmp/still-contract-fixtures \
  --backup="$PWD/apps/android/app/build/contract-output/android-backup.json"
```

The Swift checker uses the production model and tokenizer. Android's tests decode a Swift-generated backup and export it again for the Swift checker. Device tests also run the tokenizer fixtures against Android's regex implementation, which differs from a desktop JVM's implementation.

Only run the checker with `--write` when intentionally regenerating the fixtures. The sample PDF contains original sample prose and embedded bookmarks; it contains no user-uploaded book.

Prepared-output compatibility is checked separately:

```sh
xcrun swiftc apps/apple/shared/Core/{Book,ReaderPipeline,ReadingStructure,PassageLayout}.swift scripts/reader-contracts.swift -o /tmp/still-reader-contracts
/tmp/still-reader-contracts
# After Android unit tests:
/tmp/still-reader-contracts --book=apps/android/app/build/contract-output/android-prepared.json
```

## Prepared-reader verification (19 October 2026)

Verified with 46 Android JVM tests, the Android device suite plus targeted reprocess/undo/cancel checks, 72 Apple unit/layout tests, all 12 iPhone UI scenarios, and a macOS build. Android lint reports no errors (21 existing warnings). The legacy tokenizer/backup and repair contracts still pass, and the new prepared-book fixture round-trips Swift → Kotlin → Swift.

The supplied Hume PDF's Android output preserves all 124,009 word records exactly. In the same debug emulator, Continue-to-idle fell from 988–1,583 ms to 116–184 ms over three runs. Prepared JSON is 1,898,669 bytes; intermediate text copies previously occupied a 2,380,513-byte book JSON. Loading and materializing the new output happen off the UI thread. The native Apple importer was also checked on the same PDF: saved/reloaded words and heading cues match its original native output exactly. These measurements are not physical Pixel timings. Private PDFs and extracted text are not included in fixtures or the repository.

# Still book format, version 1

Swift and Kotlin implement the reader independently. They share this contract and the fixtures in `fixtures/`; there is no Rust or other shared executable core.

A library book has a UUID directory containing `original.pdf` and `book.json`. The original PDF is immutable. Portable backups use a JSON envelope: `{"format":"still-book","version":1,"book":{...},"pdfData":"<base64>"}`. Transfer the whole backup, rather than extracting the PDF again on the receiving device.

The book object preserves these keys:

| Key | Representation |
| --- | --- |
| `schemaVersion` | Integer `2` for prepared books; legacy `1` accepted |
| `id` | UUID string, exported in uppercase |
| `title`, `author`, `originalFilename` | Strings |
| `importedAt` | Floating-point seconds since **2001-01-01 00:00:00 UTC**, matching Foundation's default `Date` encoding |
| `pages` | Ordered page records; never flatten away page boundaries |
| `chapters` | Chapter records with zero-based `pageIndex` and nesting `level` |
| `position` | Optional `{pageID, utf16Offset, finished}` |
| `readingStart` | Optional position marking a reversible beginning trim |
| `isSample` | Boolean; export even when false |

New conversions use book `schemaVersion: 2` and `pipelineVersion: 1`. The backup envelope remains version 1. Both platforms accept legacy schema 1, but reading legacy/mismatched pipeline output requires the explicit **Reprocess book** action. Nothing is converted during library loading or Continue reading. Older apps reject schema 2 instead of misinterpreting its missing source fields.

A prepared page stores its stable `id`, one-based `number`, display `label`, final editable `text`, `isReviewed`, `isIncluded`, `edited`, and `wordIndex`. The compact index is a flat array of UTF-16 `(offset, length, endsParagraph)` triples; the paragraph flag is 0 or 1. Only conversion or a text edit analyzes word boundaries. Loading materializes runtime words from these saved ranges. The book stores `headingMatches` as `{title, pageIndex, level, start, end}` records; titles are normalized lookup keys and spans are UTF-16, end-exclusive. An empty list is a valid prepared result.

Raw extraction, repair baselines, geometry, and provenance are pipeline intermediates and are not stored in new output. The original PDF is retained unchanged. Restoring extracted text reads that PDF page on demand. Legacy `extractedText`, `processedText`, and `textProcessingVersion` are decoded only to preserve existing corrections during reprocessing.

**Reprocess book** reruns extraction, repair, word indexing, and heading matching from the retained PDF, then atomically replaces the saved output. Manual page text is supplied as protected pipeline input, so cross-page repair cannot consume words from a corrected page. Book identity, title/author, inclusion choices, and stable page IDs survive. Review resets only where text changes. Positions are relocated into the resulting text. Cancel/failure leaves the prior book intact; Undo reprocessing restores it during the current session. Imported chapters refresh with the pipeline unless the chapter list was customized; legacy chapter lists are conservatively preserved. `chaptersCustomized` records this user choice separately from derived output.

Bump `pipelineVersion` whenever extraction, repair, tokenization, or heading recognition changes. Incompatible output is reprocessed from the PDF rather than migrated or automatically reinterpreted. Bounds-invalid indexes require reprocessing too. Text edits rebuild affected word indexes and heading matches; title/chapter edits refresh heading recognition. Trims and page inclusion only select from the existing prepared output. Progress, speed, and display changes do not run the pipeline.

Runtime word/paragraph/heading projections are built off the UI thread when a book is selected/opened and reused across reader sessions. They are not eagerly built for every book in the library. Actual text measurement remains lazy and depends on the viewport/font size.

Positions use **UTF-16 code-unit offsets into the stored page text**. They are not Unicode scalar, grapheme, UTF-8 byte, or global word indices. Emoji and combining characters are covered by the fixtures. A saved offset resolves to the nearest preceding token on that page, clamped to the available text. Edits to an earlier page do not move a later page's position.

`readingStart` is separate from page inclusion and does not delete content. Editing its page relocates the marker using the common UTF-16 prefix/suffix. Restoring the beginning preserves manually excluded pages. Unchecked text can play immediately by default; strict review is a local preference, not a book mutation.

Tokenization preserves whitespace-delimited tokens and attached punctuation. Paragraph gaps recognize two LF or two CRLF separators. At 60–900 WPM, optional natural pauses use the largest of: sentence punctuation 1.9×, clause punctuation 1.35×, words over 12 grapheme clusters 1.25×, and paragraph endings 2.1×. The selected word receives a full interval when playback resumes. A horizontal drag pauses playback, previews relative word changes, and checkpoints on release or interruption.

Unknown JSON keys can be ignored. Missing optional positions decode as null. Future schema/backup versions are rejected rather than silently interpreted. Validation must reject invalid UUIDs, duplicate page IDs, and chapter destinations outside the book. Do not change the timestamp epoch, text normalization, or offset units without a versioned migration.

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

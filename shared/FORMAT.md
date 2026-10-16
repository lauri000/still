# Still book format, version 1

Swift and Kotlin implement the reader independently. They share this contract and the fixtures in `fixtures/`; there is no Rust or other shared executable core.

A library book has a UUID directory containing `original.pdf` and `book.json`. The original PDF is immutable. Portable backups use a JSON envelope: `{"format":"still-book","version":1,"book":{...},"pdfData":"<base64>"}`. Transfer the whole backup, rather than extracting the PDF again on the receiving device.

The book object preserves these keys:

| Key | Representation |
| --- | --- |
| `schemaVersion` | Integer `1` |
| `id` | UUID string, exported in uppercase |
| `title`, `author`, `originalFilename` | Strings |
| `importedAt` | Floating-point seconds since **2001-01-01 00:00:00 UTC**, matching Foundation's default `Date` encoding |
| `pages` | Ordered page records; never flatten away page boundaries |
| `chapters` | Chapter records with zero-based `pageIndex` and nesting `level` |
| `position` | Optional `{pageID, utf16Offset, finished}` |
| `readingStart` | Optional position marking a reversible beginning trim |
| `isSample` | Boolean; export even when false |

A page has `id`, one-based `number`, display `label`, immutable `extractedText`, editable `text`, `isReviewed`, and `isIncluded`. A chapter has `id`, `title`, `pageIndex`, `level`, and `source`. Importing a duplicate book assigns a new book UUID and import date, preserving page IDs, text, and positions.

Positions use **UTF-16 code-unit offsets into the stored page text**. They are not Unicode scalar, grapheme, UTF-8 byte, or global word indices. Emoji and combining characters are covered by the fixtures. A saved offset resolves to the nearest preceding token on that page, clamped to the available text. Edits to an earlier page do not move a later page's position.

`readingStart` is separate from page inclusion and does not delete content. Editing its page relocates the marker using the common UTF-16 prefix/suffix. Restoring the beginning preserves manually excluded pages. Unchecked text can play immediately by default; strict review is a local preference, not a book mutation.

Tokenization preserves whitespace-delimited tokens and attached punctuation. Paragraph gaps recognize two LF or two CRLF separators. At 60–900 WPM, optional natural pauses use the largest of: sentence punctuation 1.9×, clause punctuation 1.35×, words over 12 grapheme clusters 1.25×, and paragraph endings 2.1×. The selected word receives a full interval when playback resumes. A horizontal drag pauses playback, previews relative word changes, and checkpoints on release or interruption.

Unknown JSON keys can be ignored. Missing optional positions decode as null. Future schema/backup versions are rejected rather than silently interpreted. Validation must reject invalid UUIDs, duplicate page IDs, and chapter destinations outside the book. Do not change the timestamp epoch, text normalization, or offset units without a versioned migration.

## Verify both implementations

From the repository root:

```sh
xcrun swiftc Still/Core/Book.swift Still/Services/SampleBook.swift \
  scripts/contract-fixtures.swift -o /tmp/still-contract-fixtures
/tmp/still-contract-fixtures

cd android
./gradlew :app:testDebugUnitTest
cd ..
/tmp/still-contract-fixtures \
  --backup="$PWD/android/app/build/contract-output/android-backup.json"
```

The Swift checker uses the production model and tokenizer. Android's tests decode a Swift-generated backup and export it again for the Swift checker. Device tests also run the tokenizer fixtures against Android's regex implementation, which differs from a desktop JVM's implementation.

Only run the checker with `--write` when intentionally regenerating the fixtures. The sample PDF contains original sample prose and embedded bookmarks; it contains no user-uploaded book.

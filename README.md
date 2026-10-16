# Still

A native PDF speed reader for **iPad and macOS**, built with SwiftUI and PDFKit. There are no third-party dependencies, accounts, or server components.

## Run

Open `Still.xcodeproj` in Xcode 26 or later.

- **Mac:** select the `StillMac` scheme and **My Mac**, then Run.
- **iPad simulator:** select `Still` and an iPad simulator, then Run.
- **Your iPad:** select `Still`, select your connected iPad, and choose your development team under Signing & Capabilities. Change the bundle identifier if needed. Xcode handles device provisioning. This repository does not include a signing identity.

Minimum deployment targets: iPadOS/iOS 17 and macOS 14. The iOS target also supports iPhone with a stacked proofreading layout.

The generated Xcode project is checked in. To regenerate after modifying target configuration, install XcodeGen and run `xcodegen generate` using `project.yml`.

## Reading

1. Choose **Import PDF**. The imported book opens at its page selector with a PDF preview.
2. Enter a **Start page** or use the arrows, then choose **Read**. Earlier pages are skipped. New libraries start empty; no demo book is added automatically.
3. Press **Play** and set the speed. The current word stays centered with nearby words on either side. Swipe left/right to move forward/back; on Mac, click and drag. A gesture pauses playback. Release on a word and press Play to resume, or tap/click a nearby word directly.

Open a book again and choose **Continue reading** to keep your exact word position. Selecting another starting page explicitly starts from that page instead. Space plays/pauses, the arrow keys step one word, and Escape closes the reader. The text icon opens a larger nearby passage; the ellipsis opens reading options.

Optional tools live in the book’s **…** menu: **Edit text**, **Chapters**, **Choose starting word…**, book metadata, backups, and deletion. Choose starting word supports a start midway through a page and **Restore beginning**. The PDF and full page text are always retained. Review and approval are optional; **Pause at unchecked pages** in reading options enables the stricter workflow. By default, unchecked text plays immediately and pages with no selectable text are skipped.

## Storage and moving between devices

Each book is stored in the app's Application Support directory as `original.pdf` and `book.json` in its own UUID directory. On a sandboxed Mac this is inside the app container. The source PDF is never edited.

The JSON retains the original filename, title/author, import date, page labels and boundaries, the original extracted text, corrected text, inclusion/review flags, bookmark hierarchy, manual chapter boundaries, a reading position, and an optional reading-start boundary. Old libraries and backups without that boundary continue to open. A position contains a page ID and UTF-16 text offset, so changing an earlier page does not shift the saved location on a later page. Within an edited page, a position resolves to the nearest preceding token and is clamped to available text. Paragraph separators remain in page text. Trimming stores a separate page ID and text offset; it never deletes page text or chapters. The start marker moves with edits before its chosen word, and replay/seek stay within the retained text.

Edits are debounced by 500 ms and flushed when navigating, starting the reader, or backgrounding the app. Writes are atomic. Playback checkpoints every 20 words and on pause, close, or backgrounding. Swipe selection previews words without writing each intermediate position; the final source position is saved on release or interruption. Swiping respects beginning trims and excluded pages. Save failures are shown and are not reported as successful.

Choose **Export book backup…** in the book menu to save a portable JSON backup containing the original PDF, corrections, chapters, and reading progress. Import that JSON file on the other device to continue. Importing a duplicate creates a separate library copy. **Automatic iCloud sync is not included.**

## Extraction limits

- Designed for PDFs with selectable text. Scanned/image-only pages are flagged; OCR is not yet implemented. You can enter text manually or exclude them.
- PDFKit's extraction order is retained without silent rewriting. Multi-column layouts, footnotes, tables, ligatures, and line-break hyphenation require human review. The source PDF remains visible for comparison.
- Chapters come from embedded bookmarks or manual entry. Headings are not guessed automatically. Bookmark destinations are mapped to page boundaries, not exact words.
- Tokenization separates whitespace-delimited words and preserves attached punctuation and Unicode source ranges. Languages without word-separating spaces need a future language-aware tokenizer.
- Password-protected and copy-restricted PDFs report an explanatory error. Imports are limited to 250 MB.
- There are no claims that faster playback improves comprehension. The pace is user controlled.

## Verify

```sh
xcodebuild -project Still.xcodeproj -scheme StillMac \
  -derivedDataPath DerivedData/Mac CODE_SIGNING_ALLOWED=NO build

xcodebuild -project Still.xcodeproj -scheme Still \
  -destination 'platform=iOS Simulator,name=Still iPad' \
  -derivedDataPath DerivedData/iOS CODE_SIGNING_ALLOWED=NO test
```

`StillTests` covers extraction from real generated PDFs, bookmarks, Unicode offsets, exclusions, optional review gates, immediate unchecked reading, reversible beginning trims, Unicode start offsets, old-library compatibility, corrections, atomic persistence, portable backups, cancellation, playback timing, and scrub pause/resume, limits, interruption, and checkpoints. `StillUITests` covers the empty library and import picker, choosing a starting page, exact resume after relaunch, surrounding words, swipe navigation, tap selection, optional review/editing, and trimming/restoring the beginning. UI tests use an isolated temporary library.

## Source layout

- `Still/Core`: book/page/chapter model, source-preserving tokenizer, playback engine.
- `Still/Services`: PDF import, generated sample PDF, local persistence, backup and library state.
- `Still/Views`: plain book list, starting-page preview, reader, and optional editing tools; platform-specific PDFView wrappers.
- `project.yml`: iOS/macOS targets and test schemes.

Next features: optional on-device OCR, cross-device sync, and finer chapter destinations.

## Current verification

Verified on 16 October 2026 with Xcode 26.3:

- macOS target builds successfully.
- 34 unit/integration tests pass on the iPad Pro 13-inch simulator (iOS 26.3).
- All five iPad UI tests pass, covering the simplified page-selection flow, an empty library, corrections and optional review, restoring the beginning, swiping in both directions, and exact resume after relaunch. Rotation checks wait for layout to settle.
- Portrait and landscape iPad screenshots were inspected. Saved previews are in `docs/screenshots/`.
- macOS UI interaction was not manually verified because desktop Computer Use permission was unavailable. A physical iPad and developer signing were not exercised.

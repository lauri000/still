# Reading with Still


1. Choose **Import PDF**. The imported book opens at its page selector with a PDF preview.
2. Enter a **Start page** or use the arrows, then choose **Read**. Earlier pages are skipped. New libraries start empty; no demo book is added automatically.
3. On iPhone, iPad, and Android, **hold the bottom-right corner** to read and lift your finger to pause. While holding, move right to increase the current speed or left to decrease it. Each hold starts at your saved speed, wherever you touch the corner; vertical movement does not change it. The range is 60–900 WPM. On Mac, use **Play** and the speed slider.

The current word stays centered with nearby words on either side. Swipe the **text** left/right to select later/earlier words, or tap a nearby word directly. This is separate from the corner's speed gesture: text navigation pauses and never changes speed. Hold the corner again to resume at the selected word. Speed changes are remembered across books and launches. Screen-reader users can activate the corner to start/pause and use its speed adjustment actions.

Open a book again and choose **Continue reading** to keep your exact word position. Selecting another starting page explicitly starts from that page instead. On Mac, Space plays/pauses, the arrow keys step one word, and Escape closes the reader. The text icon opens a larger nearby passage; the ellipsis opens reading options.

**Focus letter** in reading options highlights one letter of the current word in red and holds that letter at the center as words advance. It is off by default and remembered on this device across books and app launches. Turn it off to center the whole word again. The cue uses a simple word-length rule, ignores punctuation when choosing a letter, and preserves accents and complete characters. Words without letters or numbers stay centered without a highlight. This is an optional visual aid, not a promise of faster reading or better comprehension.

Optional tools live in the book’s **…** menu: **Edit text**, **Chapters**, **Choose starting word…**, book metadata, backups, and deletion. Choose starting word supports a start midway through a page and **Restore beginning**. The PDF and full page text are always retained. Review and approval are optional; **Pause at unchecked pages** in reading options enables the stricter workflow. By default, unchecked text plays immediately and pages with no selectable text are skipped.

## Storage and moving between devices

Each book is stored as `original.pdf` and `book.json` in its own UUID directory. Apple uses the app's Application Support directory (inside the container on a sandboxed Mac); Android uses app-private `files/Still/`. The source PDF is never edited.

The JSON retains the original filename, title/author, import date, page labels and boundaries, the original extracted text, corrected text, inclusion/review flags, bookmark hierarchy, manual chapter boundaries, a reading position, and an optional reading-start boundary. Old libraries and backups without that boundary continue to open. A position contains a page ID and UTF-16 text offset, so changing an earlier page does not shift the saved location on a later page. Within an edited page, a position resolves to the nearest preceding token and is clamped to available text. Paragraph separators remain in page text. Trimming stores a separate page ID and text offset; it never deletes page text or chapters. The start marker moves with edits before its chosen word, and replay/seek stay within the retained text.

Edits are debounced by 500 ms and flushed when navigating, starting the reader, or backgrounding the app. Writes are atomic. Playback checkpoints every 20 words and on pause, close, or backgrounding. Swipe selection previews words without writing each intermediate position; the final source position is saved on release or interruption. Swiping respects beginning trims and excluded pages. Save failures are shown and are not reported as successful.

Choose **Export book backup…** in the book menu to save a portable JSON backup containing the original PDF, corrections, chapters, and reading progress. Import that JSON file on the other device to continue. Importing a duplicate creates a separate library copy. **Automatic iCloud sync is not included.**

## Extraction limits

- Designed for PDFs with selectable text. Scanned/image-only pages are flagged; OCR is not yet implemented. You can enter text manually or exclude them.
- The native PDF engine's extraction order is retained without silent rewriting. Multi-column layouts, footnotes, tables, ligatures, and line-break hyphenation can require corrections. The source PDF remains visible for comparison.
- Chapters come from embedded bookmarks or manual entry. Headings are not guessed automatically. Bookmark destinations are mapped to page boundaries, not exact words.
- Tokenization separates whitespace-delimited words and preserves attached punctuation and Unicode source ranges. Languages without word-separating spaces need a future language-aware tokenizer.
- Password-protected PDFs report an explanatory error. Apple also checks copy restrictions; the Android binding does not expose that permission flag. Imports are limited to 250 MB. See [Android's extraction limits](../apps/android/README.md#extraction-limits) for page-label and backup-memory details.
- There are no claims that faster playback improves comprehension. The pace is user controlled.

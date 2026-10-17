# Reading with Still


1. Choose **Import PDF**. The imported book opens at its page selector with a PDF preview.
2. Enter a **Start page** or use the arrows, then choose **Read**. Earlier pages are skipped. New libraries start empty; no demo book is added automatically.
3. On iPhone, iPad, and Android, **hold the bottom-right corner** to read and lift your finger to pause. While holding, move right to increase the current speed or left to decrease it. Each hold starts at your saved speed, wherever you touch the corner; vertical movement does not change it. The range is 60–900 WPM. On Mac, use the header’s **Play**, previous/next, and slower/faster buttons.

The current word stays centered with nearby words on either side. Text fills the space below a compact header containing page navigation and progress. On mobile, the invisible bottom-right quarter of the reading area is the hold/speed zone. Start a swipe anywhere else to select later/earlier words. The starting zone determines the entire gesture, even if your finger crosses its boundary; extra fingers cannot take over. On Mac, drag anywhere in the text to scrub and use the header buttons to change speed in 10 WPM steps.

WPM appears only while changing speed and briefly afterward. Tapping words does not move the reading position. Text navigation pauses and never changes speed. Hold the corner again on mobile, or press Play on Mac, to resume at the selected word. Speed changes are remembered across books and launches. Screen-reader users retain start/pause, speed adjustment, and word navigation actions.

Open a book again and choose **Continue reading** to keep your exact word position. Selecting another starting page explicitly starts from that page instead. On Mac, Space plays/pauses, the arrow keys step one word, and Escape closes the reader. The text icon switches between a single line and a passage of surrounding rows. Both use the same fixed center for the active word (or focus letter); earlier rows sit above it and later rows below, moving as you read. Passage mode uses a darker active word, muted surrounding words, and evenly spaced rows within a consistent column. Full passage rows are justified: spaces stretch to align their edges while the active word stays fixed. Short paragraph endings keep normal spacing. Only one view is shown at a time, and switching pauses without changing your word or speed. Swipe horizontally in either view; the ellipsis opens reading options.

**Active word size** in reading options controls the current word in both views with a percentage slider from **100% to 300%**, in 1% increments. At the default 100%, it matches the surrounding text; 200% doubles its size and 300% triples it. The setting is saved on this device across books and launches. Surrounding text stays the same size, and long words still shrink to fit the available width. Changing size pauses reading without moving your position.

**Animated scrolling** in reading options moves the surrounding words between positions during playback in both views, while the active word or focus letter stays centered. It is off by default and remembered on this device across books and launches. Motion eases to a stop in at most 180 ms and shortens at faster reading speeds. Pausing, navigating through text, switching views, and resizing settle immediately at the selected word; resuming gives that word its normal full reading interval. Apple’s **Reduce Motion** and Android’s disabled system animations override scrolling without changing your saved preference.

**Focus letter** in reading options highlights one letter of the current word in red and holds that letter at the center as words advance. It is off by default and remembered on this device across books and app launches. Turn it off to center the whole word again. The cue uses a simple word-length rule, ignores punctuation when choosing a letter, and preserves accents and complete characters. Words without letters or numbers stay centered without a highlight. This is an optional visual aid, not a promise of faster reading or better comprehension.

Optional tools live in the book’s **…** menu: **Edit text**, **Chapters**, **Choose starting word…**, book metadata, backups, and deletion. Choose starting word supports a start midway through a page and **Restore beginning**. The PDF and full page text are always retained. Review and approval are optional; **Pause at unchecked pages** in reading options enables the stricter workflow. By default, unchecked text plays immediately and pages with no selectable text are skipped.

## Storage and moving between devices

Each book is stored as `original.pdf` and `book.json` in its own UUID directory. Apple uses the app's Application Support directory (inside the container on a sandboxed Mac); Android uses app-private `files/Still/`. The source PDF is never edited.

The JSON retains the original filename, title/author, import date, page labels and boundaries, the original extracted text, corrected text, inclusion/review flags, bookmark hierarchy, manual chapter boundaries, a reading position, and an optional reading-start boundary. Old libraries and backups without that boundary continue to open. A position contains a page ID and UTF-16 text offset, so changing an earlier page does not shift the saved location on a later page. Within an edited page, a position resolves to the nearest preceding token and is clamped to available text. Paragraph separators remain in page text. Trimming stores a separate page ID and text offset; it never deletes page text or chapters. The start marker moves with edits before its chosen word, and replay/seek stay within the retained text.

Edits are debounced by 500 ms and flushed when navigating, starting the reader, or backgrounding the app. Writes are atomic. Playback checkpoints every 20 words and on pause, close, or backgrounding. Swipe selection previews words without writing each intermediate position; the final source position is saved on release or interruption. Swiping respects beginning trims and excluded pages. Save failures are shown and are not reported as successful.

Choose **Export book backup…** in the book menu to save a portable JSON backup containing the original PDF, corrections, chapters, and reading progress. Import that JSON file on the other device to continue. Importing a duplicate creates a separate library copy. **Automatic iCloud sync is not included.**

## Extraction limits

New PDFs receive automatic text cleanup before reading: broken ligatures, discretionary hyphens, wrapped words, and spacing errors supported by the PDF's glyph positions. A small bundled English vocabulary helps retain genuine compounds. Processing happens locally during import; playback does not run a parser or model.

For a previously imported book, choose **… → Improve text**. Your manual corrections and excluded pages are preserved, and the reading position and beginning trim move with their words. **Undo text improvement** is available during the current session until you change or read that book again. **Edit text → Restore extracted text** restores a page's original extraction. Backups preserve both the original and processed text.

- Designed for PDFs with selectable text. Scanned/image-only pages are flagged; OCR is not yet implemented. You can enter text manually or exclude them.
- Cleanup preserves paragraph separators and retains the native engine's reading order. Repeated page numbers/running headings are removed only when recognized at a physical margin. Multi-column layouts, footnotes, tables, unusual fonts, and ambiguous hyphens can still need corrections. The source PDF remains visible for comparison. See [the repair evaluation](pdf-repair.md).
- Chapters come from embedded bookmarks or manual entry. Headings are not guessed automatically. Bookmark destinations are mapped to page boundaries, not exact words.
- Tokenization separates whitespace-delimited words and preserves attached punctuation and Unicode source ranges. Languages without word-separating spaces need a future language-aware tokenizer.
- Password-protected PDFs report an explanatory error. Apple also checks copy restrictions; the Android binding does not expose that permission flag. Imports are limited to 250 MB. See [Android's extraction limits](../apps/android/README.md#extraction-limits) for page-label and backup-memory details.
- There are no claims that faster playback improves comprehension. The pace is user controlled.

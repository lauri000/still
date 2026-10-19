# Reading with Still


1. Choose **Import PDF**. The imported book opens at its page selector with a PDF preview.
2. Enter a **Start page**, use the arrows, or choose a section from **Contents**, then choose **Read**. Earlier pages are skipped. New libraries start empty; no demo book is added automatically.
3. In single-line view on iPhone, iPad, and Android, **hold the bottom-right corner** to read and lift your finger to pause. While holding, move right to increase the current speed or left to decrease it. Each hold starts at your saved speed, wherever you touch the corner; vertical movement does not change it. The range is 60–900 WPM. On Mac, use the header’s **Play**, previous/next, and slower/faster buttons.

**Contents** uses the PDF’s embedded outline, with expandable groups and the current section highlighted. It appears beside the preview on wide screens and opens in a sheet on phones. Choosing a section previews its page; **Read** confirms that page as the beginning. Opening or closing Contents alone preserves **Continue reading** and the exact saved word. Entries without a local page remain visible but cannot navigate. PDFs without an outline still use the page controls; Still does not infer headings from page text.

In single-line view, the current word stays centered with nearby words on either side. Text fills the space below a compact header containing page navigation and progress. On mobile, the invisible bottom-right quarter of the reading area is the hold/speed zone. Start a swipe anywhere else to select later/earlier words. The starting zone determines the entire gesture, even if your finger crosses its boundary; extra fingers cannot take over. On Mac, drag anywhere in the text to scrub and use the header buttons to change speed in 10 WPM steps.

In single-line view, WPM appears only while changing speed and briefly afterward. Tapping words does not move the reading position. Text navigation pauses and never changes speed. Hold the corner again on mobile, or press Play on Mac, to resume at the selected word. Speed changes are remembered across books and launches. Screen-reader users retain start/pause, speed adjustment, and word navigation actions.

Open a book again and choose **Continue reading** to keep your exact word position. Selecting another starting page explicitly starts from that page instead. On Mac, Space plays/pauses and the arrow keys step one word in single-line mode; Escape closes either view.

The text icon switches between single-line reading and a manually scrollable passage. Passage view shows plain, left-aligned rows with natural word spacing and no highlighted word or letter. Scroll normally with a finger, mouse, or trackpad. Tap or click any word to open single-line mode paused at that exact word, ready to continue reading. The mode toggle also preserves the current word; after browsing, it uses the first word of the top visible row. Returning to passage view brings the selected word into view. Paragraph and page boundaries start new rows, and matched headings use larger text with space around them. Paragraph blocks are laid out as they appear, without measuring the whole book. Hold-to-read, WPM controls, horizontal scrubbing, and the options menu belong to single-line mode. Passage view runs no playback timer.

**Active word size** in reading options controls the current word in single-line view with a percentage slider from **100% to 300%**, in 1% increments. At the default 100%, it matches the surrounding text; 200% doubles its size and 300% triples it. The setting is saved on this device across books and launches. Surrounding text stays the same size in single-line view. Passage view uses its own regular text size and is unaffected by this setting. Long words still shrink to fit the available width. Changing size pauses reading without moving your position.

**Animated scrolling** in reading options moves surrounding words during single-line playback while the active word or focus letter stays centered. It is off by default and remembered on this device across books and launches. Motion eases to a stop in at most 180 ms and shortens at faster reading speeds. Pausing, navigating through text, switching views, and resizing settle immediately at the selected word; resuming single-line playback gives that word its full reading interval.

**Focus letter** in reading options highlights one letter of the current word in red. Single-line view holds that letter at the center as words advance. Passage view has no focus-letter treatment. It is off by default and remembered on this device across books and app launches. In single-line view, turn it off to center the whole word again. The cue uses a simple word-length rule, ignores punctuation when choosing a letter, and preserves accents and complete characters. Words without letters or numbers stay centered without a highlight. This is an optional visual aid, not a promise of faster reading or better comprehension.

Optional tools live in the book’s **…** menu: **Edit text**, **Chapters**, **Choose starting word…**, book metadata, backups, and deletion. Choose starting word supports a start midway through a page and **Restore beginning**. The PDF and full page text are always retained. Review and approval are optional; **Pause at unchecked pages** in single-line options enables the stricter workflow for that mode. Passage view reads unchecked text and skips empty pages regardless of that preference. By default, unchecked text plays immediately and pages with no selectable text are skipped.

## Book typography and contents

Tap the chapter/page area in the reader header to open **Contents**. The original
PDF outline and additional saved chapters are available here, with **Go to page**
for direct page navigation. Selecting an entry pauses reading and seeks to its
exact heading when mapped, or to its destination page otherwise. It does not
change the beginning trim. Entries outside the retained reading selection are
visible but disabled. Closing Contents alone keeps the exact word.

Still matches saved chapter labels to unique, complete lines or wrapped heading
blocks on their destination pages. Matching ignores case and whitespace, preserves
UTF-16 source positions, and never rewrites the text. Duplicate matches, labels
found only within prose, partially trimmed headings, and missing titles get no
exact cue. A unique document-title match near the beginning is styled as a title.
This is not general heading detection: PDFs without usable chapter metadata keep
ordinary text and page navigation.

- **Multiline:** a matched heading stays in the scrolling column, slightly larger,
  with space before and after it. Every source word remains in order; there is no
  active-word highlight. Headings are laid out with their visible paragraph block.
- **Single-line:** the complete heading appears together, once, before body words
  resume. Its display lasts at least 1.2 seconds, or its word count at the chosen
  WPM if longer. Saved positions still identify the current source word within
  that heading. Nearby body text does not repeat heading words.
- **Major section ending (single-line only):** when both boundaries are mapped and the intervening
  pages are retained and readable, the final word lasts at least 0.8 seconds.
  Existing longer pauses take precedence; delays are not added together. Nested subsections do
  not add pauses unless explicitly labelled as chapters. The final book ending
  retains the normal finished state.
- Releasing, navigating, backgrounding, or closing cancels continuation immediately.
  Resuming a heading or chapter pause continues its remaining interval within the
  reading session. Page changes only update the header.

The structural index is derived once when creating a reading session, outside the
playback loop. Mode switches reuse it. Edits, text improvements, and trims cause
fresh matching the next time the reader opens, so old text offsets cannot create
stale cues. No book/backup schema or stored single-line preferences change.

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

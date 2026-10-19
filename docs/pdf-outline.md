# PDF contents navigation

The starting-page chooser shows the PDF's embedded outline on Android, iPhone,
iPad, and Mac. Existing imports already save valid bookmark destinations as
chapters, but discard destinationless groups and sort them by page. The new
chooser reads the retained original PDF so old books and backups also recover
the original outline order and hierarchy. No headings are inferred from text.

- A Contents control opens a collapsible left panel at widths of at least 760
  points/dp; smaller windows use a sheet. Wide layouts initially show a nonempty
  outline. The PDF preview and page-number controls remain available.
- Outline groups can expand/collapse independently of their own destination.
  Labels wrap; blank labels become “Untitled section”. Page labels match the
  book. Source order and duplicate destinations are retained.
- Clicking a valid local destination selects its PDF page for preview. The
  clicked row stays selected when several entries share a page. Manual page
  navigation highlights the closest preceding destination, preferring deeper
  entries on the same page. Ancestors reveal the current row automatically.
- Groups without a destination remain expandable. Invalid, external, and remote
  destinations cannot navigate and display “No local page”. Nothing launches an
  external URL. Empty outlines show “No PDF contents” with page-navigation help.
  Read errors are distinct from an empty outline and never block page selection.
- Browsing never writes progress, changes a trim, or starts playback. Read keeps
  its existing explicit-start-page behavior; Continue reading preserves the exact
  saved position when no page was chosen. Navigation is page-level, not a claim
  of precise heading-to-word alignment. The original PDF, portable schema,
  editable chapters, reader layouts, gestures, and reading cues stay unchanged.

Verification covers the same real PDF fixture on both engines (nested groups,
named and action destinations, invalid/remote entries, duplicate pages, source
order), current-row/visibility logic, empty outlines, chooser navigation and
resume/trim behavior, and existing reader regressions. Apple and Android builds
use isolated outputs and dedicated simulator/emulator devices.

Android uses PDFBox for outline parsing because PDFiumAndroidKt 2.0.3 reports
remote GoTo destinations as local pages and does not expose their action type.
PDFium still handles extraction and rendering. The outline parser uses temporary
file storage, rejects external actions, guards cycles, and caps traversal at
10,000 entries / 64 levels. The shared fixture exercises this remote-link case.

## Verification — 17 October 2026

- Android: 22 JVM tests pass; 20 phone device tests pass, plus the dedicated
  tablet-sized sidebar test. The phone run skips that width-specific test.
  Debug lint has zero errors (21 dependency/SDK/API-style warnings).
- Apple: 47 unit/layout tests pass. All 11 iPad UI scenarios pass. The 10 existing
  iPhone scenarios pass, and the new Contents/resume scenario passes after fixing
  compact-sheet state observation. Portrait and landscape are covered.
- The Mac target builds without developer signing. An isolated offscreen native
  preview verifies the outline layout; PDFKit's offscreen page tiles are not
  captured by that preview. The installed Mac app was not replaced or controlled.
- The Swift/Kotlin portable backup checker passes all six tokenizer fixtures and
  the Android export. Original PDFs and the backup schema remain unchanged.
- Screenshots are in [pdf-contents](screenshots/pdf-contents/). Physical devices
  and Apple distribution signing were not exercised.

## Earlier branch refresh — 19 October 2026

The original outline branch was rebased on main `8cd2b26`, retaining the PDF text
repair and scrolling passage releases. Its 0.1.9 preparation was not published.

- Android: 32 unit tests, 20 phone scenarios, and the tablet sidebar scenario
  pass. Debug lint reports zero errors and 21 warnings. The phone suite was
  rerun after dismissing a crashed emulator System UI dialog.
- Apple: 54 unit/layout tests and the focused iPhone contents, starting-page,
  and scrolling-passage scenarios pass. The passage test now advances beyond
  the first landscape row before asserting that an earlier row is visible.
  The Mac target builds without developer signing.
- The import regression checks repaired page metadata and local bookmark
  destinations together. Both original PDFs and unavailable outline entries
  retain their existing behavior.
- Six tokenizer contracts, 12 PDF repair contracts, the Android-to-Swift backup
  check, and eight publisher-helper tests pass.


## Integration with plain multiline — 19 October 2026

Ported the contents feature onto main `77e2970` (published 0.1.10) in
`codex/contents-current-reader`. The old branch and its release artifacts remain
untouched. No release versions or publishing metadata change in this integration.
The current plain passage layout, separate single-line options, and bounded
nearby-text layout were preserved at this integration step. Book typography and
chapter/title playback cues were implemented afterward; see [reading](reading.md).

- Android: 33 JVM tests and four focused emulator scenarios pass, covering real
  outline parsing, contents selection/trim/resume, empty contents, and plain
  multiline playback. Debug build and lint pass with zero errors.
- Apple: 56 unit/layout tests and two focused iPhone UI scenarios pass, covering
  contents selection/exact resume and plain multiline playback/preferences.
- The Mac target builds without developer signing. Verification used this chat's
  isolated app, simulator/emulator, and build outputs. Physical devices and the
  wide-screen sidebar were not rerun for this integration.

## Book typography — 19 October 2026

The reader now reuses Contents for chapter navigation and derives exact heading
cues from current text. See [reading](reading.md#book-typography-and-contents) for
matching, timing, and fallback behavior. No release metadata or backup schema
changed.

- Android: 39 JVM tests pass. The phone reader suite has 21 passing scenarios and
  one tablet-width scenario skipped. The final typography, header geometry, and
  private-book performance scenarios also pass. Debug lint has zero errors and
  the same 21 existing warnings.
- Apple: 62 unit/layout tests pass. The 11 existing iPhone scenarios pass. The new
  typography/Contents scenario initially exposed a PDFKit accessibility crash
  behind the presented reader. The hidden preview now remains still and outside
  the reader's accessibility tree; the new scenario and dismissal/resume pass,
  followed by three consecutive passes of the typography/navigation scenario.
- iPhone screenshots were inspected in portrait and landscape, together with
  Android single-line and multiline screenshots. They use the public sample book
  and are saved in [book-typography](screenshots/book-typography/).
- The Mac target builds without developer signing. Physical devices, iPad UI,
  the tablet sidebar, and Apple distribution signing were not rerun for this step.
- The private 1984 PDF (104,789 words) passes six mode-switch combinations on the
  isolated Android emulator, including 125% active-word size with Focus letter
  and Natural pauses enabled. Measured switches take 145–204 ms; combined
  token/session preparation takes 599 ms. No exact heading matches were found
  in this file, so it exercises the plain-text fallback and the prior crash path.
  Matching/layout/timing cases with headings are covered separately by unit and
  native UI tests. The private PDF and its text are not included in the repository.

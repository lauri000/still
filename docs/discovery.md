# Discover and import ebooks

**Discover** sits beside **Import file** in the Books screen on iPhone, iPad, Mac, and Android. File import accepts PDF, EPUB, and Still backups. Discovery opens a native catalog browser with title/author search, language filtering, covers, details, and explicit Previous/Next paging. Download EPUB adds the book to the same library and opens its normal starting-page screen. Books, covers, source files, and progress remain available offline.

Project Gutenberg via [Gutendex](https://gutendex.com/) is the default. Queries request EPUBs and `copyright=false`; entries without a confirmed public-domain flag or EPUB are omitted. Gutendex describes copyright status in the United States; users should check the provider's terms for their location. The detail view links to the canonical Gutenberg book page. Only the selected book is downloaded; the app does not crawl, prefetch subsequent pages, or mirror catalogs.

Catalog settings also accepts an HTTPS OPDS URL, or the Standard Ebooks preset. [Standard Ebooks feed access](https://standardebooks.org/help/how-to-add-an-opds-feed-to-your-ereader) requires authorized access, normally a Patrons Circle email as the username. No credentials are supplied by the app. Username/password fields are memory-only, not written to preferences, backups, or logs. Custom endpoint URLs are remembered after validation. Authentication is HTTP Basic over HTTPS, scoped to the exact origin (including port); cross-origin redirects lose credentials and HTTP downgrades are rejected. A 401/403 asks the user to check access rather than trying another endpoint.

## Architecture

The two native apps implement the same small provider contract independently:

- `CatalogProvider` exposes normalized `CatalogBook`/`CatalogPage` data, search, provider pagination, and an EPUB download. It does not depend on the library, reader, or UI.
- `GutendexProvider` translates API parameters and responses. Its endpoint is injectable for a self-hosted Gutendex deployment; the public instance's documentation recommends self-hosting for long-term use.
- `OPDSProvider` handles [OPDS 2.0](https://specs.opds.io/opds-2.0) publications, navigation/groups, contributor forms, relative links, acquisition relations, search templates, and pagination. Atom acquisition/navigation and OpenSearch descriptions are supported for Standard Ebooks/OPDS 1.x compatibility. A new catalog can use the existing OPDS configuration without changing the screens or importer.
- `CatalogClient` uses the platform HTTP stack, bounded responses, cancellation, timeouts, a small five-minute memory cache, HTTPS validation, and `Retry-After` cooldowns for HTTP 429/503. There are no automatic retry loops. Progress is determinate when the server supplies a length and indeterminate otherwise.
- `CatalogStore` (Apple) / `CatalogViewModel` (Android) own search/navigation state and cancel obsolete requests. Library state owns the download/import, so leaving discovery does not create a detached import or lose the final library update.

Catalog downloads reuse the released `EPUBImporter` / `EpubImporter` and the existing `ImportedBook`/`Book` representation. Both local files and catalog downloads feed the same library, prepared reader data, chapter navigation, text corrections, and backup pipeline. EPUB 3 navigation and EPUB 2 NCX destinations remain intact.

The original EPUB remains `original.epub`, alongside `book.json`; the existing native text-section preview handles EPUBs. New optional book metadata fields preserve catalog identity, a SHA-256 content hash, EPUB identifier, languages, synopsis, source URL, and a base64 cover. Existing libraries decode without migration. Older EPUB library entries receive a content hash when loaded so a catalog download of the same source is deduplicated. EPUB backups retain the existing version 2 `sourceData` envelope and include the optional metadata in `book`.

Catalog identity prevents repeat downloads of known catalog books. Content hash or EPUB identifier prevents duplicate local/downloaded EPUB imports, including the same edition obtained through another catalog. Existing explicit backup-import behavior is retained: a backup can be restored as another copy, including its corrections and reading position.

## Limits

- Text-based, unencrypted EPUB 2/3 XHTML content is supported. This is a speed-reader rendition, not a full EPUB layout engine: publisher CSS, inline illustrations, fixed-layout presentation, audio, and scripts are not rendered. Covers and original EPUB files are retained. EPUB navigation hierarchies and section-relative anchors are preserved by the existing importer.
- Downloads are limited to 80 MiB. The existing EPUB importer limits archives to 10,000 entries, 512 MiB expanded content, 16 MiB per text resource, and 10 million text characters. Catalog XML is limited to 8 MiB and cover data to 2 MiB. Archive paths, CRCs (ZIP readers), malformed packages, external entities, and unsupported/encrypted content are checked before committing a book. No archive entry is extracted to a filesystem path from the archive.
- OPDS language filtering is applied to the current page because OPDS has no mandatory language query parameter. An empty filtered page retains its Next button. Search requires the catalog to advertise a supported query template. OPDS OAuth, borrowing, purchases, DRM/license fulfillment, and indirect acquisition chains are not supported.
- Optional Standard Ebooks access cannot be verified without a member's credentials. The auth errors and Atom/OPDS parsers are covered by fixtures; the app never substitutes credentials or circumvents restrictions.
- Book files are local. There is no background download queue, resumable transfer across app termination, automatic catalog synchronization, or bulk download action. Failed/cancelled imports leave existing books intact and can be retried.

## Verification

`shared/fixtures/catalog-*.json`, `catalog-atom.xml`, and `sample.epub` are deterministic fixtures generated by `scripts/catalog-fixtures.py`; the EPUB contains original test prose. They cover filtering, Unicode, relative/search/pagination links, contributors/groups, Atom compatibility, EPUB spine order, metadata/cover retention, and offline persistence. Network tests exercise HTTP failures, authentication scope, redirects, size limits, and rate-limit cooldowns. Native integration tests exercise download → import → section preview → reading, duplicate prevention, cancellation, and source-preserving backup round trips. Existing PDF, reader, and cross-platform contract tests remain in the suites.

Run the commands in the [Apple](../apps/apple/README.md#verify) and [Android](../apps/android/README.md#verification) guides. Tests use fixtures rather than depending on external catalog availability. `sample.epub` is a small, deterministic fixture containing original test prose; regenerate it with `python3 scripts/catalog-fixtures.py`.

The public Gutendex service intermittently returned 503/timeouts during live verification. The app presents these failures with a retry action and respects any provider cooldown. Standard Ebooks live access requires the user's own authorized credentials.

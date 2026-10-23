"""Deterministic, original-content EPUB and catalog fixtures (no provider downloads)."""
from pathlib import Path
import base64
import json
import zipfile

root = Path(__file__).resolve().parents[1] / "shared" / "fixtures"
(root / "catalog-gutendex.json").write_text(json.dumps({
    "count": 3, "next": "https://gutendex.com/books?page=2", "previous": None,
    "results": [
        {"id": 1, "title": "A quiet test", "authors": [{"name": "Reader, Still"}], "languages": ["en"], "copyright": False,
         "summaries": ["An original test story."], "subjects": ["Testing"],
         "formats": {"application/epub+zip": "https://books.example/test.epub", "image/jpeg": "https://books.example/cover.jpg"}},
        {"id": 2, "title": "Restricted", "copyright": True, "formats": {"application/epub+zip": "https://books.example/restricted.epub"}},
        {"id": 3, "title": "No EPUB", "copyright": False, "formats": {"text/html": "https://books.example/read"}},
    ]}, indent=2) + "\n")
paragraph = "Attention moves one word at a time. Café, naïve, and 世界 stay intact. This is original test prose for Still. "
with zipfile.ZipFile(root / "sample.epub", "w") as z:
    def put(name, text, compress=zipfile.ZIP_DEFLATED):
        info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0)); info.compress_type = compress
        z.writestr(info, text)
    put("mimetype", "application/epub+zip", zipfile.ZIP_STORED)
    put("META-INF/container.xml", '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
    put("EPUB/package.opf", '''<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="3.0" unique-identifier="id"><metadata>
<dc:title>A quiet test</dc:title><dc:creator>Still Reader</dc:creator><dc:identifier id="id">urn:still:epub-fixture</dc:identifier><dc:language>en</dc:language></metadata>
<manifest><item id="second" href="text/second.xhtml" media-type="application/xhtml+xml"/><item id="first" href="text/first.xhtml" media-type="application/xhtml+xml"/>
<item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/></manifest><spine><itemref idref="first"/><itemref idref="second"/></spine></package>''')
    put("EPUB/text/first.xhtml", '<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Hidden title</title><style>Hidden style</style></head><body><h1>A beginning</h1>' + ''.join('<p>' + paragraph * 4 + '</p>' for _ in range(16)) + '<script>Hidden script</script></body></html>')
    put("EPUB/text/second.xhtml", '<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>A conclusion</h1><p>Last words &amp; peaceful thoughts.</p></body></html>')
    put("EPUB/cover.png", base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="))
print("Wrote catalog fixtures and sample.epub")

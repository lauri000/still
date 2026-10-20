#!/usr/bin/env python3
"""Generate small, deterministic EPUB contracts owned by Still (MIT). Real books remain untouched."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED, ZIP_STORED
ROOT = Path(__file__).resolve().parent.parent / 'shared/fixtures/epub'
ROOT.mkdir(parents=True, exist_ok=True)
CONTAINER = '<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'
PACKAGE = '''<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">still-test</dc:identifier><dc:title>A Structured Book</dc:title><dc:creator>Still</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="first" href="first.xhtml" media-type="application/xhtml+xml"/><item id="second" href="caf%C3%A9%20two.xhtml" media-type="application/xhtml+xml"/><item id="extra" href="extra.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="nav"/><itemref idref="first"/><itemref idref="extra" linear="no"/><itemref idref="second"/></spine></package>'''
def html(body): return '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Not body text</title><style>.decorative {color:red}</style></head><body>'+body+'</body></html>'
FILES = {'META-INF/container.xml': CONTAINER, 'OPS/book.opf': PACKAGE,
    'OPS/nav.xhtml': html('<nav epub:type="toc"><ol><li><a href="first.xhtml#one"><span>A different label</span></a><ol><li><a href="first.xhtml#repeat-a">Repeat</a></li><li><a href="first.xhtml#repeat-b">Repeat</a></li></ol></li><li><a href="caf%C3%A9%20two.xhtml#%E7%B5%82">The ending</a></li></ol></nav>'),
    'OPS/first.xhtml': html('<h1 id="one">First heading</h1><p>Café 🌿 some<em>thing</em> &amp; more.</p><p>Another paragraph.<br/>A new line.</p><h2 id="repeat-a">Repeat</h2><p>First repeat body.</p><span epub:type="pagebreak" title="12">PAGE NUMBER</span><h2 id="repeat-b">Repeat</h2><p>Second repeat body.</p><script>NEVER EXECUTE</script><p hidden="hidden">HIDDEN</p>'),
    'OPS/café two.xhtml': html('<h1 id="終">Last heading</h1><p>Finish here.</p>'),
    'OPS/extra.xhtml': html('<p>NONLINEAR APPENDIX</p>')}
def write(name, files):
    with ZipFile(ROOT / name, 'w') as z:
        for path, text in [('mimetype','application/epub+zip'), *reversed(list(files.items()))]:
            info=ZipInfo(path, (2020,1,1,0,0,0)); info.compress_type=ZIP_STORED if path=='mimetype' else ZIP_DEFLATED
            z.writestr(info,text.encode('utf-8'))
write('structured.epub', FILES)
legacy = dict(FILES)
legacy['OPS/book.opf'] = PACKAGE.replace('version="3.0"', 'version="2.0"').replace(' properties="nav"','').replace('<spine>', '<spine toc="ncx">').replace('</manifest>', '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest>')
legacy['OPS/toc.ncx'] = '<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint id="one"><navLabel><text>Legacy chapter</text></navLabel><content src="first.xhtml#one"/></navPoint></navMap></ncx>'
legacy['OPS/first.xhtml'] = '\ufeff<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">' + html('<h1 id="one">Legacy heading</h1><p>Some&nbsp;words &mdash; &copy;.</p>')
write('legacy.epub', legacy)
write('fixed-layout.epub', dict(FILES, **{'OPS/book.opf': PACKAGE.replace('</metadata>', '<meta property="rendition:layout">pre-paginated</meta></metadata>')}))
write('encrypted.epub', dict(FILES, **{'META-INF/encryption.xml':'<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/><CipherData><CipherReference URI="OPS/first.xhtml"/></CipherData></EncryptedData></encryption>'}))
write('unsafe-entity.epub', dict(FILES, **{'OPS/first.xhtml':'<!DOCTYPE html [<!ENTITY x SYSTEM "file:///etc/passwd">]>'+html('<p>&x;</p>')}))
write('missing-spine.epub', dict(FILES, **{'OPS/book.opf':PACKAGE.replace('idref="first"','idref="missing"')}))
write('path-escape.epub', dict(FILES, **{'META-INF/container.xml':CONTAINER.replace('OPS/book.opf','../../book.opf')}))
write('image-only.epub', dict(FILES, **{'OPS/first.xhtml':html('<img src="picture.jpg" alt="Not readable prose"/>'), 'OPS/café two.xhtml':html('<svg xmlns="http://www.w3.org/2000/svg"><text>Image text</text></svg>')}))
print('Generated 8 EPUB contracts in', ROOT)

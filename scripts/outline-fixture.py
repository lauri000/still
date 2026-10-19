#!/usr/bin/env python3
"""Rebuild the small, original PDF fixtures for both outline parsers (no dependencies)."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'shared/fixtures'

def build(outline):
    objects = {}
    def put(n, text): objects[n] = text.encode('ascii')
    put(1, '<< /Type /Catalog /Pages 2 0 R' + (' /Outlines 20 0 R /Names << /Dests << /Names [(named) [5 0 R /Fit]] >> >>' if outline else '') + ' >>')
    put(2, '<< /Type /Pages /Kids [3 0 R 5 0 R 7 0 R 9 0 R] /Count 4 >>')
    for i, title in enumerate(['Beginning', 'Named page', 'Later page', 'Action page']):
        page = 3 + i * 2
        put(page, f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 11 0 R >> >> /Contents {page + 1} 0 R >>')
        content = f'BT /F1 24 Tf 60 700 Td ({title}) Tj 0 -40 Td /F1 16 Tf (One two three four five six seven eight nine ten.) Tj ET'
        put(page + 1, f'<< /Length {len(content)} >>\nstream\n{content}\nendstream')
    put(11, '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
    if outline:
        put(20, '<< /Type /Outlines /First 21 0 R /Last 32 0 R /Count 12 >>')
        put(21, '<< /Title (Part) /Parent 20 0 R /First 22 0 R /Last 25 0 R /Count 4 /Next 26 0 R >>')
        put(22, '<< /Title (Later section) /Parent 21 0 R /Dest [7 0 R /Fit] /First 23 0 R /Last 23 0 R /Count 1 /Next 24 0 R >>')
        put(23, '<< /Title (Deep section) /Parent 22 0 R /Dest [7 0 R /XYZ 0 700 null] >>')
        put(24, '<< /Title (Named section) /Parent 21 0 R /Dest (named) /Prev 22 0 R /Next 25 0 R >>')
        put(25, '<< /Title (GoTo section) /Parent 21 0 R /A << /S /GoTo /D [9 0 R /Fit] >> /Prev 24 0 R >>')
        for n, title, dest in [
            (26, 'Back to beginning', '/Dest [3 0 R /Fit]'),
            (27, 'Same page', '/Dest [3 0 R /Fit]'),
            (28, '   ', '/Dest [7 0 R /Fit]'),
            (29, 'Website', '/A << /S /URI /URI (https://example.com) >>'),
            (30, 'Remote', '/A << /S /GoToR /F (other.pdf) /D [0 /Fit] >>'),
            (31, 'Broken named', '/Dest (missing)'),
            (32, 'Out of range', '/Dest [999 /Fit]'),
        ]:
            prev = 21 if n == 26 else n - 1
            nxt = f'/Next {n + 1} 0 R' if n < 32 else ''
            put(n, f'<< /Title ({title}) /Parent 20 0 R /Prev {prev} 0 R {nxt} {dest} >>')
    data = bytearray(b'%PDF-1.7\n')
    offsets = {}
    for n, obj in sorted(objects.items()):
        offsets[n] = len(data)
        data += f'{n} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(data); count = max(objects) + 1
    data += f'xref\n0 {count}\n0000000000 65535 f \n'.encode()
    for n in range(1, count):
        data += (f'{offsets[n]:010} 00000 n \n' if n in offsets else '0000000000 00000 f \n').encode()
    data += f'trailer\n<< /Size {count} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
    return data

(root / 'outline.pdf').write_bytes(build(True))
(root / 'no-outline.pdf').write_bytes(build(False))

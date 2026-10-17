#!/usr/bin/env python3
"""Compare native evaluation JSON without treating either engine as ground truth."""
import argparse
import difflib
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apple", type=Path)
parser.add_argument("android", type=Path)
parser.add_argument("--details", action="store_true", help="Print differing tokens (private book text)")
args = parser.parse_args()
documents = [json.loads(path.read_text()) for path in (args.apple, args.android)]
for name, document in zip(("Apple", "Android"), documents):
    print(f"{name}: {len(document['pages'])} pages, {document['seconds']:.3f}s")
    for key in ("original", "text"):
        text = "\n".join(page[key] for page in document["pages"])
        markers = text.count("\ufffe")
        wraps = len(re.findall(r"[^\W\d_][-‐][ \t]*\r?\n[ \t]*[^\W\d_]", text))
        fi_spaces = len(re.findall(r"fi (?=[a-z])", text))
        print(f"  {key}: {markers} PDFium markers; {wraps} letter/hyphen/wraps; {fi_spaces} 'fi ' candidates")
if len(documents[0]["pages"]) != len(documents[1]["pages"]):
    raise SystemExit("Page counts differ")
differences = 0
for number, (apple, android) in enumerate(zip(documents[0]["pages"], documents[1]["pages"]), 1):
    a, b = apple["text"].split(), android["text"].split()
    for operation, i, j, k, l in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
        if operation != "equal":
            differences += 1
            if args.details:
                print(f"Page {number}: Apple {a[i:j]!r}; Android {b[k:l]!r}")
print(f"{differences} differing word-stream spans; this is engine agreement, not an accuracy score.")

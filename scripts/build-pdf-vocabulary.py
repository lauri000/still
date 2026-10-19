#!/usr/bin/env python3
"""Generate the small offline PDF-repair vocabulary from SCOWL 2020.12.07.

Download/extract https://downloads.sourceforge.net/wordlist/scowl-2020.12.07.tar.gz,
then pass the extracted scowl-2020.12.07 directory. No book text is used.
"""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("scowl", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1] / "shared" / "resources"
root.mkdir(parents=True, exist_ok=True)
words = set()
sources = []
for path in sorted((args.scowl / "final").iterdir()):
    match = re.fullmatch(r"(english|american|british|british_z)-words\.(\d+)", path.name)
    if not match or int(match[2]) > 60:
        continue
    sources.append(path.name)
    for word in path.read_text(encoding="latin-1").splitlines():
        if re.fullmatch(r"[a-z]+(?:-[a-z]+)*", word):
            words.add(word)
assert len(words) > 20_000, "Unexpected or missing SCOWL input"
(root / "pdf-english-words.txt").write_text("\n".join(sorted(words)) + "\n")
(root / "SCOWL-Copyright.txt").write_bytes((args.scowl / "Copyright").read_bytes())
(root / "PDF-Vocabulary-NOTICE.txt").write_text(
    "Still's PDF repair vocabulary is a modified SCOWL 2020.12.07 word list.\n"
    "Source: https://wordlist.aspell.net/\n"
    "Release: https://downloads.sourceforge.net/wordlist/scowl-2020.12.07.tar.gz\n"
    "Copyright and license: SCOWL-Copyright.txt (distributed alongside this file).\n"
    "Modifications: union of lower-case ASCII words and hyphenated compounds,\n"
    "levels up to 60, English plus US and UK spelling variants; sorted/deduplicated.\n"
    "No proper-name lists or user documents are included.\n"
    "Regenerate with scripts/build-pdf-vocabulary.py.\n\n"
    "Input lists:\n" + "\n".join(sources) + "\n"
)
print(f"{len(words):,} words; {(root / 'pdf-english-words.txt').stat().st_size:,} bytes")

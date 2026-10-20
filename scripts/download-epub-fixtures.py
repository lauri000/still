#!/usr/bin/env python3
"""Fetch optional public EPUB fixtures, verifying the tracked source manifest. Never runs in a build."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parent.parent / "shared/fixtures/epub"

def main():
    for sample in json.loads((ROOT / "manifest.json").read_text())["samples"]:
        target = ROOT / sample["file"]
        expected = sample["sha256"]
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
            print("Verified", target.name)
            continue
        request = urllib.request.Request(sample["download_url"], headers={"User-Agent": "Still EPUB fixture downloader"})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read(25 * 1024 * 1024 + 1)
        if len(data) > 25 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit(f"Checksum mismatch for {target.name}. The source may have changed; review the new edition before updating manifest.json. Existing files were not replaced.")
        temporary = target.with_suffix(".epub.download")
        temporary.write_bytes(data)
        temporary.replace(target)
        print("Downloaded and verified", target.name)

if __name__ == "__main__":
    main()

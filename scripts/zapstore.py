#!/usr/bin/env python3
"""Prepare, sign locally, or publish Still's Zapstore listing without storing an nsec."""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
CONFIG = ROOT / "zapstore.yaml"
VERSION = "0.4.17"
BIN = Path(os.environ.get("ZSP_BIN", ROOT / ".tools/zsp")).expanduser().resolve()
HASHES = {
    ("Darwin", "arm64"): ("darwin-arm64", "7846ff44db5cd9e29a35370ad6503c06875f594eacb1ede60bd5685116f4b2d4"),
    ("Linux", "aarch64"): ("linux-arm64", "1a97b892617e764bb6e436d49dbafd334c1546f95b690b8ba3684048b8bfec6d"),
    ("Linux", "x86_64"): ("linux-amd64", "3f241da6a5dc7a85fe851d3b42b77790b651bd36c7029382a701262bda832d20"),
}


def setup():
    if "ZSP_BIN" in os.environ:
        raise RuntimeError("Unset ZSP_BIN to install the pinned project-local publisher.")
    target = HASHES.get((platform.system(), platform.machine()))
    if not target:
        raise RuntimeError("Install zsp v0.4.17 for this platform and set ZSP_BIN to its executable.")
    suffix, expected = target
    if BIN.exists() and hashlib.sha256(BIN.read_bytes()).hexdigest() == expected:
        print(f"Verified zsp {VERSION}: {BIN}")
        return
    url = f"https://github.com/zapstore/zsp/releases/download/v{VERSION}/zsp-{VERSION}-{suffix}"
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError("Publisher checksum mismatch; executable was not installed.")
    BIN.parent.mkdir(exist_ok=True)
    temporary = BIN.with_suffix(".download")
    temporary.write_bytes(data)
    temporary.chmod(0o755)
    temporary.replace(BIN)
    print(f"Installed and verified zsp {VERSION}: {BIN}")


def release():
    data = json.loads((DIST / "release.json").read_text())
    if Path(data["apk"]).name != data["apk"]:
        raise RuntimeError("Invalid artifact filename.")
    if hashlib.sha256((DIST / data["apk"]).read_bytes()).hexdigest() != data["sha256"]:
        raise RuntimeError("APK checksum differs from release.json. Rebuild before signing.")
    text = CONFIG.read_text()
    publisher = re.search(r"^pubkey:\s*(npub1[0-9a-z]+)\s*$", text, re.MULTILINE)
    source = re.search(r"^release_source:\s*(\S+)\s*$", text, re.MULTILINE)
    if not publisher or not source or (ROOT / source[1]).resolve() != (DIST / data["apk"]).resolve():
        raise RuntimeError("zapstore.yaml must contain your public npub and the current local release APK path.")
    if not BIN.is_file():
        raise RuntimeError("Run: python3 scripts/zapstore.py setup")
    actual = subprocess.run([str(BIN), "--version"], capture_output=True, text=True, check=True).stdout
    if f"v{VERSION}" not in actual:
        raise RuntimeError(f"This helper is verified with zsp v{VERSION}. Run setup or update ZSP_BIN.")
    return data, publisher[1]


def invoke(args, env, output=None, input_text=None):
    # Keep credentials out of command arguments, files, and any displayed tool errors.
    result = subprocess.run([str(BIN), *args], cwd=ROOT, env=env, capture_output=True,
                            text=True, input=input_text)
    stdout, stderr = result.stdout, result.stderr
    for key in ("SIGN_WITH", "KEYSTORE_PASSWORD"):
        value = env.get(key, "")
        if value and not value.startswith("npub1"):
            stdout, stderr = stdout.replace(value, "[redacted]"), stderr.replace(value, "[redacted]")
    if result.returncode:
        raise RuntimeError(stderr.strip() or stdout.strip() or "Publisher exited unsuccessfully.")
    if output:
        events = [json.loads(line) for line in stdout.splitlines() if line.strip()]
        if not events or not all("kind" in event and "pubkey" in event for event in events):
            raise RuntimeError("Publisher did not return the expected Nostr events.")
        output.write_text(stdout)
        print(f"Saved {output}")
    elif stdout:
        print(stdout, end="")
    if stderr:
        print(stderr, end="", file=sys.stderr)
    return [json.loads(line) for line in stdout.splitlines() if line.strip()] if output else []


def link_identity(args, env):
    # zsp 0.4.17's identity menu is interactive even with --json. Give it the
    # caller's terminal so its Publish now / Exit choices remain visible.
    print("In the identity menu, choose Publish now. Press Ctrl+C to cancel.", flush=True)
    result = subprocess.run([str(BIN), *args], cwd=ROOT, env=env)
    if result.returncode:
        raise RuntimeError("Identity linking did not finish; app publication was stopped.")


def execute(command):
    data, npub = release()
    env = os.environ.copy()
    env["RELAY_URLS"] = "wss://relay.zapstore.dev"
    env["BLOSSOM_URL"] = "https://cdn.zapstore.dev"
    if command == "check":
        env["SIGN_WITH"] = npub
    else:
        if not sys.stdin.isatty():
            raise RuntimeError("Run this command in your own terminal; the nsec prompt requires a TTY.")
        # Refuse getpass's echo fallback if a terminal is unavailable.
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)
            nsec = getpass.getpass("Nostr nsec (hidden; never saved): ").strip()
        if not nsec.startswith("nsec1"):
            raise RuntimeError("Expected an nsec1 key. Nothing was published.")
        env["SIGN_WITH"] = nsec
    args = ["publish", str(CONFIG), "--commit", data["source_commit"]]
    try:
        # Offline config validation also rejects a key that doesn't match the public npub.
        filename = "zapstore-unsigned.jsonl" if command == "check" else "zapstore-signed.jsonl"
        invoke([*args, "--offline", "--quiet"], env, DIST / filename)
        if command == "check":
            print("Listing validated offline. No signing key used and nothing published.")
            return
        signing = Path(os.environ.get("STILL_SIGNING_DIR", Path.home() / ".local/share/still/signing")).expanduser()
        password = signing / "password.txt"
        keystore = signing / "release.p12"
        if not keystore.is_file() or not password.is_file():
            raise RuntimeError("Android keystore/password missing; restore the signing directory from your backup.")
        if any(p.stat().st_mode & 0o077 for p in (keystore, password)):
            raise RuntimeError("Restrict the keystore and password to mode 600 before signing.")
        env["KEYSTORE_PASSWORD"] = password.read_text().strip()
        identity = ["identity", "--link-key", str(keystore), "--relays", "wss://relay.zapstore.dev"]
        proofs = invoke([*identity, "--offline"], env, DIST / "zapstore-identity-signed.jsonl")
        if len(proofs) != 1 or proofs[0]["kind"] != 30509 or ["d", data["certificate_sha256"]] not in proofs[0].get("tags", []):
            raise RuntimeError("The keystore does not match the release APK. Nothing was published.")
        if command == "sign":
            print("Signed locally. Nothing uploaded or published. Run the publish command when ready.")
        else:
            print(f"Publishing Still {data['version']} as {npub} to Zapstore.", flush=True)
            link_identity(identity, env)
            # Require a verifiable link on the relay before uploading the APK.
            public_env = env.copy()
            public_env["SIGN_WITH"] = npub
            public_env.pop("KEYSTORE_PASSWORD", None)
            invoke(["identity", "--verify", str(DIST / data["apk"]),
                    "--relays", "wss://relay.zapstore.dev"], public_env, input_text=npub + "\n")
            invoke([*args, "--quiet", "--skip-preview"], env)
    finally:
        env.pop("SIGN_WITH", None)
        env.pop("KEYSTORE_PASSWORD", None)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["setup", "check", "sign", "publish"])
    args = parser.parse_args()
    try:
        setup() if args.command == "setup" else execute(args.command)
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Zapstore preparation failed: {error}\n")
    except (KeyboardInterrupt, EOFError):
        parser.exit(130, "\nCancelled.\n")


if __name__ == "__main__":
    main()

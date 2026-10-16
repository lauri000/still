#!/usr/bin/env python3
"""Create a private signing key or build/sign a committed Android source snapshot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SIGNING = Path(os.environ.get("STILL_SIGNING_DIR", Path.home() / ".local/share/still/signing")).expanduser()
KEYSTORE = SIGNING / "release.p12"
PASSWORD = SIGNING / "password.txt"
ALIAS = "still-release"


def run(args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def environment():
    env = os.environ.copy()
    bundled = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    if not env.get("JAVA_HOME") and bundled.is_dir():
        env["JAVA_HOME"] = str(bundled)
    env.setdefault("ANDROID_HOME", env.get("ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk")))
    return env


def keytool(env):
    return Path(env["JAVA_HOME"]) / "bin/keytool" if env.get("JAVA_HOME") else "keytool"


def require_key():
    if not KEYSTORE.is_file() or not PASSWORD.is_file():
        raise RuntimeError("Signing key missing. Run: python3 scripts/android-release.py init-key")
    for path in [KEYSTORE, PASSWORD]:
        if path.stat().st_mode & 0o077:
            raise RuntimeError(f"Restrict private file permissions first: chmod 600 '{path}'")


def init_key():
    if KEYSTORE.exists() or PASSWORD.exists():
        raise RuntimeError(f"Signing files already exist in {SIGNING}; refusing to replace them.")
    SIGNING.mkdir(parents=True, mode=0o700, exist_ok=True)
    SIGNING.chmod(0o700)
    with PASSWORD.open("x") as file:
        file.write(secrets.token_urlsafe(48) + "\n")
    PASSWORD.chmod(0o600)
    env = environment()
    run([keytool(env), "-genkeypair", "-keystore", KEYSTORE, "-storetype", "PKCS12",
         "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA",
         "-validity", "10000", "-dname", "CN=Still, OU=Android, O=Still contributors",
         "-storepass:file", PASSWORD, "-keypass:file", PASSWORD], env=env)
    KEYSTORE.chmod(0o600)
    print(f"Created {KEYSTORE}. Back up this directory securely; its password is not printed.")


def build(ref):
    require_key()
    env = environment()
    sdk = Path(env["ANDROID_HOME"])
    versions = [p for p in (sdk / "build-tools").iterdir() if (p / "apksigner").is_file()]
    if not versions:
        raise RuntimeError("Install Android SDK Build Tools first.")
    tools = max(versions, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)))
    commit = run(["git", "rev-parse", "--verify", f"{ref}^{{commit}}"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="still-release-") as temp:
        snapshot = Path(temp)
        archive = snapshot / "source.tar"
        with archive.open("wb") as output:
            run(["git", "archive", "--format=tar", commit], cwd=ROOT, stdout=output)
        run(["tar", "-xf", archive, "-C", snapshot])
        archive.unlink()
        android = snapshot / "apps/android"
        print(f"Building committed source {commit}; working-tree edits are not included.", flush=True)
        run([android / "gradlew", "--no-daemon", "--console=plain", ":app:assembleRelease",
             ":app:testDebugUnitTest", ":app:lintRelease"], cwd=android, env=env)
        unsigned = android / "app/build/outputs/apk/release/app-release-unsigned.apk"
        aligned = snapshot / "aligned.apk"
        signed = snapshot / "signed.apk"
        run([tools / "zipalign", "-P", "16", "-f", "4", unsigned, aligned])
        run([tools / "apksigner", "sign", "--ks", KEYSTORE, "--ks-type", "PKCS12", "--ks-key-alias", ALIAS,
             "--ks-pass", f"file:{PASSWORD}", "--debuggable-apk-permitted", "false",
             "--out", signed, aligned], env=env)
        run([tools / "apksigner", "verify", "--verbose", "--print-certs", signed], env=env)
        run([tools / "zipalign", "-c", "-P", "16", "4", signed])
        badging = run([tools / "aapt2", "dump", "badging", signed], capture_output=True, text=True).stdout
        package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
        if not package or package[1] != "com.stillreader.app" or "application-debuggable" in badging:
            raise RuntimeError("Unexpected package identity or debuggable release.")
        version = package[3]
        if not re.fullmatch(r"[0-9A-Za-z.+-]+", version):
            raise RuntimeError("Unsafe version name.")
        apk = dist / f"still-{version}-android-universal.apk"
        shutil.copyfile(signed, apk)
        certificate = dist / "android-signing-certificate.pem"
        with certificate.open("wb") as output:
            run([keytool(env), "-exportcert", "-rfc", "-keystore", KEYSTORE, "-alias", ALIAS,
                 "-storepass:file", PASSWORD], env=env, stdout=output)
        der = run([keytool(env), "-exportcert", "-keystore", KEYSTORE, "-alias", ALIAS,
                   "-storepass:file", PASSWORD], env=env, capture_output=True).stdout
        with zipfile.ZipFile(apk) as zipped:
            architectures = sorted({n.split('/')[1] for n in zipped.namelist() if n.startswith("lib/") and n.endswith(".so")})
            if "arm64-v8a" not in architectures:
                raise RuntimeError("Release must include ARM64 libraries.")
            if zipped.read("assets/licenses/Still-LICENSE.txt") != (snapshot / "LICENSE").read_bytes():
                raise RuntimeError("Project license missing from APK.")
        manifest = {"version": version, "version_code": int(package[2]), "package_id": package[1],
                    "source_commit": commit, "apk": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                    "certificate_sha256": hashlib.sha256(der).hexdigest(), "architectures": architectures}
        manifest_file = dist / "release.json"
        manifest_file.write_text(json.dumps(manifest, indent=2) + "\n")
        (dist / "SHA256SUMS").write_text("".join(f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n"
                                               for p in [apk, certificate, manifest_file]))
        print(json.dumps(manifest, indent=2))
        print(f"Release artifacts: {dist}")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["init-key", "build"])
    parser.add_argument("--ref", default="HEAD", help="Committed source to build (default: HEAD)")
    args = parser.parse_args()
    try:
        init_key() if args.command == "init-key" else build(args.ref)
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Release preparation failed: {error}\n")


if __name__ == "__main__":
    main()

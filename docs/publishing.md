# Android releases and Zapstore

Still's Android package is `com.stillreader.app`. Its publisher identity is
`npub1dmhju6xrn8y09malwrvrrs4krrt6sj7l6gtnf2q7d47nmqtldpgqgctgtm`.
The Android APK signing key and the Nostr publisher key are separate credentials.

## Signing key backup

The dedicated RSA-4096 Android key is stored outside this repository:

```text
~/.local/share/still/signing/
  release.p12    PKCS12 keystore; alias: still-release
  password.txt   Random keystore password
```

Back up both files to an encrypted location or password manager. They are required
to sign future updates. The directory has mode `700`; both files have mode `600`.
The password is not printed by the release tooling. `STILL_SIGNING_DIR` can point
to a restored copy. Keystores, `.env` files, build outputs, and local tools are
ignored by Git. The public signing certificate is safe to distribute.

The key already exists on the release machine. On a completely new project only,
`python3 scripts/android-release.py init-key` creates one; it refuses to overwrite
existing signing files. Restore Still's existing key on another machine instead
of generating a replacement.

## Build a release

Run these commands from the repository root. Python 3, Git, a JDK, and the Android
SDK are required. On macOS the helper detects Android Studio's bundled JDK and
the standard SDK location; elsewhere set `JAVA_HOME` and `ANDROID_HOME`.

```sh
python3 scripts/android-release.py build
python3 scripts/zapstore.py setup
python3 scripts/zapstore.py check
```

The build helper archives **committed HEAD** into a temporary directory, runs
the release build, core unit tests, and release lint, aligns the APK for 16 KB pages,
and signs it using the private keystore. It verifies the signature, package ID,
non-debuggable manifest, ARM64 support, and bundled MIT license. Working-tree
edits are not included. Use `--ref <commit>` for a specific committed snapshot.

Artifacts appear in ignored `dist/`:

- `still-<version>-android-universal.apk`
- `SHA256SUMS`
- `release.json` with source commit, APK hash, and certificate fingerprint
- `android-signing-certificate.pem` (public certificate only)

`setup` installs a project-local zsp **0.4.17** and checks its official SHA-256
digest. `check` validates the local listing and APK offline using only the public
npub, producing unsigned events. It does not upload files or publish events.
The public listing lives in [`zapstore.yaml`](../zapstore.yaml), including the
npub needed for Zapstore's repository ownership check.

For later releases, increment `versionCode` and `versionName` in the Android build
configuration, update the artifact path and release notes in `zapstore.yaml`,
commit, and run the same build command. Keep the same signing key.

## Sign locally with your nsec

In your own terminal:

```sh
cd /path/to/still
python3 scripts/zapstore.py sign
```

Paste the nsec only at the hidden prompt. The helper checks that it matches the
npub in `zapstore.yaml`, signs the app/release events and certificate-link proof
offline, and writes `dist/zapstore-signed.jsonl` and
`dist/zapstore-identity-signed.jsonl`. **Nothing is uploaded or published.**
These output files contain public signed events, not the private key. The nsec
is never written to disk, passed as a command argument, or entered as a shell
command. zsp receives it through the environment of the short-lived local
subprocess; this is not protection against other software with access to your
user session. Do not run the helper under tracing or on a shared machine.

## Publish when ready

Commit and push `zapstore.yaml` before the first publish so Zapstore can verify
the public npub against the repository. A GitHub draft release can hold the APK,
checksums, manifest, and public certificate while you review them. Publish that
draft from GitHub when you want a public GitHub download.

```sh
python3 scripts/zapstore.py publish
```

This command prompts for your nsec again, validates and signs locally, publishes
the certificate-to-identity proof to `wss://relay.zapstore.dev`, then uploads the
APK/icon/screenshots and publishes the listing to Zapstore. In zsp's identity
menu, choose **Publish now**; Ctrl+C cancels. The helper verifies the published
certificate link before uploading the app. It uses the local
APK specified in `zapstore.yaml`, so GitHub release publication is optional for
this flow. No private key or keystore password is uploaded.

The offline signed event files alone do not publish an installable app: the APK
and images also need uploading. The `publish` command handles both steps.

After publishing, verify the listing under the configured npub in Zapstore and
test an installation on an Android device. The release APK cannot update the
earlier debug build because its signing key differs: export books, uninstall
the debug build, install the release, then import the backups.

References: [Zapstore publishing](https://zapstore.dev/docs/publish),
[zsp v0.4.17](https://github.com/zapstore/zsp/releases/tag/v0.4.17),
[Android app signing](https://developer.android.com/studio/publish/app-signing).

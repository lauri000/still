# Android successive-import memory regression

On 24 October 2026, the reported failure was reproduced in the 0.1.24 production code (`35393ca`) using an Android 16 emulator with a 192 MiB app heap. The full library/reader UI was visible. In one app session, the test downloaded/imported Moby-Dick, opened it and saved a position, then repeated that workflow for The Prince and War and Peace. Network responses came from local copies of the real EPUBs through the production catalog transport, avoiding repeated public downloads.

The old code failed on War and Peace with the same user-facing memory message. Android recorded a failed 31,391,752-byte allocation with approximately 9.4 MiB free. The test process remained alive and the import reported the failure.

| Import | 0.1.24 sampled peak heap | Fixed sampled peak heap | Fixed result |
| --- | ---: | ---: | --- |
| Moby-Dick | 173 MiB | 169 MiB | Imported and opened |
| The Prince | 102 MiB | 57 MiB | Imported and opened |
| War and Peace | 191 MiB, allocation failed | 74 MiB | Imported and opened |

The fixed test completed all three imports without restarting, reopened every book at its saved word position, and verified a duplicate download does not transfer or import another copy. Temporary download files were removed. Peaks sample allocated Java heap every 20 ms during each import; they are not whole-device RAM measurements or guarantees for every EPUB. Reader rendering and reopening are exercised after import. No forced garbage collection or increased heap allowance is used.

An earlier importer-only comparison without visible reader UI also passed all three books after the fix. War and Peace's import decreased from 7.084 seconds / 192 MiB to 3.473 seconds / 159 MiB. These are emulator diagnostics, not physical-phone benchmarks.

## Repeating the test

`CatalogMemoryTests.successiveLargeDownloadsKeepReadingAndResumeWithoutRestart` is opt-in. Build/install the debug app and androidTest with `-PpdfEvaluationBuild` to use the isolated package `com.stillreader.app.pdfevaluation`. Copy the following EPUBs into that app's private `files/import-memory/` directory using `adb run-as`. Test fixtures are never downloaded by the build or committed to Git.

| File | Source | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| `1232.epub` | [The Prince](https://www.gutenberg.org/ebooks/1232), EPUB with images | 367381 | `3416b2b50b1f4c919bf2da9258b465ec80d97eec02faa4bfeb7c1a276a3f435c` |
| `2600.epub` | [War and Peace](https://www.gutenberg.org/ebooks/2600), EPUB with images | 1855300 | `dcabdeedcc8ea49e92bf8c0f38b9ae6e2bdc2fe89ad025a1afb4697741e4c874` |
| `2701.epub` | [Moby-Dick](https://www.gutenberg.org/ebooks/2701), retained Gutenberg fixture | 814023 | `1bc88e79ef7cf9d5325abe0071e6302265b708df56d3af07db124c81e84197f6` |

Gutenberg editions can change; review changed fixtures and retain their notices. Each fixture is downloaded individually once and reused offline.

```sh
adb shell am instrument -w -r \
  -e class com.stillreader.app.CatalogMemoryTests \
  -e largeCatalogImports true \
  com.stillreader.app.pdfevaluation.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s StillImportMemory:I '*:S'
```

The test uses an isolated temporary library and deletes it afterward. It does not use or modify the release app's library. Default test runs skip this test unless explicitly enabled.

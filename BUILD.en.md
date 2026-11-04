# DSH-A-Su build and signing instructions

> Current identity (build316): `DSH-A-Su` / `io.github.sunsetrne.dsha` / `0.2.1-rc.5-20261105.0230-dsh0.2.1-rc.5` / versionCode 316.
> Single Standard variant: minSdk 30 (Android 11+), arm64-v8a, system WebView. The old `low`/GeckoView line and the separate island package are gone.
> Signing: a newly created Mod release certificate (SHA-256 `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`); the original E7E3 key is no longer used.
> Fixed build inputs: published as a public pre-release Release asset and pinned by SHA-256; see [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md).
> Historical build receipts and old identity numbers remain in [CHANGELOG.md](CHANGELOG.md) and [docs/releases/](docs/releases/); they are not inputs for the current build or signing identity.

<<<<<<< 208ea63a

Current F13 frozen inventory: 2215 source members / 200 Markdown documents; 1029 adopted Java files. Distribution/format proofs and JUnit execution counts are separate. The fixed packet is 738,101,893 bytes, SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`. Originals expand directly at project paths, not as a nested packet. These are not the complete source ZIP's size or digest.

> **F13 `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` / code190.** Directory-service composition, attachment-resolver disposal and recovery after transient background proof failure are fixed. Standard 150 selected classes/952 JUnit cases (3 skipped); Low 150 selected classes/952 cases (76 skipped). Both have zero failures/errors. Release Lint has zero errors and Standard/Low warnings 249/162; eight actual-APK gates passed. Original failures, corrections and negative controls remain separate; this is not one continuous green run. No device/ADB, real-account/model or new endurance acceptance. [Associated Release target](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.2107) · [Scope and evidence](docs/en/releases/v0.2.1-rc.2-build190-fix13-20261021.md). Actual remote availability is established by the Release and independent receipt.

Published F12 retains its original identity: [immutable English record](docs/en/releases/v0.2.1-rc.2-build190-fix12-20261021.md) · [original Release](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.1413). F11 remains an unpublished local checkpoint.

Read [README](README.en.md), [AGENTS](AGENTS.en.md), [CONTRIBUTING](CONTRIBUTING.en.md), and the [handoff guide](docs/en/handoff.md) first. `app/build.gradle` defines the application version; `tools/dsh-runtime/package.json` and its lock define DSH dependencies. The current report defines complete delivery and unverified scope. Use [README](README.en.md) and the [release introduction](docs/en/release-introduction.md) for final current identity, rather than treating the previous snapshot below as a new publication claim.

**Previous F11 source snapshot:** `0.2.1-rc.2-20261021.1157-dsh0.2.1-rc.2` / code190 fixed LAN, official Web login, the first-use notice, plugin provenance, and host recovery, and added 21 topic documents. Both variants ran 143 classes / 881 JUnit tests each, with zero failures/errors (Standard 3 skips; Low 76); Release Lint had zero errors and 249/162 warnings, and eight actual-APK gates passed. Original failures, reruns, and host-port contention limits were retained separately. That work did not include device/ADB, real-account/model conversations, or endurance acceptance. The [historical F11 record](docs/releases/v0.2.1-rc.2-build190-fix11-20261021.md) preserves that checkpoint's scope and evidence. Its 1157 Release URL was prepared but F11 was not uploaded; it is not a download or remote-publication receipt. F10 remains historical too.

The maintained local source is `C:\Users\18768\.codex\worktrees\build190-from170\DSHA_RESTART`. The old build173 checkout at `F:\DSHA_RESTART` is not the build source. On other machines, use the final tag/full commit in the private repository or its matching complete source attachment.
=======
Read [README](README.en.md), [AGENTS](AGENTS.en.md) and [CONTRIBUTING](CONTRIBUTING.en.md) first. `app/build.gradle`, `ci/release-identity.json` and `ci/sunset-inputs.lock.json` are the three authoritative sources for the current build identity.
>>>>>>> sunset/main

## Environment and variants

| Input | Requirement |
|---|---|
| JDK / native language | Java 17, no Kotlin |
| Gradle / AGP | Repository Wrapper 9.3.1 / AGP 9.1.1 |
| Android SDK | `platforms;android-37.0`, build-tools 36.0.0; compile/target API 37 |
| NDK | 26.3.11579264 (only for native builds; the repo ships prebuilt arm64 JNI) |
| Python / Node | Python 3.9+ for generators (`DSHA_PYTHON`); Node 24; CI pins in `ci/toolchain.lock.json` |
| Standard | minSdk 30, system WebView, arm64-v8a (the only variant) |

There is one Standard build line. The test variant only changes package/version via `-Pdsha.sunsetTest=true` (`io.github.sunsetrne.dsha.test`); it coexists with the release package and has isolated data. The original `dsh.android.client` and the older `com.dsh.client` are separate packages that this Mod does not overwrite.

Set `sdk.dir` in local `local.properties`, and point `JAVA_HOME`, `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) and `DSHA_PYTHON` at real tools. Do not copy another developer's absolute toolchain paths. `app/build.gradle` defines `versionCode`, `versionName` and `dshaApkNameVersion`; this page does not maintain a competing set of numbers.

## Fixed build inputs

The offline rootfs, DSH runtime, Ubuntu tools, pnpm, Python support, glibc Python, ADB wheels and recovery archives are not committed to Git. `tools/ci-assets.py` restores them according to `ci/sunset-inputs.lock.json`, which pins the public pre-release asset URL, SHA-256, byte count, format and Release/asset names.

```bash
python3 tools/ci-assets.py \
  --url "$(python3 -c 'import json;print(json.load(open("ci/sunset-inputs.lock.json"))["url"])')" \
  --sha256 "$(python3 -c 'import json;print(json.load(open("ci/sunset-inputs.lock.json"))["sha256"])')"
python3 tools/prepare-runtime-descriptor.py --check   # use --write after changing inputs and review the diff
```

See [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md) for the contents, restore flow, upgrade/rollover procedure and integrity requirements. A source-only checkout without the fixed inputs can run source-only checks but cannot produce a complete APK.

## Software validation

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
python3 tools/check-ui-i18n.py
python3 tools/localize-android-ui.py --resources-only --check
python3 tools/generate-changelog.py --check
./gradlew :app:testStandardDebugUnitTest :app:lintStandardRelease
python3 tools/run-host-tests.py --stage runtime
python3 tools/verify-stability.py --stage software
```

`preBuild` in `app/build.gradle` also runs managed-asset, built-in plugin, credential-rule, proroot, language and changelog gates; do not bypass a failing gate to produce a package. On Windows use `gradlew.bat`. When AGP/aapt2 is unavailable, `tools/run-unit-tests.py` is a pure-logic fallback, not a replacement for full Android compilation, APK checks or resource gates.

## Release packaging and signing

Explicitly supply `DSHA_KEYSTORE`, `DSHA_KEYSTORE_PASSWORD` and `DSHA_KEY_PASSWORD`; the alias defaults to `dsha-a-su` and can be overridden with `DSHA_KEY_ALIAS`. `ci/release-identity.json` is authoritative: `packageName = io.github.sunsetrne.dsha`, `certificateSha256 = a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`.

Before `assembleStandardRelease`, `packageStandardRelease` and `validateSigningStandardRelease`, `verifyPublishCertificate` checks the keystore certificate and the applicationId against that record and stops packaging on any mismatch. Historical E7E3 or a debug certificate can no longer produce a release package. Private keys are never committed; rotating the key prevents over-the-top upgrades of already installed Mod builds.

```bash
./gradlew :app:assembleStandardRelease
```

<<<<<<< 208ea63a
The delivery scope is Standard. Software validation covers affected tests and Release Lint for both variants, plus actual Standard APK/assets/ELF, signing, licensing, plugin/emergency gates, and exact digests. Record selected JUnit classes, skips, cache reuse, and unrun scope separately, without claiming a clean whole-repository or fully cold-network build. JNI static auditing uses the matching Release APK, not an old Debug product. Preserve old Low bytes; Low software checks are not a new Low delivery or device acceptance.

The local Standard release target is `F:\DSHA_RESTART\release\0.2.1-rc.2\dsha-0.2.1-rc.2.apk`; verify its digest locally and with the release record. Retain historical versions and old Low bytes, and do not present an intermediate `app/build` APK as delivery. The private repository excludes large generated archives. A Release attaches only the release APK and complete source ZIP with fixed build inputs. Put SHA-256 in the Release body; the source ZIP's own README must not contain that ZIP's digest, avoiding self-reference. Do not upload private keys, user data, device screenshots/logs, or historical forensic material. Nine necessary audit metadata files, 27 historical build sources, and one website test fixture remain under the delivery inventory; this does not distribute historical device scenes. 2054, 2330/F9, 0140/F10, and the 1157/F11 source snapshot retain their own historical identity. The linked current report, Release, and independent external receipt bind final software/APK results, source commit, attachments, and remote state. An older APK/report cannot replace that proof.

An unsigned complete `ci-package` build is for software verification only, never phone installation or release. Configuration files do not prove protected environments, online signing execution, or repository protection are configured. Deployment and publication need actual evidence.
=======
The artifact is `app/build/outputs/apk/standard/release/dsha-<dshaApkNameVersion>.apk` and must ship with a `.sha256` computed from the actual file. Verify the real APK's package name, versionCode/versionName, `debuggable=false`, arm64 ABI, certificate SHA-256 and managed-asset/recovery-archive digests. Intermediate `app/build` packages and unsigned CI packages (`-Pdsha.unsignedPackage=true`) are never presented as releases.
>>>>>>> sunset/main

## Phones and historical scope

This build change does not include device installation, start/stop, input or model-conversation acceptance. User data, sessions, configuration, plugins and unknown runtime state are preserved; a “normal Stop” message alone does not prove exit. Historical device receipts bind their own APK and time and cannot become new-package acceptance.

Linux tmpfs publication tests do not prove persistent-disk, power-loss or Android FUSE behavior. Android 10 and below are outside the supported range; Android 6/7, vendor Root/Shizuku and 16 KiB matrices remain historical records only. See the [module map](docs/en/module-map.md) and [security model](docs/en/security-model.md) for responsibilities and controls, and the [project handbook](docs/en/project-architecture-handbook.md) and [documentation index](docs/en/index.md) before maintaining source.

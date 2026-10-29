# DSHA Sunset Mod

> A third-party **Mod** of [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) — not an official DSHA distribution.
> Runs the complete [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) on Android: no root, no Termux, works right after install.

## Status

| Item | Value |
|---|---|
| Version | `0.2.1-rc.3-20261029.0741-dsh0.2.1-rc.3` |
| versionCode | 208 |
| Main branch | `Sunset-3090-Q` |
| Package | `dsh.android.client` (installs alongside the legacy `com.dsh.client`) |
| System | Android 11+ / arm64 |
| License | MIT (inherited from upstream) |

## What this is

DeepSeek Harness is DeepSeek's official agent harness, written for glibc Linux; running it on Android directly runs into native-module builds, SELinux restrictions, sandbox startup and desktop-oriented layout.

DSHA wraps all of that into a single APK: a complete Ubuntu environment where `apt`, interactive PTYs and native modules that need compiling all work. On first launch it unpacks and verifies the bundled offline environment; models and API keys are then configured in the Web UI — no Termux, root or command line required.

This repository is a Mod maintained on top of the original; the changes focus on the terminal, device capabilities and the mobile UI.

## Mod highlights

- **Three-mode root command policy (build208)**: strict (default allow-list), relaxed (blocks irreversible actions) and fully relaxed. Root channel only; Shizuku / ADB stay strict, and SMS reads always require native authorization.
- **Terminal**: standalone full-screen terminal, session sidebar (three states, landscape compression), extra keys, an output "more" panel, output preview / copy / share / save and a responsive sidebar.
- **Mobile UI v5**: plugin page cards and filters, componentized settings sub-pages, page and dialog transitions, unified launch state and floating-ball details.
- **Device capabilities**: accessibility read/tap, ADB / Shizuku / Root command channels, SMS read gate, virtual display, notifications, clipboard and sensors.
- **Web UI and access**: mobile-adapted interface, LAN access with authentication, dynamic ports.
- **Backup and restore**: encrypted backups (v5 format), upgrade and restore, external-workspace protection.
- **Plugin ecosystem**: bundled plugins, plugin package installation, permission and state management.
- **Reliability and recovery**: least-privilege model, self-check diagnostics, emergency recovery entry and background Web recovery.

## Branches

Five working branches are kept; `main` currently points at the content of `Sunset-3090-Q`.

| Branch | Purpose | Latest commit |
|---|---|---|
| `Sunset-3090-Q` | Mainline: terminal, UI, root three-mode policy (build208) | 2026-10-29 |
| `Sunset-zhongdua-G` | Terminal and device regression (landscape / extra keys) | 2026-10-26 |
| `codex/prerelease-190` | Pre-release base (upstream sync starting point) | 2026-10-25 |
| `plugin-state-fix-20261025` | Plugin state and executor fixes | 2026-10-25 |
| `v5-base-build201` | UI v5 baseline | 2026-10-26 |

Per-version changes and verification boundaries are recorded in [CHANGELOG.md](CHANGELOG.md) and [docs/releases/](docs/releases/).

## Install and migrate

1. Get an APK signed with the expected key (this repository does not ship public APK assets; see the build section below).
2. On first launch the bundled offline environment is unpacked and verified; the main screen then appears and the Web service starts in the background.
3. Open the Web UI and configure provider, model and API key in settings.
4. To migrate from an older version, export a backup first and restore it afterwards; data and system grants are not inherited across package names.

## Build from source

Requires JDK 17, Node 24, Python 3.9+, Android SDK 37 and NDK 26. Prepare the pinned assets as described in [CONTRIBUTING.md](CONTRIBUTING.md); toolchain and signing are documented in [BUILD.md](BUILD.md).

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
./gradlew :app:assembleStandardRelease
```

On Windows use `gradlew.bat`. `Debug` in test task names only selects the test variant; it does not require building or installing a debug APK. Signing keys, user data, device screenshots and logs are never committed.

## Documentation

| Document | Content |
|---|---|
| [AGENTS.md](AGENTS.md) | Project layout, startup contract and technical constraints (contributor entry point) |
| [docs/en/index.md](docs/en/index.md) | Complete English documentation index |
| [docs/security-model.en.md](docs/security-model.en.md) | Security model: permission boundaries and known weaknesses |
| [docs/plugins.md](docs/plugins.md) | Plugin installation and packaging requirements |
| [BUILD.md](BUILD.md) / [CONTRIBUTING.md](CONTRIBUTING.md) | Build, signing and contribution workflow |
| [CHANGELOG.md](CHANGELOG.md) | Full change history |

## Compatibility and known limitations

- arm64, Android 11+ only; permissions, background behaviour and power policies differ notably between ROMs.
- Virtual display, root policy modes and SMS reads depend on the OS version and native authorization state.
- Some vendor native dependencies are not guaranteed to be fully reproducible from source; the first offline-environment unpack takes a while.
- The desktop/PC connection capability has been removed from the current version; phone control and local/LAN access remain.

## Credits and license

This project is a Mod derived from [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) and keeps the original [MIT](LICENSE) license (Copyright (c) 2026 qiannianhuanxiang), preserving the original copyright and license notices.

The upstream harness is [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness); the container layer uses [proot](https://github.com/termux/proot) / [proroot](https://github.com/coderredlab/proroot), the terminal is based on [Termux terminal-view](https://github.com/termux/termux-app), the mobile adaptation is based on [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile), and the fallback device channel is [Shizuku](https://shizuku.rikka.app/). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full third-party notices.

Thanks to the original author [@qiannianhuanxiang](https://github.com/qiannianhuanxiang) and all contributors.

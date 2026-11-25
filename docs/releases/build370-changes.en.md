# DSHA 0.2.1-rc.5-20261119.0400-dsh0.2.2-rc.5 / build370

- The version code is 370. This local Standard build keeps the DSHA package name `dsh.android.client` and the existing E7E3 signing certificate, with support for arm64 devices running Android 11 or later.
- Bundled DSH moves to 0.2.2-rc.5 and the bundled mobile UI, dsh-web-mobile, moves to 3.3.5. This build incorporates upstream updates to startup, runtime maintenance, plugin management, terminal sessions, and automatic backups.
- Cross-app file access uses the system file picker. Upstream fixes how directory permission reductions and revocations are handled when saving a grant fails.
- An in-place update from build308 with the same package name and signing certificate may require runtime maintenance at first launch. The app checks the existing environment and offers Update runtime when needed.

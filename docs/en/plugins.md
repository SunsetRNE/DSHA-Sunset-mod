# Installing, updating, and sharing plugins

> Translation verified: 2026-10-21. Source baseline: released F10 `087724c` and the current Chinese build190/F12 maintenance page and production call chain. F11 is an unpublished local checkpoint, not another released version. This page describes the DSHA entry points for DSH `0.2.1-rc.2`; it adds no new version, final test total, or device validation. See the [English README](../../README.en.md), [release introduction](release-introduction.md), and [F12 record](releases/v0.2.1-rc.2-build190-fix12-20261021.md) for final identity and executed scope. An older device report is not an acceptance receipt for a new APK.

Paste a link into plugin management, import a local package through the system file picker, or open a DSHA installation link supplied by a website. The app resolves the actual package and checks its format, paths, name/version, actual dependencies, and digests before the plugin transaction commits and enables it. The current production plugin flow does not require a native review confirmation. Browsing a website does not install a plugin: installation continues only after the user opens its installation entry point. Restart Web normally when the app requests it after an update or a toggle.

## Supported sources and package contract

- GitHub `owner/repo` shorthand, repository URLs, SSH addresses, tree/blob subdirectory links, archive/codeload links, and Release assets.
- A Release page with exactly one supported archive can be resolved directly. If it has multiple candidates, select the actual asset link.
- HTTPS ZIP, TAR, TAR.GZ, and TGZ archives, and npm plugin package names. Shared text containing several links requires a target selection first.
- Local imports are identified by their actual contents. A filename extension or Provider MIME type does not replace content validation. Another file picker can be used when system DocumentsUI is unavailable.

`package.json` must contain a valid npm name and version and a valid `dsh.bundle.patch` declaration; every declared patch file must exist. `main` or `cordis.entry` is checked for an existing entry file only when the package declares that field; packages do not all have to declare either field. An ordinary npm library does not become a plugin because its name contains “dsh.” An archive cannot bypass checks with escaping paths, links outside the package, colliding names, or declared but missing entries. Download and expansion limits follow the current app checks, which report why a candidate exceeds a limit.

Prefer an author's built Release asset or `npm pack` distribution. The Android container runs Linux arm64/glibc; dependencies with native modules must support that environment. Record compatibility declarations, author tests, and local measurements separately. An author's statement is not proof of dependency contents or usability on this device.

## Dependencies, scripts, and actual privileges

Production dependency installation permits unlocked resolution and uses the bundled pnpm `10.34.5` production dependency flow. Dependency lifecycle scripts such as `postinstall` and build steps, and pnpmfile hooks, may run. Installation records the actual lock and directory digests; later reuse and restore inspect the real historical contents. An old lock is not forcibly injected into a new online candidate, and an offline restore does not resolve dependencies again inside the original.

`npm pack --ignore-scripts` restricts only the read-only package-download stage. Dependencies are normally prepared with a temporary manifest containing only dependency declarations, so that path does not run scripts from the plugin's own source; a path that reuses the author's native lock uses the original manifest. It is therefore incorrect to promise that no installation scripts execute, or to treat a download argument as the final installation permission policy.

Plugins, scripts, and ordinary terminal commands run under the app UID and can read or modify contents accessible to that UID. proot/proroot is not an independent sandbox for malicious third-party code. Package and transaction checks provide content and rollback guarantees, not a security certification of author code. Device actions still pass through the current native capability grants and execution policy. Choose third-party code based on its actual source. See the [security model](security-model.md).

## Commit, failure, and user intent

Installation prepares a candidate and records originals, fixed targets, before/after digests, and actual dependencies before switching the installation directory. Uncommitted journals are protected by the maintenance gate; recovery checks process exit and directory identity against that operation's record. A committed old journal must not roll back later user edits. Transaction originals, unknown contents, and historical dependencies cannot be implicitly deleted as temporary cleanup.

If a network error occurs after lifecycle scripts have run, installation does not automatically switch sources and replay the operation. After ordinary success or a known failure, `candidate_workspace` may clean a temporary preparation directory once its identity is confirmed; every failed candidate is not retained forever. When package-manager process or output-pipe exit is unknown, the candidate and `.dsha-package-command-unknown.json` are retained, and cleanup or automatic retry is forbidden. Originals already in a transaction, pending journals, and historical dependencies follow their transaction contract. Power-loss behavior, Android filesystem behavior, and device results remain subject to separate acceptance records.

An update of the same plugin preserves a deliberate user disable and safe-mode intent; upgrading removes only the old automatically assigned review marker. System plugins are rebuilt from the current signed APK's managed proof and remain separate from user source and dependencies. Use plugin management and startup diagnostics to distinguish toggles, source information, and actual loading.

Native `enable-state` / `disable-state` strictly checks actual readback of package name, Boolean state, and `intentVerified: true`, presents the verified toggle intent first, then independently coalesces a list refresh. Success is not component readiness or a live-loader load/disposal result; restart Web normally. Read-only list/refresh does not acquire the profile writer lock and is cancelable. Concurrent configuration rewrites or an existing writer lock return explicit errors; retained cached cards are not newly verified results. The 30-second cooperative query/short-intent budget includes queueing, preparation boundaries, and execution. A single system I/O or monitor/Binder wait is not preemptible, and unknown exit retains the original barrier rather than automatically retrying writes.

## Export, removal, and the terminal

A plugin export includes the selected user plugin's real source, existing dependencies, and bindings. System plugin bytes come from the current APK; user backups save only their enable/disable intent. Incomplete or unknown content and dependency warnings must be reported as such, rather than relabeled “fully verified.”

Removing a third-party plugin from a managed location removes that plugin's installation and registration while retaining chats, configuration, other plugins, shared dependencies, and the external import source. Export first if you need to retain it. Optional built-ins use toggles. Official core bundles can also be explicitly disabled, which may prevent Web from starting; re-enable them in the native plugin page or use the recovery workbench. Do not attempt to repair a core bundle by deleting it or replacing it with a third-party package.

The package-proof implementation distinguishes DSHA-signed components, DSH-managed packages, and user plugins using proof supplied by the APK and the actual complete package contents. An npm scope, a `dsh-` prefix, a location inside the DSH installation tree, or writable `installation.dependencies` cannot elevate provenance. A package in that tree without managed proof is shown as user/unverified. Native management permits inspection, toggles, and export, but does not automatically update or remove it in that location. User plugins in a normal profile continue through the normal install/toggle/remove flow. See [plugin runtime and permissions](plugin-runtime-and-permissions.md) for the layers and backup rechecks.

Discovery filters small manifests and actual patch bundles first, authenticates proof once per command/request, and deduplicates canonical directories without a cross-request stat cache. Actual Node Host complete FILE/mode/LINK, directory-member, and race proof uses asynchronous APIs with at most two content scans and 64 KiB read blocks. Removal fully rechecks the actual target before commit. Existing desktop fixture observations were about 0.65 seconds for two list calls and about 14 ms for an independent Worker HTTP ping while scans were pending. They apply only to those desktop inputs, not phone measurements or final performance guarantees; this page did not rerun that test.

Ordinary terminals can run `npm` and `npx`, using the same bundled CA and RuntimeTools environment as plugin installation:

```sh
npm install package-name
npm install -g tool-package-name
dsha-plugin install @author/plugin@1.0.0
```

An ordinary `npm install` does not automatically edit the DSH enabled-plugin list. `dsha-plugin` handles the actual candidate through the current plugin entry point. Do not modify a transaction scene with a bare shell to bypass the maintenance barrier.

## Verification entry points and limits

Related host regressions include `tools/test-plugin-dependencies.py`, `tools/test-plugin-transactions.py`, `tools/test-plugin-downloads.py`, and native plugin-management tests against the actual locked runtime. The [handoff guide](handoff.md) lists the full gate entry points. Each delivery report records the commands actually run and their results. This page does not claim coverage of every remote source, third-party program, device upgrade installation, or power-loss scenario.

See [runtime, data, and plugin mechanisms](runtime-data-plugin-architecture.md) for installation/toggle/runtime states, system and user layers, and the differences between Web and native entry points.

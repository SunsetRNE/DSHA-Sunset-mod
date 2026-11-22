# Application updates and managed runtime updates

> Documentation scope: the APK behavior below describes build368 candidate source (`0.2.1-rc.5-20261116.0500-dsh0.2.2-rc.1` / versionCode 368) based on published build367 / `f3f8fc03`. The candidate is unpublished, with full CI and device acceptance pending; these changes must not be attributed to the published build367. The published identity is `io.github.sunsetrne.dsha` / single Standard / build367. F10–F13/build190 records are historical. This page did not inspect the live remote feed, install an APK, or perform device acceptance. Current candidate identity is documented by [README](../../README.en.md) and the [release introduction](release-introduction.md).

APK updates and managed-component updates inside Ubuntu are different chains. An APK passes download and package-identity checks before the system installer opens. Managed components require plan review, normal task shutdown, candidate preparation/trial, and transactional switching. Both retain specific failures; version text does not replace actual outcomes.

## User-initiated APK checks

In the local A5 S2 candidate, [MainActivity](../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java) does not automatically check for APK updates or display an update Snackbar on startup, rotation, resume, or focus changes. [UpdateActivity](../../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.kt) does not implicitly check on first entry or recreation either. [UpdateEngine](../../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.kt) retains the Java-compatible `checkOnStartup`, `startupNotice`, and `markStartupNoticeShown` entry points as no-ops; they start no task or notice, and `startupNotice` always returns `null`. Clicking Check and checks triggered by a user changing channels remain available.

The legacy `check_update` key in [ConfigStore](../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) remains readable, writable, importable, and exportable. A saved `true` value does not authorize a new automatic check. The [configuration page](../../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java) hides the old switch without resetting the saved preference.

[DshaApp](../../app/src/main/java/com/deepseekharness/app/DshaApp.java) owns the single [UpdateEngine](../../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.kt). Checks, downloads, and verification before installation share its application-scoped serial queue. [UpdateRepository](../../app/src/main/java/com/deepseekharness/app/core/UpdateRepository.kt) retains permission handoff and one-time installation-result claims for the original request; the page observes, renders, and claims results. Leaving or recreating the page does not create another engine.

A user-initiated check reads the fixed HTTPS feed `https://dsha.cc/api/updates.json`, requires `schemaVersion=1`, and limits the feed to 1 MiB. [UpdateLocations](../../app/src/main/java/com/deepseekharness/app/util/UpdateLocations.java) defines source policy. A private prerelease link in README does not automatically change the updater to that repository.

The feed request uses `DSHA/<VERSION_NAME>` as User-Agent, and the receiving service sees normal network-request information. This chain attaches no conversation, terminal command, or API key. It is not automatic diagnostic reporting. The live service's privacy policy, availability, and feed contents are outside this source review.

## Channels and candidates

| Option / action | When it takes effect | Rules and failure |
|---|---|---|
| Stable / preview channel | A user change checks immediately; changing during a check supersedes it and checks the new channel; locked during download/verification | Stable accepts stable only; preview accepts stable/preview. Select by larger versionCode, not rc filename ordering |
| Default channel | Initialized when no preference exists | A version name containing `-` defaults to preview; otherwise stable |
| Check again | User click; first entry or recreation does not implicitly check | Network failure retains the channel's last result; an old candidate with unknown source cannot install directly |
| Download / resume | Starts an application-scoped foreground download after selection | Leaving/rotating does not restart it; cancel lives in the download notification (the page has none), retains partial progress, and requires another explicit request to continue |
| Install update | A foreground page claims the fully reverified result once | Rejects channel/candidate changes during verification; a permission handoff or verification interrupted by process recreation requires a user retry |

The page has a single main button that is itself the progress bar and never changes size or position: a sweep while checking, a fill from the start edge with percent and bytes while downloading, full while verifying, then "Install update". The main button is not clickable while busy. Channels remain selectable during checks and are locked during download, verification, and installation handoff. [UpdateActionPolicy](../../app/src/main/java/com/deepseekharness/app/util/UpdateActionPolicy.java) derives what it shows. Tablet landscape (≥ 840dp) uses two panes: the main card and management on the left, candidate / this-build notes on the right.

[UpdatePolicy](../../app/src/main/java/com/deepseekharness/app/util/UpdatePolicy.java) also validates flavor, minSdk, `arm64-v8a`, versionCode, allowed HTTPS source, 64-character hexadecimal SHA-256, and size up to 1 GiB. A saved candidate's checked channel is separate from the release's own channel; current preference cannot establish its origin.

Historical F10/F11 revisions sharing code190 are not selected as a newer version even with a later version name: candidates require `versionCode > BuildConfig.VERSION_CODE`. Use [README](../../README.en.md), the [release introduction](release-introduction.md) and the actual Release for public prerelease assets, attachment digests, and manual downloads. Attachment status requires the actual Release and independent external receipts. “No update” does not prove there are no remote prerelease attachments.

## Download, verification, and system installation

Allowed sources are dsha.cc download paths and GitHub Release APK paths under DSH-APP/DSHA. Redirects are checked at every hop, with restricted exceptions for GitHub asset CDNs. A domain allowlist does not trust every same-named package. [ResumableDownload](../../app/src/main/java/com/deepseekharness/app/util/ResumableDownload.java) checks actual bytes and SHA-256; installation checks them again, then verifies:

- The package matches the installed application and has `debuggable=false`.
- Actual versionCode matches the feed, is greater, and supports this Android version.
- versionName matches the current single Standard variant; Low was removed and Low candidates are rejected.
- The candidate's complete current signer set exactly matches the installed application, not just a subset.

Current release delivery uses `io.github.sunsetrne.dsha` and the Mod-owned certificate recorded in [release-identity.json](../../ci/release-identity.json). The implementation compares the actual installed signer set; a shortened fingerprint in documentation does not authorize installation. A digest establishes byte equality, and a matching signature establishes continuity of installation identity. Neither proves plugin code harmless. See [download verification](download-verification.md) for manual APK checks.

[UpdateDownloadService](../../app/src/main/java/com/deepseekharness/app/UpdateDownloadService.kt) can resume an already initiated task recorded as `downloading`. A `cancelled`, `paused`, or unknown state does not create a new task because the page opens, is recreated, or receives an old callback. If the APK was renamed before its `ready` receipt was saved, recovery first observes the existing APK instead of downloading the completed bytes again. File presence only provides an entry to verification before installation; it does not replace that verification.

Permission handoffs use unique, monotonically increasing 16-bit request codes. The new string `permission_request_code` in `dsha-updates` stores the local counter. A request is dispatched only after its code is saved; codes are not reused, and a corrupt counter, failed save, or exhaustion rejects a new request. Each one-time token binds the original release object and its SHA-256, channel, APK, and action revision. A matching request code is consumed before checking whether the original request remains valid. Cancellation, stale callbacks, empty or unknown results, and a token lost on process recreation cannot replay the action or transfer an old callback to a new candidate. A Bundle retains interruption markers for display, not executable tokens or instructions to reconstruct them.

On Android 13+, download still requests notification permission. A valid callback explicitly granting or denying notifications may continue the originally requested foreground-service (FGS) download once. An empty or unknown result does not start a download or request permission again. Notification permission is not installation permission.

On Android 8+, if this application lacks unknown-source installation permission, the page opens system settings with a dynamic request code through `startActivityForResult`. `onActivityResult` then checks `canRequestPackageInstalls()` directly. The settings page may return `RESULT_CANCELED`, so `RESULT_OK` is not required. Only a valid original request can continue verification before installation; `onResume` does not initiate another installation request. A token lost on process recreation is reported as an interruption and requires another user action. The system installer still requires user confirmation; installation is not silent.

**This candidate still dispatches installation through a FileProvider URI with `ACTION_VIEW`.** [UpdateActivity.dispatchInstall](../../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.kt) has neither historical build173 R09's PackageInstaller session fence nor a maintenance-stop call here. Before upgrading, normally stop DSH and terminals in the application and confirm completion. External ADB/file-manager upgrades likewise do not inherit managed-component stop proofs. Historical installer acceptance cannot replace installation evidence for this candidate.

Canceling system installation leaves the original application installed. Opening the system installer or dispatching an APK installation proves neither replacement nor runtime readiness. This M17 change does not alter M11's one automatic maintenance attempt per candidate or other maintenance gates. Check actual version and startup state afterward. A signature mismatch is not a reason to uninstall the original: uninstall/clear-data can lose private data, local keys, and copies. Export important data using [backup and restore](backup-and-restore.md) first.

## Managed updates inside Ubuntu

“Review update plan” opens environment maintenance, compares installed and bundled components, then runs [EnvironmentMaintenance](../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java) after confirmation. Within the same Ubuntu base, only managed components update; personal data stays in place, and UI-only APK changes should not rebuild the environment directly. An unsuitable base requires a data-preserving rebuild plan.

Managed commit first uses [MaintenanceCoordinator](../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java) to stop Web/terminals normally and enforce the exit barrier. It prepares a candidate, records digests/predecessor, and runs [RuntimeTrial](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java), checking actual port/authentication, sessions/storage, browser handshake, and exit before commit. Failure retains originals. Unknown exit cannot switch, clean, or replay. A compatibility rollback tries the predecessor with the current launcher and does not roll back conversation snapshots. See [installation and maintenance](install-and-maintenance.md).

Whether a patch-level update enters the maintenance page after an over-the-top install, how to keep a user entry when auto-start is blocked by an old Web session, and the pre-release checklist and device overwrite acceptance steps are in [over-the-top upgrades, runtime maintenance, and plugin dependency contracts](overwrite-upgrade-and-maintenance.md).

## Change and validation entry points

Selection/source/signature regressions start with [UpdatePolicyTest](../../app/src/test/java/com/deepseekharness/app/util/UpdatePolicyTest.java) and [UpdateRedirectsTest](../../app/src/test/java/com/deepseekharness/app/util/UpdateRedirectsTest.java); stop/transaction boundaries start with [MaintenanceGateTest](../../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java). The [A5 S2 candidate evidence](../design/kotlin-rust-rearchitecture-20261112/a5-s2-apk-kotlin-evidence-20261115.md) records the structural commit, behavior candidate, validation scope, and unrun checks separately. Structural validation cannot be attributed to the later behavior candidate. This page predicts no candidate verification outcome and does not claim revalidation of complete in-app install confirmation/cancellation, the remote feed, external replacement, or every Android version.

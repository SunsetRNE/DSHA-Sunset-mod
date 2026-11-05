# Overwrite install, runtime maintenance and stop-before-maintenance contract

> Verified 2026-11-05. Baselines: build324 (released), build325 (T61 mobile-shell gear fit), build326 (T60 display gate and old Web session fix), build327 (T67 maintenance performs the full stop itself).
> This English page mirrors [overwrite-upgrade-and-maintenance.md](overwrite-upgrade-and-maintenance.md); the Chinese page is authoritative if they differ. It records the invariants behind the overwrite-install incident (blank Web area, maintenance not starting, duplicated sidebar) and the later report that maintenance told the user to stop the Web by hand. It only covers source and host-side regressions; real-device results live in [docs/releases/](releases/).

## 30-second summary

1. **The decision layer was already correct.** `EnvironmentAccess.requiresMaintenanceBeforeStart` and `EnvironmentIdentity.shouldBlockRuntimeStart` return "maintenance required" for both 322→323 (dsh version change) and 323→324 (same dsh, managed patches only). Do not change those functions and do not blame them for the incident.
2. **The gap was the display layer.** `RuntimeInstallationState.isEnvironmentReady()` answers "can the old tree still run", not "is it the current candidate". Rendering the maintenance page from that answer alone demotes "Update runtime" and promotes "Enter main interface", then the entry gate sends the user back.
3. **Auto-start may be blocked silently.** If an old proot Web process survived the overwrite, `hasUnresolvedWebSession()` is true and `shouldAutoStart` returns false. That safety gate **must stay** (never replay an unknown instance automatically), but it must not remove the maintenance page.
4. **Blank area mechanism.** `WebLifecycleController` rejects starts while `!isEnvironmentReady() || requiresMaintenanceBeforeStart()`, so the watchdog cannot start the old runtime under the new APK. The Web area is blank; this is not a WebView failure.
5. **Built-in plugins must not depend on host control presence.** `dsh-web-mobile` used `:has(...)` against the host connection indicator. When the managed patch stopped rendering it, the plugin render chain broke. The fix measures available width and writes `data-mobile-nav-quick-fit` on the plugin's own element.
6. **The maintenance action must complete the full stop by itself** (build327). After the user taps "Update runtime", the maintenance task stops the old Web using the existing design (sentinel → PID record + birth identity), then takes the barrier and runs the update. If the ordinary stop is unconfirmed, it escalates exactly once to the identity-verified force stop. The user no longer has to detour through "limited main interface → Stop → back to maintenance". If the stop cannot be confirmed, the original environment is retained and the page shows an actionable retry message.

## Invariants

| # | Invariant | Checkpoint / regression |
|---|---|---|
| I1 | After an overwrite install, whenever the installed runtime is **not the current candidate** (`RuntimeDescriptor.latest` is false), maintenance is required even if the old tree is compatible and healthy and the dsh version is identical. | `OverwriteUpgradeGateTest` (T60 / build326) |
| I2 | `isEnvironmentReady()` only means "the environment can still run". The display layer must compute "runnable" and "current candidate" separately. | `RuntimeUpgradeState.upToDate(ready, latest)` |
| I3 | The user must see a full-screen maintenance page and a one-tap update. Auto-start may be blocked by an unconfirmed old Web, but the repair entry must never disappear. | `RuntimeUpgradeState.openMaintenancePage`; `OverwriteUpgradeGateTest.unresolvedOldWebStillOpensMaintenancePageButNeverAutoStarts` |
| I4 | An unconfirmed old Web session (`hasUnresolvedWebSession()`) **only blocks auto-start**; it must not block the maintenance page or a user-initiated maintenance. Unknown instances are never replayed automatically. | `EnvironmentIdentity.shouldAutoStart(..., unresolvedWebSession)` stays false for that input; `MainActivity` no longer requires `!unresolved` to open maintenance |
| I5 | Web start admission must reject "old tree + new APK" until maintenance completes or the user explicitly enters the limited main interface. The watchdog and the UI share the same decision. | `WebLifecycleController.requestStart` rejection; never loosen it to "fix" the blank area |
| I6 | A UI-only APK overwrite must not rebuild the environment; a same-dsh-version change of managed patches or descriptor inputs **is** a patch-level update (recompute descriptor, enter maintenance, reinstall the managed tree). | `RuntimeDescriptor.latest`; `OverwriteUpgradeGateTest.uiOnlyApkUpdateWithUnchangedManagedInputsNeverNeedsMaintenance` |
| I7 | Auto-start tries each candidate once. Failures, interruptions, rotation and process restarts never replay it. The limited entry writes no readiness marker. | `EnvironmentIdentity.shouldAutoStart`; `MaintenanceCoordinator.pending`; `ExtractActivity.proceed()` |
| I8 | Built-in plugins depend only on their own attributes and measured geometry, never on the presence of a host control (class names, `data-*`, `role`, `:has()`). Host UI changes must not break the plugin render chain. | `tools/test-mobile-gear-contract.mjs` (T61 / build325) |
| I9 | After the user explicitly confirms maintenance, the maintenance action **performs the full stop itself** before taking the barrier and running the update. If the ordinary stop is unconfirmed, it may escalate exactly once to an **identity-verified** force stop (PID, birth time, command line, generation; never port/name/process-group). If verification fails or the process still cannot be confirmed exited, the original environment is retained and the failure message offers a retry. An unconfirmed old Web still only blocks auto-start. | `MaintenancePreStop` (`core/`) and `WebProofStop` (`util/`); `MaintenancePreStopTest`, `WebProofStopTest`, `WebRecordedStopTest`, `WebForceStopTest`, `WebSessionProofTest` (T67 / build327) |

## Mechanism layers

### 1. Decision layer: UI entry and Web start share one identity

- `EnvironmentAccess.requiresMaintenanceBeforeStart` first checks `DshVersionPolicy` (installed newer → maintenance) and returns true; then compares `runtimeLatest`; otherwise it calls `EnvironmentIdentity.shouldBlockRuntimeStart` with the `candidateAttemptKey` (`runtime:<runtimeId>@<APK lastUpdateTime>`).
- `shouldBlockRuntimeStart` semantics: `latest` → allowed; unreadable descriptor → blocked; otherwise `shouldAutoStart(false,false,false,candidate,attempted)` — a new candidate that has not been attempted needs maintenance. `busy`/`pending` decide whether maintenance can run now; they never turn "new candidate" into "nothing to do".
- Do not change these functions to save the user a page transition.

### 2. Display layer: a patch-level update is also "not done"

- `isEnvironmentReady()` = installed + rootfs compatibility + health receipt. It does not compare `runtimeId` / `inputs`.
- `RuntimeUpgradeState` adds the second dimension: `upToDate(ready, latest)`, `maintenanceRequired`, `limitedEntry`, `openMaintenancePage`.
- `ExtractActivity` therefore treats "ready" as `environmentReady && runtimeLatest`, keeps the patch-level warning, and records limited entry correctly.
- Entry, maintenance page, main-interface banner and Web start admission must all derive from `EnvironmentAccess` / `RuntimeUpgradeState`; any site that substitutes `isEnvironmentReady()` for "done" re-creates the incident.

### 3. Auto-start safety gate: block automatic, not the entry

- An overwrite install changes `StoredWebSession.appIdentity()` (it includes the APK `lastUpdateTime`). A surviving old proot Web process therefore looks `LIVE` to the new package but incompatible.
- `EnvironmentIdentity.shouldAutoStart(...)` includes `!unresolvedWebSession`; that is correct and must stay.
- The product behavior is: keep showing the full-screen maintenance page, explain that the installed runtime is the previous one, provide "Update runtime" as the primary action, and keep the limited main interface available for logs/settings.
- This gate only decides whether to start **automatically**. A user tap is not blocked by it; the maintenance action then performs the full stop itself (next section).

### 4. Web start admission: the real reason for the blank area

`WebLifecycleController.requestStart` rejects when `!isEnvironmentReady() || requiresMaintenanceBeforeStart`. With an old tree + new APK the watchdog cannot start the Web, so the area is blank. Do not change the WebView, clear caches, reinstall the APK, or loosen this gate.

### 5. Stop before maintenance: the maintenance action does the full stop

After the user taps "Update runtime":

1. `RuntimeMaintenanceStarter.showRetryDialog` (after the confirmation dialog) calls `BackupTask.updateEnvironment(true)`, `rebuild()` or `recoverMaintenance()`. Only user-confirmed actions pass `allowForceStop=true`.
2. `MaintenanceCoordinator.exclusiveUserConfirmed` closes terminals and runs `MaintenancePreStop`:
   - request the ordinary stop (the stop queue writes the sentinel first, then verifies the PID record and birth identity; only the same verified process is signalled);
   - wait for the existing exit proof from `WebProcessManager` / `WebProcessSession` (guest **and** launcher);
   - if the ordinary stop is unconfirmed, call `HarnessController.prepareForceStop()` and `forceStop` exactly once — the candidate is verified by PID, birth time, command line, UID and generation;
   - if the process still cannot be confirmed exited, fail with a message that states the original environment was not changed and asks the user to retry "Update runtime".
3. **Residual process after an overwrite install.** The app may have been killed while the guest kept running, and `/root/.dsha-web.pid` / `.dsha-web.identity` may be missing. The session record already persists the birth identity (`StoredWebSession.Record.proof`: boot, generation, instance, nonce, guest and launcher PID + birth time + command line). `WebProofStop` uses it as a fallback: it signals only after two full identity checks agree, SIGTERM first, and (when force is allowed) a second verification before SIGKILL. PID reuse, changed birth time/command line, or an unreadable record means **no signal at all**. The container launcher is never signalled.
4. **Automatic paths never force.** Auto-start and post-upgrade cleanup still call `MaintenanceCoordinator.exclusive` without `allowForceStop`. The unconfirmed old Web only blocks auto-start (I4).
5. The three-step detour is obsolete: "limited main interface → Stop → back to maintenance" is no longer required to finish maintenance. The manual Stop button remains a manual stop/force-stop entry, not a mandatory step.

## Built-in plugin dependency ban

- `dsh-web-mobile` must not use host-node presence (`:has(> button[data-phase], > [role="status"])`) to decide its layout.
- It measures `getBoundingClientRect()` / `getComputedStyle()` and stores the result in its own `data-mobile-nav-quick-fit` attribute; CSS only reads that attribute.
- When geometry is not ready, degrade conservatively (hide the gear) instead of assuming a host control exists.
- Each such contract needs a Node test against the real artifact and a minimal DOM (`tools/test-mobile-gear-contract.mjs`).

## Pre-release checklist

```bash
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/verify-runtime-descriptor-source.py
python3 tools/generate-third-party-notices.py --check
python3 tools/check-third-party-notices.py
python3 tools/prepare-runtime-tools.py --check
python3 tools/generate-builtin-plugins.py --verify
python3 tools/generate-credential-paths.py --check
python3 tools/prepare-semver-reference.py
python3 tools/run-host-tests.py --check-manifest
python3 tools/check-source-hygiene.py
python3 tools/check-ui-i18n.py
python3 tools/generate-changelog.py --check
python3 tools/check-document-links.py
node tools/test-mobile-gear-contract.mjs
dsha-gradle :app:testStandardDebugUnitTest --tests '*OverwriteUpgradeGateTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*RuntimeUpgradeStateTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*MaintenancePreStopTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*WebProofStopTest'
```

- Any change to a fixed build input (offline rootfs, dsh runtime, Ubuntu tools, ...) requires repacking/uploading the fixed-input assets and updating the lock; UI-only APK changes do not.
- Report only commands actually executed; never describe host-side tests as real-device results.

## Device acceptance (overwrite install)

1. Prepare the previous release on an independent device, complete onboarding, start the Web once and confirm health.
2. Overwrite-install the new build **without** stopping the Web first. Check: full-screen maintenance page; "Update runtime" is the primary action; auto-start may be blocked by the unconfirmed old Web (allowed); tapping "Update runtime" makes the maintenance task stop the old Web **by itself** and continue (no detour through the limited main interface).
3. After maintenance, the Web starts, plugins load, the sidebar has one settings entry, and the diagnostic page shows the new `runtimeId`.
4. If stopping fails, the page must show the reason and the "Retry runtime update" action; the original environment must be unchanged.
5. Cold overwrite (stop DSH and terminals first) starts maintenance automatically once and succeeds.
6. Record: previous → current build, install method, whether the old Web was alive, the page shown, whether auto-start was blocked, whether the tap succeeded, Web/plugin results, and `runtimeId` before/after. Do not commit device screenshots or logs.

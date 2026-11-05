# Mandatory review process for external PRs and external merges

> Checked on 2026-11-05. Baseline: build325 (`f5c63d1a`). This process is an explicit user requirement. It applies to **every pull request from an external contributor** and **every merge request that comes from outside** (fork PRs, patches, external branch merges / rebases / cherry-picks, and the generated files they change). It is part of the release gate and cannot be replaced by green CI, an author's statement, or "looks fine".

## Hard rules

1. **A dedicated review session**: every external PR or external merge must be reviewed **line by line, over the entire diff**, by a **dedicated AI review session**. Not sampling, not CI-only, not a change-summary read.
2. **Exactly two outcomes**: **APPROVE** (only then may it be merged) or **REQUEST_CHANGES** (sent back). There is no "conditional pass" or "merge now, fix later".
3. **Green CI does not mean mergeable**: CI proves only that the checks that ran passed; it does not prove security boundaries, logical completeness, test coverage, or repository invariants.
4. **A conflict does not waive content review**: `CONFLICTING` must first be rebased (in the review session's own temporary worktree), then the review session must **re-review the generated files and affected diff**. "Conflict resolved" is not a pass.
5. **The review session does not merge or push the author's branch**: it only reads, runs checks, and submits review comments. Merging still requires **explicit user approval in the main session**, executed by the main session.
6. **Re-review after fixes**: after each author push, the review session must re-read the added diff and affected context; it may not reuse an old APPROVE after reviewing only the increment.

## Process

1. **Pin the review target**: record the PR number, head sha, base sha, commit range, and author; verify the base is the current `origin/main`. If it is behind, ask the author to rebase, or rebase in the review session's temporary worktree and re-review **all generated-file differences** (never push the author's branch).
2. **Obtain the complete diff**: `git diff <base>...<head>`, including renames, deletions, binaries, and generated files. Do not rely on GitHub's collapsed view or `--stat`. Under local proroot, `git show/diff --stat` and `--numstat` exit 139 with empty stdout (`[proroot] SIGSEGV`) for some commits or ≥8-path sets; check rc/stderr, never swallow the rc with `| head`, and use `--name-status` / `--dirstat` / ≤7-path chunks or `python3 tools/git_guard.py -- <git args>`; see [runtime engines](runtime-engines.md).
3. **Review line by line**: for every change state "what changed, why, where the boundary is, what happens on failure". Generated files (`runtime-descriptor.json`, `*.inputs.json`, `managed-package-proofs.json`, `THIRD_PARTY_NOTICES.md`, `ci/sunset-inputs.lock.json`, `tools/host-tests.manifest.json`, `CHANGELOG.md`, i18n, `docs/**` indexes) must be **recomputed with the repository scripts and compared**; hand-edited results are not acceptable.
4. **Run the gates**: at minimum `python3 tools/run-host-tests.py --check-manifest`, `python3 tools/check-source-hygiene.py`, `python3 tools/generate-changelog.py --check`, and `python3 tools/check-document-links.py`; when Java changes, add `python3 tools/format-java.py --check --files changed` and targeted unit tests; when managed patches or built-in plugins change, run the full pre-release checklist in [the over-the-top upgrade and maintenance contract](overwrite-upgrade-and-maintenance.md). Gate results are review input, never a substitute for line-by-line judgment.
5. **Check the template below**, locating each issue at `file:line`.
6. **State the outcome**: APPROVE or REQUEST_CHANGES. REQUEST_CHANGES comments must be directly actionable; APPROVE must still state the reviewed scope and unverified items.
7. **Merge**: only after explicit user approval in the main session. The review session states in its review that it did **not** merge and did **not** push the author's branch.

## Review checklist template

| Dimension | What to check |
|---|---|
| Security boundaries | Credentials / tokens / keys entering the repository, logs, or backups; path escapes (symlinks, `..`, absolute paths); device commands bypassing `DeviceShellPolicy`; permissions least-privilege and revocable; network origins, redirects, and TLS narrowed; signatures and package identity not weakened |
| Bridge contract | 3090 endpoints, authentication, `BRIDGE_PROTOCOL` feature detection, parameter parsing (per-name matching; empty ≠ absent), request/response bounds, LAN and ADB boundaries |
| Resources and concurrency | Lock ordering, threads / coroutines, process and FD leaks, exit barriers, `RuntimeTasks` / maintenance locks, timeout misread as exit, duplicate submission on rotation / recreation |
| Error handling and degradation | Originals retained on failure; UNKNOWN not treated as success; unknown actions not replayed; degradation does not exceed authority; failure text not misleading |
| i18n and UI | Text through `messages.json` / `UiText`; Chinese and English aligned; length limits; no hard-coded UI literals; scrollable on short screens / large fonts / landscape |
| Test coverage | New behavior has unit or contract tests; failure and negative controls covered; tests registered in `tools/host-tests.manifest.json`; real artifacts exercised rather than hand-written functions only |
| Repository invariants | Does it touch the locked invariants in [AGENTS.en.md](../../AGENTS.en.md); does it break over-the-top upgrade / maintenance gates / stop and birth identity / backup scope / documentation links |
| Generated files and locks | `runtime-descriptor`, inputs, proofs, notices, fixed-input locks, test manifest, CHANGELOG recomputed by scripts and mutually consistent; fixed-input changes repacked / uploaded / locked per process |
| Documentation consistency | Indexes and focused pages updated; version identity matches `app/build.gradle` / `ci/release-identity.json`; historical records not rewritten; both Chinese and English written |
| Build and release | Package, signing, `versionCode`, minSdk, arm64, flavor preserved; no debug packages / uninstall-reinstall / data clearing; Release assets and digests match |
| Scope and boundaries | No unrelated changes, formatting noise, historical-original rewrites, hand-edited generated files, or mixed commits that should be split; no test packages, local paths, or private material in the repository |

## What a REQUEST_CHANGES review must contain

Each comment must state:

- **Location**: `file:line` (precise to line or function; list every location for cross-file issues).
- **Problem**: facts and impact (which failure it triggers, which authority it crosses, what data it loses, which invariant it breaks).
- **Fix**: the smallest actionable fix; for generated files, the recompute command and expected digest / diff.
- **Severity**:
  - **P0**: blocks the merge. Security issues, data loss / corruption, failure to start, broken credential or signing boundaries, unknown actions replayed automatically.
  - **P1**: must be fixed before merge. Missing required logic, boundary errors, new behavior without tests, inconsistent generated files, broken repository invariants.
  - **P2**: should be fixed. Mergeable but must be recorded or followed up; never silently dropped.
- **Verification**: the gate command or the test / device item to add; unverified scope explicitly marked.

## Minimum bar for APPROVE

- The entire diff (including generated files, binaries, renames, and deletions) has been reviewed line by line, with the reviewed scope stated in the conclusion.
- No unresolved P0 / P1; P2 items are recorded and explicitly accepted by the user or main session.
- The contracts group, Java format (when Java changed), and targeted unit tests pass; when managed patches or built-in plugins change, the pre-release checklist is complete.
- Device / external-service / multi-device items that were not verified are marked "not verified", never as passed.
- The review states that the review session **did not merge the PR and did not push the author's branch**; merging still requires explicit user approval in the main session.

## Explicitly forbidden

- Replacing line-by-line review with "green CI", "the author tested it", or "the change is small".
- Sampling, or reviewing only added files while skipping modified existing logic.
- The review session merging by itself, pushing the author's branch, force-pushing, closing the PR, or rewriting author commits.
- Resetting the branch on conflict or ignoring auto-merged generated-file differences.
- Treating an external PR as reviewed because it was merged; the merge action cannot retroactively prove that review happened.

## Related documents

- [Over-the-top upgrades, runtime maintenance, and plugin dependency contracts](overwrite-upgrade-and-maintenance.md) (pre-release checklist, device acceptance, managed patch / built-in plugin changes)
- [Contributing](../../CONTRIBUTING.en.md), [engineering standard](engineering-standard.md), [security model](security-model.md)
- [CI build and release (Chinese)](../ci-release.md), [fixed build inputs](fixed-build-inputs.md)
- Known external PRs (PR #3 SAF, PR #5 landscape Dock) must follow this process; the main session assigns the review sessions, and this page defines the process rather than session semantics.

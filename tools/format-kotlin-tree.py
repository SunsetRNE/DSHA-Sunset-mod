#!/usr/bin/env python3
"""Run the pinned ktfmt formatter over every owned Kotlin source file in package batches.

Difference from the Java gate: Kotlin starts as a greenfield tree, so there is no
adoption manifest. Every `.kt` / `.kts` under the five owned app source sets is gated
from the first file; the Java tree keeps its explicit legacy adoption list instead.
"""

import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SETS = ("main", "standard", "debug", "androidTest", "test")
SOURCE_ROOTS = ("java", "kotlin")
SUFFIXES = (".kt", ".kts")
LOCK = ROOT / "tools/ktfmt.lock.json"
FORMATTER = ROOT / "tools/format-kotlin.py"
REPORT_DIR = ROOT / "app/build/kotlin-format"
# argparse 的 nargs='+' 在超长文件列表下会静默截断（Java 门禁实测 250+ 触发）；批次保持足够小。
MAX_BATCH = 128
EXCLUDED_PARTS = frozenset(("history", "upstream", "vendor", "third_party", "third-party", "generated"))


class TreeFormatError(Exception):
    pass


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root=ROOT, protected=(), generated=()):
    """Complete owned Kotlin inventory; missing source roots are normal, unlike the Java gate."""
    protected = set(protected)
    generated = set(generated)
    result = []
    for source_set in SETS:
        for source_root in SOURCE_ROOTS:
            directory = root / "app/src" / source_set / source_root
            if not directory.is_dir():
                continue
            for path in sorted(set(directory.rglob("*.kt")) | set(directory.rglob("*.kts"))):
                relative = path.relative_to(root).as_posix()
                if EXCLUDED_PARTS.intersection(path.relative_to(directory).parts):
                    continue
                if relative in protected or relative in generated:
                    continue
                if path.is_symlink() or not path.is_file():
                    raise TreeFormatError("FORMAT_SOURCE_LINK_OR_NONFILE: " + relative)
                result.append(relative)
    return sorted(result)


def batch_key(name):
    parts = Path(name).parts
    if len(parts) < 5 or parts[:2] != ("app", "src") or parts[2] not in SETS or parts[3] not in SOURCE_ROOTS:
        raise TreeFormatError("FORMAT_UNEXPECTED_SOURCE: " + name)
    if Path(name).suffix not in SUFFIXES:
        raise TreeFormatError("FORMAT_UNEXPECTED_SOURCE: " + name)
    inner = parts[4:-1]
    if inner[:3] == ("com", "deepseekharness", "app"):
        package = inner[3] if len(inner) > 3 else "(root)"
    else:
        package = "/".join(inner[:2]) if inner else "(root)"
    return (parts[2], package)


def package_batches(names):
    groups = defaultdict(list)
    for name in names:
        groups[batch_key(name)].append(name)
    batches = []
    for key in sorted(groups):
        group = sorted(groups[key])
        for offset in range(0, len(group), MAX_BATCH):
            batches.append((key, group[offset:offset + MAX_BATCH]))
    return batches


def run_formatter(mode, names, number, java, offline, phase):
    report = REPORT_DIR / f"tree-{phase}-{number:02d}.json"
    command = [sys.executable, str(FORMATTER), mode, "--files", *names, "--report", str(report)]
    if java:
        command += ["--java", java]
    if offline:
        command.append("--offline")
    completed = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    if completed.returncode not in ((0, 1) if mode == "--check" else (0,)):
        raise TreeFormatError(
            f"FORMAT_BATCH_FAILED {number}: {completed.stderr.strip()}\n{completed.stdout[-2000:]}")
    evidence = json.loads(report.read_text(encoding="utf-8"))
    if [entry["file"] for entry in evidence["files"]] != names:
        raise TreeFormatError("FORMAT_BATCH_REPORT_MISMATCH")
    return evidence


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--plan", action="store_true")
    modes.add_argument("--check", action="store_true")
    modes.add_argument("--write", action="store_true")
    parser.add_argument("--java", help="JDK 17 java executable")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--jobs", type=int, default=min(4, os.cpu_count() or 1),
                        help="parallel --check batches (each batch is read-only until --write)")
    args = parser.parse_args(argv)
    if not 1 <= args.jobs <= 16:
        parser.error("--jobs must be 1..16")
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    if lock.get("schema") != 1 or lock.get("tool") != "ktfmt":
        raise TreeFormatError("FORMAT_LOCK_SCHEMA")
    protected = lock.get("protectedFiles", [])
    generated = lock.get("generatedFiles", [])
    if not isinstance(protected, list) or not isinstance(generated, list):
        raise TreeFormatError("FORMAT_LOCK_LISTS")
    expected = inventory(protected=protected, generated=generated)
    batches = package_batches(expected)
    print(f"owned Kotlin: {len(expected)}; batches: {len(batches)}")
    for key, names in batches:
        print(f"{key[0]}/{key[1]}: {len(names)}")
    if args.plan:
        return 0
    if not batches and not expected:
        print("ktfmt: no owned Kotlin sources yet")
        return 0
    if any(len(names) > MAX_BATCH for _, names in batches):
        raise TreeFormatError("FORMAT_BATCH_SIZE")
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    snapshot = {name: digest(ROOT / name) for name in expected}
    # Preflight batches only read sources and write their own numbered report, so they run in
    # parallel; results keep batch order. --write batches below stay strictly sequential.
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        checks = list(pool.map(
            lambda item: run_formatter("--check", item[1][1], item[0], args.java, args.offline, "preflight"),
            enumerate(batches, 1)))
    if args.write:
        if any(digest(ROOT / name) != value for name, value in snapshot.items()):
            raise TreeFormatError("FORMAT_SOURCE_CHANGED_DURING_PREFLIGHT")
        reports = []
        for n, (_, names) in enumerate(batches, 1):
            if any(digest(ROOT / name) != snapshot[name] for name in names):
                raise TreeFormatError("FORMAT_SOURCE_CHANGED_BEFORE_WRITE")
            reports.append(run_formatter("--write", names, n, args.java, args.offline, "write"))
        # The fixed formatter's post-write SHA is authoritative; --check must then be clean.
        for evidence in reports:
            for entry in evidence["files"]:
                if digest(ROOT / entry["file"]) != entry["formattedSha256"]:
                    raise TreeFormatError("FORMAT_WRITTEN_BYTES_MISMATCH: " + entry["file"])
    else:
        reports = checks
    changed = sum(entry["needsFormatting"] for report in reports for entry in report["files"])
    summary = {
        "schema": 1, "mode": "write" if args.write else "check",
        "tool": lock["tool"], "version": lock["version"], "artifactSha256": lock["sha256"],
        "style": lock["style"],
        "files": len(expected), "batches": len(batches), "needsFormatting": changed,
        "protectedExcluded": protected,
        "reports": [f"tree-{'write' if args.write else 'preflight'}-{n:02d}.json"
                    for n in range(1, len(batches) + 1)],
    }
    (REPORT_DIR / "tree-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"pinned Kotlin formatter: {len(expected)} files, {changed} need formatting")
    return 1 if args.check and changed else 0


if __name__ == "__main__":
    if sys.flags.optimize:
        raise SystemExit("FORMAT_OPTIMIZATION_FORBIDDEN")
    try:
        raise SystemExit(main())
    except TreeFormatError as error:
        print(error, file=sys.stderr)
        raise SystemExit(2)

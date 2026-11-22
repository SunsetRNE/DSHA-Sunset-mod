#!/usr/bin/env python3
"""Run the pinned formatter over an explicit, complete inventory in package batches."""

import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from source_layout import SourceLayout, PACKAGE

ROOT = Path(__file__).resolve().parents[1]
SETS = ("main", "standard", "debug", "androidTest", "test")
SOURCE = "java/com/deepseekharness/app"
MANIFEST = ROOT / "tools/java-format-files.json"
LOCK = ROOT / "tools/java-format.lock.json"
FORMATTER = ROOT / "tools/format-java.py"
REPORT_DIR = ROOT / "app/build/java-format"
# argparse 的 nargs='+' 在超长文件列表下会静默截断（实测 250+ 触发）；批次保持足够小。
MAX_BATCH = 128
TOOL_SOURCE = "tools/java-format/JavaTokenFingerprint.java"
EXCLUDED_PARTS = frozenset(("history", "upstream", "vendor", "third_party", "third-party", "generated"))
GENERATED_OWNERS = {
    "app/src/main/java/com/deepseekharness/app/util/BuiltinPluginRegistry.java":
        "tools/generate-builtin-plugins.py",
    "app/src/main/java/com/deepseekharness/app/util/CredentialPathRules.java":
        "tools/generate-credential-paths.py",
}


class TreeFormatError(Exception):
    pass


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root=ROOT, protected=(), generated=()):
    protected = set(protected)
    generated = set(generated)
    result = []
    layout = SourceLayout(root)
    for module in layout.modules:
        for source_set in module['formatSourceSets']:
            roots = [root / module['directory'] / 'src' / source_set / language / PACKAGE
                     for language in ('java', 'kotlin')]
            if not any(directory.is_dir() for directory in roots):
                raise TreeFormatError('FORMAT_SOURCE_SET_MISSING: ' + str(roots[0]))
    for entry in layout.format_files(('.java',)):
        path, relative = entry.path, entry.relative
        if not entry.logical_path.startswith(PACKAGE + '/'):
            continue
        if EXCLUDED_PARTS.intersection(Path(entry.logical_path).parts):
            continue
        if relative in protected or relative in generated:
            continue
        result.append(relative)
    if TOOL_SOURCE not in protected:
        if not (root / TOOL_SOURCE).is_file():
            raise TreeFormatError("FORMAT_TOOL_SOURCE_MISSING")
        result.append(TOOL_SOURCE)
    return sorted(result)


def declared_files(manifest):
    data = json.loads(manifest.read_text(encoding="utf-8"))
    files = data.get("files")
    if data.get("schema") != 1 or not isinstance(files, list) or any(
        not isinstance(name, str) for name in files
    ):
        raise TreeFormatError("FORMAT_MANIFEST_SCHEMA")
    if len(files) != len(set(files)):
        raise TreeFormatError("FORMAT_MANIFEST_DUPLICATE")
    return files


def batch_key(name):
    if name == TOOL_SOURCE:
        return ("tools", "java-format")
    entry = SourceLayout(ROOT).classify(name)
    if not entry.logical_path.startswith(PACKAGE + '/') or Path(name).suffix != '.java':
        raise TreeFormatError('FORMAT_UNEXPECTED_PACKAGE: ' + name)
    inner = entry.logical_path[len(PACKAGE) + 1:].split('/')
    source_set = entry.source_set if entry.module == ':app' else entry.module + '/' + entry.source_set
    return (source_set, inner[0] if len(inner) > 1 else '(root)')


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
    command = [sys.executable, str(FORMATTER), mode, "--files", *names,
               "--report", str(report)]
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
    generated = lock.get("generatedFiles", [])
    if set(generated) != set(GENERATED_OWNERS):
        raise TreeFormatError("FORMAT_GENERATED_OWNER_REGISTRY_MISMATCH")
    for name, owner in GENERATED_OWNERS.items():
        if not (ROOT / name).is_file() or not (ROOT / owner).is_file():
            raise TreeFormatError("FORMAT_GENERATED_OWNER_MISSING: " + name)
    expected = inventory(protected=lock["protectedFiles"], generated=generated)
    declared = declared_files(MANIFEST)
    missing = sorted(set(expected) - set(declared))
    extra = sorted(set(declared) - set(expected))
    selected = expected if args.plan else declared
    batches = package_batches(selected)
    print(f"owned Java: {len(expected)}; manifest: {len(declared)}; "
          f"missing: {len(missing)}; extra: {len(extra)}; batches: {len(batches)}")
    print("generator-owned Java excluded: " + ", ".join(sorted(generated)))
    for key, names in batches:
        print(f"{key[0]}/{key[1]}: {len(names)}")
    if args.plan:
        return 0
    if missing or extra:
        raise TreeFormatError("FORMAT_MANIFEST_INCOMPLETE: update the explicit adoption list")
    if not batches or any(len(names) > MAX_BATCH for _, names in batches):
        raise TreeFormatError("FORMAT_BATCH_SIZE")
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    snapshot = {name: digest(ROOT / name) for name in selected}
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
        # The fixed formatter's post-write SHA and exact javac token digest are authoritative.
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
        "files": len(selected), "batches": len(batches), "needsFormatting": changed,
        "protectedExcluded": lock["protectedFiles"],
        "reports": [f"tree-{'write' if args.write else 'preflight'}-{n:02d}.json"
                    for n in range(1, len(batches) + 1)],
    }
    (REPORT_DIR / "tree-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"pinned Java formatter: {len(selected)} files, {changed} need formatting")
    return 1 if args.check and changed else 0


if __name__ == "__main__":
    if sys.flags.optimize:
        raise SystemExit("FORMAT_OPTIMIZATION_FORBIDDEN")
    try:
        raise SystemExit(main())
    except TreeFormatError as error:
        print(error, file=sys.stderr)
        raise SystemExit(2)

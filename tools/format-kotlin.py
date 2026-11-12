"""Pinned ktfmt formatting for explicit Kotlin files; history and upstream bytes stay out."""
import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from git_guard import git_output

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "tools/ktfmt.lock.json"
CACHE = ROOT / "app/build/kotlin-format"
SETS = ("main", "standard", "debug", "androidTest", "test")
SOURCE_ROOTS = ("java", "kotlin")
ALLOWED_SUFFIXES = (".kt", ".kts")


class FormatError(Exception):
    pass


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def checked_path(name, root=ROOT, protected=()):
    """Validate spelling as well as resolution, including every symlinked ancestor."""
    root = root.resolve()
    candidate = Path(name)
    if not candidate.is_absolute():
        candidate = root / candidate
    if ".." in candidate.parts:
        raise FormatError("FORMAT_PATH_TRAVERSAL: " + str(name))
    try:
        relative = candidate.relative_to(root)
        resolved = candidate.resolve(strict=True)
        resolved.relative_to(root)
    except (ValueError, OSError) as error:
        raise FormatError("FORMAT_PATH_OUTSIDE_OR_MISSING: " + str(name)) from error
    for parent in [candidate, *candidate.parents]:
        if parent == root:
            break
        if parent.is_symlink() or (hasattr(parent, "is_junction") and parent.is_junction()):
            raise FormatError("FORMAT_LINK_REFUSED: " + str(name))
    if resolved != candidate.absolute():
        raise FormatError("FORMAT_REDIRECTED_PATH_REFUSED: " + str(name))
    spelling = relative.as_posix()
    parts = relative.parts
    own_app = (
        len(parts) >= 5 and parts[:2] == ("app", "src")
        and parts[2] in SETS and parts[3] in SOURCE_ROOTS
    )
    forbidden = {"history", "upstream", "vendor", "third_party", "third-party", "generated"}
    if (not own_app or forbidden.intersection(parts)
            or spelling.casefold() in {item.casefold() for item in protected}
            or candidate.suffix not in ALLOWED_SUFFIXES or not candidate.is_file()):
        raise FormatError("FORMAT_UNOWNED_OR_PROTECTED: " + str(name))
    return candidate, spelling


def changed_files(root=ROOT):
    """Tracked staged/unstaged changes and new unignored files, never a source-tree scan."""
    tracked = git_output(
        ["diff", "--name-only", "-z", "--diff-filter=ACMRT", "HEAD", "--", "*.kt", "*.kts"],
        cwd=root,
    )
    untracked = git_output(
        ["ls-files", "--others", "--exclude-standard", "-z", "--", "*.kt", "*.kts"], cwd=root
    )
    return sorted(set(item for item in (tracked + untracked).decode("utf-8").split("\0") if item))


def select_files(names, lock):
    if names == ["changed"]:
        names = changed_files()
    elif "changed" in names:
        raise FormatError("FORMAT_SELECTOR_CANNOT_BE_MIXED")
    if len(names) > 256:
        raise FormatError("FORMAT_TOO_MANY_FILES: choose an explicit package batch, at most 256")
    result = []
    seen = set()
    protected = lock.get("protectedFiles", []) + lock.get("generatedFiles", [])
    for name in names:
        if not isinstance(name, str):
            raise FormatError("FORMAT_FILE_MUST_BE_STRING")
        path, relative = checked_path(name, protected=protected)
        if relative in seen:
            raise FormatError("FORMAT_DUPLICATE_FILE: " + relative)
        result.append((path, relative))
        seen.add(relative)
    return result


def java_command(explicit, lock):
    selected = explicit or os.environ.get("DSHA_JAVA17")
    if not selected and os.environ.get("JAVA_HOME"):
        selected = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
    selected = selected or shutil.which("java")
    if not selected:
        raise FormatError("FORMAT_JDK17_MISSING: set JAVA_HOME, DSHA_JAVA17, or --java")
    executable = Path(selected).resolve(strict=True)
    version = subprocess.run([str(executable), "-version"], capture_output=True, timeout=30)
    text = (version.stdout + version.stderr).decode("utf-8", errors="replace")
    match = re.search(r'version "(\d+)', text)
    if version.returncode or not match or int(match[1]) != lock["javaMajor"]:
        raise FormatError("FORMAT_REQUIRES_JDK17: " + text.strip())
    return executable, text.strip()


def artifact(lock, offline):
    CACHE.mkdir(parents=True, exist_ok=True)
    jar = CACHE / lock["artifact"]
    if jar.exists():
        data = jar.read_bytes()
        if len(data) != lock["size"] or sha256(data) != lock["sha256"]:
            raise FormatError("FORMAT_CACHE_DIGEST_MISMATCH: " + str(jar))
        return jar
    if offline:
        raise FormatError("FORMAT_OFFLINE_CACHE_MISSING: " + str(jar))
    request = urllib.request.Request(
        lock["artifactUrl"], headers={"User-Agent": "dsha-format-kotlin/1"}
    )
    with urllib.request.urlopen(request, timeout=300) as response:
        data = response.read(lock["size"] + 1)
    if len(data) != lock["size"] or sha256(data) != lock["sha256"]:
        raise FormatError("FORMAT_DOWNLOAD_DIGEST_MISMATCH")
    atomic_write(jar, data)
    return jar


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = path.stat().st_mode if path.exists() else None
    descriptor, temporary = tempfile.mkstemp(prefix=".format-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        if mode is not None:
            os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def ktfmt_command(java, jar, lock, paths):
    return [str(java), "-Dfile.encoding=UTF-8", "-jar", str(jar), *lock["flags"], *paths]


def format_one(java, jar, lock, relative, before):
    """Stdin mode: the reference behavior, also used to localize batch failures."""
    output = subprocess.run(
        ktfmt_command(java, jar, lock, ["--stdin-name=" + relative, "-"]),
        input=before,
        capture_output=True,
        timeout=60,
    )
    if output.returncode:
        raise FormatError(
            "FORMAT_PARSE_FAILED: " + relative + "\n"
            + output.stderr.decode("utf-8", errors="replace")
        )
    return output.stdout


def format_batch(java, jar, lock, selected, befores, work):
    """One formatter JVM for the whole batch, on private copies; sources are never touched here."""
    copies = []
    for (_, relative), before in zip(selected, befores):
        copy = work / "formatted" / relative
        copy.parent.mkdir(parents=True, exist_ok=True)
        copy.write_bytes(before)
        copies.append(copy)
    result = subprocess.run(
        ktfmt_command(java, jar, lock, [str(copy) for copy in copies]),
        capture_output=True,
        timeout=60 + 2 * len(copies),
    )
    if result.returncode:
        # 逐文件重跑以给出与单文件模式相同的定位信息。
        for (_, relative), before in zip(selected, befores):
            format_one(java, jar, lock, relative, before)
        raise FormatError("FORMAT_BATCH_FAILED\n" + result.stderr.decode("utf-8", errors="replace"))
    return [copy.read_bytes() for copy in copies], copies


def verify_idempotent(java, jar, lock, selected, afters, work):
    """The pinned formatter must be stable on its own output before any source is written."""
    stable = work / "stable"
    paths = []
    for (_, relative), after in zip(selected, afters):
        path = stable / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(after)
        paths.append(path)
    result = subprocess.run(
        ktfmt_command(java, jar, lock, [str(path) for path in paths]),
        capture_output=True,
        timeout=60 + 2 * len(paths),
    )
    if result.returncode:
        raise FormatError("FORMAT_IDEMPOTENCE_RUN_FAILED\n" + result.stderr.decode("utf-8", errors="replace"))
    for number, (path, after) in enumerate(zip(paths, afters)):
        if path.read_bytes() != after:
            raise FormatError(f"FORMAT_NOT_IDEMPOTENT: batch entry {number}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="fail with exit 1 if any selected file needs formatting")
    mode.add_argument("--write", action="store_true", help="write only the explicitly selected files after validation")
    parser.add_argument("--files", nargs="+", required=True, metavar="PATH|changed")
    parser.add_argument("--java", help="JDK 17 java executable (otherwise DSHA_JAVA17/JAVA_HOME/PATH)")
    parser.add_argument("--offline", action="store_true", help="require the verified app/build cache; never download")
    parser.add_argument("--report", help="write SHA/diff evidence beneath app/build/kotlin-format")
    args = parser.parse_args(argv)
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if (lock.get("schema") != 1 or lock.get("tool") != "ktfmt"
            or lock.get("artifact") != "ktfmt-" + str(lock.get("version")) + "-with-dependencies.jar"):
        raise FormatError("FORMAT_LOCK_SCHEMA")
    selected = select_files(args.files, lock)
    report = None
    if args.report:
        report = Path(args.report)
        if not report.is_absolute():
            report = ROOT / report
        try:
            report.resolve().relative_to(CACHE.resolve())
        except ValueError as error:
            raise FormatError("FORMAT_REPORT_OUTSIDE_CACHE") from error
    if not selected:
        print("PASS no selected Kotlin files")
        return 0
    java, version = java_command(args.java, lock)
    jar = artifact(lock, args.offline)
    entries = []
    prepared = []
    befores = []
    for path, relative in selected:
        before = path.read_bytes()
        before.decode("utf-8", errors="strict")
        befores.append(before)
    CACHE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="batch-", dir=CACHE) as directory:
        work = Path(directory)
        afters, _ = format_batch(java, jar, lock, selected, befores, work)
        verify_idempotent(java, jar, lock, selected, afters, work)
    for (path, relative), before, after in zip(selected, befores, afters):
        changed = before != after
        entries.append({
            "file": relative,
            "beforeSha256": sha256(before),
            "formattedSha256": sha256(after),
            "needsFormatting": changed,
            "diff": "".join(difflib.unified_diff(
                before.decode("utf-8").splitlines(keepends=True),
                after.decode("utf-8").splitlines(keepends=True),
                fromfile=relative + " (before)", tofile=relative + " (formatted)",
            )),
        })
        prepared.append((path, before, after, changed))
    if args.write:
        for path, before, after, changed in prepared:
            if path.read_bytes() != before:
                raise FormatError("FORMAT_CONCURRENT_SOURCE_CHANGE: " + str(path))
            if changed:
                atomic_write(path, after)
    if report:
        atomic_write(report, (json.dumps({
            "schema": 1, "tool": lock["tool"], "version": lock["version"],
            "artifactSha256": lock["sha256"], "javaVersion": version,
            "style": lock["style"], "mode": "write" if args.write else "check", "files": entries,
        }, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    count = sum(entry["needsFormatting"] for entry in entries)
    for entry in entries:
        label = "WROTE " if args.write and entry["needsFormatting"] else "NEEDS_FORMAT " if entry["needsFormatting"] else "PASS "
        print(label + entry["file"])
    print(f"ktfmt {lock['version']}: {len(entries)} files, {count} changed; formatter output is idempotent")
    return 1 if args.check and count else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FormatError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)

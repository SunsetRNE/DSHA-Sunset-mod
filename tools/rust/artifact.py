#!/usr/bin/env python3
"""Rust 产物的只读验证和 Gradle staging；不编译、不签名、不改写 APK。

stage --mode off|prebuilt --out JNI_DIR [--assets-out ASSETS_DIR]
      [--artifact-dir DIR --commit FULL_SHA --run-id ID --run-attempt N --repository OWNER/REPO]
verify-apk --mode off|prebuilt --apk APK [--receipt FILE | --artifact-dir DIR]
           [--commit FULL_SHA --run-id ID --run-attempt N --repository OWNER/REPO] [--output REPORT]

prebuilt 的输入目录只能有 libdsha_rs.so 和 rust-receipt.json。stage 先完成全部
检查，再写 arm64-v8a/libdsha_rs.so 和独立 assets 目录中的 rust-native-receipt.json。
未指定 --assets-out 时使用 JNI_DIR 同级的 rustAssets。off 拒绝残留，不删除文件。
CI 三项期望身份必须由外层显式传入；非 CI 可以验证 provenance=local 的产物。
所有 PASS 均只代表静态契约，不代表 Android 加载或 JNI 执行成功。
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import stat
import struct
import sys
import tempfile
import zipfile

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from git_guard import git_output  # noqa: E402
import elf  # noqa: E402

LIB_NAME = "libdsha_rs.so"
RECEIPT_NAME = "rust-receipt.json"
ASSET_NAME = "rust-native-receipt.json"
APK_ASSET = "assets/" + ASSET_NAME
ABI = "arm64-v8a"
TRIPLE = "aarch64-linux-android"
RECEIPT_SCHEMA = 2
MAX_RECEIPT_BYTES = 2 * 1024 * 1024
MAX_AGE = timedelta(hours=24)
MAX_FUTURE = timedelta(minutes=5)
LINK_ARGS = (
    "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=4096",
    "-Wl,-z,relro", "-Wl,-z,now", "-Wl,-z,noexecstack",
)
INPUT_ROOTS = ("rust", ".cargo", ".github/workflows")
INPUT_FILES = (
    "ci/rust-toolchain.lock.json", "ci/toolchain.lock.json", "app/build.gradle",
    "tools/rust/build.py", "tools/rust/artifact.py", "tools/rust/elf.py",
    "tools/check-rust-native-apk.py", "tools/check-rust-toolchain.py",
    "tools/check-rust-vendor.py", "tools/git_guard.py",
)


class GateError(RuntimeError):
    """验证失败；调用方必须失败，不能降级为 off 或预算通过。"""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def checked_path(path: Path) -> Path:
    """保留词法路径并检查全部祖先，不能先 resolve 后把链接证据抹去。"""
    path = Path(os.path.abspath(path))
    for part in (path, *path.parents):
        if part.is_symlink():
            raise GateError("RUST_SYMLINK: " + str(part))
    return path


def read_regular(path: Path, *, limit: int | None = None) -> bytes:
    path = checked_path(path)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
    try:
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode):
                raise GateError("RUST_NOT_REGULAR: " + str(path))
            if limit is not None and info.st_size > limit:
                raise GateError("RUST_FILE_BUDGET: " + str(path))
            data = stream.read() if limit is None else stream.read(limit + 1)
            if limit is not None and len(data) > limit:
                raise GateError("RUST_FILE_BUDGET: " + str(path))
            return data
    except OSError as error:
        raise GateError("RUST_FILE_READ: %s: %s" % (path, error)) from error


def file_digest(path: Path) -> str:
    path = checked_path(path)
    value = hashlib.sha256()
    try:
        with path.open("rb") as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise GateError("RUST_NOT_REGULAR: " + str(path))
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                value.update(block)
    except OSError as error:
        raise GateError("RUST_FILE_READ: %s: %s" % (path, error)) from error
    return value.hexdigest()


def parse_json(data: bytes) -> dict:
    def pairs(rows):
        value = {}
        for key, item in rows:
            if key in value:
                raise GateError("RUST_JSON_DUPLICATE_KEY: " + key)
            value[key] = item
        return value
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=pairs,
                           parse_constant=lambda value: (_ for _ in ()).throw(
                               GateError("RUST_JSON_CONSTANT: " + value)))
    except (ValueError, UnicodeError, RecursionError) as error:
        raise GateError("RUST_JSON_INVALID: " + str(error)) from error
    if not isinstance(value, dict):
        raise GateError("RUST_JSON_OBJECT_REQUIRED")
    return value


def load_json(path: Path) -> dict:
    return parse_json(read_regular(path, limit=MAX_RECEIPT_BYTES))


def file_rows(root: Path, directory: Path) -> list[dict]:
    directory = checked_path(directory)
    if not directory.is_dir():
        raise GateError("RUST_INPUT_DIR_MISSING: " + str(directory))
    result = []
    def walk_error(error):
        raise GateError("RUST_INPUT_WALK: " + str(error)) from error
    for current, dirs, files in os.walk(directory, followlinks=False, onerror=walk_error):
        # target 不是源码输入；其它未知成员均进入指纹，不依赖 git tracked 清单。
        if Path(current) == root / "rust":
            dirs[:] = [name for name in dirs if name != "target"]
        for name in dirs:
            checked_path(Path(current) / name)
        for name in files:
            path = Path(current) / name
            data = read_regular(path)
            result.append({"path": path.relative_to(root).as_posix(),
                           "size": len(data), "sha256": digest(data)})
    return result


def rows_digest(rows: list[dict]) -> str:
    """沿用 build.py 原来的 path + NUL + SHA256 + LF 稳定树摘要算法。"""
    value = hashlib.sha256()
    for row in sorted(rows, key=lambda row: row["path"]):
        value.update(row["path"].encode("utf-8") + b"\0")
        value.update(row["sha256"].encode("ascii") + b"\n")
    return value.hexdigest()


def source_inputs(root: Path = ROOT) -> dict:
    """绑定 Cargo、所有源码/vendor/config、锁、预算、builder、验证器和接线。"""
    root = checked_path(root)
    rows = file_rows(root, root / "rust")
    rows += file_rows(root, root / ".github/workflows")
    optional_config = root / ".cargo"
    if optional_config.exists() or optional_config.is_symlink():
        rows += file_rows(root, optional_config)
    for name in INPUT_FILES:
        data = read_regular(root / name)
        rows.append({"path": name, "size": len(data), "sha256": digest(data)})
    rows.sort(key=lambda row: row["path"])
    names = [row["path"] for row in rows]
    if len(names) != len(set(names)):
        raise GateError("RUST_INPUT_DUPLICATE")
    def subtree(prefix):
        return [{**row, "path": row["path"][len(prefix):]}
                for row in rows if row["path"].startswith(prefix)]
    vendor = subtree("rust/vendor/")
    crates = subtree("rust/")
    crates = [row for row in crates if row["path"].startswith("crates/")]
    lock = load_json(root / "ci/rust-toolchain.lock.json")
    return {"algorithm": "sha256-path-sha256-v1", "fingerprintSha256": rows_digest(rows),
            "files": rows, "cargoLockSha256": digest(read_regular(root / "rust/Cargo.lock")),
            "vendorTreeSha256": rows_digest(vendor), "crateSha256": rows_digest(crates),
            "buildInputs": [row["path"] for row in lock["buildInputs"]],
            "vendorFiles": len(vendor), "vendorBytes": sum(row["size"] for row in vendor)}


def _input_name(name: str) -> bool:
    if name == "rust/target" or name.startswith("rust/target/"):
        return False
    return name in INPUT_FILES or any(name == prefix or name.startswith(prefix + "/") for prefix in INPUT_ROOTS)


def assert_committed_inputs(snapshot: dict, commit: str, root: Path = ROOT) -> None:
    """精确比较 HEAD 树与当前输入，不受 index/assume-unchanged/skip-worktree 欺骗。"""
    root = checked_path(root)
    try:
        object_format = git_output(["rev-parse", "--show-object-format"], cwd=root, text=True).strip()
        if object_format not in ("sha1", "sha256"):
            raise GateError("RUST_GIT_OBJECT_FORMAT: " + object_format)
        tree = git_output(["ls-tree", "-r", "-z", "--full-tree", commit, "--",
                           *INPUT_ROOTS, *INPUT_FILES], cwd=root)
        committed = {}
        for entry in tree.split(b"\0"):
            if not entry:
                continue
            header, raw_path = entry.split(b"\t", 1)
            mode, kind, oid = header.split(b" ")
            name = raw_path.decode("utf-8")
            if not _input_name(name):
                continue
            if mode not in (b"100644", b"100755") or kind != b"blob":
                raise GateError("RUST_INPUT_NOT_COMMITTED: non-regular Git input " + name)
            committed[name] = oid.decode("ascii")
    except GateError:
        raise
    except Exception as error:
        raise GateError("RUST_INPUT_GIT_TREE: " + str(error)) from error
    actual = {}
    for row in snapshot["files"]:
        name = row["path"]
        if (not _input_name(name) or str(PurePosixPath(name)) != name or name.startswith("/")
                or ".." in PurePosixPath(name).parts or name in actual):
            raise GateError("RUST_INPUT_SNAPSHOT_PATH: " + name)
        actual[name] = row
    if set(actual) != set(committed):
        raise GateError("RUST_INPUT_NOT_COMMITTED: added=%s missing=%s"
                        % (sorted(set(actual) - set(committed)), sorted(set(committed) - set(actual))))
    for name, row in actual.items():
        data = read_regular(root / name)
        if len(data) != row["size"] or digest(data) != row["sha256"]:
            raise GateError("RUST_INPUT_SNAPSHOT_CHANGED: " + name)
        value = hashlib.new(object_format)
        value.update(b"blob " + str(len(data)).encode("ascii") + b"\0" + data)
        if value.hexdigest() != committed[name]:
            raise GateError("RUST_INPUT_NOT_COMMITTED: modified " + name)


def current_commit(root: Path = ROOT) -> str:
    try:
        commit = git_output(["rev-parse", "HEAD"], cwd=root, text=True).strip()
    except Exception as error:
        raise GateError("RUST_COMMIT_UNAVAILABLE: " + str(error)) from error
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise GateError("RUST_COMMIT_INVALID: " + commit)
    return commit


def in_ci(env: dict | None = None) -> bool:
    env = os.environ if env is None else env
    return any(str(env.get(key, "")).lower() not in ("", "0", "false", "no")
               for key in ("CI", "GITHUB_ACTIONS", "GITHUB_RUN_ID"))


def expected_identity(commit: str | None, run_id: str | None, run_attempt: str | None,
                      repository: str | None, *, root: Path = ROOT,
                      env: dict | None = None) -> dict:
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise GateError("RUST_COMMIT_REQUIRED: prebuilt 需要完整 --commit")
    if commit != current_commit(root):
        raise GateError("RUST_COMMIT_HEAD: --commit 必须等于当前 HEAD")
    fields = (run_id, run_attempt, repository)
    if in_ci(env) or any(item is not None for item in fields):
        if not all(isinstance(item, str) and item for item in fields):
            raise GateError("RUST_CI_IDENTITY_REQUIRED: 显式传 --run-id/--run-attempt/--repository")
        if not re.fullmatch(r"[1-9][0-9]*", run_id) or not re.fullmatch(r"[1-9][0-9]*", run_attempt):
            raise GateError("RUST_CI_IDENTITY_INVALID: run/attempt 必须为正十进制数")
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise GateError("RUST_CI_REPOSITORY_INVALID")
        return dict(commit=commit, provenance="github-actions", runId=run_id,
                    runAttempt=run_attempt, repository=repository)
    return dict(commit=commit, provenance="local", runId=None, runAttempt=None, repository=None)


def generation_metadata(env: dict | None = None) -> dict:
    env = os.environ if env is None else env
    if in_ci(env):
        names = ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "GITHUB_REPOSITORY", "GITHUB_WORKFLOW",
                 "GITHUB_WORKFLOW_REF", "GITHUB_WORKFLOW_SHA", "RUNNER_NAME", "RUNNER_OS", "RUNNER_ARCH")
        missing = [name for name in names if not env.get(name)]
        if missing:
            raise GateError("RUST_CI_METADATA_MISSING: " + ", ".join(missing))
        return dict(provenance="github-actions", runId=env["GITHUB_RUN_ID"],
                    runAttempt=env["GITHUB_RUN_ATTEMPT"], repository=env["GITHUB_REPOSITORY"],
                    workflow={"name": env["GITHUB_WORKFLOW"], "ref": env["GITHUB_WORKFLOW_REF"],
                              "sha": env["GITHUB_WORKFLOW_SHA"]},
                    runner={"name": env["RUNNER_NAME"], "os": env["RUNNER_OS"],
                            "arch": env["RUNNER_ARCH"], "environment": env.get("RUNNER_ENVIRONMENT")})
    return dict(provenance="local", runId=None, runAttempt=None, repository=None, workflow=None,
                runner={"name": platform.node() or "local", "os": platform.system() or "unknown",
                        "arch": platform.machine() or "unknown", "environment": "local"})


def load_budget(root: Path = ROOT) -> dict:
    budget = load_json(root / "rust/native-budget.json")
    if (budget.get("schema") != 1 or budget.get("abi") != ABI
            or budget.get("allowedRustName") != LIB_NAME):
        raise GateError("RUST_BUDGET_SCHEMA")
    for key in ("rustWarnBytes", "rustArtifactBytes", "baselineNativeFiles", "baselineNativeBytes"):
        if type(budget.get(key)) is not int or budget[key] <= 0:
            raise GateError("RUST_BUDGET_SCHEMA: " + key)
    if not budget["rustWarnBytes"] < budget["rustArtifactBytes"]:
        raise GateError("RUST_BUDGET_THRESHOLDS")
    directory = checked_path(root / "app/src/main/jniLibs" / ABI)
    files = sorted(directory.glob("*.so"))
    if len(files) != budget["baselineNativeFiles"]:
        raise GateError("RUST_BUDGET_BASELINE_FILES: %d != %d" % (len(files), budget["baselineNativeFiles"]))
    total = sum(len(read_regular(path)) for path in files)
    if total != budget["baselineNativeBytes"]:
        raise GateError("RUST_BUDGET_BASELINE_BYTES: %d != %d" % (total, budget["baselineNativeBytes"]))
    return budget


def _elf_structure(data: bytes) -> None:
    """补充 elf.py 的结构前置条件，避免空 PT_LOAD/RELRO 或伪造节表空真值。"""
    def need(condition, message):
        if not condition:
            raise GateError("RUST_ELF_STRUCTURE: " + message)
    need(len(data) >= 64 and data[:6] == elf.ELF_MAGIC, "ELF64 little-endian required")
    need(data[6] == 1 and struct.unpack_from("<I", data, 20)[0] == 1, "ELF version")
    need(struct.unpack_from("<H", data, 16)[0] == 3, "ET_DYN required")
    need(struct.unpack_from("<H", data, 52)[0] == 64, "e_ehsize")
    phoff, shoff = struct.unpack_from("<QQ", data, 32)
    phent, phnum, shent, shnum = struct.unpack_from("<HHHH", data, 54)
    need(phent == 56 and phnum > 0 and phoff >= 64 and phoff + phent * phnum <= len(data), "program headers")
    need(shent == 64 and shnum > 0 and shoff >= 64 and shoff + shent * shnum <= len(data), "section headers")
    loads, relros, dynamics = [], [], []
    for index in range(phnum):
        kind, flags, offset, address, _, filesz, memsz, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + index * phent)
        if kind not in (elf.PT_LOAD, elf.PT_GNU_RELRO, elf.PT_DYNAMIC):
            need(kind != 3, "PT_INTERP executable is not a shared library")
            continue
        need(filesz <= memsz and offset + filesz <= len(data) and address + memsz < 2 ** 64,
             "segment file/memory range")
        row = (offset, address, filesz, memsz, flags)
        if kind == elf.PT_LOAD:
            need(memsz > 0, "empty PT_LOAD")
            need(alignment >= 16384 and alignment & (alignment - 1) == 0
                 and (offset - address) % alignment == 0, "16KB PT_LOAD alignment")
            loads.append(row)
        elif kind == elf.PT_GNU_RELRO:
            need(filesz > 0 and memsz > 0, "empty RELRO")
            relros.append(row)
        else:
            need(filesz >= 16 and filesz % 16 == 0, "PT_DYNAMIC size")
            dynamics.append(row)
    need(bool(loads) and any(row[2] > 0 for row in loads), "missing PT_LOAD")
    need(bool(relros), "missing RELRO")
    need(len(dynamics) == 1, "one PT_DYNAMIC required")
    def mapped(offset, address, size):
        return any(start <= address and address + size <= start + filesz
                   and offset == fileoff + address - start
                   for fileoff, start, filesz, _, _ in loads)
    for offset, address, filesz, memsz, _ in relros:
        need(mapped(offset, address, filesz), "RELRO file mapping")
        need(any(flags & 2 and start <= address < start + loadsz
                 and address + memsz <= (start + loadsz + 4095) // 4096 * 4096
                 for _, start, _, loadsz, flags in loads), "RELRO memory mapping")
    offset, address, filesz, _, _ = dynamics[0]
    need(mapped(offset, address, filesz), "PT_DYNAMIC mapping")
    tags = {}
    terminated = False
    for base in range(offset, offset + filesz, 16):
        tag, value = struct.unpack_from("<QQ", data, base)
        if tag == elf.DT_NULL:
            terminated = True
            break
        tags.setdefault(tag, []).append(value)
    need(terminated, "unterminated PT_DYNAMIC")
    for tag in (elf.DT_STRTAB, elf.DT_STRSZ, 6, 11):  # DT_SYMTAB / DT_SYMENT
        need(len(tags.get(tag, [])) == 1, "missing/duplicate dynamic string/symbol table")
    need(tags[elf.DT_STRSZ][0] > 0 and tags[11][0] == 24, "dynamic table sizes")
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + i * shent) for i in range(shnum)]
    symbols = [section for section in sections if section[1] == elf.SHT_DYNSYM]
    need(len(symbols) == 1, "one SHT_DYNSYM required")
    section = symbols[0]
    _, _, flags, address, offset, size, link, _, _, entsize = section
    need(flags & 2 and entsize == 24 and size >= 24 and size % 24 == 0
         and link < shnum and mapped(offset, address, size), "SHT_DYNSYM mapping/size")
    strings = sections[link]
    need(strings[1] == 3 and strings[2] & 2 and mapped(strings[4], strings[3], strings[5]), "SHT_STRTAB mapping")
    need(address == tags[6][0] and strings[3] == tags[elf.DT_STRTAB][0]
         and strings[5] == tags[elf.DT_STRSZ][0], "dynamic/section tables disagree")
    table = data[strings[4]:strings[4] + strings[5]]
    for base in range(offset, offset + size, 24):
        name, info, other, index = struct.unpack_from("<IBBH", data, base)
        need(name < len(table) and table.find(b"\0", name) >= 0, "symbol string")
        if index and info >> 4 in (1, 2):
            need(other & 3 in (0, 3), "export visibility must be DEFAULT/PROTECTED")
            need(info & 15 == elf.STT_FUNC, "unexpected non-function export")


def describe_bytes(data: bytes, *, target: str, lock: dict, budget: dict) -> dict:
    if not data or len(data) > budget["rustArtifactBytes"]:
        raise GateError("RUST_ARTIFACT_BUDGET: %d" % len(data))
    try:
        _elf_structure(data)
        contract = {**elf.inspect_bytes(data), "exported_functions": elf.exported_functions(data)}
    except (elf.ElfError, struct.error, UnicodeError) as error:
        raise GateError("RUST_ELF_INVALID: " + str(error)) from error
    if target not in ("android", "host"):
        raise GateError("RUST_TARGET_INVALID")
    if (target == "android" and contract["machine"] != elf.EM_AARCH64
            or target == "host" and contract["machine"] not in (62, elf.EM_AARCH64)):
        raise GateError("RUST_ELF_MACHINE: %d" % contract["machine"])
    if contract["exported_functions"] != sorted(lock["abi"]["symbols"]):
        raise GateError("RUST_ELF_SYMBOLS: exact exported JNI set required")
    if not contract["aligned_16k"]:
        raise GateError("RUST_ELF_ALIGN")
    if not contract["relro_mapped"]:
        raise GateError("RUST_ELF_RELRO")
    if target == "android" and set(contract["needed"]) - set(lock["androidNeededAllowed"]):
        raise GateError("RUST_ELF_NEEDED: " + str(contract["needed"]))
    return dict(artifactFile=LIB_NAME, size=len(data), sha256=digest(data), elfType="ET_DYN",
                elfMachine=contract["machine"], aligned16k=contract["aligned_16k"],
                relroMapped=contract["relro_mapped"], needed=contract["needed"],
                exportedSymbols=contract["exported_functions"],
                budget="WARN" if len(data) > budget["rustWarnBytes"] else "PASS")


def _keys(value, names: set[str], label: str) -> None:
    if not isinstance(value, dict) or set(value) != names:
        raise GateError("RUST_RECEIPT_SCHEMA: " + label)


def _text(value) -> bool:
    return isinstance(value, str) and bool(value.strip())


def validate_receipt(receipt: dict, data: bytes, *, identity: dict, budget: dict,
                     root: Path = ROOT, now: datetime | None = None) -> dict:
    _keys(receipt, {"schema", "kind", "commit", "provenance", "runId", "runAttempt", "repository",
                    "workflow", "runner", "generatedAt", "target", "toolchain", "sources", "build",
                    "artifact", "reproducible", "expectation"}, "top-level")
    if type(receipt["schema"]) is not int or receipt["schema"] != RECEIPT_SCHEMA or receipt["kind"] != "dsha-rust-native":
        raise GateError("RUST_RECEIPT_SCHEMA: schema/kind")
    for name, expected in identity.items():
        if receipt[name] != expected:
            raise GateError("RUST_RECEIPT_IDENTITY: %s: %s != %s" % (name, receipt[name], expected))
    if receipt["target"] != "android":
        raise GateError("RUST_RECEIPT_TARGET: Android only")
    runner = receipt["runner"]
    _keys(runner, {"name", "os", "arch", "environment"}, "runner")
    if not all(_text(runner[key]) for key in ("name", "os", "arch")):
        raise GateError("RUST_RECEIPT_SCHEMA: runner fields")
    if runner["environment"] is not None and not _text(runner["environment"]):
        raise GateError("RUST_RECEIPT_SCHEMA: runner environment")
    if identity["provenance"] == "github-actions":
        workflow = receipt["workflow"]
        _keys(workflow, {"name", "ref", "sha"}, "workflow")
        if not all(_text(workflow[key]) for key in workflow):
            raise GateError("RUST_RECEIPT_SCHEMA: workflow fields")
        if not re.fullmatch(r"[0-9a-f]{40}", workflow["sha"]):
            raise GateError("RUST_RECEIPT_SCHEMA: workflow sha")
        # GITHUB_WORKFLOW_SHA 是 workflow 文件的提交，不是 run id。本入口只接受
        # 同仓同提交的 workflow；跨提交复用必须将来另加明确的 caller 期望。
        if workflow["sha"] != identity["commit"]:
            raise GateError("RUST_RECEIPT_WORKFLOW_COMMIT: workflow SHA must equal current HEAD")
        if not workflow["ref"].startswith(identity["repository"] + "/.github/workflows/"):
            raise GateError("RUST_RECEIPT_WORKFLOW_REPOSITORY")
    elif receipt["workflow"] is not None or runner["environment"] != "local":
        raise GateError("RUST_RECEIPT_SCHEMA: local provenance")
    generated = receipt["generatedAt"]
    if not isinstance(generated, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", generated):
        raise GateError("RUST_RECEIPT_TIMESTAMP")
    try:
        instant = datetime.strptime(generated, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise GateError("RUST_RECEIPT_TIMESTAMP") from error
    now = datetime.now(timezone.utc) if now is None else now
    if now - instant > MAX_AGE or instant - now > MAX_FUTURE:
        raise GateError("RUST_RECEIPT_FRESHNESS: bounded 24h / future 5min")
    lock = load_json(root / "ci/rust-toolchain.lock.json")
    chain = receipt["toolchain"]
    _keys(chain, {"rustc", "rustcCommit", "cargo", "host", "channel", "targetTriple", "androidApi", "ndk", "linker"}, "toolchain")
    expected_chain = dict(rustc=lock["rustc"]["version"], cargo=lock["cargo"]["version"],
                          channel=lock["channel"], targetTriple=TRIPLE, androidApi=lock["androidApi"],
                          ndk=lock["ndk"], linker=lock["linker"])
    if any(chain[key] != value or type(chain[key]) is not type(value) for key, value in expected_chain.items()):
        raise GateError("RUST_RECEIPT_TOOLCHAIN: targetTriple/API/locked toolchain")
    if (not isinstance(chain["rustcCommit"], str) or not re.fullmatch(r"[0-9a-f]{40}", chain["rustcCommit"])
            or not chain["rustcCommit"].startswith(lock["rustc"]["commit"]) or chain["host"] not in lock["targets"]):
        raise GateError("RUST_RECEIPT_TOOLCHAIN: compiler commit/host")
    # 比较完整 manifest 而不只信任回执自己声明的 aggregate digest。
    sources = receipt["sources"]
    _keys(sources, {"algorithm", "fingerprintSha256", "files", "cargoLockSha256", "vendorTreeSha256",
                    "crateSha256", "buildInputs", "vendorFiles", "vendorBytes"}, "sources")
    if not isinstance(sources["files"], list):
        raise GateError("RUST_RECEIPT_SCHEMA: source files")
    for row in sources["files"]:
        _keys(row, {"path", "size", "sha256"}, "source file")
        if (not _text(row["path"]) or type(row["size"]) is not int or row["size"] < 0
                or not isinstance(row["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"])):
            raise GateError("RUST_RECEIPT_SCHEMA: source file fields")
    if any(type(sources[key]) is not int for key in ("vendorFiles", "vendorBytes")):
        raise GateError("RUST_RECEIPT_SCHEMA: vendor totals")
    if sources != source_inputs(root):
        raise GateError("RUST_RECEIPT_SOURCE_FINGERPRINT: current inputs differ")
    assert_committed_inputs(sources, identity["commit"], root=root)
    build = receipt["build"]
    _keys(build, {"profile", "linkArgs", "remapPathPrefixes", "offline", "locked", "jobs", "environment"}, "build")
    if (build["profile"] != "release" or build["linkArgs"] != list(LINK_ARGS)
            or any(build[key] is not True for key in ("remapPathPrefixes", "offline", "locked"))
            or type(build["jobs"]) is not int or not 1 <= build["jobs"] <= 8
            or build["environment"] != {"RUSTC_WRAPPER": "cleared", "RUSTFLAGS": "set by build.py", "CARGO_NET_OFFLINE": "true"}):
        raise GateError("RUST_RECEIPT_BUILD")
    info = describe_bytes(data, target="android", lock=lock, budget=budget)
    artifact = receipt["artifact"]
    _keys(artifact, set(info) | {"reproducible"}, "artifact")
    if any(artifact[key] != value or type(artifact[key]) is not type(value) for key, value in info.items()):
        raise GateError("RUST_RECEIPT_ARTIFACT: SHA/size/ELF metadata differ")
    reproducible = receipt["reproducible"]
    _keys(reproducible, {"status", "differentDirectory", "firstSha256", "secondSha256"}, "reproducible")
    if type(reproducible["differentDirectory"]) is not bool:
        raise GateError("RUST_RECEIPT_REPRODUCIBLE")
    if reproducible["status"] == "PASS":
        if (reproducible["differentDirectory"] is not True
                or reproducible["firstSha256"] != info["sha256"] or reproducible["secondSha256"] != info["sha256"]):
            raise GateError("RUST_RECEIPT_REPRODUCIBLE")
    elif reproducible != dict(status="NOT_RUN", differentDirectory=False, firstSha256=None, secondSha256=None):
        raise GateError("RUST_RECEIPT_REPRODUCIBLE")
    if artifact["reproducible"] != reproducible["status"]:
        raise GateError("RUST_RECEIPT_REPRODUCIBLE")
    expectation = receipt["expectation"]
    if expectation != {"status": "NOT_RUN"}:
        _keys(expectation, {"status", "target", "size", "sha256"}, "expectation")
        if (expectation["status"] not in ("PASS", "PENDING") or expectation["target"] != TRIPLE
                or expectation["size"] != info["size"] or type(expectation["size"]) is not int
                or expectation["sha256"] != info["sha256"]):
            raise GateError("RUST_RECEIPT_EXPECTATION")
    return info


def artifact_files(directory: Path) -> tuple[bytes, bytes]:
    directory = checked_path(directory)
    if not directory.is_dir():
        raise GateError("RUST_ARTIFACT_DIR_MISSING: " + str(directory))
    entries = list(directory.iterdir())
    for entry in entries:
        checked_path(entry)
    if {entry.name for entry in entries} != {LIB_NAME, RECEIPT_NAME}:
        raise GateError("RUST_ARTIFACT_DIR_MEMBERS: exactly libdsha_rs.so + rust-receipt.json required")
    return read_regular(directory / LIB_NAME, limit=8 * 1024 * 1024), read_regular(directory / RECEIPT_NAME, limit=MAX_RECEIPT_BYTES)


def _stage_members(directory: Path, allowed_files: set[str], allowed_dirs: set[str]) -> set[str]:
    directory = checked_path(directory)
    if not directory.exists():
        return set()
    if not directory.is_dir():
        raise GateError("RUST_STAGE_NOT_DIRECTORY: " + str(directory))
    found = set()
    for current, dirs, files in os.walk(directory, followlinks=False):
        for name in dirs + files:
            path = checked_path(Path(current) / name)
            relative = path.relative_to(directory).as_posix()
            if path.is_dir():
                if relative not in allowed_dirs:
                    raise GateError("RUST_STAGE_UNEXPECTED_MEMBER: " + relative)
            elif relative not in allowed_files or not stat.S_ISREG(path.stat().st_mode):
                raise GateError("RUST_STAGE_UNEXPECTED_MEMBER: " + relative)
            else:
                found.add(relative)
    return found


def write_regular(path: Path, data: bytes) -> None:
    path = checked_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    checked_path(path.parent)
    if path.exists() and not stat.S_ISREG(path.stat().st_mode):
        raise GateError("RUST_NOT_REGULAR: " + str(path))
    # 临时文件与目标同目录，替换前不会留下部分 .so 或部分 JSON。
    descriptor, name = tempfile.mkstemp(prefix=".rust-stage-", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
        checked_path(path)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            # temporary 是本函数 mkstemp 返回并确认位于目标父目录的准确路径。
            if temporary.parent != path.parent or not temporary.name.startswith(".rust-stage-"):
                raise GateError("RUST_TEMP_PATH_INVALID")
            temporary.unlink()


def source_residue(root: Path = ROOT) -> None:
    for directory in (root / "app/src").glob("*/jniLibs"):
        checked_path(directory)
        for current, dirs, files in os.walk(directory, followlinks=False):
            for name in dirs + files:
                checked_path(Path(current) / name)
            if any(name.casefold() == LIB_NAME for name in files):
                raise GateError("RUST_SOURCE_JNI_RESIDUE: " + str(current))
    for path in (root / "app/src").glob("*/assets/" + ASSET_NAME):
        raise GateError("RUST_SOURCE_RECEIPT_RESIDUE: " + str(path))


def stage(*, mode: str, artifact_dir: Path | None, out: Path, assets_out: Path | None = None,
          commit: str | None = None, run_id: str | None = None, run_attempt: str | None = None,
          repository: str | None = None, root: Path = ROOT) -> dict:
    if mode not in ("off", "prebuilt"):
        raise GateError("RUST_MODE_INVALID")
    out = checked_path(out)
    assets_out = checked_path(assets_out if assets_out is not None else out.parent / "rustAssets")
    if out == assets_out or out in assets_out.parents or assets_out in out.parents:
        raise GateError("RUST_STAGE_OVERLAPPING_OUTPUTS")
    if artifact_dir is not None:
        artifact_dir = checked_path(artifact_dir)
        if any(a == b or a in b.parents or b in a.parents for a in (out, assets_out) for b in (artifact_dir,)):
            raise GateError("RUST_STAGE_INPUT_OUTPUT_OVERLAP")
    source_residue(root)
    staged = _stage_members(out, {ABI + "/" + LIB_NAME}, {ABI})
    assets = _stage_members(assets_out, {ASSET_NAME}, set())
    if mode == "off":
        if staged or assets:
            raise GateError("RUST_OFF_RESIDUE: staging contains Rust library/receipt")
        if artifact_dir is not None and artifact_dir.exists():
            if _stage_members(artifact_dir, {LIB_NAME, RECEIPT_NAME}, set()):
                raise GateError("RUST_OFF_RESIDUE: artifact directory is not empty")
        return dict(status="PASS_NO_RUST_ARTIFACT", mode=mode, operation="stage", apkVerified=False,
                    out=str(out), assetsOut=str(assets_out), androidLoadTested=False)
    identity = expected_identity(commit, run_id, run_attempt, repository, root=root)
    if artifact_dir is None:
        raise GateError("RUST_ARTIFACT_DIR_REQUIRED")
    data, receipt_bytes = artifact_files(artifact_dir)
    receipt = parse_json(receipt_bytes)
    info = validate_receipt(receipt, data, identity=identity, budget=load_budget(root), root=root)
    write_regular(out / ABI / LIB_NAME, data)
    write_regular(assets_out / ASSET_NAME, receipt_bytes)
    if read_regular(out / ABI / LIB_NAME) != data or read_regular(assets_out / ASSET_NAME) != receipt_bytes:
        raise GateError("RUST_STAGE_READBACK")
    return dict(status="PASS", mode=mode, operation="stage", apkVerified=False, out=str(out),
                assetsOut=str(assets_out), artifact=info, sourceFingerprint=receipt["sources"]["fingerprintSha256"],
                provenance=identity, receiptSha256=digest(receipt_bytes), androidLoadTested=False)


def _zip_members(apk: zipfile.ZipFile, allowed: set[str]) -> dict[str, zipfile.ZipInfo]:
    members = {}
    for item in apk.infolist():
        name = item.filename
        canonical = str(PurePosixPath(name))
        if item.is_dir():
            canonical += "/"
        if (name != item.orig_filename or "\\" in name or name.startswith("/")
                or canonical != name or ".." in PurePosixPath(name).parts):
            raise GateError("RUST_APK_NONCANONICAL_MEMBER: " + name)
        if name in members:
            raise GateError("RUST_APK_DUPLICATE_ZIP: " + name)
        kind = stat.S_IFMT(item.external_attr >> 16)
        if kind not in (0, stat.S_IFDIR if item.is_dir() else stat.S_IFREG):
            raise GateError("RUST_APK_NONREGULAR_MEMBER: " + name)
        if item.flag_bits & 1:
            raise GateError("RUST_APK_ENCRYPTED_MEMBER: " + name)
        if name.startswith("lib/") and name not in allowed | {"lib/", "lib/" + ABI + "/"}:
            raise GateError("RUST_APK_UNEXPECTED_LIB: " + name)
        if PurePosixPath(name).name.casefold() == LIB_NAME and name != "lib/" + ABI + "/" + LIB_NAME:
            raise GateError("RUST_APK_UNEXPECTED_RUST_MEMBER: " + name)
        if PurePosixPath(name).name.casefold() in (ASSET_NAME, RECEIPT_NAME) and name != APK_ASSET:
            raise GateError("RUST_APK_UNEXPECTED_RECEIPT: " + name)
        members[name] = item
    return members


def verify_apk(path: Path, *, mode: str, receipt_path: Path | None = None,
               artifact_dir: Path | None = None, commit: str | None = None,
               run_id: str | None = None, run_attempt: str | None = None,
               repository: str | None = None, root: Path = ROOT, budget: dict | None = None) -> dict:
    if mode not in ("off", "prebuilt"):
        raise GateError("RUST_MODE_INVALID")
    budget = load_budget(root) if budget is None else budget
    path = checked_path(path)
    if not path.is_file():
        raise GateError("RUST_APK_MISSING: " + str(path))
    rust_name = "lib/" + ABI + "/" + LIB_NAME
    baseline = {"lib/" + ABI + "/" + p.name for p in (root / "app/src/main/jniLibs" / ABI).glob("*.so")}
    allowed = baseline | {rust_name}
    identity = expected_identity(commit, run_id, run_attempt, repository, root=root) if mode == "prebuilt" else None
    if receipt_path is not None and artifact_dir is not None:
        raise GateError("RUST_RECEIPT_INPUT_AMBIGUOUS")
    try:
        with zipfile.ZipFile(path) as apk:
            members = _zip_members(apk, allowed)
            native = [item for name, item in members.items() if name.startswith("lib/") and not item.is_dir()]
            abis = sorted({item.filename.split("/")[1] for item in native})
            if abis != [ABI]:
                raise GateError("RUST_APK_ABI: actual native ABI set must equal [arm64-v8a]: " + str(abis))
            if sum(item.file_size for item in native) > budget["baselineNativeBytes"] + budget["rustArtifactBytes"]:
                raise GateError("RUST_APK_TOTAL_BUDGET")
            if mode == "off":
                if rust_name in members or APK_ASSET in members:
                    raise GateError("RUST_OFF_RESIDUE: APK contains Rust library/receipt")
                result = dict(status="PASS_NO_RUST_ARTIFACT", mode=mode, rustArtifact="none", nativeMembers=len(native))
            else:
                if rust_name not in members or APK_ASSET not in members:
                    raise GateError("RUST_APK_ARTIFACT_OR_RECEIPT_MISSING")
                if members[rust_name].file_size > budget["rustArtifactBytes"] or members[APK_ASSET].file_size > MAX_RECEIPT_BYTES:
                    raise GateError("RUST_APK_MEMBER_BUDGET")
                data = apk.read(members[rust_name])
                embedded = apk.read(members[APK_ASSET])
                if artifact_dir is not None:
                    external_data, external = artifact_files(artifact_dir)
                    if external_data != data:
                        raise GateError("RUST_APK_EXTERNAL_ARTIFACT_MISMATCH")
                elif receipt_path is not None:
                    external = read_regular(receipt_path, limit=MAX_RECEIPT_BYTES)
                else:
                    raise GateError("RUST_APK_EXTERNAL_RECEIPT_REQUIRED")
                if embedded != external:
                    raise GateError("RUST_APK_EMBEDDED_RECEIPT_MISMATCH")
                receipt = parse_json(external)
                info = validate_receipt(receipt, data, identity=identity, budget=budget, root=root)
                result = dict(status="PASS", mode=mode, rustArtifact="present", artifact=info,
                              nativeMembers=len(native), receiptSha256=digest(external), provenance=identity,
                              sourceFingerprint=receipt["sources"]["fingerprintSha256"])
    except (OSError, zipfile.BadZipFile, RuntimeError) as error:
        if isinstance(error, GateError):
            raise
        raise GateError("RUST_APK_ZIP_INVALID: " + str(error)) from error
    result.update(operation="verify-apk", apkVerified=True, androidLoadTested=False,
                  verification="STATIC_ONLY_NOT_ANDROID_LOAD", abi=ABI,
                  apk={"name": path.name, "sha256": file_digest(path), "size": path.stat().st_size})
    return result


def identity_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--mode", choices=("off", "prebuilt"), required=True)
    parser.add_argument("--artifact-dir", type=Path)
    parser.add_argument("--commit", help="prebuilt 必填；完整当前 git HEAD")
    parser.add_argument("--run-id", help="CI 必填，外层显式传入")
    parser.add_argument("--run-attempt", help="CI 必填，外层显式传入")
    parser.add_argument("--repository", help="CI 必填，外层显式传入 OWNER/REPO")


def report_result(result: dict, output: Path | None = None, *, protected: tuple[Path, ...] = ()) -> None:
    if output is not None:
        path = checked_path(output)
        if any(path == checked_path(item) for item in protected if item is not None):
            raise GateError("RUST_REPORT_INPUT_OVERLAP")
        write_regular(path, (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    print(json.dumps(result, ensure_ascii=False))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    stage_parser = commands.add_parser("stage")
    identity_arguments(stage_parser)
    stage_parser.add_argument("--out", type=Path, required=True, help="专用 JNI staging 根目录")
    stage_parser.add_argument("--assets-out", type=Path, help="专用 generated assets 根目录")
    apk_parser = commands.add_parser("verify-apk")
    identity_arguments(apk_parser)
    apk_parser.add_argument("--apk", type=Path, required=True)
    apk_parser.add_argument("--receipt", type=Path)
    apk_parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    options = dict(mode=args.mode, artifact_dir=args.artifact_dir, commit=args.commit,
                   run_id=args.run_id, run_attempt=args.run_attempt, repository=args.repository)
    if args.command == "stage":
        result = stage(out=args.out, assets_out=args.assets_out, **options)
        report_result(result)
    else:
        protected = (args.apk, args.receipt)
        if args.artifact_dir is not None:
            protected += (args.artifact_dir / LIB_NAME, args.artifact_dir / RECEIPT_NAME)
        try:
            result = verify_apk(args.apk, receipt_path=args.receipt, **options)
        except GateError as error:
            report_result(dict(status="FAIL", mode=args.mode, operation="verify-apk", apkVerified=False,
                               error=str(error)), args.output, protected=protected)
            raise
        report_result(result, args.output, protected=protected)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except GateError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)

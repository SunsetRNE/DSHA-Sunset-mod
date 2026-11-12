#!/usr/bin/env python3
"""Rust 旁路边界门禁（G7/G8）：源码禁区、ABI 符号、依赖白名单、构建脚本审查、产物字符串。

默认只读 `rust/crates/**` 与锁定清单；`--artifact PATH` 再核对已构建 `.so` 的导出
符号与字符串。R0 在 rust-check（source-only）与 rust-native（artifact）双跑。
"""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import json
from pathlib import Path
import re
import sys
import tomllib

TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parent
RUST = ROOT / "rust"
LOCK = ROOT / "ci/rust-toolchain.lock.json"
sys.path.insert(0, str(Path(__file__).resolve().parent / "rust"))

import elf  # noqa: E402


class GateError(RuntimeError):
    pass


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def code_lines(path: Path) -> list[str]:
    """去掉行注释/块注释，保留行号与字符串内容（字符串里的 forbidden 词也算命中）。"""
    result: list[str] = []
    block = False
    for raw in path.read_text(encoding="utf-8").splitlines():
        line, index, in_string, in_char = [], 0, False, False
        while index < len(raw):
            char = raw[index]
            pair = raw[index:index + 2]
            if block:
                if pair == "*/":
                    block = False
                    index += 2
                    continue
                index += 1
                continue
            if in_string or in_char:
                line.append(char)
                if char == "\\" and index + 1 < len(raw):
                    line.append(raw[index + 1])
                    index += 2
                    continue
                if in_string and char == '"':
                    in_string = False
                elif in_char and char == "'":
                    in_char = False
                index += 1
                continue
            if pair == "//":
                break
            if pair == "/*":
                block = True
                index += 2
                continue
            if char == '"':
                in_string = True
            elif char == "'":
                # 只有 'x' / '\n' 这类字符字面量才进入 char 状态；'static 等生命周期
                # 不是字符串边界，否则会把整行剩余部分误当字面量。
                if raw[index + 1:index + 2] == "\\":
                    in_char = True
                elif index + 2 < len(raw) and raw[index + 2] == "'":
                    in_char = True
            line.append(char)
            index += 1
        result.append("".join(line))
    return result


def test_line_ranges(lines: list[str]) -> set[int]:
    """标记 `#[cfg(test)] mod ... { }` 覆盖的行（测试代码允许 panic!/unwrap）。"""
    ranges: set[int] = set()
    index = 0
    while index < len(lines):
        if re.search(r"#\[cfg\(test\)\]", lines[index]):
            cursor, depth = index, 0
            started = False
            while cursor < len(lines):
                ranges.add(cursor)
                depth += lines[cursor].count("{") - lines[cursor].count("}")
                if lines[cursor].count("{"):
                    started = True
                if started and depth <= 0:
                    break
                cursor += 1
            index = cursor + 1
            continue
        index += 1
    return ranges


FORBIDDEN_CODE = [
    (r"std::process", "进程 API（禁止任何执行/派生入口）"),
    (r"std::fs", "文件系统 API（Rust 不接收路径、不自行 open/close）"),
    (r"std::net", "网络 API（离线纯函数）"),
    (r"\bCommand\b", "进程命令"),
    (r"\bsystem\s*\(", "system()"),
    (r"\bargv\b", "argv（ADR-0004 决定四明确不接收）"),
    (r"\bexecute\b", "execute 入口（首期 ABI 无执行路径）"),
    (r"\bfork\s*\(", "fork"),
    (r"\bexec[lv]?[pe]?\s*\(", "exec"),
    (r"\bspawn\s*\(", "spawn"),
    (r"\bkill\s*\(", "kill"),
    (r"\bsignal\b", "信号"),
    (r"\blibc::", "libc 直接调用（fd 读取走 FileExt）"),
    (r"\bprintln!", "println!（G8：Rust 不写 stdout）"),
    (r"\beprintln!", "eprintln!（G8：Rust 不写 stderr）"),
    (r"\bdbg!", "dbg!"),
    (r"\blog::", "日志依赖"),
    (r"android_logger", "logcat 依赖"),
    (r"\bFile::", "File API"),
    (r"OpenOptions", "文件打开"),
    (r"std::io::", "std::io（G8：不写标准流）"),
    (r"\bopen\s*\(", "open()"),
    (r"\bpath\b", "路径字符串/路径类型（Rust 不接收路径）"),
]
UNSAFE_ALLOWED = {"crates/dsha-core/src/jni.rs", "crates/dsha-core/src/fd.rs"}


def verify_sources(lock: dict) -> dict:
    sources = sorted((RUST / "crates").rglob("*.rs"))
    if not sources:
        raise GateError("RUST_SOURCES_EMPTY")
    symbols_expected = sorted(lock["abi"]["symbols"])
    found_symbols: list[str] = []
    for path in sources:
        relative = path.relative_to(RUST).as_posix()
        lines = code_lines(path)
        tests = test_line_ranges(lines)
        for number, line in enumerate(lines):
            for pattern, reason in FORBIDDEN_CODE:
                if re.search(pattern, line):
                    raise GateError("RUST_BOUNDARY_SOURCE: %s:%d 命中 %s（%s）"
                                    % (relative, number + 1, pattern, reason))
            if re.search(r"\bunsafe\b", line) and relative not in UNSAFE_ALLOWED:
                raise GateError("RUST_BOUNDARY_UNSAFE: %s:%d（只允许 %s）"
                                % (relative, number + 1, sorted(UNSAFE_ALLOWED)))
            if number not in tests:
                for pattern in (r"\bpanic!", r"\.unwrap\s*\(", r"\.expect\s*\("):
                    if re.search(pattern, line):
                        raise GateError("RUST_BOUNDARY_PANIC: %s:%d（生产代码禁止 panic!/unwrap/expect）"
                                        % (relative, number + 1))
        for match in re.finditer(r'pub extern "system" fn (Java_\w+)', "\n".join(lines)):
            found_symbols.append(match.group(1))
    if sorted(found_symbols) != symbols_expected:
        raise GateError("RUST_ABI_SOURCE_SYMBOLS: %s\n期望 %s"
                        % (sorted(found_symbols), symbols_expected))
    jni_text = "\n".join(code_lines(RUST / "crates/dsha-core/src/jni.rs"))
    if jni_text.count("ffi_guard") < len(symbols_expected):
        raise GateError("RUST_ABI_GUARD: 每个导出入口都必须经过 catch_unwind 保护层")
    # 非 FFI 模块必须显式禁止 unsafe；lib.rs 以 deny 允许 jni 单点豁免。
    for name in ("abi.rs", "digest.rs", "gzip.rs", "plan.rs"):
        text = (RUST / "crates/dsha-core/src" / name).read_text(encoding="utf-8")
        if "#![forbid(unsafe_code)]" not in text:
            raise GateError("RUST_BOUNDARY_UNSAFE: %s 缺少 #![forbid(unsafe_code)]" % name)
    lib = (RUST / "crates/dsha-core/src/lib.rs").read_text(encoding="utf-8")
    if "#![deny(unsafe_code)]" not in lib:
        raise GateError("RUST_BOUNDARY_UNSAFE: lib.rs 缺少 #![deny(unsafe_code)]")

    # 依赖白名单：解析锁，不允许 git/path 依赖或清单外 crate。
    lock_data = tomllib.loads((RUST / "Cargo.lock").read_text(encoding="utf-8"))
    packages = {row["name"]: row for row in lock_data["package"]}
    expected = {row["name"]: row for row in lock["dependencies"]}
    if set(packages) - {"dsha-core"} != set(expected):
        raise GateError("RUST_DEPENDENCY_WHITELIST: %s"
                        % sorted(set(packages) - {"dsha-core"} ^ set(expected)))
    for name, row in expected.items():
        actual = packages[name]
        if actual.get("version") != row["version"] or actual.get("checksum") != row["checksum"]:
            raise GateError("RUST_DEPENDENCY_MISMATCH: " + name)
        if actual.get("source") != "registry+https://github.com/rust-lang/crates.io-index":
            raise GateError("RUST_DEPENDENCY_SOURCE: " + name)
    return {"sources": len(sources), "symbols": len(symbols_expected),
            "dependencies": len(expected)}


def verify_build_inputs(lock: dict) -> dict:
    allowed_env = lock["buildInputEnvAllow"]
    for row in lock["buildInputs"]:
        path = RUST / row["path"]
        if not path.is_file():
            raise GateError("RUST_BUILD_INPUT_MISSING: " + row["path"])
        if digest(path) != row["sha256"]:
            raise GateError("RUST_BUILD_INPUT_CHANGED: " + row["path"])
        lines = code_lines(path)
        tests = test_line_ranges(lines)
        for number, line in enumerate(lines):
            if number in tests:
                continue
            for pattern, reason in (
                    (r"std::net|TcpStream|UdpSocket|\breqwest\b|\bureq\b|\bhyper\b|\bcurl\b", "网络"),
                    (r"File::create|fs::write|fs::remove|fs::rename|fs::copy|OpenOptions|create_dir",
                     "构建期写文件"),
            ):
                if re.search(pattern, line):
                    raise GateError("RUST_BUILD_INPUT_BEHAVIOR: %s:%d %s（%s）"
                                    % (row["path"], number + 1, pattern, reason))
            for match in re.finditer(r'env::var(?:_os)?\("([A-Za-z0-9_]+)"\)', line):
                name = match.group(1)
                if not any(fnmatch.fnmatch(name, pattern) for pattern in allowed_env):
                    raise GateError("RUST_BUILD_INPUT_ENV: %s:%d %s 未在允许集合"
                                    % (row["path"], number + 1, name))
    return {"buildInputs": len(lock["buildInputs"])}


def verify_artifact(path: Path, lock: dict) -> dict:
    if not path.is_file():
        raise GateError("RUST_ARTIFACT_MISSING: " + str(path))
    data = path.read_bytes()
    contract = elf.inspect_bytes(data)
    exported = elf.exported_functions(data)
    expected = sorted(lock["abi"]["symbols"])
    if exported != expected:
        raise GateError("RUST_ABI_ARTIFACT_SYMBOLS: %s\n期望 %s" % (exported, expected))
    forbidden = [("仓库路径", str(ROOT)), ("CI 家目录", "/home/runner"),
                 ("cargo registry", "/registry/src/"), ("panic hook 输出", "failed printing to")]
    for reason, needle in forbidden:
        if needle.encode("utf-8") in data:
            raise GateError("RUST_ARTIFACT_STRING: %s（%s）" % (reason, needle))
    return {"exportedSymbols": len(exported), "machine": contract["machine"],
            "aligned16k": contract["aligned_16k"], "relroMapped": contract["relro_mapped"]}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path)
    args = parser.parse_args(argv)
    lock = load_json(LOCK)
    result = {"status": "PASS", **verify_sources(lock), **verify_build_inputs(lock)}
    if args.artifact:
        result["artifact"] = verify_artifact(args.artifact, lock)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except GateError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)

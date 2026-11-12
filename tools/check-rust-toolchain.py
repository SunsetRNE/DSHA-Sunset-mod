#!/usr/bin/env python3
"""核对 Rust 工具链锁、vendor 摘要与仓库配置一致（默认离线，不下载任何东西）。

`--verify-installed` 额外核对当前解析到的 rustc/cargo 与 `rust/rust-toolchain.toml`
和 `ci/rust-toolchain.lock.json` 一致，并确认 `aarch64-linux-android` target 已安装。
CI 的 rust-check 先跑静态核对（无网络），rust-native 在安装工具链后跑
`--verify-installed`；两者都不改仓库。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[1]
RUST = ROOT / "rust"
LOCK = ROOT / "ci/rust-toolchain.lock.json"
CI_TOOLCHAIN = ROOT / "ci/toolchain.lock.json"

class GateError(RuntimeError):
    pass


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_sources(lock: dict) -> None:
    toolchain = tomllib.loads((RUST / "rust-toolchain.toml").read_text(encoding="utf-8"))
    section = toolchain["toolchain"]
    if section["channel"] != lock["channel"]:
        raise GateError("RUST_TOOLCHAIN_CHANNEL: %s != %s" % (section["channel"], lock["channel"]))
    if section.get("profile") != lock["profile"]:
        raise GateError("RUST_TOOLCHAIN_PROFILE")
    if sorted(section.get("components", [])) != sorted(lock["components"]):
        raise GateError("RUST_TOOLCHAIN_COMPONENTS")
    if sorted(section.get("targets", [])) != sorted(lock["targets"]):
        raise GateError("RUST_TOOLCHAIN_TARGETS")

    manifest = tomllib.loads((RUST / "Cargo.toml").read_text(encoding="utf-8"))
    profiles = manifest["profile"]
    expected_release = dict(optLevel="z", lto="fat", codegenUnits=1, panic="unwind", strip="symbols")
    release = profiles["release"]
    if release.get("opt-level") != expected_release["optLevel"]:
        raise GateError("RUST_PROFILE_OPT_LEVEL")
    if release.get("lto") != expected_release["lto"]:
        raise GateError("RUST_PROFILE_LTO")
    if release.get("codegen-units") != expected_release["codegenUnits"]:
        raise GateError("RUST_PROFILE_CODEGEN_UNITS")
    if release.get("panic") != expected_release["panic"]:
        raise GateError("RUST_PROFILE_PANIC: release 必须 unwind（abort 会杀 App 进程）")
    if release.get("strip") != expected_release["strip"]:
        raise GateError("RUST_PROFILE_STRIP")
    if profiles["dev"].get("panic") != "unwind":
        raise GateError("RUST_PROFILE_DEV_PANIC")

    crate = tomllib.loads((RUST / "crates/dsha-core/Cargo.toml").read_text(encoding="utf-8"))
    if crate["lib"]["name"] != lock["crate"]["lib"] or crate["lib"]["crate-type"] != [lock["crate"]["crateType"]]:
        raise GateError("RUST_CRATE_LIB")
    edition = crate["package"]["edition"]
    if isinstance(edition, dict) and edition.get("workspace"):
        edition = manifest["workspace"]["package"]["edition"]
    if edition != lock["crate"]["edition"]:
        raise GateError("RUST_CRATE_EDITION")

    abi_source = (RUST / "crates/dsha-core/src/abi.rs").read_text(encoding="utf-8")
    version = re.search(r"pub const ABI_VERSION: i32 = (\d+);", abi_source)
    if not version or int(version.group(1)) != lock["abi"]["version"]:
        raise GateError("RUST_ABI_VERSION_SOURCE")

    # Cargo.lock 与冻结依赖清单逐项一致（名字/版本/checksum/registry 来源）。
    lock_data = tomllib.loads((RUST / "Cargo.lock").read_text(encoding="utf-8"))
    resolved = {row["name"]: row for row in lock_data["package"]}
    if resolved.get("dsha-core", {}).get("version") != "0.1.0":
        raise GateError("RUST_LOCK_WORKSPACE")
    expected = {row["name"]: row for row in lock["dependencies"]}
    if set(resolved) - {"dsha-core"} != set(expected):
        raise GateError("RUST_LOCK_CRATES: %s" % sorted(set(resolved) - {"dsha-core"} ^ set(expected)))
    for name, row in expected.items():
        actual = resolved[name]
        if actual.get("version") != row["version"] or actual.get("checksum") != row["checksum"]:
            raise GateError("RUST_LOCK_MISMATCH: " + name)
        source = actual.get("source", "")
        if source != "registry+https://github.com/rust-lang/crates.io-index":
            raise GateError("RUST_LOCK_SOURCE: %s -> %s" % (name, source))

    ndk = load_json(CI_TOOLCHAIN)["ndk"]
    if lock["ndk"] != ndk:
        raise GateError("RUST_NDK_LOCK: %s != ci/toolchain.lock.json %s" % (lock["ndk"], ndk))

    vendor = RUST / "vendor"
    dirs = sorted(p.name for p in vendor.iterdir() if p.is_dir())
    expected_dirs = sorted("%s-%s" % (row["name"], row["version"]) for row in lock["dependencies"])
    if dirs != expected_dirs:
        raise GateError("RUST_VENDOR_DIRS")
    files = [p for p in vendor.rglob("*") if p.is_file()]
    total = sum(p.stat().st_size for p in files)
    if len(files) != lock["vendor"]["files"] or total != lock["vendor"]["bytes"]:
        raise GateError("RUST_VENDOR_SUMMARY: %d files/%d bytes != %d/%d"
                        % (len(files), total, lock["vendor"]["files"], lock["vendor"]["bytes"]))

    for row in lock["buildInputs"]:
        path = RUST / row["path"]
        if not path.is_file() or digest(path) != row["sha256"]:
            raise GateError("RUST_BUILD_INPUT_CHANGED: " + row["path"])
    declared = {row["path"] for row in lock["buildInputs"] if row["kind"] == "build.rs"}
    present = {p.relative_to(RUST).as_posix() for p in vendor.rglob("build.rs")}
    if declared != present:
        raise GateError("RUST_BUILD_SCRIPT_UNREVIEWED: %s" % sorted(present - declared))

    for row in lock["dependencies"]:
        if not row["licenseFiles"]:
            raise GateError("RUST_LICENSE_FILES_EMPTY: " + row["name"])


def verify_installed(lock: dict) -> dict:
    env = dict(**__import__("os").environ)
    env.pop("RUSTC_WRAPPER", None)
    try:
        version = subprocess.run(["rustc", "-vV"], cwd=RUST, env=env, capture_output=True,
                                 text=True, check=True).stdout
        cargo = subprocess.run(["cargo", "-V"], cwd=RUST, env=env, capture_output=True,
                               text=True, check=True).stdout
        targets = subprocess.run(["rustup", "target", "list", "--installed"], cwd=RUST,
                                 env=env, capture_output=True, text=True, check=True).stdout
    except (OSError, subprocess.CalledProcessError) as error:
        raise GateError("RUST_TOOLCHAIN_MISSING: 无法运行 rustc/cargo/rustup: %s" % error)
    fields = {}
    for line in version.splitlines():
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip()
    if fields.get("release") != lock["rustc"]["version"] or not fields.get(
            "commit-hash", "").startswith(lock["rustc"]["commit"]):
        raise GateError("RUST_TOOLCHAIN_INSTALLED: %s (%s)"
                        % (fields.get("release"), fields.get("commit-hash")))
    if cargo.split()[1] != lock["cargo"]["version"]:
        raise GateError("RUST_CARGO_INSTALLED: " + cargo.strip())
    installed = set(targets.split())
    host = fields.get("host", "")
    if "aarch64-linux-android" not in installed:
        raise GateError("RUST_TARGET_INSTALLED: aarch64-linux-android 缺失")
    if host and host not in installed:
        raise GateError("RUST_TARGET_INSTALLED: 宿主 target 缺失: " + host)
    return {"rustc": fields.get("release"), "host": host, "cargo": cargo.split()[1],
            "targets": sorted(installed)}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-installed", action="store_true")
    args = parser.parse_args(argv)
    lock = load_json(LOCK)
    verify_sources(lock)
    result = {"status": "PASS", "channel": lock["channel"],
              "crates": len(lock["dependencies"]), "vendorBytes": lock["vendor"]["bytes"]}
    if args.verify_installed:
        result["installed"] = verify_installed(lock)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except GateError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)

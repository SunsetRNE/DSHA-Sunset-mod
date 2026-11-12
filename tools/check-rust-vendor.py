#!/usr/bin/env python3
"""离线核对入库 `rust/vendor/` 与 `rust/Cargo.lock`；`--regenerate` 需要有网重生成比对。

默认模式（无网络、CI rust-check 与本地都跑）：

- 每个 vendor 目录名必须是 `<crate>-<version>` 且与 `Cargo.lock` 一致；
- `.cargo-checksum.json` 的 `package` 必须等于 `Cargo.lock` 记录的 registry checksum；
- 该文件列出的每个成员 SHA-256 必须与实际字节一致；不得有未登记文件。

`--regenerate`（CI rust-repro，有网络）：把 manifest 复制到无 source 替换的临时目录，
用同一 `Cargo.lock --locked` 从 crates.io 重新 `cargo vendor`，再与入库树逐文件逐字节
比对。字节不一致、文件集合变化或版本漂移都失败；这是“vendor 新鲜度”门禁，不替代
工具链锁。
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import tomllib

ROOT = Path(__file__).resolve().parents[1]
RUST = ROOT / "rust"
LOCK = ROOT / "ci/rust-toolchain.lock.json"


class GateError(RuntimeError):
    pass


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def lock_packages() -> dict:
    data = tomllib.loads((RUST / "Cargo.lock").read_text(encoding="utf-8"))
    return {row["name"]: row for row in data["package"] if "source" in row}


def verify_offline() -> dict:
    packages = lock_packages()
    lock = load_json(LOCK)
    if set(packages) != {row["name"] for row in lock["dependencies"]}:
        raise GateError("RUST_VENDOR_LOCK_SET")
    vendor = RUST / "vendor"
    if not vendor.is_dir():
        raise GateError("RUST_VENDOR_MISSING")
    verified = 0
    for name, row in sorted(packages.items()):
        directory = vendor / ("%s-%s" % (name, row["version"]))
        if not directory.is_dir():
            raise GateError("RUST_VENDOR_DIR_MISSING: " + directory.name)
        checksum_path = directory / ".cargo-checksum.json"
        if not checksum_path.is_file():
            raise GateError("RUST_VENDOR_CHECKSUM_MISSING: " + directory.name)
        metadata = load_json(checksum_path)
        if metadata.get("package") != row.get("checksum"):
            raise GateError("RUST_VENDOR_PACKAGE_CHECKSUM: " + name)
        files = metadata.get("files")
        if not isinstance(files, dict) or not files:
            raise GateError("RUST_VENDOR_FILES_EMPTY: " + name)
        listed = set(files) | {".cargo-checksum.json"}
        actual = {p.relative_to(directory).as_posix()
                  for p in directory.rglob("*") if p.is_file()}
        if actual != listed:
            raise GateError("RUST_VENDOR_UNLISTED_FILES: %s %s"
                            % (name, sorted((actual ^ listed))[:5]))
        for relative, expected in files.items():
            path = directory / relative
            if not path.is_file():
                raise GateError("RUST_VENDOR_FILE_MISSING: %s/%s" % (name, relative))
            if digest(path) != expected:
                raise GateError("RUST_VENDOR_FILE_CHANGED: %s/%s" % (name, relative))
        verified += 1
    return {"status": "PASS", "crates": verified, "files": len(lock["dependencies"])}


def copy_manifest(target: Path) -> None:
    shutil.copyfile(RUST / "Cargo.toml", target / "Cargo.toml")
    shutil.copyfile(RUST / "Cargo.lock", target / "Cargo.lock")
    shutil.copyfile(RUST / "rust-toolchain.toml", target / "rust-toolchain.toml")
    crate = target / "crates/dsha-core"
    crate.mkdir(parents=True)
    shutil.copyfile(RUST / "crates/dsha-core/Cargo.toml", crate / "Cargo.toml")
    shutil.copytree(RUST / "crates/dsha-core/src", crate / "src")


def tree_hashes(root: Path) -> dict:
    return {p.relative_to(root).as_posix(): digest(p)
            for p in root.rglob("*") if p.is_file()}


def regenerate() -> dict:
    temporary = Path(tempfile.mkdtemp(prefix="dsha-rust-vendor-"))
    try:
        manifest = temporary / "manifest"
        manifest.mkdir()
        copy_manifest(manifest)
        output = temporary / "vendor"
        env = dict(__import__("os").environ)
        env.pop("RUSTC_WRAPPER", None)
        # 故意不复制 .cargo/config.toml：重生成必须访问 crates.io，而不是从入库 vendor
        # 自证。CARGO_HOME 沿用缓存；--locked 保证版本集不可漂移。
        env.pop("CARGO_NET_OFFLINE", None)
        result = subprocess.run(["cargo", "vendor", "--locked", "--versioned-dirs", str(output)],
                                cwd=manifest, env=env, capture_output=True, text=True, check=False)
        if result.returncode != 0:
            raise GateError("RUST_VENDOR_REGENERATE_FAILED:\n%s\n%s"
                            % (result.stdout[-2000:], result.stderr[-4000:]))
        committed = tree_hashes(RUST / "vendor")
        fresh = tree_hashes(output)
        if set(committed) != set(fresh):
            missing = sorted(set(committed) - set(fresh))[:10]
            extra = sorted(set(fresh) - set(committed))[:10]
            raise GateError("RUST_VENDOR_FILE_SET: missing=%s extra=%s" % (missing, extra))
        changed = [name for name in sorted(committed) if committed[name] != fresh[name]]
        if changed:
            raise GateError("RUST_VENDOR_BYTES: %s" % changed[:10])
        return {"status": "PASS", "regenerated": True, "files": len(fresh),
                "bytes": sum((output / name).stat().st_size for name in fresh)}
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--regenerate", action="store_true",
                        help="从 crates.io 重新 vendor 并逐字节比对（需要网络，仅 CI rust-repro）")
    args = parser.parse_args(argv)
    result = verify_offline()
    if args.regenerate:
        result["regenerate"] = regenerate()
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except GateError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)

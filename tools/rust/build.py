#!/usr/bin/env python3
"""构建并核验 Rust 旁路产物（R0 工具链骨架）。

用法（一次只跑一个目标，构建串行）：

    python3 tools/rust/build.py --host  [--check] [--out DIR] [--receipt FILE] [--expect SHA256]
    python3 tools/rust/build.py --android --ndk PATH [--check] [--out DIR] [--receipt FILE] [--expect SHA256]

`--check`：把产物重建到临时目录（不同源码目录 + 固定 `--remap-path-prefix`），
逐字节比对两次构建；不一致即失败，不替换产物。`--expect` 与给定 SHA-256 比对，
用于跨 runner 复现门禁（R1 起由 `rust/artifact.lock.json` 提供；R0 不写锁）。
`--receipt` 写出 JSON 回执：工具链、源/锁/vendor 摘要、链接参数、产物摘要与
ELF 静态检查结果。本脚本只构建，不安装、不签名、不修改 APK。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

TOOLS = Path(__file__).resolve().parents[1]
ROOT = TOOLS.parent
RUST = ROOT / "rust"
LOCK = ROOT / "ci/rust-toolchain.lock.json"
BUDGET = RUST / "native-budget.json"
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from git_guard import git_output  # noqa: E402

import elf  # noqa: E402

LINK_ARGS = (
    "-Wl,-z,max-page-size=16384",
    "-Wl,-z,common-page-size=4096",
    "-Wl,-z,relro",
    "-Wl,-z,now",
    "-Wl,-z,noexecstack",
)


class BuildError(RuntimeError):
    """构建/核验失败；调用方必须失败，不得降级成“跳过 Rust”。"""


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def tree_digest(root: Path, names: list[str]) -> str:
    """对相对路径 + 文件摘要做稳定摘要（不依赖 mtime / 目录遍历顺序）。"""
    value = hashlib.sha256()
    for name in sorted(names):
        value.update(name.encode("utf-8") + b"\0")
        value.update(digest(root / name).encode("ascii") + b"\n")
    return value.hexdigest()


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def run(command: list[str], *, cwd: Path, env: dict) -> str:
    try:
        result = subprocess.run(command, cwd=cwd, env=env, capture_output=True,
                                text=True, check=False)
    except OSError as error:
        raise BuildError("无法执行 %s: %s" % (command[0], error)) from error
    if result.returncode != 0:
        raise BuildError("命令失败 (%d): %s\nstdout:\n%s\nstderr:\n%s"
                         % (result.returncode, " ".join(command),
                            result.stdout[-4000:], result.stderr[-4000:]))
    return result.stdout


def toolchain(lock: dict) -> dict:
    """固定 RUSTC 为本目录 rust-toolchain.toml 解析出的已安装 rustc（不依赖 PATH）。"""
    env = dict(os.environ)
    env.pop("RUSTC_WRAPPER", None)
    try:
        rustc_path = run(["rustup", "which", "rustc"], cwd=RUST, env=env).strip()
    except (BuildError, FileNotFoundError):
        candidate = shutil.which("rustc")
        if not candidate:
            raise BuildError("RUST_TOOLCHAIN_MISSING: 找不到 rustc（需要 rustup 1.100.0 工具链）")
        rustc_path = candidate
    if not Path(rustc_path).is_file():
        raise BuildError("RUST_TOOLCHAIN_MISSING: rustc 路径不存在: " + rustc_path)
    version = run([rustc_path, "-vV"], cwd=RUST, env=env)
    fields = {}
    for line in version.splitlines():
        key, _, value = line.partition(":")
        fields[key.strip()] = value.strip()
    release = fields.get("release", "")
    commit = fields.get("commit-hash", "")
    host = fields.get("host", "")
    expected = lock["rustc"]
    if release != expected["version"] or not commit.startswith(expected["commit"]):
        raise BuildError("RUST_TOOLCHAIN_MISMATCH: rustc %s (%s)，要求 %s (%s)"
                         % (release, commit, expected["version"], expected["commit"]))
    cargo = run(["cargo", "-V"], cwd=RUST, env=env).split()
    if len(cargo) < 2 or cargo[1] != lock["cargo"]["version"]:
        raise BuildError("RUST_TOOLCHAIN_MISMATCH: cargo %s，要求 %s"
                         % (" ".join(cargo), lock["cargo"]["version"]))
    return dict(rustc=release, rustcCommit=commit, cargo=cargo[1], host=host,
                rustcPath=str(Path(rustc_path).resolve()))


def ndk_env(ndk: Path | None, lock: dict) -> dict:
    """返回 android 构建的额外环境；不带 NDK 时由调用方拒绝 android 构建。"""
    if ndk is None:
        raise BuildError("ANDROID_NDK_REQUIRED: --android 需要 --ndk <NDK 根目录>")
    properties = ndk / "source.properties"
    if not properties.is_file():
        raise BuildError("ANDROID_NDK_INVALID: 缺少 source.properties: %s" % ndk)
    revision = ""
    for line in properties.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith("Pkg.Revision"):
            revision = line.split("=", 1)[-1].strip()
    if revision != lock["ndk"]:
        raise BuildError("ANDROID_NDK_MISMATCH: %s != %s" % (revision, lock["ndk"]))
    linker = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin" / (
        "aarch64-linux-android%d-clang" % lock["androidApi"])
    if not linker.is_file():
        raise BuildError("ANDROID_LINKER_MISSING: %s（本机 aarch64 无 x86_64 NDK 工具链时属预期）"
                         % linker)
    return {str(linker): "aarch64-linux-android"}


def build_env(target: str, *, triples: dict, ndk: Path | None, lock: dict,
              target_dir: Path, remap_source: Path, jobs: int) -> dict:
    env = dict(os.environ)
    # §0.2.2：RUSTC_WRAPPER 是唯一外部命令入口；R0 构建显式清掉它并固定 RUSTC。
    for key in ("RUSTC_WRAPPER", "CARGO_ENCODED_RUSTFLAGS", "CARGO_BUILD_RUSTFLAGS"):
        env.pop(key, None)
    cargo_home = Path(env.get("CARGO_HOME") or (Path.home() / ".cargo")).resolve()
    rustup_home = Path(env.get("RUSTUP_HOME") or (Path.home() / ".rustup")).resolve()
    found = toolchain(lock)
    env["RUSTC"] = found["rustcPath"]
    remap = [
        "--remap-path-prefix=%s=/src" % remap_source,
        "--remap-path-prefix=%s=/cargo" % cargo_home,
        "--remap-path-prefix=%s=/rustup" % rustup_home,
    ]
    if ndk is not None:
        remap.append("--remap-path-prefix=%s=/ndk" % ndk.resolve())
    flags = list(remap)
    for item in LINK_ARGS:
        flags.append("-C")
        flags.append("link-arg=" + item)
    env["RUSTFLAGS"] = " ".join(flags)
    env["CARGO_TARGET_DIR"] = str(target_dir)
    env["CARGO_BUILD_JOBS"] = str(jobs)
    env["CARGO_TERM_COLOR"] = "never"
    env["CARGO_NET_OFFLINE"] = "true"
    env["RUST_BACKTRACE"] = "0"
    if target == "android":
        if ndk is None:
            raise BuildError("ANDROID_NDK_REQUIRED")
        env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = triples["linker"]
    return env


def artifact_path(target_dir: Path, target: str) -> Path:
    base = target_dir / (("aarch64-linux-android") if target == "android" else "") / "release"
    return base / "libdsha_rs.so"


def build_once(*, target: str, target_dir: Path, remap_source: Path, ndk: Path | None,
               lock: dict, jobs: int) -> Path:
    triples = {"linker": ""}
    if target == "android":
        linker_map = ndk_env(ndk, lock)
        triples["linker"] = next(iter(linker_map))
    env = build_env(target, triples=triples, ndk=ndk, lock=lock,
                    target_dir=target_dir, remap_source=remap_source, jobs=jobs)
    command = ["cargo", "build", "--release", "--locked", "--offline"]
    if target == "android":
        command += ["--target", "aarch64-linux-android"]
    run(command, cwd=remap_source, env=env)
    path = artifact_path(target_dir, target)
    if not path.is_file():
        raise BuildError("RUST_ARTIFACT_MISSING: " + str(path))
    return path


def source_manifest() -> tuple[dict, str, str]:
    lock = load_json(LOCK)
    cargo_lock = RUST / "Cargo.lock"
    vendor = RUST / "vendor"
    vendor_files = [p.relative_to(vendor).as_posix() for p in vendor.rglob("*") if p.is_file()]
    vendor_sha = tree_digest(vendor, vendor_files)
    crate_files = [p.relative_to(RUST).as_posix()
                   for p in (RUST / "crates").rglob("*") if p.is_file()]
    crate_sha = tree_digest(RUST, crate_files)
    return lock, vendor_sha, crate_sha


def scan_artifact(data: bytes, forbidden: list[str]) -> None:
    for name, needle in forbidden:
        if needle.encode("utf-8") in data:
            raise BuildError("RUST_ARTIFACT_LEAK: 产物包含 %s（%s）" % (name, needle))


def describe_artifact(path: Path, *, target: str, lock: dict, budget: dict) -> dict:
    data = path.read_bytes()
    contract = elf.inspect_with_symbols(path)
    expected = sorted(lock["abi"]["symbols"])
    if contract["exported_functions"] != expected:
        raise BuildError("RUST_ABI_SYMBOLS: 导出符号与 ci/rust-toolchain.lock.json 不一致\n实际: %s\n期望: %s"
                         % (contract["exported_functions"], expected))
    if target == "android" and contract["machine"] != elf.EM_AARCH64:
        raise BuildError("RUST_ABI_MACHINE: e_machine=%d，要求 %d"
                         % (contract["machine"], elf.EM_AARCH64))
    if target == "host" and contract["machine"] not in (62, elf.EM_AARCH64):
        raise BuildError("RUST_ABI_MACHINE: 宿主 e_machine=%d 不是 x86_64/aarch64"
                         % contract["machine"])
    if not contract["aligned_16k"]:
        raise BuildError("RUST_ELF_ALIGN: 存在未按 16 KB 对齐的 PT_LOAD")
    if not contract["relro_mapped"]:
        raise BuildError("RUST_ELF_RELRO: PT_GNU_RELRO 未落在页映射内")
    if target == "android":
        allowed = set(lock["androidNeededAllowed"])
        unexpected = [item for item in contract["needed"] if item not in allowed]
        if unexpected:
            raise BuildError("RUST_ELF_NEEDED: 出现未登记依赖 %s（允许 %s）"
                             % (unexpected, sorted(allowed)))
    scan_artifact(data, [("仓库路径", str(ROOT)), ("cargo home", str(Path.home() / ".cargo")),
                         ("rustup home", str(Path.home() / ".rustup")),
                         ("CI 家目录", "/home/runner")])
    size = len(data)
    if size > budget["rustArtifactBytes"]:
        raise BuildError("RUST_BUDGET: 产物 %d B 超过硬上限 %d B"
                         % (size, budget["rustArtifactBytes"]))
    if size > budget["rustWarnBytes"]:
        print("::warning::Rust 产物 %d B 超过警戒线 %d B（硬上限 %d B）"
              % (size, budget["rustWarnBytes"], budget["rustArtifactBytes"]))
    return dict(artifactFile=path.name, size=size, sha256=digest(path),
                elfMachine=contract["machine"], aligned16k=contract["aligned_16k"],
                relroMapped=contract["relro_mapped"], needed=contract["needed"],
                exportedSymbols=contract["exported_functions"],
                budget="WARN" if size > budget["rustWarnBytes"] else "PASS")


def receipt(*, found: dict, lock: dict, target: str, ndk: Path | None,
            artifact: dict, reproducible: dict, jobs: int) -> dict:
    vendor_sha, crate_sha = "", ""
    lock_data = load_json(LOCK)
    vendor_sha, crate_sha = source_manifest()[1:]
    run_id = os.environ.get("GITHUB_RUN_ID")
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT")
    try:
        commit = git_output(["rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except Exception:  # 仅回执字段；没有 git 时如实写 null，不伪造绑定
        commit = None
    return {
        "schema": 1,
        "kind": "dsha-rust-native",
        "commit": commit,
        "runId": run_id,
        "runAttempt": attempt,
        "target": target,
        "toolchain": {"rustc": found["rustc"], "rustcCommit": found["rustcCommit"],
                      "cargo": found["cargo"], "host": found["host"],
                      "channel": lock["channel"], "targetTriple":
                          "aarch64-linux-android" if target == "android" else found["host"],
                      "androidApi": lock["androidApi"] if target == "android" else None,
                      "ndk": lock["ndk"] if target == "android" else None,
                      "linker": lock["linker"] if target == "android" else None},
        "sources": {"cargoLockSha256": digest(RUST / "Cargo.lock"),
                    "vendorTreeSha256": vendor_sha, "crateSha256": crate_sha,
                    "buildInputs": [row["path"] for row in lock["buildInputs"]],
                    "vendorFiles": lock["vendor"]["files"],
                    "vendorBytes": lock["vendor"]["bytes"]},
        "build": {"profile": "release", "linkArgs": list(LINK_ARGS),
                  "remapPathPrefixes": True, "offline": True, "locked": True,
                  "jobs": jobs,
                  "environment": {"RUSTC_WRAPPER": "cleared", "RUSTFLAGS": "set by build.py",
                                  "CARGO_NET_OFFLINE": "true"}},
        "artifact": artifact,
        "reproducible": reproducible,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    which = parser.add_mutually_exclusive_group(required=True)
    which.add_argument("--host", action="store_true", help="构建主机目标（供 JVM 差分/探针）")
    which.add_argument("--android", action="store_true", help="交叉构建 aarch64-linux-android")
    parser.add_argument("--ndk", type=Path, help="NDK 根目录（--android 必需）")
    parser.add_argument("--check", action="store_true",
                        help="不同源码目录重建两次并逐字节比对（默认串行）")
    parser.add_argument("--out", type=Path, help="把最终 .so 复制到该目录")
    parser.add_argument("--receipt", type=Path, help="写出构建回执 JSON")
    parser.add_argument("--expect", help="与给定 SHA-256 比对（跨 runner 复现门禁）")
    parser.add_argument("--jobs", type=int, default=2, help="CARGO_BUILD_JOBS（默认 2，避免叠加构建）")
    args = parser.parse_args(argv)
    target = "android" if args.android else "host"
    lock = load_json(LOCK)
    budget = load_json(BUDGET)
    if args.jobs < 1 or args.jobs > 8:
        raise BuildError("--jobs 必须在 1..8")
    ndk = args.ndk.resolve() if args.ndk else None
    if target == "android":
        ndk_env(ndk, lock)
    elif ndk is not None:
        raise BuildError("--ndk 只用于 --android")

    work = RUST / "target/rust-build"
    artifact = build_once(target=target, target_dir=work, remap_source=RUST,
                          ndk=ndk, lock=lock, jobs=args.jobs)
    found = toolchain(lock)
    reproducible = {"status": "NOT_RUN", "differentDirectory": False,
                    "firstSha256": None, "secondSha256": None}
    if args.check:
        # 第二次构建：复制 rust/ 到仓库外临时目录（不含 target），验证不同源码目录
        # 在固定 --remap-path-prefix 下字节一致；两次构建串行，不并行叠加内存。
        temporary = Path(tempfile.mkdtemp(prefix="dsha-rust-repro-"))
        try:
            copy = temporary / "rust"
            shutil.copytree(RUST, copy, ignore=shutil.ignore_patterns("target"))
            second = build_once(target=target, target_dir=copy / "target/repro",
                                remap_source=copy, ndk=ndk, lock=lock, jobs=args.jobs)
            first_sha, second_sha = digest(artifact), digest(second)
            if first_sha != second_sha:
                raise BuildError("RUST_NOT_REPRODUCIBLE: 两次构建字节不同 %s != %s"
                                 % (first_sha, second_sha))
            reproducible = {"status": "PASS", "differentDirectory": True,
                            "firstSha256": first_sha, "secondSha256": second_sha}
        finally:
            shutil.rmtree(temporary, ignore_errors=True)

    final_sha = digest(artifact)
    if args.expect and final_sha != args.expect:
        raise BuildError("RUST_ARTIFACT_EXPECT: %s != %s" % (final_sha, args.expect))
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(artifact, args.out / artifact.name)
    info = describe_artifact(artifact, target=target, lock=lock, budget=budget)
    info["reproducible"] = reproducible["status"]
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        data = receipt(found=found, lock=lock, target=target, ndk=ndk, artifact=info,
                       reproducible=reproducible, jobs=args.jobs)
        args.receipt.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                                encoding="utf-8", newline="\n")
    print(json.dumps({"status": "PASS", "target": target, "artifact": str(artifact),
                      "size": info["size"], "sha256": info["sha256"],
                      "reproducible": reproducible["status"], "budget": info["budget"]},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except BuildError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)

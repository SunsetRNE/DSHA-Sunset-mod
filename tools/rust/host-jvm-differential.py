#!/usr/bin/env python3
"""主机 JVM 差分探针：用 JDK17 编译生产桥 + host-jvm/，加载主机目标 .so 对拍 digest。

R0 只证明“Rust host 库 + JDK17 JVM”的 System.load 与 JNI 调用链；R1 起直接编译
`app/src/main/java/com/deepseekharness/app/util/{RustNative,RustDigest,RustBackends}.java`
（纯 Java、零 Android import），在同一进程里对比 Rust 后端与 JDK `MessageDigest`：

- SHA-256 / SHA-1 已知向量、offset/中间区间、CHUNK 边界、单次 32 MiB 上限；
- 空内容（length=0 按 ABI 拒绝）、EOF 短读、非法 offset/length/algorithm、负 fd/巨大 fd、
  只写 fd、关闭的 fd、非 direct / 过小 direct 输出缓冲、null 输出缓冲；
- 运行层开关默认 off / 显式 on / 一键回退，摘要不变。

`-Xcheck:jni` 全程开启；探针不经 Gradle、不依赖 Robolectric、不接入 Android 单测，也不
证明 Android `.so` 已加载（后者由 R3 签名 `.dev` 包与 APK 静态门禁覆盖）。
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

ROOT = Path(__file__).resolve().parents[2]
PROBE_SOURCE = ROOT / "tools/rust/host-jvm"
APP_SOURCE = ROOT / "app/src/main/java/com/deepseekharness/app/util"
BRIDGE_SOURCES = (
    "RustNative.java",
    "RustDigest.java",
    "RustBackends.java",
)
PROBE_CLASS = "com.deepseekharness.app.util.RustHostProbe"

# 固定的检查名称集合（评审 P2-7）：探针新增/删除检查必须同步改这里，避免
# “碰巧少跑了几项但整体仍 PASS”。loadLibrary 在源码失败/成功两个分支同名，只算一次。
EXPECTED_CHECKS = {
    "abiVersion",
    "chunkBoundaryPair",
    "classPinned",
    "closedFd",
    "crossChunk",
    "eofShortRead",
    "errorZeroWrite",
    "heapBufferRejected",
    "hugeFd",
    "invalidLengthTooLarge",
    "invalidLengthZero",
    "invalidOffset",
    "loadLibrary",
    "maxLength32MiB",
    "messageDigestReference",
    "negativeFd",
    "notImplementedStub",
    "nullBufferRejected",
    "offsetMiddle",
    "offsetOverflow",
    "oneKeyRollback",
    "outputBound",
    "outputBoundNoPartialWrite",
    "sha1Hex",
    "sha1KnownVector",
    "sha256Hex",
    "sha256KnownVector",
    "switchDigestEqual",
    "switchOffUsesJvm",
    "switchOnUsesRust",
    "unknownAlgorithm",
    "writeOnlyFd",
    "zeroLengthRejected",
}


class ProbeError(RuntimeError):
    pass


def java_home() -> Path:
    candidate = os.environ.get("JAVA_HOME")
    if candidate and (Path(candidate) / "bin/java").is_file():
        return Path(candidate)
    for fallback in ("/opt/jdk17", "/usr/lib/jvm/temurin-17-jdk-amd64"):
        if (Path(fallback) / "bin/java").is_file():
            return Path(fallback)
    java = shutil.which("java")
    if java:
        return Path(java).resolve().parents[1]
    raise ProbeError("JDK17_UNAVAILABLE: 设置 JAVA_HOME 或安装 JDK 17")


def run(command: list[str], *, cwd: Path, env: dict) -> str:
    result = subprocess.run(command, cwd=cwd, env=env, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise ProbeError("命令失败 (%d): %s\n%s\n%s"
                         % (result.returncode, " ".join(command), result.stdout[-2000:],
                            result.stderr[-4000:]))
    return result.stdout


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lib", type=Path, required=True, help="主机目标 libdsha_rs.so")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args(argv)
    library = args.lib.resolve()
    if not library.is_file():
        raise ProbeError("RUST_HOST_LIB_MISSING: " + str(library))
    sources = [PROBE_SOURCE / "RustHostProbe.java"]
    for name in BRIDGE_SOURCES:
        source = APP_SOURCE / name
        if not source.is_file():
            raise ProbeError("RUST_BRIDGE_SOURCE_MISSING: " + str(source))
        sources.append(source)
    home = java_home()
    env = dict(os.environ)
    env.pop("PYTHONOPTIMIZE", None)
    with tempfile.TemporaryDirectory(prefix="dsha-rust-jvm-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        run([str(home / "bin/javac"), "-encoding", "UTF-8", "-Xlint:all", "-Werror",
             "-d", str(classes), *[str(path) for path in sources]], cwd=ROOT, env=env)
        output = run([str(home / "bin/java"), "-Xcheck:jni",
                      "--add-opens", "java.base/java.io=ALL-UNNAMED",
                      "-cp", str(classes), PROBE_CLASS, str(library)], cwd=ROOT, env=env)
    rows = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
    checks = [row for row in rows if "check" in row]
    summary = next((row for row in rows if "status" in row and "check" not in row), {})
    failed = [row for row in checks if row.get("status") != "PASS"]
    names = {row.get("check") for row in checks}
    missing = sorted(EXPECTED_CHECKS - names)
    unexpected = sorted(names - EXPECTED_CHECKS)
    if missing or unexpected or len(checks) != len(EXPECTED_CHECKS):
        raise ProbeError("RUST_HOST_PROBE_CHECKS: missing=%s unexpected=%s count=%d expected=%d"
                         % (missing, unexpected, len(checks), len(EXPECTED_CHECKS)))
    status = "PASS" if checks and not failed and summary.get("failures", 1) == 0 else "FAIL"
    if status != "PASS":
        raise ProbeError("RUST_HOST_PROBE_FAILED:\n" + output)
    report = {
        "schema": 1,
        "kind": "dsha-rust-host-differential",
        "status": "PASS",
        "scope": "R1 digest 主机 JVM 差分：SHA-256/SHA-1、offset/跨块/32 MiB、短读、非法参数、"
                 "fd 生命周期、direct buffer 契约、运行层开关与回退；-Xcheck:jni 全程开启。"
                 "不证明 Android .so 已加载（R3 设备证据单独记录）",
        "library": {"file": library.name, "size": library.stat().st_size,
                    "sha256": hashlib.sha256(library.read_bytes()).hexdigest()},
        "java": {"home": str(home),
                 "version": next((row.get("javaVersion") for row in rows
                                  if row.get("javaVersion")), None)},
        "checks": checks,
        "tests": {"probeChecks": len(checks), "failures": len(failed), "skipped": 0},
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8", newline="\n")
    print(json.dumps({"status": "PASS", "checks": len(checks),
                      "librarySha256": report["library"]["sha256"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except ProbeError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)

#!/usr/bin/env python3
"""主机 JVM 差分探针骨架：用 JDK17 编译并运行 host-jvm/，加载主机目标 .so。

R0 只证明“Rust host 库 + JDK17 JVM”的 System.load 与 JNI 调用链（abiVersion 与
NOT_IMPLEMENTED 占位返回码）。R1 起在同一骨架上扩展 digest / gzip / verify_plan 的
JVM↔Rust 对拍；探针不经 Gradle、不依赖 Robolectric、不接入 Android 单测。
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
JAVA_SOURCE = ROOT / "tools/rust/host-jvm"


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
    home = java_home()
    env = dict(os.environ)
    env.pop("PYTHONOPTIMIZE", None)
    with tempfile.TemporaryDirectory(prefix="dsha-rust-jvm-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        sources = sorted(str(path) for path in JAVA_SOURCE.rglob("*.java"))
        run([str(home / "bin/javac"), "-encoding", "UTF-8", "-Xlint:all", "-Werror",
             "-d", str(classes), *sources], cwd=ROOT, env=env)
        output = run([str(home / "bin/java"), "-Xcheck:jni", "-cp", str(classes), "RustHostProbe",
                      str(library)], cwd=ROOT, env=env)
    rows = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
    checks = [row for row in rows if "check" in row]
    status = "PASS" if checks and all(row.get("status") == "PASS" for row in checks) else "FAIL"
    if status != "PASS":
        raise ProbeError("RUST_HOST_PROBE_FAILED:\n" + output)
    report = {
        "schema": 1,
        "kind": "dsha-rust-host-differential",
        "status": "PASS",
        "scope": "R0 加载与调用链（abiVersion / NOT_IMPLEMENTED）；不证明 Android .so 已加载，"
                 "真机证据由 R3 签名 .dev 包单独记录",
        "library": {"file": library.name, "size": library.stat().st_size,
                    "sha256": hashlib.sha256(library.read_bytes()).hexdigest()},
        "java": {"home": str(home),
                 "version": next((row.get("javaVersion") for row in rows
                                  if row.get("javaVersion")), None)},
        "checks": checks,
        "tests": {"probeChecks": len(checks), "failures": 0, "skipped": 0},
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

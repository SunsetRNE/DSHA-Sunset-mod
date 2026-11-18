#!/usr/bin/env python3
"""主机 JVM 差分探针：JDK17 编译生产桥 + host-jvm/，加载主机目标 .so 对拍。

R1 起按源码布局解析生产 JVM 桥（Java/Kotlin）；R3 另含 JVM 参考读取器及限额常量。
Java-only 闭包继续只用 javac；混合闭包要求显式提供与仓库 KGP/验证元数据一致的
编译器 JAR 目录，不联网、不自动改写 Gradle 缓存，然后在同一进程内对拍：

- digest（R1）：SHA-256 / SHA-1 已知向量、offset/中间区间、CHUNK 边界、单次 32 MiB
  上限；空内容（length=0 按 ABI 拒绝）、EOF 短读、非法 offset/length/algorithm、负
  fd/巨大 fd、只写 fd、关闭的 fd、非 direct / 过小 direct 输出缓冲、null 输出缓冲；
  运行层开关默认 off / 显式 on / 一键回退，摘要不变。
- verify_plan（R2）：`PlanViewCodec`/`PlanViewValidator` 与 Rust `plan::validate` 在
  `tools/rust/fixtures.json` 确定性语料（含 256 条种子随机视图）上比较 VALID/INVALID
  与原因码集合；另测 JNI direct buffer 契约、OUTPUT_BOUND 不写半成品、2 MiB 视图上限、
  幂等与输入不变。

- archive（R3）：生产桥 `RustGzipOutputStream`/`RustGzipInputStream` 与 JVM 参考实现
  `StrictGzipInputStream`/`GZIPOutputStream` 对拍单成员 gzip：Rust pack → JVM 严格读取、
  JVM pack → Rust 读取、分块 1/7/4096 字节一致、第三方（Python gzip）固定语料、合法/非法
  语料逐例比较“输出字节 + 错误消息”、截断前缀、native 契约错误码、会话上限、运行层开关。

三个探针都在 `-Xcheck:jni` 下运行；探针不经 Gradle、不依赖 Robolectric、不接入
Android 单测，也不证明 Android `.so` 已加载（后者由 R3 签名 `.dev` 包与 APK 静态门禁
覆盖）。检查名称集合固定（`EXPECTED_CHECKS`），漏跑/多跑都失败。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from source_layout import SourceLayout  # noqa: E402
PROBE_SOURCE = ROOT / "tools/rust/host-jvm"
APP_SOURCE = ROOT / "app/src/main/java/com/deepseekharness/app"
MAKE_FIXTURES = ROOT / "tools/rust/make-fixtures.py"
BRIDGE_SOURCES = (
    "util/RustNative",
    "util/RustDigest",
    "util/RustBackends",
    "util/PlanViewCodec",
    "util/PlanViewValidator",
    "util/RustArchiveCodec",
    "util/RustGzipOutputStream",
    "util/RustGzipInputStream",
    # JVM 参考读取器（生产实现）与其限额常量：差分必须比同一份 JVM 语义，而不是测试副本。
    "backup/StrictGzipInputStream",
    "backup/BackupLimits",
)
PROBE_SOURCES = ("RustHostProbe.java", "PlanViewHostProbe.java", "ArchiveHostProbe.java")
DIGEST_PROBE_CLASS = "com.deepseekharness.app.util.RustHostProbe"
PLAN_PROBE_CLASS = "com.deepseekharness.app.util.PlanViewHostProbe"
ARCHIVE_PROBE_CLASS = "com.deepseekharness.app.util.ArchiveHostProbe"

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
    "archiveEntryIsImplemented",
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
    # R2 verify_plan（PlanViewHostProbe）
    "planAbiVersion",
    "planCanonicalBytesAgree",
    "planDeterministicRepeats",
    "planFixtureCorpus",
    "planJniBufferContract",
    "planLoadLibrary",
    "planNegativeControlDetectsMismatch",
    "planOneKeyRollback",
    "planOutputBoundNoPartialWrite",
    "planReasonSetOrderDedup",
    "planSelectionDefaultOff",
    "planSelectionOnUsesRust",
    "planValidCanonical",
    "planViewLimit",
    # R3 archive（ArchiveHostProbe）
    "archiveAbiVersion",
    "archiveCorpusJvmRustAgree",
    "archiveFinishTailFailure",
    "archiveHeaderPinned",
    "archiveJvmPackRustReads",
    "archiveLimitErrorMapping",
    "archiveLoadLibrary",
    "archiveNativeContractCodes",
    "archiveNegativeControlDetectsMismatch",
    "archiveOneKeyRollback",
    "archiveOptionalHeaderTruncation",
    "archivePythonGzipInterop",
    "archiveRustPackDeterministic",
    "archiveRustPackJvmReads",
    "archiveSelectionDefaultOff",
    "archiveSessionLimit",
    "archiveTrailerPinned",
    "archiveTruncationCorpus",
    "archiveWrapperClosedSemantics",
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


def parse_rows(output: str) -> tuple[list[dict], list[dict]]:
    rows = [json.loads(line) for line in output.splitlines() if line.startswith("{")]
    checks = [row for row in rows if "check" in row]
    summaries = [row for row in rows if "status" in row and "check" not in row]
    return checks, summaries


def bridge_sources(root=ROOT) -> list[Path]:
    """按逻辑类名解析两种语言，缺失或 Java/Kotlin 双实现均拒绝。"""
    try:
        layout = SourceLayout(root)
        return [layout.resolve_class(name) for name in BRIDGE_SOURCES]
    except ValueError as error:
        raise ProbeError("RUST_BRIDGE_SOURCE_LAYOUT: " + str(error)) from error


def kotlin_libraries(directory: Path | None, root=ROOT) -> list[Path]:
    """只接受源码依赖校验元数据锁住的编译器闭包，不下载、不放宽验证。"""
    if directory is None or not directory.is_dir():
        raise ProbeError("KOTLIN_COMPILER_REQUIRED: mixed sources require --kotlin-libs DIR")
    settings = (root / "settings.gradle").read_text(encoding="utf-8")
    match = re.search(r"gradle\.ext\.kotlinVersion\s*=\s*'([^']+)'", settings)
    if not match:
        raise ProbeError("KOTLIN_VERSION_NOT_PINNED")
    version = match.group(1)
    metadata = ET.parse(root / "gradle/verification-metadata.xml").getroot()
    pins = {}
    for component in metadata.findall("./{*}components/{*}component"):
        for artifact in component.findall("{*}artifact"):
            pins[artifact.attrib["name"]] = {row.attrib["value"] for row in artifact.findall("{*}sha256")}
    required = ["kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime",
                "kotlin-daemon-embeddable", "kotlin-reflect", "kotlinx-coroutines-core-jvm", "annotations"]
    selected = []
    for name in required:
        matches = sorted(directory.glob(name + "-*.jar"))
        # 不以 prefix 误选 stdlib-jdk8 等不同 artifact；其版本必须以数字开头。
        matches = [path for path in matches if re.fullmatch(re.escape(name) + r"-\d[^/]*\.jar", path.name)]
        if len(matches) != 1 or matches[0].is_symlink():
            raise ProbeError("KOTLIN_COMPILER_ARTIFACT_AMBIGUOUS: " + name)
        path = matches[0]
        if name in required[:4] and path.name != name + "-" + version + ".jar":
            raise ProbeError("KOTLIN_COMPILER_VERSION: " + path.name)
        if hashlib.sha256(path.read_bytes()).hexdigest() not in pins.get(path.name, set()):
            raise ProbeError("KOTLIN_COMPILER_DIGEST: " + path.name)
        selected.append(path)
    return selected


def compile_sources(home: Path, sources: list[Path], classes: Path, temporary: Path,
                    env: dict, kotlin_libs: Path | None) -> tuple[str, dict]:
    kotlin = [path for path in sources if path.suffix == ".kt"]
    java_sources = [path for path in sources if path.suffix == ".java"]
    runtime_cp = str(classes)
    evidence = {"languages": sorted({path.suffix for path in sources}), "kotlin": None}
    if kotlin:
        jars = kotlin_libraries(kotlin_libs)
        library_dir = temporary / "kotlin-libs"
        library_dir.mkdir()
        for jar in jars:
            shutil.copyfile(jar, library_dir / jar.name)
        stdlib = next(path for path in jars if path.name.startswith("kotlin-stdlib-"))
        compiler_cp = str(library_dir / "*")
        run([str(home / "bin/java"), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-cp", compiler_cp,
             "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
             "-jvm-target", "17", "-module-name", "rust_host_probe",
             "-classpath", str(library_dir / stdlib.name), "-d", str(classes),
             *[str(path) for path in sources]], cwd=ROOT, env=env)
        runtime_cp += os.pathsep + compiler_cp
        evidence["kotlin"] = {"jars": [{"name": path.name,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in jars]}
    run([str(home / "bin/javac"), "-J-Xmx128m", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
         "-source", "17", "-target", "17", "-cp", runtime_cp, "-d", str(classes),
         *[str(path) for path in java_sources]], cwd=ROOT, env=env)
    return runtime_cp, evidence


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lib", type=Path, required=True, help="主机目标 libdsha_rs.so")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--kotlin-libs", type=Path, help="混合闭包所需的锁定编译器 JAR 目录")
    args = parser.parse_args(argv)
    library = args.lib.resolve()
    if not library.is_file():
        raise ProbeError("RUST_HOST_LIB_MISSING: " + str(library))
    sources = [PROBE_SOURCE / name for name in PROBE_SOURCES] + bridge_sources()
    home = java_home()
    env = dict(os.environ)
    env.pop("PYTHONOPTIMIZE", None)
    with tempfile.TemporaryDirectory(prefix="dsha-rust-jvm-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        corpus = Path(temporary) / "plan-fixtures.txt"
        fixtures_summary_path = Path(temporary) / "plan-fixtures-summary.json"
        # 夹具由 tools/rust/fixtures.json 确定性生成；canonical 字节 pin 在生成器内核对。
        run([sys.executable, str(MAKE_FIXTURES), "--out", str(corpus),
             "--summary", str(fixtures_summary_path)], cwd=ROOT, env=env)
        runtime_cp, compiler_evidence = compile_sources(
            home, sources, classes, Path(temporary), env, args.kotlin_libs)
        java = str(home / "bin/java")
        digest_output = run([java, "-Xmx128m", "-XX:ActiveProcessorCount=1", "-Xcheck:jni",
                             "--add-opens", "java.base/java.io=ALL-UNNAMED",
                             "-cp", runtime_cp, DIGEST_PROBE_CLASS, str(library)],
                            cwd=ROOT, env=env)
        plan_output = run([java, "-Xmx128m", "-XX:ActiveProcessorCount=1", "-Xcheck:jni",
                           "-cp", runtime_cp, PLAN_PROBE_CLASS, str(library), str(corpus)],
                          cwd=ROOT, env=env)
        archive_output = run([java, "-Xmx128m", "-XX:ActiveProcessorCount=1", "-Xcheck:jni",
                              "-cp", runtime_cp, ARCHIVE_PROBE_CLASS, str(library)],
                             cwd=ROOT, env=env)
        fixtures_summary = json.loads(fixtures_summary_path.read_text(encoding="utf-8"))
    digest_checks, digest_summaries = parse_rows(digest_output)
    plan_checks, plan_summaries = parse_rows(plan_output)
    archive_checks, archive_summaries = parse_rows(archive_output)
    checks = digest_checks + plan_checks + archive_checks
    summaries = digest_summaries + plan_summaries + archive_summaries
    failed = [row for row in checks if row.get("status") != "PASS"]
    names = {row.get("check") for row in checks}
    missing = sorted(EXPECTED_CHECKS - names)
    unexpected = sorted(names - EXPECTED_CHECKS)
    if missing or unexpected or len(checks) != len(EXPECTED_CHECKS):
        raise ProbeError("RUST_HOST_PROBE_CHECKS: missing=%s unexpected=%s count=%d expected=%d"
                         % (missing, unexpected, len(checks), len(EXPECTED_CHECKS)))
    failures = sum(int(row.get("failures", 1)) for row in summaries)
    if len(summaries) != 3:
        raise ProbeError("RUST_HOST_PROBE_SUMMARY: 期望三个探针汇总，实际 %d" % len(summaries))
    # 评审 P2-7：即使 failures 计数为 0，汇总状态不是 PASS 也必须硬失败。
    not_passing = [row for row in summaries if row.get("status") != "PASS"]
    if not_passing:
        raise ProbeError("RUST_HOST_PROBE_SUMMARY_STATUS: %s"
                         % json.dumps(not_passing, ensure_ascii=False)[:1000])
    status = "PASS" if checks and not failed and failures == 0 else "FAIL"
    if status != "PASS":
        raise ProbeError("RUST_HOST_PROBE_FAILED:\n" + digest_output + plan_output
                         + archive_output)
    plan_summary = plan_summaries[0]
    archive_summary = archive_summaries[0]
    report = {
        "schema": 1,
        "kind": "dsha-rust-host-differential",
        "status": "PASS",
        "scope": "R1 digest + R2 PlanView + R3 gzip archive 主机 JVM 差分：SHA-256/SHA-1、"
                 "offset/跨块/32 MiB、短读、非法参数、fd 生命周期、direct buffer 契约、"
                 "运行层开关与回退；PlanView v1 结构/范围/策略版本/有效期原因集合（含固定夹具与"
                 "种子随机视图）、JNI 缓冲契约、2 MiB 上限、幂等/输入不变；单成员 gzip 的"
                 "Rust/JVM pack 互读、分块不变性、第三方固定语料、合法/非法/截断语料逐例比较"
                 "（输出字节 + 错误消息）、native 契约错误码、会话上限、一键回退；"
                 "-Xcheck:jni 全程开启。不证明 Android .so 已加载（R3 设备证据单独记录）",
        "library": {"file": library.name, "size": library.stat().st_size,
                    "sha256": hashlib.sha256(library.read_bytes()).hexdigest()},
        "java": {"home": str(home),
                 "version": next((row.get("javaVersion") for row in plan_summaries
                                  if row.get("javaVersion")), None)},
        "checks": checks,
        "compiler": compiler_evidence,
        "sources": [{"path": path.relative_to(ROOT).as_posix(),
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in sources],
        "fixtures": {
            "generator": "tools/rust/make-fixtures.py",
            "source": "tools/rust/fixtures.json",
            "planCorpusCases": plan_summary.get("corpusCases"),
            "planNativeCalls": plan_summary.get("nativeCalls"),
            "randomSeed": fixtures_summary.get("randomSeed"),
            "randomCount": fixtures_summary.get("randomCount"),
            "planCanonicalBytes": fixtures_summary.get("canonicalBytes"),
            "planCanonicalSha256": fixtures_summary.get("canonicalSha256"),
            "archiveCorpusChecks": archive_summary.get("checks"),
            "archiveNativeCalls": archive_summary.get("nativeCalls"),
        },
        "tests": {"probeChecks": len(checks), "failures": len(failed), "skipped": 0},
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8", newline="\n")
    print(json.dumps({"status": "PASS", "checks": len(checks),
                      "planCorpusCases": plan_summary.get("corpusCases"),
                      "planNativeCalls": plan_summary.get("nativeCalls"),
                      "archiveChecks": archive_summary.get("checks"),
                      "archiveNativeCalls": archive_summary.get("nativeCalls"),
                      "randomSeed": fixtures_summary.get("randomSeed"),
                      "randomCount": fixtures_summary.get("randomCount"),
                      "librarySha256": report["library"]["sha256"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except ProbeError as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)

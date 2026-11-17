#!/usr/bin/env python3
"""跨平台重编会话启动器；显式输出，锁定 NDK/API 和页对齐。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
NDK_VERSION = json.loads((ROOT / "ci/toolchain.lock.json").read_text(encoding="utf-8"))["ndk"]


LOG_LIMIT = 8192


def evidence_path(value, output):
    """静态路径检查加独占创建；不声称防御并发替换父目录的原子 NOFOLLOW。"""
    root = ROOT.resolve()
    path = Path(value)
    if not path.is_absolute():
        path = root / path
    base = root / "app/build"
    if (".." in path.parts or not path.is_relative_to(base) or path == base
            or output.resolve().is_relative_to(path) or path.is_relative_to(output.resolve())):
        raise ValueError("NATIVE_SESSION_EVIDENCE_PATH")
    for parent in (path, *path.parents):
        if parent == root:
            break
        if parent.is_symlink():
            raise ValueError("NATIVE_SESSION_EVIDENCE_SYMLINK")
    if path.exists():
        raise ValueError("NATIVE_SESSION_EVIDENCE_EXISTS")
    return path


def byte_identity(content):
    return {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}


def file_identity(path):
    info = {"path": str(path)}
    try:
        info["resolvedPath"] = str(path.resolve())
        digest, size = hashlib.sha256(), 0
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                size += len(block)
                digest.update(block)
        info.update(available=True, size=size, sha256=digest.hexdigest())
    except (OSError, RuntimeError) as error:
        info.update(available=False, error=type(error).__name__)
    return info


def elf_regions(content):
    """有界解析 ELF64 little-endian；摘要不证明代码等价或运行正确。"""
    if len(content) < 64 or content[:6] != b"\x7fELF\x02\x01":
        raise ValueError("ELF64_LITTLE_ENDIAN_REQUIRED")
    header = struct.unpack_from("<16sHHIQQQIHHHHHH", content)
    phoff, shoff = header[5:7]
    phsize, phcount, shsize, shcount, names_index = header[9:14]
    if (phsize != 56 or shsize != 64 or not 0 < phcount <= 1024
            or not 0 < shcount <= 4096 or names_index >= shcount):
        raise ValueError("ELF_TABLE_UNSUPPORTED")

    def span(offset, size):
        if offset < 0 or size < 0 or offset + size > len(content):
            raise ValueError("ELF_BOUNDS")
        return content[offset:offset + size]

    span(phoff, phsize * phcount)
    span(shoff, shsize * shcount)
    table = [struct.unpack_from("<IIQQQQIIQQ", content, shoff + i * shsize)
             for i in range(shcount)]
    names = span(table[names_index][4], table[names_index][5])
    sections, loads = [], []
    for index, row in enumerate(table):
        end = names.find(b"\0", row[0])
        if row[0] >= len(names) or end < 0:
            raise ValueError("ELF_SECTION_NAME")
        sections.append({"index": index, "name": names[row[0]:end].decode("utf-8", "replace"),
                         "type": row[1], "flags": row[2], "offset": row[4], "size": row[5],
                         "fileBytes": None if row[1] == 8 else byte_identity(span(row[4], row[5]))})
    for index in range(phcount):
        row = struct.unpack_from("<IIQQQQQQ", content, phoff + index * phsize)
        if row[0] == 1:
            loads.append({"index": index, "flags": row[1], "offset": row[2],
                          "virtualAddress": row[3], "memorySize": row[6], "align": row[7],
                          "fileBytes": byte_identity(span(row[2], row[5]))})
    return {"entry": header[4], "sections": sections, "loadSegments": loads}


def run_recorded(argv, stage, records, timeout=None):
    record = {"stage": stage, "argv": argv, "cwd": os.getcwd(), "returncode": None}
    records.append(record)
    streams = []
    try:
        try:
            streams.append(tempfile.TemporaryFile())
            streams.append(tempfile.TemporaryFile())
        except OSError as error:
            record["captureUnavailable"] = type(error).__name__
        options = {"timeout": timeout} if timeout is not None else {}
        if len(streams) == 2:
            options.update(stdout=streams[0], stderr=streams[1])
        try:
            result = subprocess.run(argv, check=True, **options)
            record["returncode"] = result.returncode
        except Exception as error:
            record.update(error=type(error).__name__, returncode=getattr(error, "returncode", None))
            raise
        finally:
            # 可选日志读取失败也不能覆盖进程异常或把成功改成失败。
            if len(streams) == 2:
                for name, stream in zip(("stdout", "stderr"), streams):
                    try:
                        stream.seek(0)
                        data = stream.read(LOG_LIMIT + 1)
                        record[name] = data[:LOG_LIMIT].decode("utf-8", "replace")
                        record[name + "Truncated"] = len(data) > LOG_LIMIT
                    except OSError as error:
                        record[name + "Unavailable"] = type(error).__name__
    finally:
        for stream in streams:
            try:
                stream.close()
            except OSError as error:
                record["captureCloseError"] = type(error).__name__


def save_evidence(path, output, ndk, properties, tools, suffix, expected, content,
                  compared, stage, failure, commands, snapshot_errors):
    path = evidence_path(path, output)  # 失败时再检查，不在成功检查时建目录。
    path.parent.mkdir(parents=True, exist_ok=True)
    path.mkdir()  # 不复用旧目录；所有证据文件也以 exclusive 模式创建。
    report = {"schema": 1, "kind": "native-session-failure-diagnostics", "status": "INCOMPLETE",
              "output": str(output), "comparisonPerformed": compared, "failure": {"stage": stage,
              "type": type(failure).__name__, "message": str(failure),
              "returncode": getattr(failure, "returncode", None)}, "commands": commands,
              "ci": {key: os.environ[key] for key in ("GITHUB_SHA", "GITHUB_RUN_ID",
                     "GITHUB_RUN_ATTEMPT", "GITHUB_REPOSITORY") if key in os.environ},
              "host": {"system": platform.system(), "machine": platform.machine(),
                       "python": platform.python_version()},
              "ndk": {"path": str(ndk), "requiredRevision": NDK_VERSION, "properties": properties},
              "implicitInputs": "unknown: known default paths only; no driver/linker dependency trace, "
                                "headers or implicit configuration attestation",
              "toolchainInventoryComplete": False, "files": {}, "errors": snapshot_errors, "firstDifference": None}
    # 先留 INCOMPLETE 清单，再逐项更新；诊断失败不能改变原始异常。
    with (path / "report.json").open("x+", encoding="utf-8") as manifest:
        def persist():
            manifest.seek(0)
            json.dump(report, manifest, ensure_ascii=False, indent=2)
            manifest.write("\n")
            manifest.truncate()
            manifest.flush()

        persist()
        def attempt(label, operation):
            try:
                return operation()
            except Exception as error:
                report["errors"].append({"item": label, "error": type(error).__name__})
                return None
            finally:
                persist()

        source = Path(__file__)
        inputs = [source, source.with_name("session-launcher.c"), ROOT / "ci/toolchain.lock.json",
                  ndk / "source.properties"]
        inputs += [tools / (name + suffix) for name in ("clang", "ld.lld", "llvm-strip", "llvm-readelf")]
        library = tools.parent / "sysroot/usr/lib/aarch64-linux-android/23"
        inputs += [library / name for name in ("crtbegin_dynamic.o", "crtend_android.o", "libc.so", "libdl.so")]
        builtins = attempt("builtins-discovery", lambda: sorted(
            [item for base in ("lib/clang", "lib64/clang") for item in
             (tools.parent / base).glob("*/lib/linux/libclang_rt.builtins-aarch64-android.a")]))
        if not builtins:
            report["errors"].append({"item": "builtins", "unavailable": True})
        inputs += builtins or []
        report["inputs"] = [file_identity(item) for item in inputs]
        for info in report["inputs"]:
            if not info["available"]:
                report["errors"].append({"item": info["path"], "unavailable": info["error"]})
        persist()
        for label, data in (("expected", expected), ("candidate", content)):
            info = {"available": data is not None, "basis": "comparison-snapshot" if compared else "failure-snapshot"}
            report["files"][label] = info
            if data is None:
                report["errors"].append({"item": label, "unavailable": True})
                persist()
                continue
            info.update(byte_identity(data))
            def copy():
                with (path / (label + ".elf")).open("xb") as stream:
                    stream.write(data)
                info["copy"] = label + ".elf"
            attempt(label + "-copy", copy)
            info["elf"] = attempt(label + "-elf", lambda: elf_regions(data))
            if "copy" in info:
                for tag, options in (("layout", ["-h", "-l", "-S", "-n", "--wide"]),
                                     ("comment", ["--string-dump=.comment", "--wide"])):
                    attempt(label + "-" + tag, lambda: run_recorded(
                        [str(tools / ("llvm-readelf" + suffix)), *options,
                         str(path / info["copy"])], label + "-" + tag, commands, timeout=30))
        if compared and expected is not None and content is not None and expected != content:
            offset = next((i for i in range(min(len(expected), len(content)))
                           if expected[i] != content[i]), min(len(expected), len(content)))
            report["firstDifference"] = {"offset": offset,
                "expectedByte": expected[offset] if offset < len(expected) else None,
                "candidateByte": content[offset] if offset < len(content) else None}
        for item in commands:
            if any(key.endswith(("Unavailable", "Error", "Truncated")) and value
                   for key, value in item.items()):
                report["errors"].append({"item": item["stage"] + "-output", "error": "INCOMPLETE_CAPTURE"})
        report["status"] = "INCOMPLETE" if report["errors"] else "COMPLETE"
        persist()


def build(ndk, output, check=False, evidence_dir=None):
    if evidence_dir is not None:
        if not check:
            raise ValueError("NATIVE_SESSION_EVIDENCE_REQUIRES_CHECK")
        evidence_dir = evidence_path(evidence_dir, output)
    properties = (ndk / "source.properties").read_text(encoding="utf-8")
    if "Pkg.Revision = " + NDK_VERSION not in properties:
        raise ValueError("NATIVE_SESSION_NDK_VERSION")
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}.get(platform.system())
    if not host:
        raise ValueError("NATIVE_SESSION_BUILD_HOST")
    suffix = ".exe" if platform.system() == "Windows" else ""
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dsha-session-build-", dir=output.parent) as directory:
        candidate = Path(directory) / "libdsha-session.so"
        command = [str(tools / ("clang" + suffix)), "--target=aarch64-linux-android23",
                   "-std=c11", "-D_GNU_SOURCE", "-O2", "-fPIE", "-pie",
                   "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2",
                   "-ffile-prefix-map=" + str(ROOT) + "=/src",
                   "-Wl,-z,relro", "-Wl,-z,now", "-Wl,-z,max-page-size=16384",
                   "-Wl,-z,common-page-size=4096", str(Path(__file__).with_name("session-launcher.c")),
                   "-o", str(candidate)]
        commands, expected, content, compared, stage = [], None, None, False, "compile"
        try:
            for stage, argv in (("compile", command),
                                ("strip", [str(tools / ("llvm-strip" + suffix)), "--strip-unneeded", str(candidate)]),
                                ("readelf", [str(tools / ("llvm-readelf" + suffix)), "--program-headers", "--wide", str(candidate)])):
                if evidence_dir is None:
                    subprocess.run(argv, check=True)
                else:
                    run_recorded(argv, stage, commands)
            stage = "candidate-read"
            content = candidate.read_bytes()
            if check:
                stage = "comparison"
                expected = output.read_bytes() if output.is_file() else None
                compared = True
                if expected is None or expected != content:
                    raise ValueError("NATIVE_SESSION_REBUILD_MISMATCH")
            else:
                candidate.replace(output)
        except Exception as failure:
            if evidence_dir is not None:
                try:
                    # 未到比较阶段的快照单独标记；比较之后绝不重读参与比较的字节。
                    snapshot_errors = []
                    if not compared:
                        snapshots = []
                        for label, source in (("expected", output), ("candidate", candidate)):
                            try:
                                snapshots.append(source.read_bytes() if source.is_file() else None)
                            except OSError as error:
                                snapshots.append(None)
                                snapshot_errors.append({"item": label + "-snapshot", "error": type(error).__name__})
                        expected, content = snapshots
                    save_evidence(evidence_dir, output, ndk, properties, tools, suffix,
                                  expected, content, compared, stage, failure, commands, snapshot_errors)
                except Exception as error:
                    try:
                        print("NATIVE_SESSION_EVIDENCE_FAILED: " + type(error).__name__, file=sys.stderr)
                    except Exception:
                        pass  # 诊断提示写失败也不能覆盖原始异常。
            raise
    value = hashlib.sha256(content).hexdigest()
    print("PASS native session reproducible bytes" if check else "native session sha256", value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, default=os.environ.get("ANDROID_NDK_HOME"))
    parser.add_argument("--output", type=Path)
    parser.add_argument("--check", action="store_true", help="Rebuild in a temporary directory and compare without replacing the shipped ELF")
    parser.add_argument("--evidence-dir", type=Path, help="With --check, preserve failures only in a new app/build subdirectory")
    args = parser.parse_args()
    if args.ndk is None:
        parser.error("需要 --ndk 或 ANDROID_NDK_HOME")
    if args.output is None and not args.check:
        parser.error("Write mode requires explicit --output; use --check for read-only verification")
    output = args.output or ROOT / "app/src/main/jniLibs/arm64-v8a/libdsha-session.so"
    build(args.ndk.resolve(), output.resolve(), check=args.check, evidence_dir=args.evidence_dir)


if __name__ == "__main__":
    main()

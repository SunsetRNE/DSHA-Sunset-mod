#!/usr/bin/env python3
"""为独立 Standard 模拟器调试目标构建 x86_64 原生库，不改 ARM 发布输入。"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
NDK_VERSION = "26.3.11579264"
SOURCES = {
    "proot": ("proot.zip", "29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135"),
    "talloc": ("talloc.tar.gz", "dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"),
    "shmem": ("shmem.tar.gz", "1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867"),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def command(args):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(" ".join(map(str, args)) + "\n" + result.stdout + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--proot", type=Path, required=True)
    parser.add_argument("--talloc", type=Path, required=True)
    parser.add_argument("--shmem", type=Path, required=True)
    args = parser.parse_args()
    ndk = args.ndk.resolve()
    if "Pkg.Revision = " + NDK_VERSION not in (ndk / "source.properties").read_text():
        raise ValueError("EMULATOR_NDK_VERSION")
    sources = {}
    for name, (archive, expected) in SOURCES.items():
        directory = getattr(args, name).resolve()
        directory.relative_to(ROOT)
        if digest(directory / archive) != expected:
            raise ValueError("EMULATOR_SOURCE_HASH:" + name)
        sources[name] = next((directory / "source").iterdir())
    work = ROOT / "app/build/emulator-native"
    work.mkdir(parents=True, exist_ok=True)
    headers = work / "include"
    headers.mkdir(exist_ok=True)
    output = work / "jniLibs/x86_64"
    output.mkdir(parents=True, exist_ok=True)
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
    suffix = ".exe" if platform.system() == "Windows" else ""
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    clang = tools / ("clang" + suffix)
    strip = tools / ("llvm-strip" + suffix)
    readelf = tools / ("llvm-readelf" + suffix)
    common = [clang, "--target=x86_64-linux-android30", "-O2", "-g0", "-D_GNU_SOURCE", "-D_FILE_OFFSET_BITS=64", "-fPIC", "-I" + str(headers)]
    linking = ["-Wl,-z,relro", "-Wl,-z,now", "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=4096"]

    # talloc.c 仅依赖 libc；把配置能力显式锁为 NDK API30 提供的接口。
    (headers / "replace.h").write_text("""#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/types.h>
#include <errno.h>
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 3
#define HAVE_INTPTR_T 1
#define HAVE_VA_COPY 1
#define HAVE_CONSTRUCTOR_ATTRIBUTE 1
#define HAVE_SYS_AUXV_H 1
#define HAVE_GETAUXVAL 1
#define MIN(a,b) ((a)<(b)?(a):(b))
""", encoding="ascii")
    talloc = sources["talloc"]
    command(common + ["-shared", "-I" + str(talloc), str(talloc / "talloc.c"), "-Wl,-soname,libtalloc.so.2", *linking, "-o", output / "libtalloc.so"])
    shmem = sources["shmem"]
    (headers / "sys").mkdir(exist_ok=True)
    shutil.copyfile(shmem / "shm.h", headers / "sys/shm.h")
    command(common + ["-shared", "-std=c11", '-D_PATH_TMP="./"', str(shmem / "shmem.c"), "-Wl,-soname,libandroid-shmem.so", "-llog", "-landroid", *linking, "-o", output / "libandroidshmem.so"])

    proot = work / "proot"
    shutil.copytree(sources["proot"] / "src", proot, dirs_exist_ok=True)
    canon = proot / "path/canon.c"
    source = canon.read_text()
    anchor = 'assert(status != 1 || sscanf(guest_path, "/proc/%*d/fd/%d", &status) == 1);'
    if source.count(anchor) != 1:
        raise ValueError("EMULATOR_PROOT_CANON_ANCHOR")
    canon.write_text(source.replace(anchor, "/* Android 命名 fd 按原错误码处理，不触发旧断言。 */"))
    (headers / "build.h").write_text("#define HAVE_PROCESS_VM 1\n#define HAVE_SECCOMP_FILTER 1\n", encoding="ascii")
    cc = common + ["-std=gnu11", "-I" + str(proot), "-I" + str(talloc), "-DWITH_LIBANDROID_SHMEM", '-DVERSION="5.1.107.92-dsha-emulator"', '-DPROOT_UNBUNDLE_LOADER="/unused"', "-DARG_MAX=131072", "-Wno-implicit-function-declaration"]
    objects = re.findall(r"([\w/-]+\.o)", (proot / "GNUmakefile").read_text().split("OBJECTS +=", 1)[1].split("define define_from_arch", 1)[0])
    obj = work / "objects"
    obj.mkdir(exist_ok=True)
    def compile_item(item):
        destination = obj / item.replace("/", "_")
        command(cc + ["-c", proot / item.replace(".o", ".c"), "-o", destination])
        return destination
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        compiled = list(pool.map(compile_item, objects))
    command(cc + ["-pie", *linking, *compiled, "-L" + str(output), "-l:libtalloc.so", "-l:libandroidshmem.so", "-llog", "-landroid", "-o", output / "libproot.so"])
    for triple, address, name in (("x86_64-linux-android30", "0x600000000000", "libprootloader.so"), ("i686-linux-android30", "0x20000000", "libprootloader32.so")):
        command([clang, "--target=" + triple, "-O2", "-D_GNU_SOURCE", "-I" + str(proot), "-I" + str(headers), "-ffreestanding", "-fPIC", "-fno-stack-protector", "-fno-builtin", "-nostdlib", "-static", "-Wl,--build-id=none,-Ttext=" + address + ",-z,noexecstack,-z,max-page-size=16384", proot / "loader/loader.c", proot / "loader/assembly.S", "-o", output / name])

    # 沿用固定 PTY 的六处出生身份补丁，不能退回启动后读取裸 PID。
    termux = ROOT / "tools/termux-jni"
    text = (termux / "termux.c").read_text(encoding="utf-8")
    if hashlib.sha256(text.encode()).hexdigest() != "729112f2e66cdddb7e7311ddf8a89ad54037a54bddbf4d5291f2e1fff5b97373":
        raise ValueError("EMULATOR_TERMUX_SOURCE")
    patches = [
        ("#include <dirent.h>", '#include <dirent.h>\n#include "dsha-pty.h"'),
        ("    pid_t pid = fork();", '    int identity_pair[2];\n    if (dsha_pty_prepare(identity_pair) != 0) {\n        close(ptm);\n        return throw_runtime_exception(env, "Cannot prepare PTY identity handshake");\n    }\n    pid_t pid = fork();'),
        ('        return throw_runtime_exception(env, "Fork failed");', '        close(identity_pair[0]); close(identity_pair[1]); close(ptm);\n        return throw_runtime_exception(env, "Fork failed");'),
        ("        *pProcessId = (int) pid;", '        if (!dsha_pty_parent(env, identity_pair, pid)) {\n            close(ptm);\n            if ((*env)->ExceptionCheck(env)) return -1;\n            return throw_runtime_exception(env, "Cannot confirm PTY identity before exec");\n        }\n        *pProcessId = (int) pid;'),
        ("        setsid();", "        if (setsid() < 0) _exit(125);\n        dsha_pty_child(identity_pair);"),
        ("    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);", "    if ((*env)->ExceptionCheck(env)) return -1;\n    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);"),
    ]
    for before, after in patches:
        if text.count(before) != 1:
            raise ValueError("EMULATOR_PTY_PATCH_ANCHOR")
        text = text.replace(before, after)
    patched = work / "termux-patched.c"
    patched.write_text(text, encoding="utf-8")
    command(common + ["-std=gnu11", "-shared", "-fvisibility=hidden", "-fstack-protector-strong", "-D_FORTIFY_SOURCE=2", "-I" + str(termux), patched, termux / "dsha-process.c", termux / "dsha-pty.c", *linking, "-Wl,--no-undefined,-soname,libtermux.so", "-o", output / "libtermux.so"])
    command(common + ["-std=c11", "-fPIE", "-pie", ROOT / "tools/native-session/session-launcher.c", *linking, "-o", output / "libdsha-session.so"])
    receipts = {}
    for binary in sorted(output.glob("*.so")):
        header = binary.read_bytes()[:20]
        expected = 3 if binary.name == "libprootloader32.so" else 62
        if header[:4] != b"\x7fELF" or struct.unpack_from("<H", header, 18)[0] != expected:
            raise ValueError("EMULATOR_ELF_MACHINE:" + binary.name)
        command([strip, "--strip-unneeded", binary])
        receipts[binary.name] = {"sha256": digest(binary), "bytes": binary.stat().st_size, "machine": expected, "headers": command([readelf, "-h", binary])}
    (work / "receipt.json").write_text(json.dumps({"ndk": NDK_VERSION, "abi": "x86_64", "api": 30, "sources": {key: value[1] for key, value in SOURCES.items()}, "libraries": receipts}, indent=2) + "\n")
    print("PASS x86_64 native build", output, len(receipts))


if __name__ == "__main__":
    main()

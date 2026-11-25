#!/usr/bin/env python3
"""Prepare and verify the local build370 fixed inputs in an isolated directory."""
import argparse
from contextlib import redirect_stdout
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
import zlib


ROOT = Path(__file__).resolve().parents[1]
ASSETS = "app/src/main/assets"
DSH = f"{ASSETS}/dsh-runtime.bin"
ROOTFS = f"{ASSETS}/offline-rootfs.bin"
SIDECARS = (f"{ASSETS}/dsh-runtime.inputs.json", f"{ASSETS}/ubuntu-tools.inputs.json")
RECOVERY = "tools/recovery-runtime/archives/recovery-dsh-runtime.bin"
RECOVERY_ROOTFS = "tools/recovery-runtime/archives/recovery-rootfs.bin"
DESCRIPTOR = f"{ASSETS}/runtime-descriptor.json"
RECOVERY_LOCK = "tools/recovery-runtime/lock.json"
VERIFIER_INPUTS = (DESCRIPTOR, RECOVERY_LOCK, "tools/ci-build-inputs.lock.json",
                   f"{ASSETS}/adb-wheels.lock.json")
VERSION = "0.2.2-rc.5"


def load_json(relative):
    return json.loads((ROOT / relative).read_text(encoding="utf-8"))


def load_tool(filename):
    spec = importlib.util.spec_from_file_location("local_" + filename, ROOT / "tools" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(*arguments):
    return subprocess.check_output(["git", "-C", str(ROOT), *arguments], text=True).strip()


def source_identity(commit):
    head = git("rev-parse", "HEAD")
    if commit and head != commit:
        raise ValueError("Checkout HEAD differs from --commit.")
    if git("status", "--porcelain=v1", "--untracked-files=no"):
        raise ValueError("Use a clean checkout of the finalized build370 commit.")
    if load_json("ci/release-identity.json")["versionCode"] != 370:
        raise ValueError("This script is for build370.")
    descriptor = load_json(DESCRIPTOR)
    proof = load_json(SIDECARS[0])
    recovery = load_json(RECOVERY_LOCK)
    manifest = load_json("tools/dsh-runtime/package.json")
    lock = load_json("tools/dsh-runtime/package-lock.json")
    versions = (descriptor["dshVersion"], proof["version"], recovery["dshVersion"],
                manifest["dependencies"]["@deepseek-ai/dsh"],
                lock["packages"]["node_modules/@deepseek-ai/dsh"]["version"])
    digest = proof["archive_sha256"]
    rescue_digest = next(row["sha256"] for row in recovery["archives"]
                         if row["asset"] == Path(RECOVERY).name)
    if set(versions) != {VERSION} or digest != rescue_digest or digest != descriptor["inputs"][Path(DSH).name]:
        raise ValueError("The finalized build370 runtime pins disagree.")
    return head, descriptor["runtimeId"], digest


def init_validator(assets, directory):
    assets.ROOT = directory
    for name in VERIFIER_INPUTS:
        path = assets.bounded(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, path)


def verify_fixed_metadata(directory):
    for name in SIDECARS:
        if (directory / name).read_bytes() != (ROOT / name).read_bytes():
            raise ValueError("Fixed sidecar differs from the committed bytes: " + name)
    with (directory / RECOVERY_ROOTFS).open("rb") as archive:
        if archive.read(6) != b"\xfd7zXZ\0":
            raise ValueError("build370 recovery rootfs must be XZ.")


def prepare(args, workspace, assets, names, digest):
    base_lock = load_json("ci/sunset-inputs.lock.json")
    base = args.base_bundle or workspace / "upstream.zip"
    if args.base_bundle is None:
        assets.download(base_lock["url"], base)
    if base_lock.get("format") != "bundle" or base.stat().st_size != base_lock["bytes"] \
            or assets.digest(base) != base_lock["sha256"]:
        raise ValueError("Upstream ZIP does not match its committed lock.")
    output = workspace / "inputs"
    init_validator(assets, output)
    with zipfile.ZipFile(base) as archive:
        members = assets.selected_members(archive, names, False)
        for name in sorted(names - {DSH, SIDECARS[0], RECOVERY, RECOVERY_ROOTFS}):
            path = assets.bounded(name)
            path.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(members[name]) as source, path.open("xb") as target:
                shutil.copyfileobj(source, target, length=1024 * 1024)

    npm = shutil.which("npm.cmd") or shutil.which("npm")
    if npm is None:
        raise ValueError("Node.js 24.9+ with npm 11 is required.")
    npm_root = workspace / "npm"
    npm_root.mkdir()
    for name in ("package.json", "package-lock.json"):
        shutil.copyfile(ROOT / "tools/dsh-runtime" / name, npm_root / name)
    command = [npm, "ci", "--prefix", str(npm_root), "--os=linux", "--cpu=arm64", "--libc=glibc",
               "--ignore-scripts", "--no-audit", "--no-fund", "--registry=https://registry.npmjs.org"]
    if args.npm_cache:
        command.extend(["--cache", str(args.npm_cache.resolve())])
    if args.offline:
        command.append("--offline")
    subprocess.run(command, cwd=workspace, check=True, stdout=sys.stderr)
    subprocess.run([sys.executable, "-B", str(ROOT / "tools/build-dsh-runtime.py"),
                    "--source", str(npm_root / "node_modules"), "--output", str(output / DSH),
                    "--version", VERSION], cwd=workspace, check=True, stdout=sys.stderr)
    if assets.digest(output / DSH) != digest:
        raise ValueError("Rebuilt DSH archive differs from the committed SHA-256.")
    (output / RECOVERY).parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(output / DSH, output / RECOVERY)
    standard_assets = load_tool("prepare-standard-assets.py")
    standard_assets.build_optimized_rootfs(output / ROOTFS, output / RECOVERY_ROOTFS)
    verify_fixed_metadata(output)

    # The CI ZIP writer takes member timestamps from the staged input files.
    stamp = time.mktime((2026, 1, 1, 0, 0, 0, 0, 0, -1))
    for name in names:
        path = assets.bounded(name)
        path.chmod(0o644)
        os.utime(path, (stamp, stamp))
    bundle = workspace / "prepared.zip"
    with redirect_stdout(sys.stderr):
        assets.main(["--pack", str(bundle)])
    return bundle, dict(upstreamSha256=base_lock["sha256"], upstreamBytes=base_lock["bytes"],
                        node=subprocess.check_output(["node", "--version"], text=True).strip(),
                        npm=subprocess.check_output([npm, "--version"], text=True).strip(),
                        python=sys.version.split()[0], zlib=zlib.ZLIB_RUNTIME_VERSION)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--output", type=Path, help="Create a new fixed-input ZIP outside this checkout")
    modes.add_argument("--verify", type=Path, help="Check an existing ZIP without modifying the checkout")
    parser.add_argument("--commit", help="Require this finalized source commit")
    parser.add_argument("--sha256", help="Require this bundle SHA-256 for --verify")
    parser.add_argument("--base-bundle", type=Path, help="Reuse the upstream ZIP instead of downloading it")
    parser.add_argument("--npm-cache", type=Path)
    parser.add_argument("--offline", action="store_true", help="Use cached npm data and --base-bundle")
    args = parser.parse_args()
    if args.offline and args.base_bundle is None:
        parser.error("--offline requires --base-bundle")
    if args.output and args.sha256:
        parser.error("--sha256 is for --verify")
    target = (args.output or args.verify).absolute()
    if args.output and (target.exists() or target.resolve().is_relative_to(ROOT)):
        parser.error("Use a new ZIP path outside the source checkout.")
    if args.output:
        target.parent.mkdir(parents=True, exist_ok=True)
    if args.verify and (not target.is_file() or target.stat().st_size > 2 * 1024 ** 3):
        parser.error("--verify requires an existing ZIP of at most 2 GiB")

    sys.dont_write_bytecode = True
    assets = load_tool("ci-assets.py")
    verifier = load_tool("verify-runtime-descriptor-source.py")
    commit, runtime_id, digest = source_identity(args.commit)
    verifier.verify(ROOT)
    names = assets.allowed()
    with tempfile.TemporaryDirectory(prefix="dsha370-", dir=target.parent) as temporary:
        workspace = Path(temporary)
        bundle, evidence = prepare(args, workspace, assets, names, digest) if args.output else (target, {})
        zip_hash = assets.digest(bundle)
        if args.sha256 and args.sha256 != zip_hash:
            raise ValueError("ZIP SHA-256 differs from --sha256.")

        restored = workspace / "restored"
        init_validator(assets, restored)
        assets.restore(bundle, zip_hash, False, workspace)
        verify_fixed_metadata(restored)
        files = [dict(path=name, bytes=(restored / name).stat().st_size,
                      sha256=assets.digest(restored / name)) for name in sorted(names)]
        if source_identity(commit) != (commit, runtime_id, digest):
            raise ValueError("Source pins changed during preparation.")
        verifier.verify(ROOT)
        if args.output:
            if target.exists():
                raise FileExistsError(target)
            bundle.rename(target)
        print(json.dumps(dict(status="PASS_FIXED_INPUTS", commit=commit, versionCode=370, runtimeId=runtime_id,
                              dshVersion=VERSION, dshArchiveSha256=digest, zip=target.name,
                              sha256=zip_hash, bytes=target.stat().st_size, files=files, **evidence),
                         indent=2))


if __name__ == "__main__":
    main()

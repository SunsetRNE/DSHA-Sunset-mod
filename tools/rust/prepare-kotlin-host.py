#!/usr/bin/env python3
"""为 Rust host-JVM 探针准备锁定 Kotlin 编译器闭包；--check 不联网、不写入。"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MAVEN = "https://repo.maven.apache.org/maven2/"
MAX_BYTES = 128 * 1024 * 1024
# Kotlin 2.2.10 compiler POM 的精确 runtime 闭包；annotations 为编译 JVM 声明所需。
# 升级 KGP 时明确重新核对闭包，不把旧 ancillary 版本套到未知编译器上。
SUPPORTED_KGP = "2.2.10"
COORDINATES = (
    ("org.jetbrains.kotlin", "kotlin-compiler-embeddable", None),
    ("org.jetbrains.kotlin", "kotlin-stdlib", None),
    ("org.jetbrains.kotlin", "kotlin-script-runtime", None),
    ("org.jetbrains.kotlin", "kotlin-daemon-embeddable", None),
    ("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
    ("org.jetbrains", "annotations", "13.0"),
)


class PrepareError(RuntimeError):
    pass


def checked_path(path: Path) -> Path:
    path = Path(os.path.abspath(path))
    for parent in (path, *path.parents):
        if parent.is_symlink():
            raise PrepareError("KOTLIN_HOST_LINK: " + str(parent))
    return path


def locked_artifacts(root=ROOT) -> list[dict]:
    root = Path(root)
    settings = (root / "settings.gradle").read_text(encoding="utf-8")
    versions = re.findall(r"gradle\.ext\.kotlinVersion\s*=\s*'([^']+)'", settings)
    if versions != [SUPPORTED_KGP]:
        raise PrepareError("KOTLIN_HOST_UNSUPPORTED_KGP: explicitly review the compiler closure: " + repr(versions))
    version = versions[0]
    metadata = ET.parse(root / "gradle/verification-metadata.xml").getroot()
    components = metadata.findall("./{*}components/{*}component")
    result = []
    for group, name, pinned in COORDINATES:
        selected_version = pinned or version
        filename = name + "-" + selected_version + ".jar"
        matches = [component for component in components
                   if component.attrib.get("group") == group
                   and component.attrib.get("name") == name
                   and component.attrib.get("version") == selected_version]
        if len(matches) != 1:
            raise PrepareError("KOTLIN_HOST_COORDINATE_PIN: " + filename)
        artifacts = [artifact for artifact in matches[0].findall("{*}artifact")
                     if artifact.attrib.get("name") == filename]
        if len(artifacts) != 1:
            raise PrepareError("KOTLIN_HOST_ARTIFACT_PIN: " + filename)
        hashes = [row.attrib.get("value", "") for row in artifacts[0].findall("{*}sha256")]
        if len(hashes) != 1 or not re.fullmatch(r"[0-9a-f]{64}", hashes[0]):
            raise PrepareError("KOTLIN_HOST_SHA256_PIN: " + filename)
        url = MAVEN + group.replace(".", "/") + "/" + name + "/" + selected_version + "/" + filename
        result.append({"group": group, "name": name, "version": selected_version,
                       "file": filename, "url": url, "sha256": hashes[0]})
    return result


def allowed_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme != "https" or parsed.netloc != "repo.maven.apache.org"
            or not parsed.path.startswith("/maven2/") or parsed.query or parsed.fragment
            or "\\" in url or any(char.isspace() for char in url)):
        raise PrepareError("KOTLIN_HOST_OFFICIAL_HTTPS_REQUIRED")


class OfficialRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        allowed_url(newurl)
        return super().redirect_request(request, fp, code, message, headers, newurl)


def read_digest(path: Path) -> str:
    path = checked_path(path)
    if not path.is_file():
        raise PrepareError("KOTLIN_HOST_FILE_MISSING: " + str(path))
    size = path.stat().st_size
    if not 0 < size <= MAX_BYTES:
        raise PrepareError("KOTLIN_HOST_SIZE: " + str(path))
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def check_existing(directory: Path, rows: list[dict], require_all: bool) -> list[Path]:
    directory = checked_path(directory)
    if not directory.exists():
        if require_all:
            raise PrepareError("KOTLIN_HOST_DIR_MISSING: " + str(directory))
        return []
    if not directory.is_dir():
        raise PrepareError("KOTLIN_HOST_NOT_DIRECTORY: " + str(directory))
    files = []
    # 不接受旧版/多候选的同名 artifact；避免准备与消费者选择出不同闭包。
    for row in rows:
        candidates = [path for path in directory.glob(row["name"] + "-*.jar")
                      if re.fullmatch(re.escape(row["name"]) + r"-\d[^/]*\.jar", path.name)]
        target = directory / row["file"]
        if any(path != target for path in candidates):
            raise PrepareError("KOTLIN_HOST_EXTRA_VERSION: " + row["name"])
        if target.exists() or target.is_symlink():
            if read_digest(target) != row["sha256"]:
                raise PrepareError("KOTLIN_HOST_EXISTING_DIGEST: preserved " + str(target))
            files.append(target)
        elif require_all:
            raise PrepareError("KOTLIN_HOST_FILE_MISSING: " + str(target))
    return files


def download(row: dict, target: Path, opener=None) -> None:
    target = checked_path(target)
    if target.exists():
        raise PrepareError("KOTLIN_HOST_TARGET_EXISTS: " + str(target))
    allowed_url(row["url"])
    opener = opener or urllib.request.build_opener(OfficialRedirect()).open
    request = urllib.request.Request(row["url"], headers={"User-Agent": "dsha-kotlin-host/1"})
    # TemporaryFile 无可见候选文件名；拒绝摘要后只关闭临时流，绝不覆盖已有 JAR。
    with tempfile.TemporaryFile(dir=target.parent) as staged:
        total = 0
        value = hashlib.sha256()
        with opener(request, timeout=120) as response:
            allowed_url(response.geturl())
            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                total += len(chunk)
                if total > MAX_BYTES:
                    raise PrepareError("KOTLIN_HOST_DOWNLOAD_LIMIT: " + row["file"])
                value.update(chunk)
                staged.write(chunk)
        if total == 0 or value.hexdigest() != row["sha256"]:
            raise PrepareError("KOTLIN_HOST_DOWNLOAD_DIGEST: " + row["file"])
        staged.seek(0)
        # 独占创建，不用 replace 覆盖可能并发出现的原件。中断后的不完整文件留待显式处理。
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(target, flags, 0o644)
        except FileExistsError as error:
            raise PrepareError("KOTLIN_HOST_TARGET_EXISTS: " + str(target)) from error
        with os.fdopen(descriptor, "wb") as output:
            shutil.copyfileobj(staged, output, 1024 * 1024)
            output.flush()
            os.fsync(output.fileno())
    if read_digest(target) != row["sha256"]:
        raise PrepareError("KOTLIN_HOST_POST_WRITE_DIGEST: " + str(target))


def prepare(directory: Path, check=False, root=ROOT, opener=None) -> dict:
    rows = locked_artifacts(root)
    directory = checked_path(directory)
    present = check_existing(directory, rows, require_all=check)
    if not check:
        directory.mkdir(parents=True, exist_ok=True)
        checked_path(directory)
        for row in rows:
            target = directory / row["file"]
            if target not in present:
                download(row, target, opener)
    files = check_existing(directory, rows, require_all=True)
    return {"status": "PASS", "kind": "kotlin-host-compiler", "checkOnly": check,
            "kgp": SUPPORTED_KGP, "directory": str(directory),
            "files": [{**row, "bytes": path.stat().st_size} for row, path in zip(rows, files)]}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dir", type=Path, default=ROOT / "app/build/kotlin-host")
    parser.add_argument("--check", action="store_true", help="仅核对已有文件；不创建目录、不联网")
    args = parser.parse_args(argv)
    print(json.dumps(prepare(args.dir, args.check), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (PrepareError, OSError, ET.ParseError) as error:
        print("FAIL " + str(error), file=sys.stderr)
        raise SystemExit(1)

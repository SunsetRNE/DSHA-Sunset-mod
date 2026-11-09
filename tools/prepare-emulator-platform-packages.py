#!/usr/bin/env python3
"""为模拟器 x86_64 调试目标补齐 linux-x64 平台可选包（锁定版本 + 完整性校验）。

`tools/build-dsh-runtime.py` 只把 arm64 平台包打进正式 APK；Windows 上跑 `npm install`
得到的模块树里，`@img/sharp-*`、`@koromix/koffi-*` 这类可选依赖只会安装 win32 变体。
模拟器目标需要同一批包的 linux-x64 变体，否则容器内的 DSH 一旦 `require('sharp')`
就会以 `TRIAL_NATIVE_MODULES_FAILED` 结束试运行，部署永远停在「安装文件已提交」。

来源与完整性：版本与 sha512-integrity 一律取自 `tools/dsh-runtime/package-lock.json`
的锁定条目（不是 package.json 的范围值）；`npm pack` 下载的 tarball 先核 sha512，
解包只接受常规文件，拒绝绝对路径、`..` 与链接成员，先解到临时目录再原子改名。
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/dsh-runtime/package-lock.json'


def linux_variants(dependencies):
    """返回该包声明里属于 linux-x64/glibc 的可选包名。"""
    result = []
    for name in dependencies or {}:
        if 'linux' not in name or 'x64' not in name:
            continue
        # 只要 gnu/glibc，musl 在 Android 的 glibc 容器里用不到。
        if 'musl' in name:
            continue
        result.append(name)
    return sorted(result)


def required_packages(tree):
    """缺失的 linux-x64 可选包 → 锁定条目（版本 + integrity）。"""
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    locked = lock.get('packages') or {}
    need = {}
    for package_json in tree.rglob('package.json'):
        relative = package_json.parent.relative_to(tree)
        if len(relative.parts) > 3:
            continue
        try:
            metadata = json.loads(package_json.read_text(encoding='utf-8'))
        except (OSError, ValueError, UnicodeError):
            continue
        for name in linux_variants(metadata.get('optionalDependencies')):
            if (tree / name).is_dir():
                continue
            entry = locked.get('node_modules/' + name)
            if not entry or not entry.get('version') or not entry.get('integrity'):
                raise ValueError(
                    'EMULATOR_PACKAGE_NOT_LOCKED: ' + name
                    + ' —— 不允许按 package.json 的范围值下载未锁定的包')
            need[name] = {'version': entry['version'], 'integrity': entry['integrity']}
    return need


def npm_command():
    found = shutil.which('npm') or shutil.which('npm.cmd')
    if not found:
        raise RuntimeError('npm 不在 PATH（Windows 下请确认 npm.cmd 可用）')
    return found


def verify_integrity(archive, integrity):
    """按 npm 的 sha512-<base64> 字段核对下载物。"""
    algorithm, encoded = integrity.split('-', 1)
    if algorithm != 'sha512':
        raise RuntimeError('不支持的 integrity 算法 ' + algorithm)
    digest = hashlib.new(algorithm, archive.read_bytes()).digest()
    if base64.b64encode(digest).decode('ascii') != encoded:
        raise RuntimeError('integrity 不符：' + archive.name)


def safe_members(source, target_root):
    """只放行常规文件；拒绝链接成员；逐成员核对解包路径不越界。"""
    for member in source:
        parts = Path(member.name).parts
        if len(parts) < 2 or parts[0] != 'package':
            continue
        if member.issym() or member.islnk():
            raise RuntimeError('tar 包含链接成员，拒绝解包: ' + member.name)
        if not member.isfile():
            continue
        relative = Path(*parts[1:])
        destination = (target_root / relative).resolve()
        if not str(destination).startswith(str(target_root.resolve()) + os.sep):
            raise RuntimeError('tar 成员越界，拒绝解包: ' + member.name)
        member.name = relative.as_posix()
        yield member


def pack(name, entry, cache):
    archive = cache / (name.replace('/', '-').replace('@', '') + '-' + entry['version'] + '.tgz')
    if archive.is_file():
        try:
            verify_integrity(archive, entry['integrity'])
            return archive
        except RuntimeError:
            archive.unlink()  # 缓存损坏就重下，不静默复用。
    cache.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        [npm_command(), 'pack', name + '@' + entry['version'], '--silent',
         '--pack-destination', str(cache)],
        capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        raise RuntimeError('npm pack 失败 ' + name + '\n' + result.stdout + result.stderr)
    produced = sorted(cache.glob('*.tgz'), key=lambda path: path.stat().st_mtime)
    if not produced:
        raise RuntimeError('npm pack 没有产物 ' + name)
    newest = produced[-1]
    verify_integrity(newest, entry['integrity'])
    if newest != archive:
        newest.replace(archive)
    return archive


def extract(archive, target):
    staging = target.parent / (target.name + '.unpack-' + os.urandom(6).hex())
    staging.mkdir(parents=True, exist_ok=False)
    try:
        with tarfile.open(archive) as source:
            source.extractall(staging, members=safe_members(source, staging))
        if target.exists():
            shutil.rmtree(target)
        staging.replace(target)
    except BaseException:
        shutil.rmtree(staging, ignore_errors=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node-modules', type=Path, required=True)
    parser.add_argument('--cache', type=Path, default=ROOT / 'app/build/emulator-platform-cache')
    parser.add_argument('--check', action='store_true', help='只报告缺失项，不下载')
    args = parser.parse_args()
    tree = args.node_modules.resolve()
    if not (tree / '@deepseek-ai/dsh').is_dir():
        raise ValueError('EMULATOR_MODULES_NOT_INSTALLED: ' + str(tree))
    need = required_packages(tree)
    if args.check:
        for name, entry in sorted(need.items()):
            print('MISSING', name, entry['version'])
        print('PASS' if not need else 'INCOMPLETE', len(need), 'linux-x64 平台包缺失')
        return 0 if not need else 1
    added = []
    for name, entry in sorted(need.items()):
        archive = pack(name, entry, args.cache.resolve())
        extract(archive, tree / name)
        added.append(name + '@' + entry['version'])
    for name in added:
        print('ADDED', name)
    print('PASS linux-x64 平台包齐全：新增', len(added))
    return 0


if __name__ == '__main__':
    sys.exit(main())

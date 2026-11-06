#!/usr/bin/env python3
"""为模拟器 x86_64 调试目标补齐 linux-x64 平台可选包。

`tools/build-dsh-runtime.py` 只把 arm64 平台包打进正式 APK；Windows 上跑 `npm install`
得到的模块树里，`@img/sharp-*`、`@koromix/koffi-*` 这类可选依赖只会安装 win32 变体。
模拟器目标需要同一批包的 linux-x64 变体，否则容器内的 DSH 一旦 `require('sharp')`
就会以 `TRIAL_NATIVE_MODULES_FAILED` 结束试运行，部署永远停在「安装文件已提交」。

这里按 `package.json` 的 optionalDependencies 找到当前缺失的 linux-x64 变体，
用 `npm pack` 取回锁定版本并解到模块树里。只补 gnu 变体：容器是 glibc Ubuntu。
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


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
            target = tree / name
            if target.is_dir():
                continue
            version = (metadata.get('optionalDependencies') or {}).get(name)
            need[name] = version
    return need


def pack(name, version, cache):
    archive = cache / (name.replace('/', '-').replace('@', '') + '-' + version + '.tgz')
    if archive.is_file():
        return archive
    cache.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ['npm', 'pack', name + '@' + version, '--silent', '--pack-destination', str(cache)],
        capture_output=True, text=True, encoding='utf-8', errors='replace')
    if result.returncode:
        raise RuntimeError('npm pack 失败 ' + name + '\n' + result.stdout + result.stderr)
    produced = sorted(cache.glob('*.tgz'), key=lambda path: path.stat().st_mtime)
    if not produced:
        raise RuntimeError('npm pack 没有产物 ' + name)
    newest = produced[-1]
    if newest != archive:
        newest.replace(archive)
    return archive


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
        for name, version in sorted(need.items()):
            print('MISSING', name, version)
        print('PASS' if not need else 'INCOMPLETE', len(need), 'linux-x64 平台包缺失')
        return 0 if not need else 1
    added = []
    for name, version in sorted(need.items()):
        archive = pack(name, version, args.cache.resolve())
        target = tree / name
        target.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as source:
            members = []
            for member in source:
                parts = Path(member.name).parts
                if len(parts) < 2 or parts[0] != 'package':
                    continue
                member.name = str(Path(*parts[1:]))
                members.append(member)
            source.extractall(target, members=members)
        added.append(name + '@' + version)
    for name in added:
        print('ADDED', name)
    print('PASS linux-x64 平台包齐全：新增', len(added))


if __name__ == '__main__':
    sys.exit(main())

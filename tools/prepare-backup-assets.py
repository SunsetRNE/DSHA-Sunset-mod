#!/usr/bin/env python3
"""从锁定归档和受管安装表生成依赖与签名内置插件的精确文件证明。"""
from source_text import write_text as write_source_text, matches_text
from pathlib import Path
from runtime_input_contract import BUILTIN_RUNTIME_SUFFIXES, relative
import hashlib
import json
import os
import re
import stat
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/'
MAX_SIGNED_BUILTIN_FILES = 2048
MAX_SIGNED_BUILTIN_BYTES = 64 * 1024 * 1024
MAX_SIGNED_BUILTIN_NODES = 8192
MAX_SIGNED_BUILTIN_PATH = 1024
MAX_SIGNED_BUILTIN_DEPTH = 32
MAX_PROOF_BYTES = 2 * 1024 * 1024
PACKAGE_NAME = re.compile(r'(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*\Z')


def archive_proofs(archive):
    """保持既有归档证明的字段、排序与摘要算法不变。"""
    archive = Path(archive)
    records = {}
    with tarfile.open(archive, 'r:gz') as tar:
        for item in tar:
            if not item.name.startswith(PREFIX):
                continue
            rel = item.name[len(PREFIX):]
            parts = rel.split('/')
            count = 2 if parts[0].startswith('@') else 1
            if len(parts) <= count:
                continue
            name, package_path = '/'.join(parts[:count]), '/'.join(parts[count:])
            if item.isfile():
                sha = hashlib.sha256()
                with tar.extractfile(item) as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                        sha.update(chunk)
                value = package_path + '\0FILE\0' + str(item.mode & 0o777) + '\0' + sha.hexdigest() + '\n'
            elif item.issym():
                value = package_path + '\0LINK\0' + item.linkname + '\n'
            elif item.isdir():
                continue
            else:
                raise ValueError('unsupported package entry')
            records.setdefault(name, []).append((package_path, value))
    proofs = {name: hashlib.sha256(''.join(value for _, value in sorted(
        rows, key=lambda row: row[0].encode('utf-16be'))).encode()).hexdigest()
        for name, rows in records.items()}
    sha = hashlib.sha256()
    with archive.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            sha.update(chunk)
    return {'version': 1, 'archiveSha256': sha.hexdigest(), 'packages': dict(sorted(proofs.items()))}


def _relative(value, *, package_path=False):
    value = relative(value)
    if any(ord(char) < 32 or ord(char) == 127 for char in value) or package_path and (
            len(value) > MAX_SIGNED_BUILTIN_PATH or len(value.split('/')) > MAX_SIGNED_BUILTIN_DEPTH):
        raise ValueError('SIGNED_BUILTIN_PATH: ' + repr(value))
    return value


def _regular_asset(assets, source):
    """安装清单不能借文件或父目录链接取插件树外的字节。"""
    current = assets
    for part in _relative(source).split('/'):
        current = current / part
        node = current.lstat()
        if stat.S_ISLNK(node.st_mode):
            raise ValueError('SIGNED_BUILTIN_ASSET_LINK: ' + source)
    if not stat.S_ISREG(node.st_mode):
        raise ValueError('SIGNED_BUILTIN_ASSET_TYPE: ' + source)
    return current, node


def _file_record(assets, row, package_path, remaining):
    file, before = _regular_asset(assets, row['asset'])
    if before.st_size > remaining:
        raise ValueError('SIGNED_BUILTIN_BYTE_LIMIT')
    sha, size = hashlib.sha256(), 0
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0)
    with os.fdopen(os.open(file, flags), 'rb') as stream:
        opened = os.fstat(stream.fileno())
        if (opened.st_dev, opened.st_ino, opened.st_mode, opened.st_size) != (
                before.st_dev, before.st_ino, before.st_mode, before.st_size):
            raise ValueError('SIGNED_BUILTIN_ASSET_CHANGED: ' + row['asset'])
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            size += len(chunk)
            if size > remaining:
                raise ValueError('SIGNED_BUILTIN_BYTE_LIMIT')
            sha.update(chunk)
        after = os.fstat(stream.fileno())
    current = file.lstat()
    identity = lambda node: (node.st_dev, node.st_ino, node.st_mode, node.st_size,
                             node.st_mtime_ns, node.st_ctime_ns)
    if identity(before) != identity(after) or identity(before) != identity(current) or size != before.st_size:
        raise ValueError('SIGNED_BUILTIN_ASSET_CHANGED: ' + row['asset'])
    # RuntimeAssetFiles 经 AndroidBackupFileSystem.atomic 新建时为 0600，只有
    # executable 才明确 chmod 0755；不沿用 Git checkout 的文件位。
    return {'path': package_path, 'sha256': sha.hexdigest(),
            'mode': 0o755 if row['executable'] else 0o600, 'size': size}


def build_signed_builtin_files(root):
    """只证明注册表签名实体的精确安装项，不包含共享 node_modules。"""
    assets = Path(root) / 'app/src/main/assets'
    registry = json.loads((assets / 'builtin-plugins.json').read_text(encoding='utf-8'))
    manifest = json.loads((assets / 'managed-runtime-inputs.json').read_text(encoding='utf-8'))
    if registry.get('schema') != 1 or not isinstance(registry.get('plugins'), list):
        raise ValueError('SIGNED_BUILTIN_REGISTRY')
    if manifest.get('schema') != 1 or not isinstance(manifest.get('installs'), list):
        raise ValueError('SIGNED_BUILTIN_INSTALLS')
    installs, sources, targets = [], set(), set()
    for row in manifest['installs']:
        if not isinstance(row, dict):
            raise ValueError('SIGNED_BUILTIN_INSTALLS')
        source, target = _relative(row.get('asset')), _relative(row.get('target'))
        if source in sources or target in targets or not isinstance(row.get('executable'), bool):
            raise ValueError('DUPLICATE_OR_INVALID_RUNTIME_INSTALL')
        sources.add(source)
        targets.add(target)
        installs.append(row)
    result, guest_roots = {}, []
    for plugin in registry['plugins']:
        if not isinstance(plugin, dict):
            raise ValueError('SIGNED_BUILTIN_REGISTRY')
        name, guest = plugin.get('name'), plugin.get('guestDirectory')
        if not isinstance(name, str) or len(name) > 214 or not PACKAGE_NAME.fullmatch(name) or name in result:
            raise ValueError('SIGNED_BUILTIN_NAME')
        if not isinstance(guest, str) or not guest.startswith('/'):
            raise ValueError('SIGNED_BUILTIN_GUEST_PATH')
        guest = _relative(guest[1:])
        if any(guest == other or guest.startswith(other + '/') or other.startswith(guest + '/')
               for other in guest_roots):
            raise ValueError('SIGNED_BUILTIN_GUEST_OVERLAP')
        guest_roots.append(guest)
        entrypoint = _relative(plugin.get('entrypoint'), package_path=True)
        tree = 'app-integration' if name == 'dsh-app-integration' else 'builtin-plugins/' + name
        directory = assets / tree
        if not directory.is_dir() or directory.is_symlink():
            raise ValueError('BUILTIN_RUNTIME_TREE_MISSING:' + name)
        selected = {}
        for row in installs:
            if not row['target'].startswith(guest + '/'):
                continue
            package_path = _relative(row['target'][len(guest) + 1:], package_path=True)
            if package_path.startswith('node_modules/'):
                continue
            if row['asset'] != tree + '/' + package_path:
                raise ValueError('BUILTIN_RUNTIME_TARGET_MISMATCH:' + row['asset'])
            if row.get('extract') is not None:
                raise ValueError('SIGNED_BUILTIN_EXTRACT:' + row['asset'])
            selected[package_path] = row
        # 与 runtime_input_contract 使用同一运行资源后缀；共享依赖和源码目录
        # 不属于签名部署文件证明，显式安装的其它文件（如 LICENSE）仍进入证明。
        for folder, directories, files in os.walk(directory, followlinks=False):
            folder = Path(folder)
            directories[:] = [part for part in directories if not (
                folder == directory and part in ('node_modules', 'src'))]
            for part in directories:
                if (folder / part).is_symlink():
                    raise ValueError('SIGNED_BUILTIN_ASSET_LINK:' + (folder / part).as_posix())
            for filename in files:
                file = folder / filename
                if file.suffix not in BUILTIN_RUNTIME_SUFFIXES:
                    continue
                package_path = _relative(file.relative_to(directory).as_posix(), package_path=True)
                if package_path not in selected:
                    raise ValueError('BUILTIN_RUNTIME_FILE_UNINSTALLED:' + tree + '/' + package_path)
        if 'package.json' not in selected or entrypoint not in selected:
            raise ValueError('SIGNED_BUILTIN_REQUIRED_FILE:' + name)
        if len(selected) > MAX_SIGNED_BUILTIN_FILES:
            raise ValueError('SIGNED_BUILTIN_FILE_LIMIT:' + name)
        nodes = set()
        for package_path in selected:
            parts = package_path.split('/')
            for count in range(1, len(parts)):
                parent = '/'.join(parts[:count])
                if parent in selected:
                    raise ValueError('SIGNED_BUILTIN_PATH_CONFLICT:' + name)
                nodes.add(parent)
            nodes.add(package_path)
            if len(nodes) > MAX_SIGNED_BUILTIN_NODES:
                raise ValueError('SIGNED_BUILTIN_NODE_LIMIT:' + name)
        entries, size = [], 0
        for package_path, row in sorted(selected.items(), key=lambda pair: pair[0].encode('utf-16be')):
            record = _file_record(assets, row, package_path, MAX_SIGNED_BUILTIN_BYTES - size)
            entries.append(record)
            size += record['size']
        result[name] = entries
    return dict(sorted(result.items()))


def proof_content(output):
    """与运行时整个证明表的 2 MiB 读取上限一致，不生成不可消费的截断证明。"""
    content = json.dumps(output, ensure_ascii=False, indent=2) + '\n'
    if len(content.encode('utf-8')) > MAX_PROOF_BYTES:
        raise ValueError('MANAGED_PACKAGE_PROOF_BYTE_LIMIT')
    return content


def build_proofs(root, archive=None):
    root = Path(root)
    archive = Path(archive) if archive is not None else root / 'app/src/main/assets/dsh-runtime.bin'
    output = archive_proofs(archive)
    output['signedBuiltinFiles'] = build_signed_builtin_files(root)
    proof_content(output)
    return output


def main(args=None):
    args = sys.argv[1:] if args is None else args
    if args not in ([], ['--write'], ['--check']):
        raise SystemExit('usage: prepare-backup-assets.py [--check|--write]')
    output = build_proofs(ROOT)
    target = ROOT / 'app/src/main/assets/managed-package-proofs.json'
    content = proof_content(output)
    if args == ['--check']:
        if not matches_text(target, content, encoding='utf8'):
            raise SystemExit('受管依赖证明与当前归档不一致；先运行 tools/prepare-backup-assets.py --write 并审阅改动')
    else:
        write_source_text(target, content, encoding='utf8')
    print('managed package proofs:', len(output['packages']))
    return 0


if __name__ == '__main__':
    main()

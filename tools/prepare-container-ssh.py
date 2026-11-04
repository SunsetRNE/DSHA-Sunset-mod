#!/usr/bin/env python3
"""按固定 Ubuntu 包锁重建或离线校验独立 SSH 资产，不改基础包数据库。"""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'tools/container-ssh/packages.lock.json'
ASSETS = ROOT / 'app/src/main/assets/container-ssh'
SELECTED = {
    'openssh-server': {'usr/sbin/sshd': 'sshd'},
    'openssh-client': {'usr/bin/ssh-keygen': 'ssh-keygen'},
    'libwrap0': {'usr/lib/aarch64-linux-gnu/libwrap.so.0': 'lib/libwrap.so.0'},
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def checked_lock(path):
    lock = json.loads(path.read_text(encoding='utf-8'))
    rows = lock.get('packages', [])
    if lock.get('schema') != 1 or lock.get('architecture') != 'arm64' or len(rows) != len(SELECTED):
        raise ValueError('SSH_PACKAGE_LOCK')
    if {row['Package'] for row in rows} != set(SELECTED):
        raise ValueError('SSH_PACKAGE_MEMBERS')
    for row in rows:
        name = row['Filename']
        if (row['Architecture'] != 'arm64' or not name.startswith('pool/main/')
                or '..' in PurePosixPath(name).parts or not re.fullmatch(r'[A-Za-z0-9_./+~%-]+', name)
                or not re.fullmatch(r'[a-f0-9]{64}', row['SHA256']) or int(row['Size']) > 8 * 1024 * 1024):
            raise ValueError('SSH_PACKAGE_SOURCE')
    return lock


def check_assets(directory, lock):
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    expected = {'schema': 1, 'architecture': 'aarch64', 'files': lock['files']}
    if manifest != expected:
        raise ValueError('SSH_ASSET_MANIFEST')
    names = {'manifest.json'} | set(lock['licenses'])
    seen = set()
    for row in lock['files']:
        name = row['asset']
        if not re.fullmatch(r'payload-[0-9]+\.bin', name) or row['target'] in seen:
            raise ValueError('SSH_ASSET_MEMBER')
        seen.add(row['target'])
        names.add(name)
        path = directory / name
        if path.is_symlink():
            raise ValueError('SSH_ASSET_LINK')
        data = path.read_bytes()
        if len(data) != row['bytes'] or sha(data) != row['sha256']:
            raise ValueError('SSH_ASSET_DIGEST:' + name)
        if len(data) < 20 or data[:6] != b'\x7fELF\x02\x01' or int.from_bytes(data[18:20], 'little') != 183:
            raise ValueError('SSH_ASSET_ARCHITECTURE:' + name)
    if seen != {'sshd', 'ssh-keygen', 'lib/libwrap.so.0'}:
        raise ValueError('SSH_ASSET_TARGETS')
    if {path.name for path in directory.iterdir()} != names:
        raise ValueError('SSH_ASSET_MEMBER_SET')
    for name, expected_digest in lock['licenses'].items():
        if '/' in name or '\\' in name or sha((directory / name).read_bytes()) != expected_digest:
            raise ValueError('SSH_ASSET_LICENSE')
    return {'packages': len(lock['packages']), 'files': len(seen),
            'payloadBytes': sum(row['bytes'] for row in lock['files'])}


def deb_data(data, decoder):
    if not data.startswith(b'!<arch>\n'):
        raise ValueError('SSH_DEB_FORMAT')
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        if len(header) != 60 or header[-2:] != b'`\n':
            raise ValueError('SSH_DEB_HEADER')
        size = int(header[48:58])
        name = header[:16].decode().strip().rstrip('/')
        body = data[offset + 60:offset + 60 + size]
        if len(body) != size:
            raise ValueError('SSH_DEB_TRUNCATED')
        if name == 'data.tar.zst':
            with decoder.ZstdDecompressor().stream_reader(io.BytesIO(body)) as reader:
                expanded = reader.read(32 * 1024 * 1024 + 1)
            if len(expanded) > 32 * 1024 * 1024:
                raise ValueError('SSH_DEB_EXPANDED_LIMIT')
            return expanded
        if name.startswith('data.tar.'):
            return body
        offset += 60 + size + size % 2
    raise ValueError('SSH_DEB_DATA_MISSING')


def rebuild(lock, output, cache, decoder, offline):
    output.mkdir(parents=True, exist_ok=False)
    cache.mkdir(parents=True, exist_ok=True)
    contents, licenses = {}, {}
    for row in lock['packages']:
        package = row['Package']
        path = cache / Path(row['Filename']).name
        data = path.read_bytes() if path.is_file() else b''
        if len(data) != int(row['Size']) or sha(data) != row['SHA256']:
            if offline:
                raise ValueError('SSH_PACKAGE_CACHE_MISSING:' + package)
            with urllib.request.urlopen('https://ports.ubuntu.com/ubuntu-ports/' + row['Filename'], timeout=60) as response:
                data = response.read(8 * 1024 * 1024 + 1)
            if len(data) != int(row['Size']) or sha(data) != row['SHA256']:
                raise ValueError('SSH_PACKAGE_DIGEST:' + package)
            path.write_bytes(data)
        with tarfile.open(fileobj=io.BytesIO(deb_data(data, decoder)), mode='r:*') as archive:
            members = {item.name.removeprefix('./'): item for item in archive}
            for source, target in SELECTED[package].items():
                seen = set()
                while not members[source].isfile():
                    if source in seen or not members[source].issym():
                        raise ValueError('SSH_PACKAGE_LINK')
                    seen.add(source)
                    source = posixpath.normpath(posixpath.join(posixpath.dirname(source), members[source].linkname))
                    if source.startswith('/') or '..' in PurePosixPath(source).parts:
                        raise ValueError('SSH_PACKAGE_LINK_ESCAPE')
                contents[target] = archive.extractfile(members[source]).read()
            copyright_path = 'usr/share/doc/' + package + '/copyright'
            if copyright_path in members:
                licenses[package + '-copyright.txt'] = archive.extractfile(members[copyright_path]).read()
            elif package != 'openssh-server':
                raise ValueError('SSH_PACKAGE_LICENSE:' + package)
    for row in lock['files']:
        data = contents[row['target']]
        if sha(data) != row['sha256'] or len(data) != row['bytes']:
            raise ValueError('SSH_REBUILD_PAYLOAD_MISMATCH')
        (output / row['asset']).write_bytes(data)
    for name, expected in lock['licenses'].items():
        if sha(licenses[name]) != expected:
            raise ValueError('SSH_REBUILD_LICENSE_MISMATCH')
        (output / name).write_bytes(licenses[name])
    (output / 'manifest.json').write_text(json.dumps(
        {'schema': 1, 'architecture': 'aarch64', 'files': lock['files']}, indent=2) + '\n', encoding='utf-8')
    return check_assets(output, lock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--lock', type=Path, default=LOCK)
    parser.add_argument('--assets', type=Path, default=ASSETS)
    parser.add_argument('--decoder', type=Path)
    parser.add_argument('--cache', type=Path, default=ROOT / 'app/build/container-ssh-downloads')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    lock = checked_lock(args.lock)
    if args.check:
        result = check_assets(args.assets, lock)
    else:
        if args.output is None or args.decoder is None:
            parser.error('rebuild requires --output and --decoder')
        for path in (args.output, args.decoder, args.cache):
            path.resolve().relative_to(ROOT)
        sys.path.insert(0, str(args.decoder.resolve()))
        import zstandard
        result = rebuild(lock, args.output.resolve(), args.cache.resolve(), zstandard, args.offline)
    print('PASS container SSH assets ' + json.dumps(result))


if __name__ == '__main__':
    main()
"""Create or verify the source companion for the four Standard native components."""
import argparse
from collections import Counter
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'tools/proot-source'
LOCK = ROOT / 'tools/proot-source.lock.json'
ARCHIVE_NAME = 'dsha-proot-talloc-source.zip'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source_bytes(lock):
    output = io.BytesIO()
    entries = {}
    for name, expected in lock['files'].items():
        path = SOURCE / name
        if not path.is_file() or path.is_symlink():
            raise ValueError('PROOT_SOURCE_MISSING: ' + name)
        data = path.read_bytes()
        if name.endswith(('.py', '.md', '.h')):
            data = data.replace(b'\r\n', b'\n')
        if sha(data) != expected:
            raise ValueError('PROOT_SOURCE_CHANGED: ' + name)
        entries[name] = data
    entries['SHA256SUMS'] = ''.join(value + '  ' + name + '\n'
                                     for name, value in sorted(lock['libraries'].items())).encode('ascii')
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as archive:
        for name, data in sorted(entries.items()):
            member = zipfile.ZipInfo(name)
            member.create_system = 3
            member.external_attr = 0o100644 << 16
            archive.writestr(member, data)
    return output.getvalue()


def write_source(path, data):
    # Reuse identical output. Only complete archives become visible after a retry or interruption.
    if path.is_file() and path.read_bytes() == data:
        return
    with tempfile.TemporaryDirectory(dir=path.parent) as tmp:
        stage = Path(tmp) / 'source.zip'
        stage.write_bytes(data)
        os.replace(stage, path)


def verify_apk(apk, lock=None):
    if lock is None:
        lock = json.loads(LOCK.read_text(encoding='utf-8'))
    binaries = lock['libraries']
    expected = {'lib/arm64-v8a/' + name: digest for name, digest in binaries.items()}
    expected.update({'assets/licenses/' + name: digest for name, digest in lock['licenses'].items()})
    actual = Counter(name for name in apk.namelist() if name in expected
                     or (name.startswith('lib/') and PurePosixPath(name).name in binaries))
    if actual != Counter(expected.keys()):
        raise ValueError('PROOT_APK_MEMBERS: ' + str(actual))
    for name, digest in expected.items():
        if sha(apk.read(name)) != digest:
            raise ValueError('PROOT_APK_BYTES: ' + name)
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument('--write-source', type=Path)
    action.add_argument('--check-source', type=Path)
    args = parser.parse_args()
    if args.write_source and (args.write_source.suffix.lower() != '.zip'
                              or args.write_source.resolve() == args.apk.resolve()):
        parser.error('source output must be a separate .zip file')
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    with zipfile.ZipFile(args.apk) as apk:
        verify_apk(apk, lock)
    source = source_bytes(lock)
    if args.write_source:
        write_source(args.write_source, source)
    elif args.check_source.read_bytes() != source:
        raise ValueError('PROOT_SOURCE_COMPANION_BYTES')
    with args.apk.open('rb') as apk_stream:
        apk_digest = hashlib.sha256()
        for block in iter(lambda: apk_stream.read(1048576), b''):
            apk_digest.update(block)
        apk_sha = apk_digest.hexdigest()
    print(json.dumps(dict(apkSha256=apk_sha, sourceSha256=sha(source), sourceBytes=len(source),
                          libraries=lock['libraries']), indent=2))


if __name__ == '__main__':
    main()

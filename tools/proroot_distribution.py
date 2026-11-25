"""Restore pinned build inputs locally and verify the original vendor files in a complete APK."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
from urllib.request import urlopen
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
NATIVE_DIR = 'app/src/main/jniLibs/arm64-v8a'
VENDOR_SOURCE_GLOB = NATIVE_DIR + '/libproroot*.so'
# coderredlab/proroot v1.2.8, commit 14b72a764490d0280eee3915ecfa3e8a132b392e.
LICENSE_NAME = 'proroot-v1.2.8-LICENSE.txt'
LICENSE_SHA256 = '0576d93d18783c1dd677c1d412757a20a3098a2585590b760e444201a3ff565e'
UPSTREAM = 'https://raw.githubusercontent.com/coderredlab/proroot/14b72a764490d0280eee3915ecfa3e8a132b392e/arm64-v8a/'
MEMBER_LIMIT = 1024 * 1024


def vendor_pins(root=ROOT):
    lock = json.loads((root / 'tools/proroot-compat/abi.lock.json').read_text(encoding='utf-8'))
    return lock['originalVendorSha256']


def verify_apk(apk):
    expected = {'lib/arm64-v8a/' + name: digest for name, digest in vendor_pins().items()}
    license_member = 'assets/licenses/' + LICENSE_NAME
    expected[license_member] = LICENSE_SHA256
    actual = Counter(name for name in apk.namelist()
                     if name == license_member or
                     (name.startswith('lib/') and PurePosixPath(name).match('libproroot*.so')))
    if actual != Counter(expected.keys()):
        raise ValueError('APK_PROROOT_MEMBERS: missing=%s unexpected=%s duplicate=%s' % (
            sorted(expected.keys() - actual.keys()), sorted(actual.keys() - expected.keys()),
            sorted(name for name, count in actual.items() if count > 1)))
    for name, digest in expected.items():
        if apk.getinfo(name).file_size > MEMBER_LIMIT or hashlib.sha256(apk.read(name)).hexdigest() != digest:
            raise ValueError('APK_PROROOT_BYTES: ' + name)
    return expected


def prepare_inputs(directory, apk_path=None, upstream=False):
    pins = vendor_pins()
    pending = {}
    for name, digest in pins.items():
        path = directory / name
        if path.is_symlink() or (path.exists() and not path.is_file()):
            raise ValueError('PROROOT_INPUT_NOT_REGULAR: ' + name)
        if not path.is_file() or path.stat().st_size > MEMBER_LIMIT or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            pending[name] = digest
    if not pending:
        return 0
    if apk_path:
        with ZipFile(apk_path) as apk:
            verify_apk(apk)
            contents = {name: apk.read('lib/arm64-v8a/' + name) for name in pending}
    elif upstream:
        contents = {}
        for name, digest in pending.items():
            with urlopen(UPSTREAM + name, timeout=40) as response:
                data = response.read(MEMBER_LIMIT + 1)
            if len(data) > MEMBER_LIMIT or hashlib.sha256(data).hexdigest() != digest:
                raise ValueError('UPSTREAM_PROROOT_BYTES: ' + name)
            contents[name] = data
    else:
        raise ValueError('Missing or changed proroot input. Provide a complete matching APK with '
                         '--apk PATH or use --upstream to obtain the pinned files from the author.')
    directory.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.proroot-', dir=directory) as temporary:
        staged = Path(temporary)
        for name, data in contents.items():
            (staged / name).write_bytes(data)
        for name in contents:
            os.replace(staged / name, directory / name)
    return len(pending)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sources = parser.add_mutually_exclusive_group()
    sources.add_argument('--apk', type=Path, help='read exact originals from a complete, previously built APK')
    sources.add_argument('--upstream', action='store_true', help='read immutable files from the original publisher')
    options = parser.parse_args()
    destination = ROOT / NATIVE_DIR
    if destination.resolve() != destination:
        raise ValueError('PROROOT_INPUT_DIRECTORY_REDIRECTED')
    restored = prepare_inputs(destination, options.apk, options.upstream)
    print(f'PASS: checked {len(vendor_pins())} original proroot files, restored {restored}')

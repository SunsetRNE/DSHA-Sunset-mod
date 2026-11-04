#!/usr/bin/env python3
"""暂存并验证 Ubuntu 官方 ARM64 索引；不修改现有运行时或资产。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
FINGERPRINT = 'F6ECB3762474EDA9D21B7022871920D1991BC93C'


def download(url, destination):
    with urllib.request.urlopen(url, timeout=60) as response:
        destination.write_bytes(response.read())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.relative_to(ROOT)
    output.mkdir(parents=True, exist_ok=False)
    keyring = output / 'ubuntu-archive-keyring.gpg'
    with tarfile.open(ROOT / 'app/src/main/assets/offline-rootfs.bin') as archive:
        member = archive.getmember('./usr/share/keyrings/ubuntu-archive-keyring.gpg')
        keyring.write_bytes(archive.extractfile(member).read())
    gnupg = output / 'gnupg'
    gnupg.mkdir()
    evidence = []
    for suite in ('noble', 'noble-updates', 'noble-security'):
        directory = output / suite
        directory.mkdir()
        release = directory / 'InRelease'
        url = 'https://ports.ubuntu.com/ubuntu-ports/dists/' + suite + '/'
        download(url + 'InRelease', release)
        result = subprocess.run([
            'gpg', '--homedir', gnupg.relative_to(ROOT).as_posix(), '--no-options',
            '--batch', '--no-auto-key-retrieve', '--no-default-keyring',
            '--keyring', './' + keyring.relative_to(ROOT).as_posix(), '--status-fd', '1',
            '--verify', release.relative_to(ROOT).as_posix(),
        ], cwd=ROOT, capture_output=True, text=True)
        (directory / 'signature.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
        result.check_returncode()
        if '[GNUPG:] VALIDSIG ' + FINGERPRINT + ' ' not in result.stdout:
            raise ValueError('UBUNTU_SIGNING_FINGERPRINT:' + suite)
        entries = {}
        inside = False
        for line in release.read_text(encoding='utf-8').splitlines():
            if line == 'SHA256:':
                inside = True
            elif inside and line.startswith(' '):
                digest, size, name = line.split()
                entries[name] = (digest, int(size))
            elif inside:
                break
        relative = 'main/binary-arm64/Packages.xz'
        digest, size = entries[relative]
        packages = directory / 'Packages.xz'
        download(url + relative, packages)
        actual = hashlib.sha256(packages.read_bytes()).hexdigest()
        if actual != digest or packages.stat().st_size != size:
            raise ValueError('UBUNTU_INDEX_DIGEST:' + suite)
        evidence.append({'suite': suite, 'url': url, 'signer': FINGERPRINT,
                         'inReleaseSha256': hashlib.sha256(release.read_bytes()).hexdigest(),
                         'packagesSha256': actual, 'packagesBytes': size})
        print('PASS signed ARM64 index:', suite, flush=True)
    (output / 'receipt.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()

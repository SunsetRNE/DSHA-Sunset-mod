#!/usr/bin/env python3
"""Bind an isolated runtime directory to exact bytes of a pinned DSHA archive."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import posixpath
import stat
import sys
import tarfile

PREFIX = 'usr/local/lib/node_modules/@deepseek-ai/dsh/'


def file_digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    assert root.is_dir()
    assert len(args.sha256) == 64 and all(c in '0123456789abcdef' for c in args.sha256)
    assert file_digest(args.archive) == args.sha256, 'ARCHIVE_SHA256_MISMATCH'
    files, links, entries, expected_dirs = {}, {}, {}, set()
    with tarfile.open(args.archive, 'r:*') as archive:
        members = archive.getmembers()
        for entry in members:
            name = PurePosixPath(entry.name)
            assert not name.is_absolute() and '..' not in name.parts and '\\' not in entry.name
            assert name.as_posix() == entry.name and entry.name not in entries
            assert entry.isfile() or entry.issym(), 'UNSUPPORTED_ARCHIVE_TYPE'
            entries[entry.name] = entry
            if entry.issym():
                assert not PurePosixPath(entry.linkname).is_absolute()
                target = posixpath.normpath(posixpath.join(posixpath.dirname(entry.name), entry.linkname))
                assert target != '..' and not target.startswith('../'), 'ARCHIVE_LINK_ESCAPE'
                if entry.name.startswith(PREFIX):
                    assert target.startswith(PREFIX), 'RUNTIME_LINK_OUTSIDE_PREFIX'
                    links[entry.name[len(PREFIX):]] = entry.linkname
        for name in entries:
            assert all(parent.as_posix() not in entries for parent in PurePosixPath(name).parents), 'ARCHIVE_NON_DIRECTORY_PARENT'
            if name.startswith(PREFIX):
                expected_dirs.update(parent.as_posix() for parent in PurePosixPath(name[len(PREFIX):]).parents if parent.as_posix() != '.')
        for relative in sorted(expected_dirs, key=lambda name: (name.count('/'), name)):
            assert stat.S_ISDIR((root / relative).lstat().st_mode), 'RUNTIME_DIRECTORY_TYPE: ' + relative
        for entry in members:
            if not entry.isfile() or not entry.name.startswith(PREFIX):
                continue
            relative = entry.name[len(PREFIX):]
            path = root / relative
            assert path.is_file() and not path.is_symlink(), 'RUNTIME_FILE_TYPE: ' + relative
            h = hashlib.sha256()
            with archive.extractfile(entry) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    h.update(chunk)
            digest = h.hexdigest()
            assert path.stat().st_size == entry.size and file_digest(path) == digest, 'RUNTIME_BYTE_MISMATCH: ' + relative
            files[relative] = {'sha256': digest, 'bytes': entry.size}
    assert files, 'EMPTY_RUNTIME_ARCHIVE'
    for relative, target in links.items():
        path = root / relative
        assert path.is_symlink() and os.readlink(path) == target, 'RUNTIME_LINK_MISMATCH'
        assert path.resolve().is_relative_to(root) and path.exists(), 'RUNTIME_LINK_ESCAPE_OR_DANGLING'
    observed, observed_dirs = set(), set()
    for directory, dirs, names in os.walk(root, followlinks=False):
        for name in dirs + names:
            path = Path(directory) / name
            relative = path.relative_to(root).as_posix()
            mode = path.lstat().st_mode
            if stat.S_ISDIR(mode):
                observed_dirs.add(relative)
            else:
                assert stat.S_ISREG(mode) or stat.S_ISLNK(mode), 'RUNTIME_SPECIAL_FILE: ' + relative
                observed.add(relative)
    assert observed == files.keys() | links.keys(), 'RUNTIME_UNEXPECTED_OR_MISSING_FILES'
    assert observed_dirs == expected_dirs, 'RUNTIME_UNEXPECTED_OR_MISSING_DIRS'
    package = json.loads((root / 'package.json').read_text())
    tree_hash = hashlib.sha256(json.dumps({'files': files, 'links': links, 'directories': sorted(expected_dirs)}, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    report = {'status': 'PASS_ARCHIVE_BOUND_RUNTIME', 'archive': str(args.archive.resolve()), 'archiveSha256': args.sha256,
              'archiveBytes': args.archive.stat().st_size, 'runtimeRoot': str(root), 'version': package.get('version'),
              'regularFiles': len(files), 'internalLinks': len(links), 'directories': len(expected_dirs), 'runtimeTreeSha256': tree_hash,
              'files': files, 'links': links}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ['files', 'links']}))


if __name__ == '__main__':
    if sys.flags.optimize:
        raise SystemExit('DATA_COMPAT_OPTIMIZATION_FORBIDDEN')
    main()

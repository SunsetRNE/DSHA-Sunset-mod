#!/usr/bin/env python3
"""Read-only APK size/byte inventory; signatures and runtime proofs have separate gates."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import sys
import zipfile

from recovery_apk_assets import archive_locations, SHARED
from recovery_descriptor_limits import MAX_BYTES as DESCRIPTOR_MAX_BYTES


def sha256(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(block)
    return digest.hexdigest()


def inspect_apk(path, duplicate_min_bytes=1024 * 1024):
    """Hash actual entry bytes, including small entries needed for exact comparisons."""
    path = Path(path)
    before = path.stat()
    with path.open('rb') as stream:
        apk_hash = sha256(stream)
    entries = {}
    groups = collections.defaultdict(list)
    recovery = None
    with zipfile.ZipFile(path) as apk:
        for info in apk.infolist():
            if info.filename in entries:
                raise ValueError('APK_DUPLICATE_ENTRY_NAME: ' + info.filename)
            with apk.open(info) as stream:
                digest = sha256(stream)
            row = dict(name=info.filename, bytes=info.file_size,
                       storedBytes=info.compress_size, compression=info.compress_type,
                       sha256=digest)
            entries[info.filename] = row
            if not info.is_dir() and info.file_size >= duplicate_min_bytes:
                # CRC and length alone never establish equality.
                groups[(info.file_size, digest)].append(row)
        descriptor_name = 'assets/recovery-runtime.json'
        if descriptor_name in entries:
            if entries[descriptor_name]['bytes'] > DESCRIPTOR_MAX_BYTES:
                raise ValueError('RECOVERY_DESCRIPTOR_BYTE_LIMIT')
            descriptor = json.loads(apk.read(descriptor_name))
            sources = archive_locations(apk, descriptor)
            recovery = dict(runtimeId=descriptor['id'], sources=sources,
                            allShared=sources == SHARED,
                            dedicatedArchiveBytes=sum(
                                entries['assets/' + logical]['storedBytes']
                                for logical, source in sources.items() if source == logical),
                            redundantDedicatedArchives=[
                                logical for logical, source in sources.items()
                                if source != logical and 'assets/' + logical in entries])
    after = path.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError('APK_CHANGED_DURING_INSPECTION')
    duplicates = []
    for (size, digest), rows in groups.items():
        if len(rows) > 1:
            duplicates.append(dict(bytes=size, sha256=digest,
                                   names=sorted(row['name'] for row in rows),
                                   storedBytes=sum(row['storedBytes'] for row in rows)))
    duplicates.sort(key=lambda row: (-row['storedBytes'], row['names']))
    return dict(path=str(path.resolve()), bytes=before.st_size, sha256=apk_hash,
                entryCount=len(entries),
                storedEntryBytes=sum(row['storedBytes'] for row in entries.values()),
                unpackedEntryBytes=sum(row['bytes'] for row in entries.values()),
                duplicateMinBytes=duplicate_min_bytes, duplicateGroups=duplicates,
                largestEntries=sorted(entries.values(), key=lambda row: (-row['storedBytes'], row['name']))[:30],
                recovery=recovery, entries=entries)


def compare(current, baseline):
    current_entries, old_entries = current['entries'], baseline['entries']
    added = sorted(set(current_entries) - set(old_entries))
    removed = sorted(set(old_entries) - set(current_entries))
    changes, same_bytes = [], 0
    for name in sorted(set(current_entries) & set(old_entries)):
        new, old = current_entries[name], old_entries[name]
        if new['sha256'] == old['sha256']:
            same_bytes += 1
        if any(new[key] != old[key] for key in ('sha256', 'bytes', 'storedBytes', 'compression')):
            changes.append(dict(name=name, before=old, after=new,
                                storedByteDelta=new['storedBytes'] - old['storedBytes']))
    return dict(baseline={key: baseline[key] for key in ('path', 'bytes', 'sha256')},
                byteDelta=current['bytes'] - baseline['bytes'], sameByteEntries=same_bytes,
                added=[current_entries[name] for name in added],
                removed=[old_entries[name] for name in removed],
                changed=sorted(changes, key=lambda row: (-row['storedByteDelta'], row['name'])))


def evaluate(current, baseline=None, require_shared_recovery=False, max_bytes=None):
    failures = []
    if max_bytes is not None and current['bytes'] > max_bytes:
        failures.append('APK_SIZE_LIMIT')
    recovery = current['recovery']
    if require_shared_recovery:
        if recovery is None or not recovery['allShared']:
            failures.append('RECOVERY_SHARED_REQUIRED')
        if recovery and recovery['redundantDedicatedArchives']:
            failures.append('RECOVERY_REDUNDANT_DEDICATED_ARCHIVE')
    report = {key: value for key, value in current.items() if key != 'entries'}
    report.update(schema=1, scope='size-and-byte-inventory; signature and runtime gates remain required',
                  requireSharedRecovery=require_shared_recovery, maxBytes=max_bytes,
                  failures=failures, result='FAIL' if failures else 'PASS')
    if baseline is not None:
        report['comparison'] = compare(current, baseline)
    return report


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError('must be a positive byte count')
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--baseline', type=Path, help='report exact member and total-size changes')
    parser.add_argument('--require-shared-recovery', action='store_true',
                        help='require both exact-hash shared mappings and no leftover dedicated archives')
    parser.add_argument('--max-bytes', type=positive_int, help='optional explicit total APK size budget')
    parser.add_argument('--duplicate-min-bytes', type=positive_int, default=1024 * 1024)
    args = parser.parse_args(argv)
    try:
        current = inspect_apk(args.apk, args.duplicate_min_bytes)
        baseline = inspect_apk(args.baseline, args.duplicate_min_bytes) if args.baseline else None
        report = evaluate(current, baseline, args.require_shared_recovery, args.max_bytes)
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, zipfile.BadZipFile) as error:
        report = dict(schema=1, result='FAIL', failures=[str(error)], apk=str(args.apk))
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['result'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())

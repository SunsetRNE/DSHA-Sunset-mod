#!/usr/bin/env python3
"""Real ZIP controls for byte equality, size growth, and opt-in recovery sharing."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile
import zlib

spec = importlib.util.spec_from_file_location('verify_apk_size', Path(__file__).with_name('verify-apk-size.py'))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def write_apk(path, blobs, shared=None, extra_dedicated=False):
    blobs = dict(blobs)
    if shared is not None:
        content = {'recovery-rootfs.bin': b'pinned rootfs', 'recovery-dsh-runtime.bin': b'pinned dsh'}
        descriptor = dict(id='a' * 64, archives=[dict(asset=name, sha256=hashlib.sha256(data).hexdigest())
                                              for name, data in content.items()])
        mapping = dict(schema=1, runtimeId=descriptor['id'], archives=[])
        for name, data in content.items():
            source = verifier.SHARED[name] if shared else name
            blobs['assets/' + source] = data
            if extra_dedicated:
                blobs['assets/' + name] = data
            mapping['archives'].append(dict(asset=name, source=source, sha256=hashlib.sha256(data).hexdigest()))
        blobs['assets/recovery-runtime.json'] = json.dumps(descriptor).encode()
        blobs['assets/recovery-asset-locations.json'] = json.dumps(mapping).encode()
    with zipfile.ZipFile(path, 'w') as apk:
        for name, data in blobs.items():
            apk.writestr(name, data)


class ApkSizeTests(unittest.TestCase):
    def test_sha_detects_duplicates_and_ignores_same_size_different_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            write_apk(path, {'a.bin': b'AAAA', 'b.bin': b'AAAA', 'c.bin': b'BBBB'})
            groups = verifier.inspect_apk(path, 1)['duplicateGroups']
            self.assertEqual([row['names'] for row in groups], [['a.bin', 'b.bin']])
            self.assertEqual(groups[0]['sha256'], hashlib.sha256(b'AAAA').hexdigest())

    def test_equal_length_crc_collision_is_not_a_duplicate(self):
        # Fixed real CRC32 collision: a CRC-only inventory would wrongly group these.
        first, second = b'plumless', b'buckeroo'
        self.assertEqual(len(first), len(second))
        self.assertEqual(zlib.crc32(first), zlib.crc32(second))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            write_apk(path, {'a.bin': first, 'b.bin': second})
            self.assertEqual(verifier.inspect_apk(path, 1)['duplicateGroups'], [])

    def test_comparison_hashes_same_length_changes_and_accounts_added_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            old, new = Path(directory) / 'old.apk', Path(directory) / 'new.apk'
            write_apk(old, {'same': b'fixed', 'changed': b'before', 'gone': b'old'})
            write_apk(new, {'same': b'fixed', 'changed': b'after!', 'added': b'new content'})
            result = verifier.compare(verifier.inspect_apk(new), verifier.inspect_apk(old))
            self.assertEqual(result['sameByteEntries'], 1)
            self.assertEqual([row['name'] for row in result['changed']], ['changed'])
            self.assertEqual([row['name'] for row in result['added']], ['added'])
            self.assertEqual([row['name'] for row in result['removed']], ['gone'])
            self.assertEqual(result['byteDelta'], new.stat().st_size - old.stat().st_size)

    def test_independent_recovery_is_valid_unless_sharing_explicitly_required(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            write_apk(path, {}, shared=False)
            current = verifier.inspect_apk(path)
            self.assertEqual(verifier.evaluate(current)['result'], 'PASS')
            self.assertEqual(verifier.evaluate(current, require_shared_recovery=True)['failures'],
                             ['RECOVERY_SHARED_REQUIRED'])

    def test_shared_mapping_accepts_pinned_bytes_but_rejects_leftover_duplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            write_apk(path, {}, shared=True)
            self.assertEqual(verifier.evaluate(verifier.inspect_apk(path), require_shared_recovery=True)['result'], 'PASS')
            write_apk(path, {}, shared=True, extra_dedicated=True)
            result = verifier.evaluate(verifier.inspect_apk(path), require_shared_recovery=True)
            self.assertEqual(result['failures'], ['RECOVERY_REDUNDANT_DEDICATED_ARCHIVE'])

    def test_shared_mapping_cannot_bypass_archive_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            write_apk(path, {}, shared=True)
            with zipfile.ZipFile(path) as apk:
                blobs = {row.filename: apk.read(row) for row in apk.infolist()}
            blobs['assets/dsh-runtime.bin'] = b'tampered'
            write_apk(path, blobs)
            with self.assertRaisesRegex(ValueError, 'RECOVERY_ARCHIVE_HASH'):
                verifier.inspect_apk(path)

    def test_duplicate_zip_names_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            with zipfile.ZipFile(path, 'w') as apk, warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                apk.writestr('a', b'one')
                apk.writestr('a', b'two')
            with self.assertRaisesRegex(ValueError, 'APK_DUPLICATE_ENTRY_NAME'):
                verifier.inspect_apk(path)

    def test_cli_size_and_missing_recovery_fail_without_changing_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'app.apk'
            write_apk(path, {'data': b'example'})
            before = path.read_bytes()
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                status = verifier.main([str(path), '--max-bytes', '1', '--require-shared-recovery'])
            self.assertEqual(status, 1)
            self.assertEqual(json.loads(output.getvalue())['failures'],
                             ['APK_SIZE_LIMIT', 'RECOVERY_SHARED_REQUIRED'])
            self.assertEqual(path.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()

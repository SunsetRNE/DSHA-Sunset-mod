#!/usr/bin/env python3
"""Independent negative controls for descriptor-anchored startup CAS scans."""
import contextlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch


_spec = importlib.util.spec_from_file_location(
    'cas_scan_review_fixture', Path(__file__).with_name('test-plugin-cas-materialize.py'))
_fixture = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_fixture)


class CasScanOutcomeTest(unittest.TestCase):
    """Actual scanner with ordinary files; also runs on Windows path adapters."""
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='dsha-cas-outcome-'))
        self.addCleanup(shutil.rmtree, self.root)
        self.home = self.root / 'root/.dsh'
        self.modules = self.home / 'profiles/web/node_modules'
        self.package = self.modules / 'private-fixture-package'
        self.package.mkdir(parents=True)
        (self.modules.parent / 'package.json').write_text('{"private":true,"dependencies":{},"dsh":{"profile":{"bundles":[]}}}')
        (self.package / 'ordinary.txt').write_bytes(b'PRIVATE_CAS_CONTENT')
        self.environment = patch.dict(os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh'})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        spec = importlib.util.spec_from_file_location('cas_outcome_manager', _fixture.ASSETS / 'plugin-manager.py')
        self.manager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.manager)
        self.transaction = self.manager.transactions()
        self.rows = []
        self.transaction.g['cas_timing'] = lambda phase, elapsed, **fields: self.rows.append(dict(phase=phase, **fields))

    def assert_outcome(self, status, code):
        self.assertEqual(len(self.rows), 1)
        row = self.rows[0]
        self.assertEqual(row['phase'], 'scan')
        self.assertEqual(row['scanStatus'], status)
        self.assertIs(row['countsComplete'], status == 'completed')
        self.assertEqual(row['errorCode'], code)
        self.assertNotIn('PRIVATE_CAS', json.dumps(row))
        self.assertNotIn(str(self.root), json.dumps(row))
        self.assertFalse((self.home / 'plugin-install-operations').exists())
        return row

    def test_completed_scan_with_no_candidates_is_explicit(self):
        self.assertEqual(self.transaction.cas_links(), [])
        row = self.assert_outcome('completed', 'NONE')
        self.assertEqual((row['entries'], row['directories'], row['candidates']), (2, 2, 0))
        self.assertEqual((self.package / 'ordinary.txt').read_bytes(), b'PRIVATE_CAS_CONTENT')

    def test_missing_root_and_missing_parent_are_completed_empty_scans(self):
        shutil.rmtree(self.modules)
        self.assertEqual(self.transaction.cas_links(), [])
        self.assert_outcome('completed', 'NONE')
        self.rows.clear()
        shutil.rmtree(self.modules.parent)
        self.assertEqual(self.transaction.cas_links(), [])
        self.assert_outcome('completed', 'NONE')

    def test_root_validation_failure_is_not_a_completed_scan(self):
        shutil.rmtree(self.modules)
        self.modules.write_bytes(b'PRIVATE_CAS_CONTENT')
        with self.assertRaises(ValueError):
            self.transaction.cas_links()
        self.assert_outcome('failed', 'VALIDATION_FAILED')

    def test_cancellation_after_observed_entries_is_partial_and_rethrown(self):
        error = self.manager.PluginCancelled('PRIVATE_CAS_CANCEL')
        (self.package / 'a-trigger.txt').write_bytes(b'ordinary fixture')
        observed = []
        def check():
            if observed:
                raise error
        reader = self.transaction.cas_links.__globals__['DeleteObjects']
        original = reader.stat
        def observe(instance, parent, name):
            info = original(instance, parent, name)
            if name == 'a-trigger.txt':
                observed.append(True)
            return info
        # The path adapter performs explicit per-entry stat on both platforms.
        original_init = reader.__init__
        def path_init(instance, *args):
            original_init(instance, *args)
            instance.relative = False
        with patch.object(reader, '__init__', path_init), patch.object(reader, 'stat', observe), \
                patch.dict(self.transaction.g, check_cancel=check):
            with self.assertRaises(self.manager.PluginCancelled) as caught:
                self.transaction.cas_links()
        self.assertIs(caught.exception, error)
        row = self.assert_outcome('cancelled', 'CANCELLED')
        self.assertGreater(row['entries'], 0)

    def test_failure_codes_are_fixed_and_original_exception_is_preserved(self):
        reader = self.transaction.cas_links.__globals__['DeleteObjects']
        for error, code in [(PermissionError('PRIVATE_CAS_PERMISSION'), 'PERMISSION_DENIED'),
                            (OSError('PRIVATE_CAS_IO'), 'IO_ERROR'),
                            (ValueError('PRIVATE_CAS_VALIDATION'), 'VALIDATION_FAILED'),
                            (RuntimeError('PRIVATE_CAS_UNEXPECTED'), 'UNEXPECTED_ERROR')]:
            with self.subTest(code=code):
                self.rows.clear()
                with patch.object(reader, 'stat', side_effect=error):
                    with self.assertRaises(type(error)) as caught:
                        self.transaction.cas_links()
                self.assertIs(caught.exception, error)
                self.assert_outcome('failed', code)

    def test_startup_cli_emits_complete_scan_and_unchanged_final_receipt(self):
        result = subprocess.run([sys.executable, str(_fixture.ASSETS / 'materialize-cas-links.py')],
                                env={**os.environ, 'PYTHONIOENCODING': 'utf-8'},
                                capture_output=True, text=True, encoding='utf-8', timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        timings = [json.loads(line.removeprefix('CAS_TIMING: ')) for line in result.stdout.splitlines()
                   if line.startswith('CAS_TIMING: ')]
        scan = [row for row in timings if row['phase'] == 'scan']
        self.assertEqual(len(scan), 1)
        self.assertEqual((scan[0]['scanStatus'], scan[0]['countsComplete'], scan[0]['errorCode']),
                         ('completed', True, 'NONE'))
        receipts = [json.loads(line.removeprefix('PLUGIN_RESULT: ')) for line in result.stdout.splitlines()
                    if line.startswith('PLUGIN_RESULT: ')]
        self.assertEqual(len(receipts), 1)
        self.assertEqual((receipts[0]['status'], receipts[0]['migratedFiles']), ('ok', 0))
        self.assertTrue(result.stdout.splitlines()[-1].startswith('PLUGIN_RESULT: '))

    def test_startup_cli_failed_scan_does_not_become_success(self):
        shutil.rmtree(self.modules)
        self.modules.write_bytes(b'PRIVATE_CAS_CONTENT')
        result = subprocess.run([sys.executable, str(_fixture.ASSETS / 'materialize-cas-links.py')],
                                env={**os.environ, 'PYTHONIOENCODING': 'utf-8'},
                                capture_output=True, text=True, encoding='utf-8', timeout=30)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        scans = [json.loads(line.removeprefix('CAS_TIMING: ')) for line in result.stdout.splitlines()
                 if line.startswith('CAS_TIMING: ') and json.loads(line.removeprefix('CAS_TIMING: '))['phase'] == 'scan']
        self.assertEqual(len(scans), 1)
        self.assertEqual((scans[0]['scanStatus'], scans[0]['countsComplete'], scans[0]['errorCode']),
                         ('failed', False, 'VALIDATION_FAILED'))
        self.assertNotIn('PRIVATE_CAS', json.dumps(scans[0]))
        receipts = [json.loads(line.removeprefix('PLUGIN_RESULT: ')) for line in result.stdout.splitlines()
                    if line.startswith('PLUGIN_RESULT: ')]
        self.assertEqual(len(receipts), 1)
        self.assertEqual(receipts[0]['status'], 'error')
        self.assertIs(receipts[0]['automaticRetry'], False)
        self.assertTrue(result.stdout.splitlines()[-1].startswith('PLUGIN_RESULT: '))


@unittest.skipIf(os.name == 'nt', 'Actual Linux directory descriptors and NOFOLLOW')
class CasScanReviewTest(unittest.TestCase):
    setUp = _fixture.CasMaterializeTest.setUp
    tearDown = _fixture.CasMaterializeTest.tearDown
    make_link = _fixture.CasMaterializeTest.make_link

    def inject_during_deep_read(self, action):
        deep = self.package / 'a/b/c'
        deep.mkdir(parents=True)
        (deep / 'payload').write_bytes(b'ordinary deep file')
        original = os.scandir
        listdir = os.listdir
        fired = []

        def inspect(scan, directory):
            for entry in scan:
                if entry.name == 'payload' and not fired:
                    # The operation addresses the held deep directory FD.
                    self.assertEqual(Path(os.readlink('/proc/self/fd/' + str(directory))), deep)
                    fired.append(True)
                    action()
                yield entry

        def list_private(directory):
            actual = (Path(os.readlink('/proc/self/fd/' + str(directory)))
                      if isinstance(directory, int) else Path(directory))
            outside = self.root / 'outside'
            self.assertFalse(actual == outside or outside in actual.parents,
                             'The scanner followed a substituted external directory')
            return listdir(directory)

        @contextlib.contextmanager
        def scan_private(directory):
            actual = Path(os.readlink('/proc/self/fd/' + str(directory)))
            outside = self.root / 'outside'
            self.assertFalse(actual == outside or outside in actual.parents,
                             'The scanner followed a substituted external directory')
            with original(directory) as scan:
                yield inspect(scan, directory)

        @contextlib.contextmanager
        def injected():
            with patch.object(os, 'scandir', scan_private), patch.object(os, 'listdir', list_private):
                yield

        return injected(), fired

    def test_replaced_parent_link_cannot_redirect_held_descendant_scan(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'untouched').write_bytes(b'external original')
        retained = self.root / 'retained-package'

        def replace():
            self.package.rename(retained)
            self.package.symlink_to(outside, target_is_directory=True)

        injected, fired = self.inject_during_deep_read(replace)
        with injected, self.assertRaises((ValueError, OSError)):
            self.transaction.cas_links()
        self.assertTrue(fired)
        self.assertEqual((outside / 'untouched').read_bytes(), b'external original')
        self.assertEqual(list(outside.iterdir()), [outside / 'untouched'])
        self.assertTrue((retained / 'index.js').is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_parent_rename_away_and_back_rejected_by_directory_stability(self):
        retained = self.root / 'retained-package'

        def replace_and_restore():
            self.package.rename(retained)
            self.package.mkdir()
            self.package.rmdir()
            retained.rename(self.package)

        injected, fired = self.inject_during_deep_read(replace_and_restore)
        with injected, self.assertRaises((ValueError, OSError)):
            self.transaction.cas_links()
        self.assertTrue(fired)
        self.assertTrue(self.link.is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_replaced_ancestor_above_module_root_rejected_on_unwind(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'untouched').write_bytes(b'external original')
        retained = self.root / 'retained-profile'

        def replace():
            self.profile.rename(retained)
            self.profile.symlink_to(outside, target_is_directory=True)

        injected, fired = self.inject_during_deep_read(replace)
        with injected, self.assertRaises((ValueError, OSError)):
            self.transaction.cas_links()
        self.assertTrue(fired)
        self.assertEqual((outside / 'untouched').read_bytes(), b'external original')
        self.assertEqual(list(outside.iterdir()), [outside / 'untouched'])
        self.assertTrue((retained / 'node_modules/test-plugin/index.js').is_symlink())

    def test_scan_candidates_do_not_authorize_replaced_publish_ancestor(self):
        original = self.transaction.boundary
        retained = self.root / 'retained-package'
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'index.js').write_bytes(b'external later original')

        def boundary(name):
            if name == 'cas-prepared':
                self.package.rename(retained)
                self.package.symlink_to(outside, target_is_directory=True)
            return original(name)

        self.transaction.boundary = boundary
        with self.assertRaises((ValueError, OSError)):
            self.transaction.materialize_cas_links()
        self.assertTrue(self.transaction.pending())
        self.assertEqual((outside / 'index.js').read_bytes(), b'external later original')
        self.assertTrue((retained / 'index.js').is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_stale_regular_dirent_replaced_by_cas_link_is_rejected(self):
        payload = self.package / 'payload'
        payload.write_bytes(b'ordinary original')
        before = self.package.stat()
        original = os.scandir
        fired = []

        class CachedEntry:
            def __init__(entry_self, entry):
                entry_self.entry = entry
                entry_self.name = entry.name

            def is_symlink(entry_self):
                return entry_self.entry.is_symlink()

            def is_dir(entry_self, **kwargs):
                return entry_self.entry.is_dir(**kwargs)

            def is_file(entry_self, **kwargs):
                cached = entry_self.entry.is_file(**kwargs)
                if entry_self.name == 'payload' and not fired:
                    self.assertTrue(cached)
                    payload.unlink()
                    payload.symlink_to(self.target)
                    # Restoring directory mtime must not hide the replacement;
                    # its ctime and identity are independently checked.
                    os.utime(self.package, ns=(before.st_atime_ns, before.st_mtime_ns))
                    fired.append(True)
                return cached

        @contextlib.contextmanager
        def cached_scan(directory):
            with original(directory) as scan:
                yield (CachedEntry(entry) for entry in scan)

        with patch.object(os, 'scandir', cached_scan), self.assertRaisesRegex(ValueError, '变化'):
            self.transaction.cas_links()
        self.assertTrue(fired)
        self.assertTrue(payload.is_symlink())
        self.assertTrue(self.link.is_symlink())
        self.assertEqual(self.target.read_bytes(), b'export const answer=42;\n')

    def test_late_broken_cas_link_is_not_hidden_by_prior_success(self):
        self.transaction.materialize_cas_links()
        self.assertFalse(self.link.is_symlink())
        deep = self.package / 'nested/deeper'
        deep.mkdir(parents=True)
        stamp = self.modules.stat().st_mtime_ns
        broken, target = self.make_link(deep / 'broken.js', b'new CAS bytes')
        target.unlink()
        self.assertEqual(self.modules.stat().st_mtime_ns, stamp)
        result, value = _fixture.CasMaterializeTest.cli(self)
        self.assertEqual((result.returncode, value['status']), (1, 'error'))
        self.assertTrue(broken.is_symlink())
        self.assertFalse(self.link.is_symlink())

    def test_cancel_during_regular_file_scan_returns_no_partial_receipt(self):
        for index in range(100):
            (self.package / ('plain-' + str(index))).write_bytes(b'ordinary bytes')
        original = self.transaction.g['check_cancel']
        cancelled = []
        seen = []

        def check():
            seen.append(True)
            if len(seen) == 60:
                cancelled.append(True)
                raise self.manager.PluginCancelled('cancel during live scan')
            original()

        with patch.dict(self.transaction.g, check_cancel=check):
            with self.assertRaisesRegex(self.manager.PluginCancelled, 'cancel during live scan'):
                self.transaction.cas_links()
        self.assertTrue(cancelled)
        self.assertTrue(self.link.is_symlink())
        self.assertFalse((self.home / 'plugin-install-operations').exists())

    def test_path_based_fallback_preserves_per_entry_checks(self):
        reader = self.transaction.cas_links.__globals__['DeleteObjects']
        original_init, original_stat = reader.__init__, reader.stat
        calls = []

        def path_init(instance, *args):
            original_init(instance, *args)
            instance.relative = False

        def counted(instance, parent, name):
            calls.append(name)
            return original_stat(instance, parent, name)

        with patch.object(reader, '__init__', path_init), patch.object(reader, 'stat', counted), \
                patch.object(os, 'scandir', side_effect=AssertionError('path fallback cannot use dirents')):
            found = self.transaction.cas_links()
        self.assertEqual([entry['path'] for entry in found], ['test-plugin/index.js'])
        self.assertIn('package.json', calls)


if __name__ == '__main__':
    unittest.main(verbosity=2)

#!/usr/bin/env python3
"""用临时签名插件与安装表验证加性的逐文件证明，不还原真实归档。"""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    'prepare_backup_signed_files', Path(__file__).with_name('prepare-backup-assets.py'))
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class SignedBuiltinFilesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='signed-builtin-proof-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.assets = self.root / 'app/src/main/assets'
        self.assets.mkdir(parents=True)
        self.registry = {'schema': 1, 'plugins': [], 'officialBundles': [
            {'name': '@deepseek-ai/dsh-base', 'internal': True}]}
        self.installs = {'schema': 1, 'installs': []}
        self.plugin('dsh-sample')

    def plugin(self, name):
        tree = 'app-integration' if name == 'dsh-app-integration' else 'builtin-plugins/' + name
        guest = '/root/dsha-' + name[len('dsh-'):]
        self.registry['plugins'].append({
            'name': name, 'guestDirectory': guest, 'entrypoint': 'lib/index.js', 'internal': False})
        self.install('package.json', json.dumps({'name': name, 'version': '1.0.0'}).encode(), name=name)
        self.install('lib/index.js', b'export {};\n', name=name)
        return tree

    def tree(self, name='dsh-sample'):
        return 'app-integration' if name == 'dsh-app-integration' else 'builtin-plugins/' + name

    def install(self, path, content=b'fixture', *, name='dsh-sample', executable=False, **extra):
        source = self.tree(name) + '/' + path
        file = self.assets / source
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(content)
        self.installs['installs'].append(dict(
            asset=source, target='root/dsha-' + name[len('dsh-'):] + '/' + path,
            executable=executable, **extra))
        return file

    def save(self):
        (self.assets / 'builtin-plugins.json').write_text(json.dumps(self.registry), encoding='utf-8')
        (self.assets / 'managed-runtime-inputs.json').write_text(json.dumps(self.installs), encoding='utf-8')

    def build(self):
        self.save()
        return GENERATOR.build_signed_builtin_files(self.root)

    def test_hash_size_installed_modes_and_stable_order(self):
        data = '中文🙂\x00payload'.encode()
        self.install('assets/payload.png', data)
        executable = self.install('scripts/run.sh', b'#!/bin/sh\nexit 0\n', executable=True)
        if os.name != 'nt':
            executable.chmod(0o644)
            (self.assets / self.tree() / 'lib/index.js').chmod(0o755)
        first = self.build()
        self.installs['installs'].reverse()
        self.assertEqual(first, self.build())
        entries = {row['path']: row for row in first['dsh-sample']}
        self.assertEqual(['assets/payload.png', 'lib/index.js', 'package.json', 'scripts/run.sh'],
                         list(entries))
        self.assertEqual({'path': 'assets/payload.png', 'sha256': hashlib.sha256(data).hexdigest(),
                          'mode': 0o600, 'size': len(data)}, entries['assets/payload.png'])
        self.assertEqual(0o600, entries['lib/index.js']['mode'])
        self.assertEqual(0o755, entries['scripts/run.sh']['mode'])
        for row in entries.values():
            self.assertEqual({'path', 'sha256', 'mode', 'size'}, set(row))
            self.assertRegex(row['sha256'], r'^[a-f0-9]{64}$')
            self.assertIs(type(row['size']), int)
            self.assertIs(type(row['mode']), int)

    def test_only_registry_plugins_exact_target_prefix_and_app_integration(self):
        self.plugin('dsh-app-integration')
        for source, target in [('unregistered/package.json', 'root/dsha-other/package.json'),
                               ('lookalike/package.json', 'root/dsha-sample-extra/package.json'),
                               ('official/package.json', 'root/official/package.json')]:
            file = self.assets / source
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(b'{}')
            self.installs['installs'].append(dict(asset=source, target=target, executable=False))
        result = self.build()
        self.assertEqual(['dsh-app-integration', 'dsh-sample'], list(result))
        self.assertEqual(['lib/index.js', 'package.json'],
                         [row['path'] for row in result['dsh-app-integration']])

    def test_root_dependencies_excluded_but_nested_node_modules_are_proved(self):
        self.install('node_modules/shared/runtime.js')
        self.install('lib/node_modules/embedded/runtime.js')
        # 依赖可以由别处资产安装，根 node_modules 不被误当成本插件源码。
        row = self.installs['installs'][-2]
        row['asset'] = 'shared/runtime.js'
        shared = self.assets / row['asset']
        shared.parent.mkdir(parents=True)
        shared.write_bytes(b'shared bytes')
        entries = self.build()['dsh-sample']
        self.assertNotIn('node_modules/shared/runtime.js', [row['path'] for row in entries])
        self.assertIn('lib/node_modules/embedded/runtime.js', [row['path'] for row in entries])

    def test_nested_node_modules_extra_runtime_file_is_not_ignored(self):
        extra = self.assets / self.tree() / 'lib/node_modules/uninstalled/runtime.js'
        extra.parent.mkdir(parents=True)
        extra.write_bytes(b'not installed')
        with self.assertRaisesRegex(ValueError, 'BUILTIN_RUNTIME_FILE_UNINSTALLED'):
            self.build()

    def test_source_docs_maps_and_types_are_not_runtime_install_obligations(self):
        for name in ('src/client.js', 'src/package.json', 'lib/types/index.d.ts',
                     'lib/index.js.map', 'README.md'):
            extra = self.assets / self.tree() / name
            extra.parent.mkdir(parents=True, exist_ok=True)
            extra.write_bytes(b'source only')
        self.assertEqual(2, len(self.build()['dsh-sample']))

    def test_missing_runtime_installation_fails(self):
        self.installs['installs'].pop()
        with self.assertRaisesRegex(ValueError, 'BUILTIN_RUNTIME_FILE_UNINSTALLED'):
            self.build()

    def test_additional_runtime_source_fails(self):
        extra = self.assets / self.tree() / 'lib/new-feature.js'
        extra.write_bytes(b'new runtime')
        with self.assertRaisesRegex(ValueError, 'BUILTIN_RUNTIME_FILE_UNINSTALLED'):
            self.build()

    def test_redirected_installation_and_cross_plugin_source_fail(self):
        self.installs['installs'][1]['target'] = 'root/dsha-sample/lib/different.js'
        with self.assertRaisesRegex(ValueError, 'BUILTIN_RUNTIME_TARGET_MISMATCH'):
            self.build()
        self.installs['installs'][1]['target'] = 'root/dsha-sample/lib/index.js'
        self.installs['installs'][1]['asset'] = 'builtin-plugins/dsh-other/lib/index.js'
        with self.assertRaisesRegex(ValueError, 'BUILTIN_RUNTIME_TARGET_MISMATCH'):
            self.build()

    def test_duplicate_sources_and_targets_fail(self):
        original = self.installs['installs'][-1]
        for row in (dict(original), dict(original, target='root/dsha-sample/copy.js'),
                    dict(original, asset='other/bytes.js')):
            with self.subTest(row=row):
                self.installs['installs'].append(row)
                with self.assertRaisesRegex(ValueError, 'DUPLICATE_OR_INVALID_RUNTIME_INSTALL'):
                    self.build()
                self.installs['installs'].pop()

    def test_executable_requires_actual_boolean(self):
        for invalid in (1, 0, 'false', None):
            with self.subTest(invalid=invalid):
                self.installs['installs'][-1]['executable'] = invalid
                with self.assertRaisesRegex(ValueError, 'DUPLICATE_OR_INVALID_RUNTIME_INSTALL'):
                    self.build()

    def test_unsafe_source_target_and_guest_paths_fail(self):
        for field, invalid in (('asset', '../escape.js'), ('target', 'root/dsha-sample/../escape.js'),
                               ('target', 'root/dsha-sample/lib\\escape.js'),
                               ('asset', '/absolute.js'), ('target', 'root/dsha-sample/lib//index.js'),
                               ('target', 'root/dsha-sample/lib/./index.js'),
                               ('asset', 'builtin-plugins/dsh-sample/lib/line\nbreak.js')):
            with self.subTest(field=field, invalid=invalid):
                row = self.installs['installs'][-1]
                previous, row[field] = row[field], invalid
                with self.assertRaises(ValueError):
                    self.build()
                row[field] = previous
        for invalid in ('root/dsha-sample', '/root/../escape', '//root/dsha-sample'):
            self.registry['plugins'][0]['guestDirectory'] = invalid
            with self.assertRaises(ValueError):
                self.build()

    def test_package_relative_length_and_depth_limits(self):
        path = '/'.join(['a' * 250] * 4 + ['b' * 20])
        self.assertEqual(1024, len(path))
        self.install(path)
        self.assertIn(path, [row['path'] for row in self.build()['dsh-sample']])
        invalid = path + 'c'
        self.installs['installs'].append(dict(
            asset=self.tree() + '/' + invalid, target='root/dsha-sample/' + invalid, executable=False))
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_PATH'):
            self.build()
        self.installs['installs'].pop()
        depth32 = '/'.join(['d'] * 31 + ['file.js'])
        self.install(depth32)
        self.assertIn(depth32, [row['path'] for row in self.build()['dsh-sample']])
        depth33 = '/'.join(['d'] * 32 + ['file.js'])
        self.installs['installs'].append(dict(
            asset=self.tree() + '/' + depth33, target='root/dsha-sample/' + depth33, executable=False))
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_PATH'):
            self.build()

    def test_file_limits_are_per_plugin_and_do_not_truncate(self):
        self.plugin('dsh-second')
        self.assertEqual(2048, GENERATOR.MAX_SIGNED_BUILTIN_FILES)
        with patch.object(GENERATOR, 'MAX_SIGNED_BUILTIN_FILES', 2):
            self.assertEqual([2, 2], [len(rows) for rows in self.build().values()])
            self.install('extra.txt')
            with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_FILE_LIMIT'):
                self.build()

    def test_byte_limit_counts_all_files_and_allows_exact_boundary(self):
        self.assertEqual(64 * 1024 * 1024, GENERATOR.MAX_SIGNED_BUILTIN_BYTES)
        total = sum(row['size'] for row in self.build()['dsh-sample'])
        with patch.object(GENERATOR, 'MAX_SIGNED_BUILTIN_BYTES', total):
            self.assertEqual(2, len(self.build()['dsh-sample']))
            self.install('extra.txt', b'x')
            with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_BYTE_LIMIT'):
                self.build()

    def test_oversized_file_is_rejected_before_reading_contents(self):
        large = self.install('large.bin', b'')
        with large.open('wb') as stream:
            stream.truncate(GENERATOR.MAX_SIGNED_BUILTIN_BYTES + 1)
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_BYTE_LIMIT'):
            self.build()

    def test_derived_file_directory_node_limit(self):
        self.assertEqual(8192, GENERATOR.MAX_SIGNED_BUILTIN_NODES)
        self.install('a/x/extra.json')
        with patch.object(GENERATOR, 'MAX_SIGNED_BUILTIN_NODES', 6):
            self.assertEqual(3, len(self.build()['dsh-sample']))
            self.install('b/y/extra.json')
            with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_NODE_LIMIT'):
                self.build()

    def test_registry_names_roots_and_required_files_fail_closed(self):
        self.registry['plugins'].append(dict(self.registry['plugins'][0]))
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_NAME'):
            self.build()
        self.registry['plugins'].pop()
        self.plugin('dsh-other')
        self.registry['plugins'][-1]['guestDirectory'] = '/root/dsha-sample/child'
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_GUEST_OVERLAP'):
            self.build()
        self.registry['plugins'].pop()
        self.registry['plugins'][0]['entrypoint'] = 'lib/missing.js'
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_REQUIRED_FILE'):
            self.build()

    def test_extracted_member_requires_an_explicit_supported_proof_contract(self):
        self.installs['installs'][-1]['extract'] = {'member': 'lib/index.js', 'sha256': 'a' * 64}
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_EXTRACT'):
            self.build()

    @unittest.skipIf(os.name == 'nt', 'Requires POSIX symlinks')
    def test_source_file_and_parent_links_cannot_supply_proved_bytes(self):
        target = self.root / 'outside.js'
        target.write_bytes(b'outside')
        file = self.assets / self.tree() / 'lib/index.js'
        file.unlink()
        file.symlink_to(target)
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_ASSET_LINK'):
            self.build()
        file.unlink()
        file.write_bytes(b'export {};\n')
        (self.assets / self.tree() / 'linked').symlink_to(self.root, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'SIGNED_BUILTIN_ASSET_LINK'):
            self.build()

    @unittest.skipIf(os.name == 'nt', 'Requires POSIX symlinks')
    def test_root_shared_dependency_link_is_not_traversed(self):
        (self.assets / self.tree() / 'node_modules').symlink_to(self.root, target_is_directory=True)
        self.assertEqual(2, len(self.build()['dsh-sample']))

    def test_new_field_is_additive_and_build_does_not_write_json(self):
        self.save()
        previous = {'version': 1, 'archiveSha256': 'f' * 64,
                    'packages': {'@deepseek-ai/existing': 'e' * 64}}
        target = self.assets / 'managed-package-proofs.json'
        target.write_bytes(b'previous proof must stay untouched')
        with patch.object(GENERATOR, 'archive_proofs', return_value=copy.deepcopy(previous)) as archive:
            output = GENERATOR.build_proofs(self.root)
        archive.assert_called_once_with(self.assets / 'dsh-runtime.bin')
        self.assertEqual(previous, {key: output[key] for key in previous})
        self.assertEqual(set(previous) | {'signedBuiltinFiles'}, set(output))
        self.assertEqual({'dsh-sample'}, set(output['signedBuiltinFiles']))
        self.assertEqual(b'previous proof must stay untouched', target.read_bytes())

    def test_entire_proof_byte_limit_matches_runtime_and_does_not_write_partial_json(self):
        self.assertEqual(2 * 1024 * 1024, GENERATOR.MAX_PROOF_BYTES)
        output = {'version': 1, 'packages': {}, 'signedBuiltinFiles': {'中文': []}}
        content = GENERATOR.proof_content(output)
        boundary = len(content.encode('utf-8'))
        with patch.object(GENERATOR, 'MAX_PROOF_BYTES', boundary):
            self.assertEqual(content, GENERATOR.proof_content(output))
        self.save()
        target = self.assets / 'managed-package-proofs.json'
        target.write_bytes(b'preserve existing proof')
        with patch.object(GENERATOR, 'MAX_PROOF_BYTES', boundary - 1), \
                patch.object(GENERATOR, 'ROOT', self.root), \
                patch.object(GENERATOR, 'build_proofs', return_value=output):
            with self.assertRaisesRegex(ValueError, 'MANAGED_PACKAGE_PROOF_BYTE_LIMIT'):
                GENERATOR.main(['--write'])
        self.assertEqual(b'preserve existing proof', target.read_bytes())

    def test_cli_check_write_and_invalid_argument_contracts_are_preserved(self):
        self.save()
        output = {'version': 1, 'archiveSha256': 'a' * 64, 'packages': {},
                  'signedBuiltinFiles': self.build()}
        target = self.assets / 'managed-package-proofs.json'
        with patch.object(GENERATOR, 'ROOT', self.root), \
                patch.object(GENERATOR, 'build_proofs', return_value=output), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit):
                GENERATOR.main(['--check'])
            self.assertEqual(0, GENERATOR.main(['--write']))
            before = target.read_bytes()
            with patch.object(GENERATOR, 'write_source_text') as writer:
                self.assertEqual(0, GENERATOR.main(['--check']))
                writer.assert_not_called()
            self.assertEqual(before, target.read_bytes())
            self.assertEqual(0, GENERATOR.main([]))
            with self.assertRaisesRegex(SystemExit, 'usage:'):
                GENERATOR.main(['--unknown'])

    @unittest.skipIf(os.name == 'nt', 'Requires actual POSIX installed modes')
    def test_generated_real_builtin_files_are_accepted_by_runtime_verifier(self):
        repository = Path(__file__).resolve().parents[1]
        actual_assets = repository / 'app/src/main/assets'
        registry = json.loads((actual_assets / 'builtin-plugins.json').read_text(encoding='utf-8'))
        installs = json.loads((actual_assets / 'managed-runtime-inputs.json').read_text(encoding='utf-8'))
        files = GENERATOR.build_signed_builtin_files(repository)
        self.assertEqual({plugin['name'] for plugin in registry['plugins']}, set(files))
        by_target = {row['target']: row for row in installs['installs']}
        guest = self.root / 'isolated-installed-guest'
        for plugin in registry['plugins']:
            target_root = guest / plugin['guestDirectory'].lstrip('/')
            for entry in files[plugin['name']]:
                target = target_root / entry['path']
                row = by_target[plugin['guestDirectory'].lstrip('/') + '/' + entry['path']]
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((actual_assets / row['asset']).read_bytes())
                target.chmod(entry['mode'])
        proof_path = guest / 'root/.dsh/managed-package-proofs.json'
        proof_path.parent.mkdir(parents=True)
        document = {'version': 1, 'archiveSha256': '0' * 64,
                    'packages': {}, 'signedBuiltinFiles': files}
        proof_bytes = (json.dumps(document, ensure_ascii=False, indent=2) + '\n').encode()
        proof_path.write_bytes(proof_bytes)
        environment = {'DSHA_TEST_ROOT': str(guest), 'DSH_HOME': '/root/.dsh',
                       'DSHA_MANAGED_PACKAGE_PROOFS_SHA256': hashlib.sha256(proof_bytes).hexdigest()}
        with patch.dict(os.environ, environment):
            spec = importlib.util.spec_from_file_location(
                'generated_builtin_files_runtime', actual_assets / 'register-builtin-plugins.py')
            runtime = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(runtime)
            for plugin in registry['plugins']:
                with self.subTest(name=plugin['name'], files=len(files[plugin['name']])):
                    self.assertTrue(runtime.signed_builtin_package(
                        plugin['name'], runtime.local(plugin['guestDirectory'])))

    def test_archive_fields_retain_the_existing_digest_algorithm(self):
        archive = self.root / 'fake-archive.bin'
        archive.write_bytes(b'archive hash fixture; no real tar archive needed')
        plain = tarfile.TarInfo(GENERATOR.PREFIX + 'library/z.js')
        plain.mode, plain.size = 0o755, 3
        scoped = tarfile.TarInfo(GENERATOR.PREFIX + '@scope/library/index.js')
        scoped.mode, scoped.size = 0o644, 4
        linked = tarfile.TarInfo(GENERATOR.PREFIX + 'library/a.js')
        linked.type, linked.linkname = tarfile.SYMTYPE, 'z.js'
        directory = tarfile.TarInfo(GENERATOR.PREFIX + 'library/dir')
        directory.type = tarfile.DIRTYPE
        payload = {plain.name: b'one', scoped.name: b'two\n'}

        class FakeTar:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

            def __iter__(self):
                return iter([plain, scoped, linked, directory])

            def extractfile(self, item):
                return io.BytesIO(payload[item.name])

        with patch.object(GENERATOR.tarfile, 'open', return_value=FakeTar()):
            result = GENERATOR.archive_proofs(archive)
        self.assertEqual(1, result['version'])
        self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), result['archiveSha256'])
        # 以显式拼接区分 NUL 与十进制文件位，避免 Python 八进制转义混淆。
        raw = 'a.js\0LINK\0z.js\n' + 'z.js\0FILE\0' + str(0o755) + '\0' + hashlib.sha256(b'one').hexdigest() + '\n'
        scoped_raw = 'index.js\0FILE\0' + str(0o644) + '\0' + hashlib.sha256(b'two\n').hexdigest() + '\n'
        self.assertEqual({'@scope/library': hashlib.sha256(scoped_raw.encode()).hexdigest(),
                          'library': hashlib.sha256(raw.encode()).hexdigest()}, result['packages'])


if __name__ == '__main__':
    unittest.main()

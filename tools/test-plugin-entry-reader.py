#!/usr/bin/env python3
"""固定归档内真实 js-yaml 的只读 entry 投影；不创建 raw/managed 运行时证明。"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
_spec = importlib.util.spec_from_file_location('entry_discovery_helpers', ROOT / 'tools/test-plugin-discovery.py')
_discovery = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_discovery)


class EntryReaderTest(unittest.TestCase):
    setUp = _discovery.DiscoveryTest.setUp
    tearDown = _discovery.DiscoveryTest.tearDown
    put = _discovery.DiscoveryTest.put
    plugin = _discovery.DiscoveryTest.plugin
    items = _discovery.DiscoveryTest.items
    entry_bundle = _discovery.DiscoveryTest.entry_bundle

    @classmethod
    def setUpClass(cls):
        cls.parser_fixture = tempfile.TemporaryDirectory(prefix='dsha-entry-parser-')
        cls.parser_anchor = None
        archive = Path(os.environ.get('DSHA_RUNTIME_ARCHIVE', str(ASSETS / 'dsh-runtime.bin')))
        if not archive.is_file():
            cls.parser_fixture.cleanup()
            raise AssertionError('固定 YAML 解析资产缺失：app/src/main/assets/dsh-runtime.bin')
        expected = json.loads((ASSETS / 'runtime-descriptor.json').read_text(encoding='utf8'))['inputs']['dsh-runtime.bin']
        digest = hashlib.sha256()
        with archive.open('rb') as source:
            for block in iter(lambda: source.read(1024 * 1024), b''):
                digest.update(block)
        if digest.hexdigest() != expected:
            cls.parser_fixture.cleanup()
            raise AssertionError('固定 YAML 解析资产摘要与源码 pin 不一致')
        # 仅提取固定归档中的真实 YAML 与 boot 解析锚点；不创建或冒用 raw/managed 夹具证明。
        prefix = 'usr/local/lib/node_modules/@deepseek-ai/dsh/'
        with tarfile.open(archive, 'r:*') as stream:
            for row in stream:
                name = row.name.removeprefix('./')
                if not row.isfile() or not (name == prefix + 'package.json'
                        or name.startswith(prefix + 'node_modules/js-yaml/')
                        or name.startswith(prefix + 'node_modules/@deepseek-ai/dsh-app-boot/')):
                    continue
                relative = Path(name)
                if relative.is_absolute() or '..' in relative.parts:
                    raise AssertionError('fixed parser member escaped fixture')
                target = Path(cls.parser_fixture.name) / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with stream.extractfile(row) as source:
                    target.write_bytes(source.read())
        cls.parser_anchor = str(Path(cls.parser_fixture.name) / prefix / 'package.json')


    @classmethod
    def tearDownClass(cls):
        cls.parser_fixture.cleanup()


    def real_entry_parser(self):
        return patch.object(self.manager, '_entry_parser_anchor', return_value=self.parser_anchor)


    def test_entry_projection_literal_profile_override_retains_ancestor_reason(self):
        directory = self.entry_bundle('- insert:\n    - id: parent\n      name: cordis:group\n      group: true\n      config:\n        - id: child\n          name: ./index.js\n')
        self.put('root/.dsh/profiles/web/cordis.patch.yml',
                 '- id: parent\n  disabled: true\n- id: child\n  disabled: true\n- id: child\n  disabled: false\n')
        with self.real_entry_parser(), patch.object(self.manager, '_entry_read_node', wraps=self.manager._entry_read_node) as reader:
            item = self.items()['test-plugin']
        self.assertEqual(1, reader.call_count, 'one parser serves the complete list')
        self.assertEqual('', item['entryReadError'])
        self.assertTrue(item['selected']); self.assertTrue(item['installed'])
        self.assertEqual('profile', item['availability']); self.assertEqual('', item['readOnlyReason'])
        entries = {row['entryId']: row for row in item['entries']}
        self.assertIs(entries['parent']['patchDisabled'], True)
        self.assertIs(entries['child']['patchDisabled'], False)
        self.assertEqual('ANCESTOR_DISABLED', entries['child']['patchReason'])
        self.assertEqual('parent', entries['child']['parentId'])
        self.assertEqual(hashlib.sha256((directory / 'index.js').as_uri().encode()).hexdigest(), entries['child']['moduleHash'])
        self.assertNotIn('effectiveEnabled', entries['child'])


    def test_entry_projection_opaque_expression_never_executes_plugin_or_profile(self):
        marker = self.root / 'must-not-exist'
        expression = 'require("node:fs").writeFileSync(' + json.dumps(str(marker)) + ', "executed")'
        directory = self.entry_bundle('- insert:\n    - id: child\n      name: ./index.js\n      disabled: !!js ' + json.dumps(expression) + '\n')
        (directory / 'index.js').write_text(expression, encoding='utf8')
        self.put('root/.dsh/profiles/web/cordis.patch.yml', '- id: child\n  disabled: !!js ' + json.dumps(expression) + '\n')
        with self.real_entry_parser():
            item = self.items()['test-plugin']
        self.assertEqual('', item['entryReadError'])
        self.assertIsNone(item['entries'][0]['patchDisabled'])
        self.assertEqual('PROFILE_OPAQUE', item['entries'][0]['patchReason'])
        self.assertFalse(marker.exists())


    def test_entry_projection_bad_yaml_duplicate_ids_and_structural_overrides_are_unknown(self):
        directory = self.entry_bundle('- insert:\n    - id: child\n      name: ./index.js\n')
        profile = self.root / 'root/.dsh/profiles/web/cordis.patch.yml'
        cases = [
            ('- id: child\n  disabled: !foreign true\n', 'ENTRY_YAML_INVALID'),
            ('- id: child\n  disabled: false\n  disabled: true\n', 'ENTRY_YAML_INVALID'),
            ('- id: child\n  config: []\n', 'ENTRY_STRUCTURE_AMBIGUOUS'),
            ('- id: child\n  disabled: &cycle [*cycle]\n', 'ENTRY_STRUCTURE_AMBIGUOUS'),
        ]
        with self.real_entry_parser():
            for text, reason in cases:
                with self.subTest(text=text):
                    profile.write_text(text, encoding='utf8')
                    item = self.items()['test-plugin']
                    self.assertEqual(reason, item['entryReadError'])
                    self.assertIsNone(item['entries'][0]['patchDisabled'])
            profile.write_bytes(b'\xff\xfeinvalid')
            self.assertEqual('ENTRY_YAML_INVALID', self.items()['test-plugin']['entryReadError'])
            profile.write_text('[]\n')
            (directory / 'cordis.patch.yml').write_text('- insert:\n    - id: child\n      name: ./index.js\n    - id: child\n      name: ./other.js\n')
            self.assertEqual('ENTRY_STRUCTURE_AMBIGUOUS', self.items()['test-plugin']['entryReadError'])


    def test_entry_projection_duplicate_ids_across_bundles_are_not_unique(self):
        for name in ('first-plugin', 'second-plugin'):
            self.entry_bundle('- insert:\n    - id: shared\n      name: ./index.js\n', name)
        with self.real_entry_parser():
            items = self.items()
        for name in ('first-plugin', 'second-plugin'):
            self.assertEqual('ENTRY_STRUCTURE_AMBIGUOUS', items[name]['entryReadError'])
            self.assertIsNone(items[name]['entries'][0]['patchDisabled'])


    def test_entry_projection_profile_and_bundle_patch_changes_advance_revision(self):
        directory = self.entry_bundle('- insert:\n    - id: child\n      name: ./index.js\n')
        before = self.manager.list_state_revision()
        with self.real_entry_parser():
            first = self.items()['test-plugin']['patchRevision']
            profile = self.put('root/.dsh/profiles/web/cordis.patch.yml', '- id: child\n  disabled: true\n')
            self.assertNotEqual(before, self.manager.list_state_revision())
            second = self.items()['test-plugin']['patchRevision']
            self.assertNotEqual(first, second)
            (directory / 'cordis.patch.yml').write_text('- insert:\n    - id: another\n      name: ./index.js\n')
            self.assertNotEqual(second, self.items()['test-plugin']['patchRevision'])
            self.assertTrue(profile.is_file())


    def test_entry_projection_rejects_bundle_profile_and_writer_races(self):
        directory = self.entry_bundle('- insert:\n    - id: child\n      name: ./index.js\n')
        profile = self.put('root/.dsh/profiles/web/cordis.patch.yml', '[]\n')
        lock = Path(str(self.manifest) + '.lock')
        original = self.manager._entry_read_node
        with self.real_entry_parser():
            for target in (directory / 'cordis.patch.yml', profile, lock):
                def changed(request):
                    observed = original(request)
                    target.write_text(str(os.getpid()) + '\n' if target == lock else '[]\n# changed\n')
                    return observed
                with self.subTest(target=target.name), patch.object(self.manager, '_entry_read_node', side_effect=changed), \
                        patch.object(self.manager, 'result') as result:
                    with self.assertRaisesRegex(ValueError, '列表读取期间变化|配置正在修改'):
                        self.manager.cmd_list()
                    result.assert_not_called()
                if target == lock:
                    lock.unlink()
                else:
                    target.write_text('[]\n')


    def test_entry_reader_does_not_inherit_preload_or_node_path_modules(self):
        anchor = self.put('isolated-parser/package.json', {'name': 'fixture-anchor'})
        boot = self.put('isolated-parser/node_modules/@deepseek-ai/dsh-app-boot/package.json',
                        {'name': '@deepseek-ai/dsh-app-boot', 'main': 'lib/index.js'})
        self.put('isolated-parser/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js',
                 "throw Error('must not execute boot');")
        module_marker = self.root / 'injected-module-ran'
        preload_marker = self.root / 'injected-preload-ran'
        self.put('injected-modules/js-yaml/package.json', {'name': 'js-yaml', 'main': 'index.js'})
        self.put('injected-modules/js-yaml/index.js',
                 'require("node:fs").writeFileSync(' + json.dumps(str(module_marker)) + ', "executed");throw Error("injected yaml");')
        preload = self.put('injected-preload.cjs',
                           'require("node:fs").writeFileSync(' + json.dumps(str(preload_marker)) + ', "executed");')
        polluted = {'NODE_PATH': str(self.root / 'injected-modules'), 'NODE_OPTIONS': '--require=' + str(preload)}
        request = {'anchor': str(anchor), 'profile': '[]', 'bundles': []}
        with patch.dict(os.environ, polluted), patch.object(self.manager.subprocess, 'Popen', wraps=self.manager.subprocess.Popen) as launch:
            result = self.manager._entry_read_node(request)
        self.assertEqual('ENTRY_READER_UNAVAILABLE', result['error'])
        self.assertFalse('NODE_OPTIONS' in launch.call_args.kwargs['env'], 'entry reader inherited NODE_OPTIONS')
        self.assertFalse('NODE_PATH' in launch.call_args.kwargs['env'], 'entry reader inherited NODE_PATH')
        self.assertFalse(module_marker.exists())
        self.assertFalse(preload_marker.exists())
        # 负控证明该可写 NODE_PATH 真能进入 Node 解析链；不依赖 mock 模块或静态字串断言。
        probe = self.put('node-path-negative-control.cjs',
                         'const {createRequire}=require("node:module");const load=createRequire(' + json.dumps(str(anchor)) + ');'
                         'try{createRequire(load.resolve("@deepseek-ai/dsh-app-boot"))("js-yaml");}catch(_){}')
        environment = dict(os.environ, NODE_PATH=polluted['NODE_PATH']); environment.pop('NODE_OPTIONS', None)
        process = subprocess.run(['node', str(probe)], env=environment, capture_output=True, text=True)
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual('executed', module_marker.read_text())
        self.assertFalse(preload_marker.exists())
        self.assertTrue(boot.is_file())

    def test_entry_projection_rejects_escaping_paths_and_bounds_total_entries(self):
        directory = self.entry_bundle('[]\n')
        package_file = directory / 'package.json'
        package = json.loads(package_file.read_text()); package['dsh']['bundle']['patch'] = '../../outside.yml'
        package_file.write_text(json.dumps(package))
        observations = self.manager._read_entry_projection([('test-plugin', str(directory), dict(package, description=''))])
        self.assertEqual('ENTRY_PATCH_PATH', observations['test-plugin']['entryReadError'])
        package['dsh']['bundle']['patch'] = 'cordis.patch.yml'; package_file.write_text(json.dumps(package))
        (directory / 'cordis.patch.yml').write_text('- insert:\n' + ''.join('    - id: entry%d\n      name: ./index.js\n' % i for i in range(2049)))
        with self.real_entry_parser():
            item = self.items()['test-plugin']
        self.assertEqual('ENTRY_INPUT_LIMIT', item['entryReadError'])
        self.assertLessEqual(len(item['entries']), 2048)



if __name__ == '__main__':
    unittest.main(verbosity=2)

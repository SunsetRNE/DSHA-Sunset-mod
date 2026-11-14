#!/usr/bin/env python3
"""插件兼容准入与同锁提交：真实固定规则、合成 runtime、隔离磁盘和旧票负控。"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'


class CompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        archive = Path(os.environ.get('DSHA_RUNTIME_ARCHIVE', str(ASSETS / 'dsh-runtime.bin')))
        expected = json.loads((ASSETS / 'runtime-descriptor.json').read_text())['inputs']['dsh-runtime.bin']
        if not archive.is_file(): raise AssertionError('固定 app-boot 归档缺失')
        digest = hashlib.sha256()
        with archive.open('rb') as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
        if digest.hexdigest() != expected: raise AssertionError('固定 app-boot 归档摘要不符')
        name = 'usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js'
        with tarfile.open(archive, 'r:*') as stream:
            member = next(row for row in stream if row.name.removeprefix('./') == name and row.isfile())
            source = stream.extractfile(member).read().decode()
        # 执行锁定代码的纯函数段，不加载整个 app-boot，也不把合成 runtime 写成部署已验证。
        cls.upstream = source[source.index('const NODE_PLATFORMS ='):source.index('//#region lib/types/profile-compatibility.js')]

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dsha-compatibility-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.environment = patch.dict(os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh', 'DSHA_PLUGIN_TASK': '1'*32})
        self.environment.start(); self.addCleanup(self.environment.stop)
        spec = importlib.util.spec_from_file_location('compatibility_manager_fixture', ASSETS / 'plugin-manager.py')
        self.m = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.m); self.life = self.m.lifecycle()
        self.home = self.root / 'root/.dsh'; self.home.mkdir(parents=True)
        self.profile = self.home / 'profiles/web'
        self.put(self.profile / 'package.json', {'dependencies': {}, 'dsh': {'profile': {'bundles': []}}})
        self.source = self.home / 'plugin-sources.json'; self.put(self.source, {})
        runtime = self.root / 'usr/local/lib/node_modules/@deepseek-ai/dsh'
        self.put(runtime / 'package.json', {'name': '@deepseek-ai/dsh', 'version': '0.2.2-rc.1'})
        boot = runtime / 'node_modules/@deepseek-ai/dsh-app-boot'
        self.boot = boot / 'package.json'
        self.put(self.boot, {'name': '@deepseek-ai/dsh-app-boot', 'version': '0.2.2-rc.1', 'main': 'lib/index.js'})
        self.put(boot / 'lib/index.js', "throw Error('must never import app-boot');")

    def put(self, path, value):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value) if isinstance(value, (dict, list)) else value, encoding='utf8')
        return path

    def package(self, name='test-plugin', version='1.0.0', **fields):
        return dict(name=name, version=version, dsh={'bundle': {'patch': 'cordis.patch.yml'}}, **fields)

    def directory(self, name='test-plugin', version='1.0.0', slot='incoming', **fields):
        directory = self.root / slot
        self.put(directory / 'package.json', self.package(name, version, **fields))
        self.put(directory / 'cordis.patch.yml', '[]\n')
        self.put(directory / 'index.js', "throw Error('must never execute plugin');")
        return directory

    def install(self, name='test-plugin', version='1.0.0', source=None, slot='incoming'):
        directory = self.directory(name, version, slot=slot)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(name, self.m.register_plugin(str(directory), source or 'npm:' + name + '@' + version))
        return Path(self.m.resolve_plugin_dir(name))

    def archive(self, directories):
        target = self.root / 'plugins.tgz'
        with tarfile.open(target, 'w:gz') as stream:
            for index, directory in enumerate(directories): stream.add(directory, arcname='plugins/' + str(index))
        return target

    def preview(self, directories, **request):
        archive = self.archive(directories)
        return self.life.inspect(dict(command='file ' + str(archive), **request), emit=False)

    def commit(self, preview):
        with patch.object(self.m, 'result') as result, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.life.install_preview(preview['previewId'], preview['confirmationSha256']))
        return result.call_args.kwargs

    def test_rules_match_pinned_app_boot_without_importing_plugins(self):
        cases = [
            {}, {'engines': {'dsh': '>=0.2.1 <0.3.0'}},
            {'engines': {'dsh': '^0.2.2-rc.1'}, 'peerDependencies': {'@deepseek-ai/dsh-a': '<0.1.0'}},
            {'engines': {'dsh': ''}, 'peerDependencies': {'@deepseek-ai/dsh': '*'}},
            {'engines': {'dsh': 'workspace:*'}}, {'engines': {'dsh': 'nonsense'}},
            {'engines': None}, {'engines': {'dsh': 1}}, {'peerDependencies': []},
            {'peerDependencies': {'@deepseek-ai/dsh': '*', '@deepseek-ai/dsh-extra': '<0.1.0'}},
            {'peerDependencies': {'@deepseek-ai/dsh': 'workspace:^', '@deepseek-ai/dsh-extra': 'workspace:~'}},
            {'peerDependencies': {'@deepseek-ai/dsh-extra': 'workspace:*'}},
            {'peerDependencies': {'@deepseek-ai/dsh-extra': 'workspace:>=0.2.1'}},
            {'peerDependencies': {'@deepseek-ai/dsha': '<0.1.0'}},
            {'peerDependencies': {'unrelated': 3}}, {'engines': {'dsh': '*'}, 'peerDependencies': {'unrelated': 3}},
        ]
        packages = [self.package(**fields) for fields in cases]
        script = """const fs=require('node:fs');
const input=JSON.parse(fs.readFileSync(0,'utf8'));globalThis.__fixtureSemver=require(input.adapter);
(async()=>{const source='const semver=globalThis.__fixtureSemver;\\n'+input.source+'\\nexport {evaluatePluginCompatibility};';
const reference=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const rows=input.packages.map(pkg=>{try{const issue=reference.evaluatePluginCompatibility(pkg,{},'0.2.2-rc.1');return {unmet:issue?.requirements||{}}}catch(_){return {error:true}}});
process.stdout.write(JSON.stringify(rows));})().catch(e=>{console.error(e);process.exitCode=1;});"""
        script_path = self.put(self.root / 'compatibility-reference.cjs', script)
        result = subprocess.run(['node', str(script_path)], input=json.dumps({'source': self.upstream, 'adapter': str(ASSETS / 'plugin-semver.cjs'), 'packages': packages}), text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        expected = json.loads(result.stdout)
        reports = self.life.compatibility_reports(packages)
        for package, old, report in zip(packages, expected, reports):
            with self.subTest(manifest=package):
                if old.get('error'): self.assertEqual('check-failed', report['compatibility'])
                else:
                    self.assertEqual(old['unmet'], report['unmetRequirements'])
                    self.assertEqual(bool(old['unmet']), report['compatibility'] == 'incompatible')
        self.assertEqual('unconstrained', reports[0]['compatibility'])
        self.assertEqual('compatible', reports[1]['compatibility'])

    def test_platform_is_node_host_not_android_or_cpu(self):
        platform = subprocess.check_output(['node', '-p', 'process.platform'], text=True).strip()
        values = [[platform], ['android' if platform != 'android' else 'linux'], [], 'linux', ['arm64'], [None]]
        packages = [dict(self.package(), dsh={'bundle': {'patch': 'cordis.patch.yml', 'platforms': value}}) for value in values]
        states = [report['compatibility'] for report in self.life.compatibility_reports(packages)]
        self.assertEqual(['unconstrained', 'unsupported-platform', 'check-failed', 'check-failed', 'check-failed', 'check-failed'], states)

    def test_runtime_absence_bad_version_and_node_failure_are_not_unconstrained(self):
        for version in ('', 'broken'):
            self.put(self.boot, {'name': '@deepseek-ai/dsh-app-boot', 'version': version, 'main': 'lib/index.js'})
            self.assertEqual('check-failed', self.life.package_metadata(self.package())['compatibility'])
        with patch.object(self.life.compatibility_reports.__globals__['subprocess'], 'run', side_effect=OSError('unavailable')):
            with self.assertRaises(self.m.PluginPolicyError) as caught: self.life.require_compatible(self.package())
        self.assertEqual('PLUGIN_COMPATIBILITY_CHECK_FAILED', caught.exception.code)

    def test_preload_is_not_executed_by_compatibility_reader(self):
        sentinel = self.root / 'preload-ran'
        preload = self.put(self.root / 'preload.cjs', "require('node:fs').writeFileSync(" + json.dumps(str(sentinel)) + ",'executed');")
        with patch.dict(os.environ, {'NODE_OPTIONS': '--require=' + str(preload)}):
            self.assertEqual('unconstrained', self.life.package_metadata(self.package())['compatibility'])
        self.assertFalse(sentinel.exists())

    def test_exemptions_are_exact_read_only_and_bad_records_never_authorize(self):
        incompatible = self.package(engines={'dsh': '<0.1.0'})
        filename = self.profile / 'compatibility.json'
        cases = [({}, False), ({'test-plugin@1.0.0': ['0.2.2-rc.1']}, True),
                 ({'test-plugin@1.0.0': ['0.2.2-rc.1+different']}, False),
                 ({'test-plugin@1.0.0': '0.2.2-rc.1'}, False),
                 ({'test-plugin@1.0.0': ['0.2.2-rc.1', '*']}, False),
                 ({'test-plugin@v1.0.0': ['0.2.2-rc.1']}, False),
                 ({'test-plugin@1.0.0': ['v0.2.2-rc.1']}, False),
                 ({'bad@latest': ['*'], 'test-plugin@1.0.0': ['0.2.2-rc.1']}, True),
                 ([], False), ('{broken', False)]
        for value, allowed in cases:
            with self.subTest(value=value):
                self.put(filename, value); before = filename.read_bytes()
                report = self.life.package_metadata(incompatible)
                self.assertEqual(allowed, report['versionExempted'])
                self.assertEqual('exempted' if allowed else 'incompatible', report['compatibility'])
                self.assertEqual(before, filename.read_bytes())

    def test_multiroot_denial_occurs_before_first_dependency_script(self):
        first = self.directory(name='first-plugin', slot='first', dependencies={'runtime-dep': '1.0.0'})
        second = self.directory(name='second-plugin', slot='second', engines={'dsh': '<0.1.0'})
        with patch.object(self.m, 'prepare_dependencies', side_effect=AssertionError('no preparation before batch admission')):
            with self.assertRaises(self.m.PluginPolicyError) as caught: self.preview([first, second])
        self.assertEqual('PLUGIN_INCOMPATIBLE', caught.exception.code)
        self.assertEqual({}, json.loads((self.profile / 'package.json').read_text())['dependencies'])

    def test_successful_commit_reports_restart_without_reexecuting_dependencies(self):
        preview = self.preview([self.directory()])
        with patch.object(self.m, 'prepare_dependencies', side_effect=AssertionError('preview dependencies already prepared')):
            receipt = self.commit(preview)
        self.assertEqual('restart-required', receipt['application']); self.assertEqual('no-hmr', receipt['restartReason'])
        self.assertEqual('install', receipt['stage']); self.assertEqual(['test-plugin'], receipt['installed'])

    def test_multiroot_commit_rebinds_only_own_source_write_inside_writer(self):
        preview = self.preview([self.directory('first-plugin', slot='first'), self.directory('second-plugin', slot='second')])
        receipt = self.commit(preview)
        self.assertEqual(['first-plugin', 'second-plugin'], receipt['installed'])
        self.assertEqual({'first-plugin', 'second-plugin'}, set(json.loads((self.profile / 'package.json').read_text())['dsh']['profile']['bundles']))

    def test_revoke_exemption_after_prepare_blocks_commit_and_preserves_candidate(self):
        filename = self.put(self.profile / 'compatibility.json', {'test-plugin@1.0.0': ['0.2.2-rc.1']})
        preview = self.preview([self.directory(engines={'dsh': '<0.1.0'})])
        self.put(filename, {})
        with self.assertRaises(self.m.PluginPolicyError) as caught: self.commit(preview)
        self.assertEqual('PLUGIN_INCOMPATIBLE', caught.exception.code)
        self.assertTrue(Path(self.life.preview_path(preview['previewId'])).exists())
        self.assertIsNone(self.m.resolve_plugin_dir('test-plugin'))

    def test_writer_rechecks_exemption_after_wait(self):
        filename = self.put(self.profile / 'compatibility.json', {'test-plugin@1.0.0': ['0.2.2-rc.1']})
        preview = self.preview([self.directory(engines={'dsh': '<0.1.0'})]); original = self.m.builtin.operation_lock
        @contextlib.contextmanager
        def changed(cancel=None):
            with original(cancel):
                self.put(filename, {}); yield
        with patch.object(self.m.builtin, 'operation_lock', changed), self.assertRaises(self.m.PluginPolicyError) as caught:
            self.commit(preview)
        self.assertEqual('PLUGIN_INCOMPATIBLE', caught.exception.code); self.assertEqual('commit', caught.exception.stage)
        self.assertIsNone(self.m.resolve_plugin_dir('test-plugin'))

    def test_source_and_manifest_aba_invalidate_old_preview(self):
        installed = self.install()
        for target in (self.source, installed / 'package.json'):
            with self.subTest(target=target.name):
                preview = self.preview([self.directory(version='2.0.0', slot='update')])
                before = target.read_bytes(); target.write_bytes(before + b' '); target.write_bytes(before)
                with self.assertRaises(self.m.PluginPolicyError) as caught: self.commit(preview)
                self.assertEqual('PLUGIN_SOURCE_CHANGED', caught.exception.code)
                self.assertEqual('1.0.0', json.loads((installed / 'package.json').read_text())['version'])

    def test_writer_rechecks_actual_source_version_and_candidate(self):
        installed = self.install(); preview = self.preview([self.directory(version='2.0.0', slot='update')])
        original = self.m.builtin.operation_lock
        @contextlib.contextmanager
        def changed(cancel=None):
            with original(cancel):
                self.put(self.source, {'test-plugin': 'https://github.com/changed/source'}); yield
        with patch.object(self.m.builtin, 'operation_lock', changed), self.assertRaises(self.m.PluginPolicyError) as caught:
            self.commit(preview)
        self.assertEqual('PLUGIN_SOURCE_CHANGED', caught.exception.code)
        self.assertEqual('1.0.0', json.loads((installed / 'package.json').read_text())['version'])

    def test_changed_runtime_evidence_refuses_prepared_candidate(self):
        preview = self.preview([self.directory()]); value = json.loads(self.boot.read_text()); value['version'] = '0.2.2-rc.2'; self.put(self.boot, value)
        with self.assertRaises(self.m.PluginPolicyError) as caught: self.commit(preview)
        self.assertEqual('PLUGIN_COMPATIBILITY_CHANGED', caught.exception.code)

    def test_user_disable_intent_survives_installing_new_version(self):
        self.install()
        with contextlib.redirect_stdout(io.StringIO()): self.m.set_plugin_state('test-plugin', False)
        marker = Path(self.m.builtin.marker_path('test-plugin')); before = marker.read_bytes()
        preview = self.preview([self.directory(version='2.0.0', slot='update')]); self.commit(preview)
        self.assertEqual(before, marker.read_bytes())
        self.assertNotIn('test-plugin', json.loads((self.profile / 'package.json').read_text())['dsh']['profile']['bundles'])

    def test_enable_denies_incompatible_but_emergency_disable_stays_available(self):
        installed = self.install(); pkg = json.loads((installed / 'package.json').read_text()); pkg['engines'] = {'dsh': '<0.1.0'}; self.put(installed / 'package.json', pkg)
        with self.assertRaises(self.m.PluginPolicyError) as caught: self.m.set_plugin_state('test-plugin', True)
        self.assertEqual('PLUGIN_INCOMPATIBLE', caught.exception.code)
        with contextlib.redirect_stdout(io.StringIO()): receipt = self.m.set_plugin_state('test-plugin', False)
        self.assertFalse(receipt['enabled']); self.assertEqual('restart-required', receipt['application'])

    def test_prepare_update_accepts_only_newer_exact_npm_version(self):
        self.install()
        for version in ('latest', '^2.0.0', 'v2.0.0', '1.0.0', '0.9.0'):
            with self.subTest(version=version), self.assertRaises(self.m.PluginPolicyError) as caught:
                self.life.prepare_update('test-plugin', version)
            self.assertEqual('PLUGIN_UPDATE_VERSION_INVALID', caught.exception.code)
        downloaded = self.directory(version='2.0.0', slot='update'); archive = self.archive([downloaded]); calls = []
        def npm(spec, consume):
            calls.append(spec); return consume(str(archive), source='npm:' + spec)
        with patch.object(self.life, 'remote_json', return_value=self.package(version='2.0.0')), patch.object(self.m, 'cmd_npm', side_effect=npm), patch.object(self.m, 'result') as result:
            self.assertEqual(0, self.life.prepare_update('test-plugin', '2.0.0'))
        self.assertEqual(['test-plugin@2.0.0'], calls)
        preview = result.call_args.kwargs['preview']; self.assertEqual('1.0.0', preview['fromVersion'])
        self.assertEqual('2.0.0', preview['items'][0]['version']); self.commit(preview)

    def assert_rejected_updates_leave_no_pending_preview(self, after_inspect):
        installed = self.install()
        retained = self.preview([self.directory('other-plugin', slot='other')])
        retained_path = Path(self.life.preview_path(retained['previewId']))
        retained_bytes = {str(path.relative_to(retained_path)): path.read_bytes()
                          for path in retained_path.rglob('*') if path.is_file()}
        previews_path = retained_path.parent
        pending_before = self.life.pending_previews()
        target = self.life.target_snapshot('test-plugin')
        installed_bytes = (installed / 'package.json').read_bytes()
        original_inspect = self.life.inspect
        completed = []

        def prepared_then_rechecked(request, emit):
            # 只控制 inspect 内的第一次版本判断，使真实归档/兼容/依赖检查完成。
            # 最后一道 prepare_update 复核仍使用真实 SemVer，不能伪造已准备目录。
            with patch.dict(original_inspect.__func__.__globals__, {'compare_versions': lambda left, right: 1}):
                preview = original_inspect(request, emit)
            completed.append(Path(self.life.preview_path(preview['previewId'])))
            return preview

        for version in ('0.9.0', '1.0.0'):
            archive = self.archive([self.directory(version=version, slot='update')])
            archive_bytes = archive.read_bytes()
            state = {'test-plugin': {'available': True, 'checkedAt': int(time.time()),
                     'installedVersion': '1.0.0', 'targetSnapshot': target,
                     'request': {'command': 'file ' + str(archive), 'name': 'test-plugin'}}}
            self.life.write(self.life.path('plugin-updates.json'), state)
            boundary = patch.object(self.life, 'inspect', side_effect=prepared_then_rechecked) if after_inspect else contextlib.nullcontext()
            with boundary:
                for attempt in range(2):
                    with self.subTest(version=version, attempt=attempt, after_inspect=after_inspect):
                        with self.assertRaises(self.m.PluginPolicyError) as caught:
                            self.life.prepare_update('test-plugin')
                        self.assertEqual('PLUGIN_UPDATE_VERSION_INVALID', caught.exception.code)
                        self.assertEqual(pending_before, self.life.pending_previews())
                        self.assertEqual([retained_path.name], sorted(path.name for path in previews_path.iterdir()))
                        self.assertEqual(archive_bytes, archive.read_bytes())
                        self.assertEqual(installed_bytes, (installed / 'package.json').read_bytes())
                        self.assertEqual(retained_bytes, {str(path.relative_to(retained_path)): path.read_bytes()
                                                         for path in retained_path.rglob('*') if path.is_file()})
        if after_inspect:
            self.assertEqual(4, len(completed))
            self.assertTrue(all(not path.exists() for path in completed))

    def test_lower_or_same_update_rejection_preserves_originals_and_other_previews(self):
        self.assert_rejected_updates_leave_no_pending_preview(False)

    def test_post_inspect_version_rejection_removes_only_new_private_preview(self):
        self.assert_rejected_updates_leave_no_pending_preview(True)

    def test_github_and_release_never_silently_change_to_registry(self):
        self.install()
        for source in ('https://github.com/owner/repository', 'https://github.com/owner/repository/releases/tag/v1.0.0'):
            self.put(self.source, {'test-plugin': source})
            with patch.object(self.life, 'remote_json', side_effect=AssertionError('no registry fallback')), self.assertRaises(self.m.PluginPolicyError) as caught:
                self.life.prepare_update('test-plugin', '2.0.0')
            self.assertEqual('PLUGIN_UPDATE_SOURCE_UNSUPPORTED', caught.exception.code)

    def test_multiroot_external_source_aba_after_first_commit_is_partial(self):
        preview = self.preview([self.directory('first-plugin', slot='first'), self.directory('second-plugin', slot='second')])
        register = self.m.register_plugin
        def between(*args, **kwargs):
            name = register(*args, **kwargs)
            if name == 'first-plugin':
                content = self.source.read_bytes(); self.source.write_bytes(content + b' '); self.source.write_bytes(content)
            return name
        with patch.object(self.m, 'register_plugin', side_effect=between), patch.object(self.m, 'result') as result, contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, self.life.install_preview(preview['previewId'], preview['confirmationSha256']))
        self.assertEqual('partial', result.call_args.args[0]); self.assertEqual(['first-plugin'], result.call_args.kwargs['installed'])
        self.assertEqual('PLUGIN_SOURCE_CHANGED', result.call_args.kwargs['errorCode'])
        self.assertIsNotNone(self.m.resolve_plugin_dir('first-plugin')); self.assertIsNone(self.m.resolve_plugin_dir('second-plugin'))
        self.assertTrue(Path(self.life.preview_path(preview['previewId'])).is_dir())

    def test_changed_candidate_under_writer_never_replaces_installed_version(self):
        installed = self.install(); preview = self.preview([self.directory(version='2.0.0', slot='update')])
        original = self.m.builtin.operation_lock
        candidate = Path(self.life.preview_path(preview['previewId'])) / preview['reviewedRoots'][0]['path']
        @contextlib.contextmanager
        def changed(cancel=None):
            with original(cancel):
                self.put(candidate / 'index.js', 'changed after lock wait'); yield
        with patch.object(self.m.builtin, 'operation_lock', changed), self.assertRaises(ValueError): self.commit(preview)
        self.assertEqual('1.0.0', json.loads((installed / 'package.json').read_text())['version'])

    def test_sources_and_manifest_links_are_not_followed_as_evidence(self):
        if os.name == 'nt': self.skipTest('本测试使用 Linux 真实符号链接')
        installed = self.install()
        source_bytes = self.source.read_bytes(); alternate = self.root / 'alternate-sources.json'; alternate.write_bytes(source_bytes)
        self.source.unlink(); self.source.symlink_to(alternate)
        with self.assertRaises(self.m.PluginPolicyError): self.life.target_snapshot('test-plugin')
        self.source.unlink(); self.source.write_bytes(source_bytes)
        manifest = installed / 'package.json'; alternate = self.root / 'alternate-package.json'; alternate.write_bytes(manifest.read_bytes())
        manifest.unlink(); manifest.symlink_to(alternate)
        with self.assertRaises(self.m.PluginPolicyError): self.life.target_snapshot('test-plugin')

    def test_source_missing_to_created_invalidates_prepared_target(self):
        self.source.unlink(); preview = self.preview([self.directory()]); self.put(self.source, {})
        with self.assertRaises(self.m.PluginPolicyError) as caught: self.commit(preview)
        self.assertEqual('PLUGIN_SOURCE_CHANGED', caught.exception.code)

    def test_duplicate_exemption_keys_and_symlink_cannot_grant(self):
        package = self.package(engines={'dsh': '<0.1.0'}); filename = self.profile / 'compatibility.json'
        self.put(filename, '{"test-plugin@1.0.0":[],"test-plugin@1.0.0":["0.2.2-rc.1"]}')
        self.assertFalse(self.life.package_metadata(package)['versionExempted'])
        if os.name != 'nt':
            target = self.put(self.root / 'grant.json', {'test-plugin@1.0.0': ['0.2.2-rc.1']})
            filename.unlink(); filename.symlink_to(target)
            self.assertFalse(self.life.package_metadata(package)['versionExempted'])

    def test_exact_update_download_mismatch_fails_before_dependency_scripts(self):
        self.install(); archive = self.archive([self.directory(version='1.5.0', slot='update')])
        def npm(spec, consume): return consume(str(archive), source='npm:' + spec)
        with patch.object(self.life, 'remote_json', return_value=self.package(version='2.0.0')), patch.object(self.m, 'cmd_npm', side_effect=npm), patch.object(self.m, 'prepare_dependencies', side_effect=AssertionError('no script before version admission')):
            with self.assertRaises(self.m.PluginPolicyError) as caught: self.life.prepare_update('test-plugin', '2.0.0')
        self.assertEqual('PLUGIN_TARGET_VERSION_CHANGED', caught.exception.code)

    def test_exemption_aba_or_atomic_replacement_invalidates_preview(self):
        filename = self.profile / 'compatibility.json'
        for atomic in (False, True):
            with self.subTest(atomic=atomic):
                grant = {'test-plugin@1.0.0': ['0.2.2-rc.1']}; self.put(filename, grant)
                preview = self.preview([self.directory(engines={'dsh': '<0.1.0'})])
                if atomic:
                    replacement = self.put(self.profile / 'replacement.json', grant); os.replace(replacement, filename)
                else:
                    self.put(filename, {}); self.put(filename, grant)
                with self.assertRaises(self.m.PluginPolicyError) as caught: self.commit(preview)
                self.assertEqual('PLUGIN_COMPATIBILITY_CHANGED', caught.exception.code)
                self.assertIsNone(self.m.resolve_plugin_dir('test-plugin'))

    def test_safe_mode_keeps_marker_on_compatibility_denial(self):
        installed = self.install()
        with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(0, self.life.safe_mode('on'))
        pkg = json.loads((installed / 'package.json').read_text()); pkg['engines'] = {'dsh': '<0.1.0'}; self.put(installed / 'package.json', pkg)
        # 更新存储的内容证据，仅隔离夹具让兼容检查分支可达；这不是生产回执或真实运行证明。
        state = self.life.read(self.life.path('plugin-safe-mode.json'), {})
        state['fingerprints']['test-plugin'] = self.m.dependencies().current(str(installed))['sha256']
        self.life.write(self.life.path('plugin-safe-mode.json'), state)
        marker = Path(self.m.builtin.marker_path('test-plugin')); before = marker.read_bytes()
        with patch.object(self.m, 'result') as result, contextlib.redirect_stdout(io.StringIO()): self.assertEqual(1, self.life.safe_mode('off'))
        self.assertEqual('PLUGIN_INCOMPATIBLE', result.call_args.kwargs['errorCode'])
        self.assertEqual(before, marker.read_bytes()); self.assertTrue(result.call_args.kwargs['safeMode'])

    def test_rollback_keeps_previous_version_and_disabled_intent(self):
        self.install(); self.m.set_plugin_state('test-plugin', False)
        self.commit(self.preview([self.directory(version='2.0.0', slot='update')]))
        with patch.object(self.m, 'result') as result: self.life.rollback('test-plugin')
        preview = result.call_args.kwargs['preview']; receipt = self.commit(preview)
        installed = Path(self.m.resolve_plugin_dir('test-plugin'))
        self.assertEqual('1.0.0', json.loads((installed / 'package.json').read_text())['version'])
        self.assertEqual('rollback', receipt['stage']); self.assertEqual('restart-required', receipt['application'])
        self.assertTrue(Path(self.m.builtin.marker_path('test-plugin')).exists())

    def test_list_reads_one_compatibility_batch_and_keeps_legacy_update_text(self):
        self.install(); self.put(self.life.path('plugin-updates.json'), {'test-plugin': {'installedVersion': '1.0.0', 'compatibility': 'old update prose'}})
        original = self.life.compatibility_reports
        with patch.object(self.life, 'compatibility_reports', wraps=original) as reports, patch.object(self.m, 'result') as result:
            self.assertEqual(0, self.m.cmd_list())
        self.assertEqual(1, reports.call_count)
        row = next(row for row in result.call_args.kwargs['items'] if row['name'] == 'test-plugin')
        self.assertEqual('unconstrained', row['compatibilityState']); self.assertEqual('old update prose', row['compatibility'])
        before = self.m.list_state_revision(); self.put(self.profile / 'compatibility.json', {})
        self.assertNotEqual(before, self.m.list_state_revision())

    def test_removed_profile_link_cannot_win_via_stale_follow_stat(self):
        installed = self.install()
        with contextlib.redirect_stdout(io.StringIO()): self.m.set_plugin_state('test-plugin', False)
        active = Path(self.m.local(self.m.builtin.NODE_MODULES)) / 'test-plugin'
        if active.is_symlink(): active.unlink()
        original = self.m.os.path.isfile
        def stale(path):
            if os.fspath(path) == str(active / 'package.json'): return True
            return original(path)
        with patch.object(self.m.os.path, 'isfile', side_effect=stale):
            self.assertEqual(installed, Path(self.m.resolve_plugin_dir('test-plugin')))
            self.assertEqual('1.0.0', self.life.target_snapshot('test-plugin')['version'])

    def test_cli_reports_typed_compatibility_failure(self):
        installed = self.install(); pkg = json.loads((installed / 'package.json').read_text()); pkg['engines'] = {'dsh': '<0.1.0'}; self.put(installed / 'package.json', pkg)
        with patch('sys.argv', ['plugin-manager.py', 'enable-state', 'test-plugin']), patch.object(self.m, 'result') as result:
            self.assertEqual(1, self.m.main())
        self.assertEqual('PLUGIN_INCOMPATIBLE', result.call_args.kwargs['errorCode'])
        self.assertEqual('failed', result.call_args.kwargs['application'])


if __name__ == '__main__': unittest.main(verbosity=2)

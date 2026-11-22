#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""内置插件默认启停机制：注册器收敛、显式启用与覆盖更新代次。

build336 起 `dsh-web-mobile` 不再默认禁用（上游 3.3.1 起适配宿主 0.2.2；当前随包 3.3.5），
`builtin-plugins.json` 里当前没有任何 `defaultDisabled: true` 条目；本测试仍用
**合成的** `DEFAULT_DISABLED=('dsh-web-mobile',)` 覆盖注册器
（register-builtin-plugins.py）的真实文件系统流程，不启动 Web / DSH：
  1. 干净 profile：默认关闭项写禁用标记、不进入 bundles，其余内置插件照常注册；
  2. 覆盖更新：旧版本启用过的（含上一代次的显式启用记录）都收敛为禁用；
  3. 只有当前安装代次的显式启用（node_modules/<name>.enabled）跨重启保持，
     下一次 APK 覆盖更新再次收敛为禁用；
  4. 显式禁用优先；禁用后仍可再次启用；
  5. 自动默认禁用用独立来源记录，恢复默认仅迁移身份未变的自动标记；
  6. 旧空/Web/安全模式/未知标记保留，来源损坏或中断时保守失败并可重入。
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets'
MOBILE = 'dsh-web-mobile'
NOTIFIER = 'dsh-task-notifier'
GENERATION = 'DSHA_APK_GENERATION_V1\n'
ENABLED = 'DSHA_DEFAULT_OFF_ENABLED_V1\n'


@unittest.skipIf(os.name == 'nt', 'Requires actual POSIX symlinks')
class BuiltinPluginDefaultOffTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='dsha-builtin-default-')
        self.root = Path(self.temp.name)
        self.environment = patch.dict(
            os.environ, {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh'})
        self.environment.start()
        spec = importlib.util.spec_from_file_location(
            'builtin_default_off', ASSETS / 'register-builtin-plugins.py')
        self.builtin = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.builtin)
        self.profile = self.root / 'root/.dsh/profiles/web'
        self.manifest = self.profile / 'package.json'
        self.marker = self.profile / 'node_modules' / (MOBILE + '.disabled')
        self.enable_marker = self.profile / 'node_modules' / (MOBILE + '.enabled')
        self.default_intent = self.profile / 'node_modules' / (MOBILE + '.default-disabled.json')
        self.mobile_link = self.profile / 'node_modules' / MOBILE
        self.notifier_link = self.profile / 'node_modules' / NOTIFIER
        self.plugin(NOTIFIER)
        self.plugin(MOBILE)
        self.names = patch.object(
            self.builtin, 'builtin_names', return_value=[NOTIFIER, MOBILE])
        self.names.start()
        # 当前注册表没有默认禁用项；机制用合成的默认关闭项继续覆盖。
        self.default_off = patch.object(self.builtin, 'DEFAULT_DISABLED', (MOBILE,))
        self.default_off.start()
        self.write_generation(327)

    def tearDown(self):
        self.default_off.stop()
        self.names.stop()
        self.environment.stop()
        self.temp.cleanup()

    def plugin(self, name):
        directory = self.root / 'root' / ('dsha-' + name[len('dsh-'):])
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'package.json').write_text(json.dumps({
            'name': name, 'version': '1.0.0', 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}},
            ensure_ascii=False), encoding='utf-8')
        return directory

    def write_generation(self, version_code):
        path = self.root / 'usr/local/share/dsha/apk-generation'
        path.parent.mkdir(parents=True, exist_ok=True)
        if version_code is None:
            path.unlink(missing_ok=True)
        else:
            path.write_text(GENERATION + str(version_code) + '\n', encoding='ascii')

    def write_managed_identity(self, identity):
        path = self.root / 'usr/local/share/dsha/managed-assets-v2'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('DSHA_MANAGED_ASSETS_V2\n' + identity + '\n', encoding='ascii')

    def write_enable_marker(self, generation):
        self.enable_marker.parent.mkdir(parents=True, exist_ok=True)
        self.enable_marker.write_text(ENABLED + generation + '\n', encoding='utf-8')

    def read_enable_marker(self):
        return self.enable_marker.read_text(encoding='utf-8')

    def write_manifest(self, bundles, dependencies=None):
        self.profile.mkdir(parents=True, exist_ok=True)
        self.manifest.write_text(json.dumps({
            'dependencies': dependencies or {},
            'dsh': {'profile': {'bundles': bundles, 'patchReload': 'startup'}}},
            ensure_ascii=False), encoding='utf-8')

    def bundles(self):
        return json.loads(self.manifest.read_text(encoding='utf-8'))['dsh']['profile']['bundles']

    def dependencies(self):
        return json.loads(self.manifest.read_text(encoding='utf-8'))['dependencies']

    def register(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = self.builtin.register()
        self.assertEqual(0, code, output.getvalue())
        self.assertIn('BUILTIN_REGISTER_OK', output.getvalue())
        return output.getvalue()

    def enable(self, name=MOBILE):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = self.builtin.enable_plugin(name)
        self.assertEqual(0, code, output.getvalue())

    def disable(self, name=MOBILE):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = self.builtin.disable_plugin(name)
        self.assertEqual(0, code, output.getvalue())

    def assert_disabled(self):
        self.assertNotIn(MOBILE, self.bundles())
        self.assertTrue(self.marker.is_file())
        self.assertEqual('', self.marker.read_text(encoding='utf-8'))
        self.assertFalse(self.enable_marker.exists())
        self.assertFalse(os.path.lexists(self.mobile_link))

    def test_registry_declares_no_default_disabled_plugin(self):
        registry = json.loads((ASSETS / 'builtin-plugins.json').read_text(encoding='utf-8'))
        disabled = [row['name'] for row in registry['plugins'] if row.get('defaultDisabled') is True]
        self.assertEqual([], disabled)
        # 夹具覆盖的是合成默认关闭项；真实模块从注册表派生的列表必须为空。
        spec = importlib.util.spec_from_file_location(
            'builtin_real_registry', ASSETS / 'register-builtin-plugins.py')
        real = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(real)
        self.assertEqual((), real.DEFAULT_DISABLED)
        self.assertTrue(all(row.get('defaultDisabled', False) is False
                            for row in registry['plugins']))

    def test_clean_install_disables_mobile_and_registers_the_rest(self):
        self.assertFalse(self.manifest.exists())
        self.register()

        self.assert_disabled()
        self.assertIn(NOTIFIER, self.bundles())
        for official in self.builtin.OFFICIAL_BUNDLES:
            self.assertIn(official, self.bundles())
        self.assertTrue(os.path.islink(self.notifier_link))
        self.assertEqual('link:/root/dsha-web-mobile', self.dependencies()[MOBILE])

        # 每次启动都会跑注册器；默认关闭不能变成“每次启动尝试启用”。
        bundles = self.bundles()
        self.register()
        self.assertEqual(bundles, self.bundles())
        self.assert_disabled()

    def test_over_the_top_upgrade_disables_a_previously_enabled_plugin(self):
        # 旧版本默认启用（或用户当时启用过）但没有任何本代次启用记录：覆盖更新必须收敛。
        self.write_manifest(list(self.builtin.OFFICIAL_BUNDLES) + [NOTIFIER, MOBILE])
        self.register()

        self.assert_disabled()
        self.assertIn(NOTIFIER, self.bundles())

    def test_over_the_top_upgrade_disables_an_enable_from_an_older_apk(self):
        # 上一个安装代次显式启用过：代次不匹配，同样收敛为禁用。
        self.write_manifest(list(self.builtin.OFFICIAL_BUNDLES) + [NOTIFIER, MOBILE])
        self.write_enable_marker('apk:326')
        self.register()

        self.assert_disabled()
        self.assertFalse(self.enable_marker.exists())

    def test_explicit_enable_in_current_install_survives_restarts(self):
        self.register()
        self.enable()

        self.assertIn(MOBILE, self.bundles())
        self.assertFalse(self.marker.exists())
        self.assertTrue(os.path.islink(self.mobile_link))
        self.assertEqual(ENABLED + 'apk:327\n', self.read_enable_marker())

        self.register()
        self.assertIn(MOBILE, self.bundles())
        self.assertFalse(self.marker.exists())
        self.assertEqual(ENABLED + 'apk:327\n', self.read_enable_marker())

    def test_next_apk_update_disables_a_current_install_enable(self):
        self.register()
        self.enable()
        self.write_generation(328)
        self.register()

        self.assert_disabled()

    def test_explicit_disable_after_enable_stays_disabled_and_can_be_reenabled(self):
        self.register()
        self.enable()
        self.disable()

        self.assert_disabled()
        self.register()
        self.assert_disabled()

        self.enable()
        self.assertIn(MOBILE, self.bundles())
        self.assertFalse(self.marker.exists())
        self.assertEqual(ENABLED + 'apk:327\n', self.read_enable_marker())

    def test_web_plugin_manager_disable_marker_is_kept_and_link_stays_for_metadata(self):
        # Web 插件页写的是精确标记；注册器保留内容与受管入口，只把它移出加载列表。
        self.write_manifest(list(self.builtin.OFFICIAL_BUNDLES) + [NOTIFIER])
        self.marker.parent.mkdir(parents=True, exist_ok=True)
        self.marker.write_text(self.builtin.WEB_BUILTIN_DISABLED_MARKER, encoding='utf-8')
        self.write_enable_marker('apk:327')
        self.register()

        self.assertNotIn(MOBILE, self.bundles())
        self.assertEqual(self.builtin.WEB_BUILTIN_DISABLED_MARKER,
                         self.marker.read_text(encoding='utf-8'))
        self.assertFalse(self.enable_marker.exists())
        self.assertTrue(os.path.islink(self.mobile_link))
        self.assertEqual(self.plugin(MOBILE).resolve(), self.mobile_link.resolve())

    def test_missing_apk_marker_uses_managed_identity_and_resets_when_it_changes(self):
        self.write_generation(None)
        identity_a, identity_b = 'a' * 64, 'b' * 64
        self.write_managed_identity(identity_a)
        self.register()
        self.enable()
        self.assertEqual(ENABLED + 'runtime:' + identity_a + '\n', self.read_enable_marker())

        self.register()
        self.assertIn(MOBILE, self.bundles())

        self.write_managed_identity(identity_b)
        self.register()
        self.assert_disabled()
    def test_default_on_registers_clean_mobile_once(self):
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
            first = self.manifest.read_bytes()
            self.register()
        self.assertIn(MOBILE, self.bundles())
        self.assertEqual(first, self.manifest.read_bytes())
        self.assertFalse(self.marker.exists())
        self.assertFalse(self.default_intent.exists())
        self.assertEqual(self.plugin(MOBILE).resolve(), self.mobile_link.resolve())

    def test_automatic_default_off_is_restored_when_policy_becomes_default_on(self):
        self.register()
        self.assert_disabled()
        recorded = json.loads(self.default_intent.read_text(encoding='utf-8'))
        self.assertEqual(1, recorded['version'])
        self.assertEqual('builtin-default', recorded['source'])
        self.assertEqual(6, len(recorded['disabledStat']))
        # 相同默认策略重复注册必须保留原始来源事实，而不是每轮重写“授权”。
        original = self.default_intent.read_bytes()
        self.register()
        self.assertEqual(original, self.default_intent.read_bytes())
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
            self.assertIn(MOBILE, self.bundles())
            self.assertFalse(self.marker.exists())
            self.assertFalse(self.default_intent.exists())
            self.assertEqual(self.plugin(MOBILE).resolve(), self.mobile_link.resolve())
            first = self.manifest.read_bytes()
            self.register()
            self.assertEqual(first, self.manifest.read_bytes())

    def test_explicit_native_disable_cancels_automatic_provenance(self):
        self.register()
        self.assertTrue(self.default_intent.is_file())
        self.disable()
        self.assertFalse(self.default_intent.exists())
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
            self.write_generation(999)
            self.register()
        self.assert_disabled()

    def test_enable_clears_automatic_provenance(self):
        self.register()
        self.assertTrue(self.default_intent.is_file())
        self.enable()
        self.assertFalse(self.default_intent.exists())
        self.assertFalse(self.marker.exists())
        self.assertIn(MOBILE, self.bundles())

    def test_legacy_and_explicit_markers_are_preserved_when_default_returns(self):
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            for content in ('', 'UNKNOWN_PREVIOUS_INTENT\n', 'DSHA_SAFE_MODE\n',
                            self.builtin.WEB_BUILTIN_DISABLED_MARKER):
                with self.subTest(content=content):
                    self.write_manifest(list(self.builtin.OFFICIAL_BUNDLES) + [NOTIFIER, MOBILE])
                    self.marker.parent.mkdir(parents=True, exist_ok=True)
                    self.marker.write_text(content, encoding='utf-8')
                    before = self.marker.stat()
                    self.register()
                    self.register()
                    self.assertNotIn(MOBILE, self.bundles())
                    self.assertEqual(content, self.marker.read_text(encoding='utf-8'))
                    self.assertEqual(before.st_ino, self.marker.stat().st_ino)
                    self.assertFalse(self.default_intent.exists())

    def test_legacy_empty_marker_can_be_explicitly_enabled_without_re_disabling(self):
        self.marker.parent.mkdir(parents=True, exist_ok=True)
        self.marker.write_text('', encoding='utf-8')
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
            self.assert_disabled()
            self.enable()
            for generation in (336, 366, 999):
                self.write_generation(generation)
                self.register()
                self.assertIn(MOBILE, self.bundles())
                self.assertFalse(self.marker.exists())
                self.assertFalse(self.default_intent.exists())

    def test_changed_marker_is_never_reclassified_as_automatic(self):
        for content in ('', 'DSHA_SAFE_MODE\n', self.builtin.WEB_BUILTIN_DISABLED_MARKER):
            with self.subTest(content=content):
                self.register()
                # 模拟外部/旧版写者替换同名标记；即使仍为空，inode 也已经不同。
                replacement = self.marker.with_suffix('.replacement')
                replacement.write_text(content, encoding='utf-8')
                os.replace(replacement, self.marker)
                with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
                    self.register()
                self.assertNotIn(MOBILE, self.bundles())
                self.assertEqual(content, self.marker.read_text(encoding='utf-8'))
                self.enable()

    def test_marker_link_or_bad_provenance_is_not_auto_restored(self):
        self.register()
        target = self.root / 'unrelated-empty-marker'
        os.replace(self.marker, target)
        self.marker.symlink_to(target)
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assertTrue(self.marker.is_symlink())
        self.assertEqual('', target.read_text(encoding='utf-8'))
        self.assertNotIn(MOBILE, self.bundles())
        self.enable()
        self.write_generation(328)
        self.register()
        self.default_intent.write_text('{bad json', encoding='utf-8')
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assert_disabled()
        self.assertEqual('{bad json', self.default_intent.read_text(encoding='utf-8'))

    def test_existing_empty_default_off_marker_is_not_adopted_as_automatic(self):
        self.marker.parent.mkdir(parents=True, exist_ok=True)
        self.marker.write_text('', encoding='utf-8')
        self.register()
        self.assertFalse(self.default_intent.exists())
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assert_disabled()

    def test_in_place_empty_rewrite_does_not_reuse_the_automatic_source(self):
        self.register()
        before = self.marker.stat()
        self.marker.write_text('', encoding='utf-8')
        # 某些文件系统时钟粒度较粗；显式设置新时间确保本用例确实模拟了新写入。
        os.utime(self.marker, ns=(before.st_atime_ns, before.st_mtime_ns + 1_000_000_000))
        self.assertEqual(before.st_ino, self.marker.stat().st_ino)
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assert_disabled()

    def test_invalid_source_schema_never_authorizes_restoration(self):
        self.register()
        valid = self.default_intent.read_text(encoding='utf-8')
        for content in ('[]', ' ' * 1025,
                        valid.replace('"version": 1', '"version": 1, "version": 1'),
                        valid.replace('"builtin-default"', '"user"'),
                        valid.replace('"version": 1', '"version": true')):
            with self.subTest(content=content[:80]):
                self.default_intent.write_text(content, encoding='utf-8')
                with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
                    self.register()
                self.assert_disabled()
                self.assertEqual(content, self.default_intent.read_text(encoding='utf-8'))

    def test_linked_source_record_does_not_authorize_restoration(self):
        self.register()
        target = self.root / 'unrelated-source-record'
        os.replace(self.default_intent, target)
        original = target.read_bytes()
        self.default_intent.symlink_to(target)
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assert_disabled()
        self.assertTrue(self.default_intent.is_symlink())
        self.assertEqual(original, target.read_bytes())

    def test_orphan_source_cleanup_is_reentrant_without_reviving_a_new_marker(self):
        self.register()
        original_unlink = os.unlink

        def deny_source(path, *args, **kwargs):
            if os.fspath(path) == str(self.default_intent):
                raise PermissionError('fixture cannot finish source cleanup')
            return original_unlink(path, *args, **kwargs)

        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()), patch.object(os, 'unlink', deny_source):
            with contextlib.redirect_stdout(io.StringIO()):
                result = self.builtin.register()
        self.assertNotEqual(0, result)
        self.assertFalse(self.marker.exists())
        self.assertTrue(self.default_intent.exists())
        # 删除标记后若用户重新关闭，残余来源绝不能被当作删除新标记的许可。
        self.marker.write_text('', encoding='utf-8')
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assert_disabled()
        self.assertTrue(self.default_intent.exists())
        self.enable()
        self.assertFalse(self.default_intent.exists())

    def test_orphan_source_without_new_marker_finishes_cleanup_on_retry(self):
        self.register()
        original_unlink = os.unlink

        def deny_source(path, *args, **kwargs):
            if os.fspath(path) == str(self.default_intent):
                raise PermissionError('fixture cannot finish source cleanup')
            return original_unlink(path, *args, **kwargs)

        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()), patch.object(os, 'unlink', deny_source):
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertNotEqual(0, self.builtin.register())
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
            self.register()
        self.assertIn(MOBILE, self.bundles())
        self.assertFalse(self.marker.exists())
        self.assertFalse(self.default_intent.exists())

    def test_unrelated_profile_and_user_marker_are_not_migrated(self):
        self.register()
        other = self.root / 'root/.dsh/profiles/other/node_modules'
        other.mkdir(parents=True)
        other_marker = other / (MOBILE + '.disabled')
        other_marker.write_text('', encoding='utf-8')
        other_source = other / self.default_intent.name
        other_source.write_bytes(self.default_intent.read_bytes())
        user_marker = self.marker.with_name('user-plugin.disabled')
        user_marker.write_text('', encoding='utf-8')
        user_source = user_marker.with_name('user-plugin.default-disabled.json')
        user_source.write_bytes(self.default_intent.read_bytes())
        observed = {path: path.read_bytes() for path in (other_marker, other_source, user_marker, user_source)}
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assertIn(MOBILE, self.bundles())
        self.assertEqual(observed, {path: path.read_bytes() for path in observed})

    def test_default_marker_removal_failure_stops_before_enabling(self):
        self.register()
        original_unlink = os.unlink

        def deny_marker(path, *args, **kwargs):
            if os.fspath(path) == str(self.marker):
                raise PermissionError('fixture cannot remove marker')
            return original_unlink(path, *args, **kwargs)

        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()), patch.object(os, 'unlink', deny_marker):
            with contextlib.redirect_stdout(io.StringIO()):
                result = self.builtin.register()
        self.assertNotEqual(0, result)
        self.assert_disabled()
        self.assertTrue(self.default_intent.is_file())
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assertIn(MOBILE, self.bundles())
        self.assertFalse(self.default_intent.exists())

    def test_source_record_failure_leaves_a_conservative_disabled_state(self):
        original_open = os.open

        def deny_source(path, *args, **kwargs):
            if os.fspath(path) == str(self.default_intent):
                raise PermissionError('fixture cannot record default source')
            return original_open(path, *args, **kwargs)

        with patch.object(os, 'open', deny_source), contextlib.redirect_stdout(io.StringIO()):
            result = self.builtin.register()
        self.assertNotEqual(0, result)
        self.assertEqual('', self.marker.read_text(encoding='utf-8'))
        self.assertFalse(self.default_intent.exists())
        with patch.object(self.builtin, 'DEFAULT_DISABLED', ()):
            self.register()
        self.assert_disabled()


if __name__ == '__main__':
    unittest.main()

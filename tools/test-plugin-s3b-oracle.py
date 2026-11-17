#!/usr/bin/env python3
"""S3b 冻结差分 oracle：只在 tempfile 中导入并执行旧 Python，绝不访问生产 profile。

默认 --check 重跑旧实现并对拍 JSON；--write 显式更新冻结结果。没有 JVM、Node、
包管理器或插件入口执行。兼容报告是明确注入的外部事实，判定仍由 require_compatible
执行。依赖图由真实 Dependencies.current 扫描；路径重定位仅用于随机 tempfile 的
摘要对拍，不模拟任何插件规则。新增用例应修改 scenarios() 后显式 --write。
"""
import argparse
import contextlib
import copy
import difflib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
FIXTURE = ROOT / 'app/src/test/resources/plugin-s3b-oracle.json'
LEGACY = ('register-builtin-plugins.py', 'plugin-lifecycle.py', 'plugin-manager.py',
          'plugin-dependencies.py', 'backup-plugin-graph.py', 'builtin-plugins.json',
          'runtime-tools.json')
MOBILE = 'dsh-web-mobile'
NOTIFIER = 'dsh-task-notifier'
AUTO = 'dsh-auto-review'
OFFICIAL_AUTO = '@deepseek-ai/dsh-experimental-auto-review'
REVIEW = b'DSHA_REVIEW_REQUIRED\n'
WEB = b'DSHA_WEB_BUILTIN_DISABLED_V1\n'


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(',', ':')).encode()


def digest(value):
    return hashlib.sha256(value).hexdigest()


def put(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(value, (dict, list)):
        value = json.dumps(value, ensure_ascii=True).encode()
    elif isinstance(value, str):
        value = value.encode()
    path.write_bytes(value)
    path.chmod(0o644)
    return path


def package(name, **extra):
    return dict({'name': name, 'version': '1.0.0', 'main': 'index.js',
                 'dsh': {'bundle': {'patch': 'cordis.patch.yml'}}}, **extra)


def manifest(names=()):
    return {'name': 'owned-profile', 'private': True, 'custom': {'preserve': [1, '用户']},
            'dependencies': {}, 'devDependencies': {'owned-tool': '^2'},
            'dsh': {'custom': 7, 'profile': {'bundles': list(names), 'patchReload': 'live',
                                           'ownedSetting': {'keep': True}}}}


def registry(names=(MOBILE, NOTIFIER), default_off=()):
    return {'schema': 1, 'officialBundles': [{'name': '@deepseek-ai/dsh-base'},
                                           {'name': '@deepseek-ai/dsh-web-app'}],
            'plugins': [{'name': name, 'guestDirectory': '/root/dsha-' + name[4:],
                         'defaultDisabled': name in default_off} for name in names]}


def scenarios():
    cases = []
    for label, marker in [('exact', REVIEW), ('short', REVIEW[:-1]),
                          ('long', REVIEW + b'x'), ('same-length-wrong', b'X' + REVIEW[1:]),
                          ('empty', b''), ('safe-mode', b'DSHA_SAFE_MODE\n'),
                          ('invalid-utf8', b'\xff' * len(REVIEW)), ('absent', None)]:
        cases.append({'id': 'review-marker-' + label, 'operation': 'migrate',
                      'plugins': [{'name': 'legacy-plugin', 'marker': marker}]})
    for label, overrides in [
            ('marker-symlink', {'markerType': 'symlink'}),
            ('marker-directory', {'markerType': 'directory'}),
            ('source-npm', {'reference': '^1.0.0'}),
            ('source-other-path', {'reference': 'link:/root/untrusted/legacy-plugin'}),
            ('source-dot-path', {'reference': 'link:/root/.dsh/plugin-src/./legacy-plugin'}),
            ('directory-symlink', {'entityType': 'symlink'}),
            ('directory-missing', {'entityType': 'missing'}),
            ('identity-spoof', {'package': package('another-plugin')}),
            ('invalid-version', {'package': package('legacy-plugin', version='latest')}),
            ('missing-patch', {'missingPatch': True}),
            ('dependency-missing', {'package': package('legacy-plugin', dependencies={'absent-helper': '1'})}),
            ('dependency-present', {'package': package('legacy-plugin', dependencies={'real-helper': '1'}), 'helper': True}),
            ('enable-failure', {'failEnable': True}),
    ]:
        cases.append({'id': 'review-' + label, 'operation': 'migrate',
                      'plugins': [dict({'name': 'legacy-plugin', 'marker': REVIEW}, **overrides)]})
    cases.append({'id': 'review-partial-and-queue-order', 'operation': 'migrate', 'plugins': [
        {'name': 'first-plugin', 'marker': REVIEW},
        {'name': 'blocked-plugin', 'marker': REVIEW, 'compatibility': 'incompatible'},
        {'name': 'third-plugin', 'marker': REVIEW},
        {'name': 'platform-plugin', 'marker': REVIEW, 'compatibility': 'unsupported-platform'},
        {'name': 'check-failed-plugin', 'marker': REVIEW, 'compatibility': 'check-failed'}]})
    cases.append({'id': 'review-reserved-and-invalid-names', 'operation': 'migrate', 'plugins': [
        {'name': MOBILE, 'marker': REVIEW}, {'name': '@deepseek-ai/dsh-base', 'marker': REVIEW},
        {'name': 'UPPER-invalid', 'marker': REVIEW}]})
    for label, settings in [
            ('new', {'initial': None}), ('merge-fields', {}),
            ('existing-profile-files-missing', {'alreadyMerged': True}),
            ('old-entity-quarantine', {'oldEntity': True}),
            ('user-disabled', {'marker': b'', 'oldEntity': True}),
            ('safe-mode', {'marker': b'DSHA_SAFE_MODE\n', 'oldEntity': True}),
            ('web-exact', {'marker': WEB, 'oldEntity': True}),
            ('web-short', {'marker': WEB[:-1], 'oldEntity': True}),
            ('web-long', {'marker': WEB + b'x', 'oldEntity': True}),
            ('web-symlink', {'marker': WEB, 'markerType': 'symlink', 'oldEntity': True}),
            ('entity-missing-partial', {'missing': MOBILE, 'oldEntity': True}),
            ('alias-failure-partial', {'failAlias': MOBILE}),
            ('all-entities-missing', {'missingAll': True}),
            ('auto-review-dedupe', {'auto': True}),
            ('auto-review-disabled-alias', {'auto': True, 'autoMarker': True}),
            ('default-off-new', {'initial': None, 'defaultOff': True}),
            ('default-off-current-apk', {'defaultOff': True, 'enabled': 'apk:369'}),
            ('default-off-old-apk', {'defaultOff': True, 'enabled': 'apk:368'}),
            ('default-off-user-disabled', {'defaultOff': True, 'enabled': 'apk:369', 'marker': b''}),
            ('default-off-marker-directory', {'defaultOff': True, 'enabled': 'apk:368', 'marker': b'', 'markerType': 'directory'}),
            ('default-off-marker-dangling-symlink', {'defaultOff': True, 'enabled': 'apk:368', 'marker': b'', 'markerType': 'dangling-symlink'}),
            ('generation-runtime-fallback', {'defaultOff': True, 'apk': None, 'enabled': 'runtime:' + 'a' * 64}),
            ('generation-runtime-identity-changed', {'defaultOff': True, 'apk': None, 'enabled': 'runtime:' + 'b' * 64}),
            ('generation-unmanaged-fallback', {'defaultOff': True, 'apk': None, 'managed': None, 'enabled': 'unmanaged'}),
            ('generation-invalid-apk-fallback', {'defaultOff': True, 'apkRaw': b'DSHA_APK_GENERATION_V1\n369\nextra\n', 'enabled': 'runtime:' + 'a' * 64}),
            ('generation-invalid-managed-fallback', {'defaultOff': True, 'apk': None, 'managedRaw': b'DSHA_MANAGED_ASSETS_V2\nINVALID\n', 'enabled': 'unmanaged'}),
            ('default-source-unchanged', {'preDefault': True}),
            ('default-source-entity-missing', {'preDefault': True, 'entityMissingAfterDefault': True}),
            ('default-source-preserves-enable-intent', {'preDefault': True, 'enableAfterDefault': True}),
            ('default-source-replaced-marker', {'preDefault': True, 'replaceMarker': True}),
            ('default-source-rewritten-marker', {'preDefault': True, 'rewriteMarker': True}),
            ('default-source-marker-symlink', {'preDefault': True, 'linkMarker': True}),
            ('default-source-corrupt', {'preDefault': True, 'corruptSource': True}),
            ('default-source-symlink', {'preDefault': True, 'linkSource': True}),
            ('default-source-wrong-origin', {'preDefault': True, 'wrongSource': True}),
            ('default-source-cleanup-failure', {'preDefault': True, 'failSourceUnlink': True}),
    ]:
        cases.append(dict({'id': 'builtin-' + label, 'operation': 'register'}, **settings))
    return cases


class Oracle:
    def __init__(self, base, assets, scenario, frozen_root, expected=None):
        self.root = base / scenario['id']
        self.root.mkdir()
        self.assets, self.spec = assets, scenario
        self.frozen_root = frozen_root
        self.events, self.graphs, self.results = [], {}, []
        self.module = None
        self.injections = [{'target': 'time.time', 'value': 1700000000,
                            'affects': ['activation receipt confirmedAt only; filesystem stat remains real']}]
        self.stat_slots = {}
        self.stat_identities = []
        self.expected_stats = (expected or {}).get('statIdentities', [])

    def stat_fact(self, values):
        actual = tuple(values)
        if actual not in self.stat_slots:
            index = len(self.stat_slots)
            frozen = self.expected_stats[index] if index < len(self.expected_stats) else list(actual)
            self.stat_slots[actual] = frozen
            self.stat_identities.append(frozen)
        return self.stat_slots[actual]

    def path(self, guest):
        return self.root / guest.lstrip('/')

    def normalize(self, value):
        if isinstance(value, str):
            return value.replace(str(self.root), '$ROOT')
        if isinstance(value, list):
            return [self.normalize(item) for item in value]
        if isinstance(value, dict):
            return {self.normalize(key): self.normalize(item) for key, item in value.items()}
        return value

    def relocated_graph(self, nodes):
        # 只重定位旧扫描输出；这是摘要编码，不是迁移/注册规则。
        def relocate(value):
            if isinstance(value, str):
                return value.replace(str(self.root), self.frozen_root + '/' + self.spec['id'])
            if isinstance(value, list):
                return [relocate(item) for item in value]
            if isinstance(value, dict):
                return {relocate(key): relocate(item) for key, item in value.items()}
            return value
        return relocate(nodes)

    def plugin(self, directory, name, pkg=None):
        put(directory / 'package.json', pkg or package(name))
        put(directory / 'cordis.patch.yml', '[]\n')
        put(directory / 'index.js', 'throw Error("oracle must never execute plugin entry");\n')

    def marker(self, name, content, kind='file'):
        path = Path(self.module.builtin.marker_path(name))
        path.parent.mkdir(parents=True, exist_ok=True)
        if kind == 'directory':
            path.mkdir()
        elif kind == 'symlink':
            target = put(self.path('/marker-targets/' + name.replace('/', '__')), content)
            path.symlink_to(os.path.relpath(target, path.parent))
        elif kind == 'dangling-symlink':
            target = self.path('/marker-targets/' + name.replace('/', '__') + '-missing')
            assert not os.path.lexists(target)
            path.symlink_to(os.path.relpath(target, path.parent))
        elif content is not None:
            put(path, content)

    def entity(self, path):
        if path.is_symlink():
            return {'type': 'symlink', 'target': self.normalize(os.readlink(path)),
                    'resolved': self.normalize(str(path.resolve())), 'exists': path.exists()}
        if path.is_dir():
            files = {}
            for file in sorted(path.rglob('*')):
                if file.is_file() and not file.is_symlink():
                    files[file.relative_to(path).as_posix()] = file.read_bytes().hex()
            return {'type': 'directory', 'filesHex': files}
        if path.is_file():
            return {'type': 'file', 'bytesHex': path.read_bytes().hex()}
        return {'type': 'missing'}

    def file_fact(self, path):
        b = self.module.builtin
        if not os.path.lexists(path):
            return {'kind': 'MISSING', 'bytesHex': '', 'stat': None,
                    'stable': True, 'targetRegular': False}
        before = b.disabled_stat(path.lstat())
        kind = 'SYMLINK' if path.is_symlink() else 'DIRECTORY' if path.is_dir() else 'FILE'
        raw = path.read_bytes() if path.is_file() else b''
        stable = before == b.disabled_stat(path.lstat())
        return {'kind': kind, 'bytesHex': raw.hex(), 'stat': self.stat_fact(before),
                'stable': stable, 'targetRegular': path.is_file()}

    def markers(self):
        b = self.module.builtin
        result = {}
        nm = self.path('/root/.dsh/profiles/web/node_modules')
        for path in sorted(nm.rglob('*')) if nm.exists() else []:
            if not path.name.endswith(('.disabled', '.enabled', '.default-disabled.json')):
                continue
            name = path.relative_to(nm).as_posix()
            row = self.entity(path)
            if path.is_symlink() and path.is_file():
                row['targetBytesHex'] = path.read_bytes().hex()
            if path.name.endswith('.default-disabled.json') and path.is_file():
                try:
                    raw = path.read_bytes()
                    value = json.loads(raw)
                    if isinstance(value, dict) and isinstance(value.get('disabledStat'), list):
                        plugin_name = name.removesuffix('.default-disabled.json')
                        marker = Path(b.marker_path(plugin_name))
                        matches = marker.exists() and value['disabledStat'] == b.disabled_stat(marker.lstat())
                        value['disabledStat'] = self.stat_fact(value['disabledStat'])
                        # --write 保留原始字节；--check 只映射六字段身份数值。
                        raw = re.sub(rb'("disabledStat"\s*:\s*)\[[^\]]*\]',
                                     lambda found: found[1] + json.dumps(value['disabledStat']).encode(), raw)
                        if path.is_symlink():
                            row['targetBytesHex'] = raw.hex()
                        else:
                            row['bytesHex'] = raw.hex()
                        row['json'] = value
                        row['disabledStatMatches'] = bool(matches)
                        row['acceptedByOldReader'] = b.read_default_disabled_intent(plugin_name) is not None
                except (ValueError, OSError):
                    pass
            row['fileFact'] = {'kind': row['type'].upper(),
                               'bytesHex': row.get('targetBytesHex', row.get('bytesHex', '')),
                               'stat': self.stat_fact(b.disabled_stat(path.lstat())),
                               'stable': True, 'targetRegular': path.is_file()}
            result[name] = row
        return result

    def state(self, names):
        b = self.module.builtin
        quarantine = []
        q = self.path('/root/.dsha-system-plugin-quarantine')
        for directory in sorted(q.iterdir()) if q.exists() else []:
            quarantine.append({'migration': json.loads((directory / 'migration.json').read_bytes()),
                               'content': self.entity(directory / 'content')})
        quarantine.sort(key=lambda value: json.dumps(value, sort_keys=True))
        activation = self.module.read_json(str(self.path('/root/.dsh/plugin-activations.json')), None)
        aliases = {}
        for name in names:
            path = self.path('/root/.dsh/profiles/web/node_modules/' + name)
            row = self.entity(path)
            target = self.path(b.BUILTIN_DIRECTORIES[name]) if name in b.BUILTIN_DIRECTORIES else self.path('/root/.dsh/plugin-src/' + name)
            row.update(kind=row['type'].upper(), canonicalCurrent=path.resolve() == target.resolve(),
                       packagePresent=(path / 'package.json').is_file(),
                       absolute=path.is_symlink() and os.path.isabs(os.readlink(path)))
            aliases[name] = row
        profile_files = {name: self.entity(self.path('/root/.dsh/profiles/web/' + name))
                         for name in ('cordis.patch.yml', 'pnpm-workspace.yaml')}
        return {'manifest': b.read_manifest(), 'markers': self.markers(),
                'aliases': aliases, 'profileFiles': profile_files,
                'activationState': activation, 'quarantine': quarantine}

    def run(self):
        s = self.spec
        environment = {'DSHA_TEST_ROOT': str(self.root), 'DSH_HOME': '/root/.dsh',
                       'DSHA_PLUGIN_TASK': '', 'DSHA_PLUGIN_QUERY': '0',
                       'DSHA_MANAGED_PACKAGE_PROOFS_SHA256': ''}
        with patch.dict(os.environ, environment), contextlib.ExitStack() as stack:
            descriptor = importlib.util.spec_from_file_location('s3b_manager', self.assets / 'plugin-manager.py')
            self.module = importlib.util.module_from_spec(descriptor)
            descriptor.loader.exec_module(self.module)
            m, b = self.module, self.module.builtin
            life, deps = m.lifecycle(), m.dependencies()
            stack.enter_context(patch.object(sys, 'dont_write_bytecode', True))
            # 固定非规则时间字段；不 patch stat，真实 inode/类型/字节参与旧判断。
            stack.enter_context(patch.object(life.g['time'], 'time', return_value=1700000000))
            reg = registry()
            initial = manifest(['user-kept', MOBILE, MOBILE])
            names = [MOBILE, NOTIFIER]
            compatibility = {}
            entities = {}
            setup = []
            if s['operation'] == 'migrate':
                initial = manifest()
                for row in s['plugins']:
                    name = row['name']
                    relative = '/root/.dsh/plugin-src/' + name
                    directory = self.path(relative)
                    if row.get('entityType') != 'missing':
                        target = self.path('/directory-targets/' + name) if row.get('entityType') == 'symlink' else directory
                        self.plugin(target, name, row.get('package'))
                        if row.get('entityType') == 'symlink':
                            directory.parent.mkdir(parents=True, exist_ok=True)
                            directory.symlink_to(os.path.relpath(target, directory.parent))
                        if row.get('missingPatch'):
                            (target / 'cordis.patch.yml').unlink()
                        if row.get('helper'):
                            self.plugin(target / 'node_modules/real-helper', 'real-helper')
                    initial['dependencies'][name] = row.get('reference', 'link:' + relative)
                    self.marker(name, row.get('marker'), row.get('markerType', 'file'))
                    compatibility[name] = row.get('compatibility', 'compatible')
                    entities[name] = self.entity(directory)
                names = [row['name'] for row in s['plugins']]
            else:
                if s.get('auto'):
                    names = [AUTO, NOTIFIER]
                    reg = registry(names)
                    initial = manifest([AUTO, OFFICIAL_AUTO, 'user-kept'])
                    if s.get('autoMarker'):
                        self.marker(OFFICIAL_AUTO, b'')
                for name in names:
                    target = self.path('/root/dsha-' + name[4:])
                    if not s.get('missingAll') and s.get('missing') != name:
                        self.plugin(target, name)
                    entities[name] = self.entity(target)
                focus = names[0]
                if s.get('oldEntity'):
                    old = self.path('/root/.dsh/profiles/web/node_modules/' + focus)
                    self.plugin(old, focus, package(focus, version='0.1.0'))
                    put(old / 'user-byte.bin', b'old\x00preserved\xff\n')
                    initial['dependencies'][focus] = 'link:/old/profile/copy'
                if 'marker' in s:
                    self.marker(focus, s['marker'], s.get('markerType', 'file'))
                if 'enabled' in s:
                    put(Path(b.enable_intent_path(focus)), 'DSHA_DEFAULT_OFF_ENABLED_V1\n' + s['enabled'] + '\n')
                if s.get('defaultOff'):
                    reg = registry(names, [focus])
                if s.get('apk', 369) is not None:
                    put(self.path('/usr/local/share/dsha/apk-generation'), s.get('apkRaw', 'DSHA_APK_GENERATION_V1\n' + str(s.get('apk', 369)) + '\n'))
                if s.get('managed', 'a' * 64) is not None:
                    put(self.path('/usr/local/share/dsha/managed-assets-v2'), s.get('managedRaw', 'DSHA_MANAGED_ASSETS_V2\n' + s.get('managed', 'a' * 64) + '\n'))
                if s.get('alreadyMerged'):
                    initial = manifest(['user-kept'] + names)
                    initial['dependencies'] = {name: 'link:/root/dsha-' + name[4:] for name in names}
                    initial['dsh']['profile']['patchReload'] = 'startup'
                if s.get('preDefault'):
                    reg = registry(names)
                    self.injections.append({'target': 'DEFAULT_DISABLED (setup only)', 'value': [focus],
                                            'affects': ['setup automatic source and disabled marker']})
                    with patch.object(b, 'DEFAULT_DISABLED', (focus,)), \
                            patch.object(b, 'DEFAULT_BUILTINS', tuple(names)), \
                            contextlib.redirect_stdout(io.StringIO()):
                        setup.append({'operation': 'register-default-off', 'returnCode': b.register()})
                    marker = Path(b.marker_path(focus))
                    source = Path(b.default_disabled_intent_path(focus))
                    if s.get('replaceMarker'):
                        replacement = put(marker.with_suffix('.replacement'), b'')
                        os.replace(replacement, marker)
                    if s.get('rewriteMarker'):
                        before = marker.stat()
                        marker.write_bytes(b'')
                        os.utime(marker, ns=(before.st_atime_ns, before.st_mtime_ns + 1000000000))
                    if s.get('linkMarker'):
                        target = marker.parent / 'saved-marker'
                        os.replace(marker, target)
                        marker.symlink_to(target.name)
                    if s.get('corruptSource'):
                        source.write_bytes(b'{bad-json')
                    if s.get('wrongSource'):
                        value = json.loads(source.read_bytes()); value['source'] = 'user'
                        put(source, value)
                    if s.get('linkSource'):
                        target = source.parent / 'saved-source'
                        os.replace(source, target)
                        source.symlink_to(target.name)
                    if s.get('entityMissingAfterDefault'):
                        current_entity = self.path('/root/dsha-' + focus[4:])
                        unavailable = self.path('/unavailable-entity')
                        assert current_entity.resolve().is_relative_to(self.root.resolve())
                        assert unavailable.resolve().is_relative_to(self.root.resolve())
                        os.replace(current_entity, unavailable)
                        entities[focus] = self.entity(current_entity)
                        setup.append({'operation': 'move-entity-out-of-managed-path', 'name': focus})
                    if s.get('enableAfterDefault'):
                        put(Path(b.enable_intent_path(focus)), 'DSHA_DEFAULT_OFF_ENABLED_V1\napk:369\n')
                        setup.append({'operation': 'add-current-apk-enable-intent', 'name': focus})
                    initial = b.read_manifest()
                if 'initial' in s:
                    initial = s['initial']
            # 合成注册表子集只覆盖模块的派生常量；判定与FS操作仍调用旧实现。
            # 每个用例独占模块，不修改仓库资产或生产注册表。
            rows = reg['plugins']
            for key, value in {'DEFAULT_BUILTINS': tuple(row['name'] for row in rows),
                               'DEFAULT_DISABLED': tuple(row['name'] for row in rows if row.get('defaultDisabled')),
                               'OFFICIAL_BUNDLES': tuple(row['name'] for row in reg['officialBundles']),
                               'BUILTIN_DIRECTORIES': {row['name']: row['guestDirectory'] for row in rows}}.items():
                stack.enter_context(patch.object(b, key, value))
            self.injections.append({'target': 'registry-derived module constants', 'value': reg,
                                    'affects': ['managed name membership', 'default-off policy', 'official exclusions', 'entity paths']})
            if initial is not None:
                put(self.path('/root/.dsh/profiles/web/package.json'), initial)
            original_current = deps.current
            sha_map = {}
            def current(directory):
                value = original_current(directory)
                assert value['sha256'] == digest(encoded(value['nodes'])), 'old current sha must match actual graph'
                frozen_sha = digest(encoded(self.relocated_graph(value['nodes'])))
                sha_map[value['sha256']] = frozen_sha
                fact = copy.deepcopy(value)
                fact['sha256'] = frozen_sha
                fact['sha256AtFrozenRoot'] = frozen_sha
                fact['actualSha256Verified'] = True
                self.graphs[self.normalize(str(directory))] = self.normalize(fact)
                return value
            stack.enter_context(patch.object(deps, 'current', side_effect=current))
            def reports(packages):
                reports_out = []
                for pkg in packages:
                    state = compatibility.get(pkg['name'], 'compatible')
                    code = {'incompatible': 'PLUGIN_DSH_INCOMPATIBLE',
                            'unsupported-platform': 'PLUGIN_PLATFORM_UNSUPPORTED',
                            'check-failed': 'PLUGIN_COMPATIBILITY_CHECK_FAILED'}.get(state, '')
                    reports_out.append({'compatibility': state, 'compatibilityCode': code,
                                        'compatibilityMessage': 'oracle compatibility ' + state,
                                        'versionExempted': False, 'compatibilityEvidence': 'fixture-evidence'})
                return reports_out
            if s['operation'] == 'migrate':
                stack.enter_context(patch.object(life, 'compatibility_reports', side_effect=reports))
                self.injections.append({'target': 'Lifecycle.compatibility_reports', 'value': compatibility,
                                        'affects': ['compatibility allow/block and partial result; real require_compatible executes']})
                original_queue, original_enable = life.queue_activation, b.enable_plugin
                def queue(name, fingerprint, version, **kwargs):
                    original_queue(name, fingerprint, version, **kwargs)
                    self.events.append({'event': 'queue-persisted', 'name': name, 'sha256': fingerprint,
                                        'version': version, 'state': life.activation_state()})
                def enable(name):
                    before = life.activation_state()
                    self.events.append({'event': 'enable-enter', 'name': name, 'queueAlreadyPersisted': name in before['entries']})
                    fail = next(row for row in s['plugins'] if row['name'] == name).get('failEnable')
                    rc = 1 if fail else original_enable(name)
                    self.events.append({'event': 'enable-return', 'name': name, 'returnCode': rc})
                    return rc
                stack.enter_context(patch.object(life, 'queue_activation', side_effect=queue))
                stack.enter_context(patch.object(b, 'enable_plugin', side_effect=enable))
                stack.enter_context(patch.object(m, 'result', side_effect=lambda status, message, **kw: self.results.append(dict(status=status, message=message, **kw))))
                if any(row.get('failEnable') for row in s['plugins']):
                    self.injections.append({'target': 'builtin.enable_plugin', 'value': 'return 1 without mutation',
                                            'affects': ['skipped result; queue remains persisted; no actual activation']})
            if s.get('failAlias'):
                original_link = b.ensure_symlink
                stack.enter_context(patch.object(b, 'ensure_symlink', side_effect=lambda name, directory: False if name == s['failAlias'] else original_link(name, directory)))
                self.injections.append({'target': 'builtin.ensure_symlink', 'value': {s['failAlias']: False},
                                        'affects': ['missing alias and BUILTIN_REGISTER_PARTIAL; manifest already written']})
            if s.get('failSourceUnlink'):
                original_unlink = os.unlink
                source_path = b.default_disabled_intent_path(names[0])
                def unlink(path, *args, **kwargs):
                    if os.fspath(path) == source_path:
                        raise PermissionError('oracle source cleanup failure')
                    return original_unlink(path, *args, **kwargs)
                stack.enter_context(patch.object(os, 'unlink', side_effect=unlink))
                self.injections.append({'target': 'os.unlink source-record only', 'value': 'PermissionError',
                                        'affects': ['marker removed but source retained; register failure before enable']})
            input_state = self.state(names)
            apk_marker = self.file_fact(Path(b.APK_GENERATION_MARKER))
            managed_marker = self.file_fact(Path(b.MANAGED_ASSET_MARKER))
            metadata = {}
            compatibility_facts = {}
            if s['operation'] == 'migrate':
                for row in s['plugins']:
                    name = row['name']
                    compatibility_facts[name] = {'checked': False}
                    directory = self.path('/root/.dsh/plugin-src/' + name)
                    try:
                        pkg = m.plugin_package(str(directory))
                        metadata[name] = {'valid': True, 'name': pkg['name'], 'version': pkg['version']}
                    except (ValueError, OSError) as error:
                        metadata[name] = {'valid': False, 'name': '', 'version': '',
                                          'error': str(error), 'errorType': type(error).__name__}
                    else:
                        try:
                            life.require_compatible(pkg, stage='enable')
                            compatibility_facts[name] = {'checked': True, 'allowed': True}
                        except m.PluginPolicyError as error:
                            compatibility_facts[name] = {'checked': True, 'allowed': False,
                                                         'errorCode': error.code, 'message': str(error)}
            # 所有候选都采样真实 current（包括旧规则早退的候选），明确哪些依赖扫描被执行。
            if s['operation'] == 'migrate':
                for row in s['plugins']:
                    directory = self.path('/root/.dsh/plugin-src/' + row['name'])
                    if directory.is_dir():
                        try:
                            current(str(directory))
                        except (ValueError, OSError) as error:
                            self.graphs[self.normalize(str(directory))] = {'scanError': str(error)}
            input_graphs = copy.deepcopy(self.graphs)
            scanned_during = []
            def observed_current(directory):
                scanned_during.append(self.normalize(str(directory)))
                return current(directory)
            stack.enter_context(patch.object(deps, 'current', side_effect=observed_current))
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                rc = life.migrate_legacy_reviews() if s['operation'] == 'migrate' else b.register()
            result = self.state(names)
            # 用冻结根摘要替换原始运行摘要；实际扫描摘要已在上面独立断言。
            def remap(value):
                if isinstance(value, str):
                    return sha_map.get(value, value)
                if isinstance(value, list):
                    return [remap(item) for item in value]
                if isinstance(value, dict):
                    return {key: remap(item) for key, item in value.items()}
                return value
            codes = re.findall(r'BUILTIN_REGISTER_(OK|FAIL|PARTIAL)', output.getvalue())
            result.update(returnCode=rc, classification=self.results[-1]['status'] if self.results else (codes[-1] if codes else 'NONE'),
                          emittedResults=self.results, registerSignals=codes, events=self.events,
                          dependenciesScannedDuringOperation=scanned_during)
            return self.normalize(remap({'id': s['id'], 'operation': s['operation'],
                'statIdentities': self.stat_identities,
                'input': {'manifest': initial, 'registry': reg, 'markers': input_state['markers'],
                          'aliases': input_state['aliases'], 'entities': entities,
                          'profileFiles': input_state['profileFiles'],
                          'compatibility': compatibility, 'compatibilityFacts': compatibility_facts,
                          'metadata': metadata,
                          'dependenciesCurrent': input_graphs,
                          'apkMarker': apk_marker, 'managedMarker': managed_marker,
                          'apkGeneration': b.apk_generation(), 'managedAssetIdentity': b.managed_asset_identity(),
                          'setup': setup}, 'injections': self.injections, 'result': result}))


def generate(frozen_root=None, expected=None):
    with tempfile.TemporaryDirectory(prefix='dsha-s3b-oracle-') as directory:
        base = Path(directory)
        assets = base / 'legacy-assets'; assets.mkdir()
        sources = []
        for name in LEGACY:
            raw = (ASSETS / name).read_bytes()
            put(assets / name, raw)
            sources.append({'path': 'app/src/main/assets/' + name, 'sha256': digest(raw)})
        # 包括动态 import 的 pyc 也只能存在临时副本。
        with patch.object(sys, 'dont_write_bytecode', True):
            prior = {case['id']: case for case in (expected or {}).get('cases', [])}
            rows = [Oracle(base, assets, case, frozen_root or str(base), prior.get(case['id'])).run()
                    for case in scenarios()]
        return {'schema': 1, 'legacyAssets': sources,
                'normalization': {'frozenRoot': frozen_root or str(base),
                    'paths': '$ROOT denotes the isolated case root; all symlinks are real POSIX symlinks',
                    'dependencySha256': 'Actual old current() sha is verified against its actual graph; check relocates graph paths to frozenRoot/caseId for stable digest comparison. At --write the frozen digest equals the actual old digest.',
                    'defaultSource': 'Actual six-field stat identities and original sidecar bytes are frozen. Check maps each newly observed identity to the corresponding frozen statIdentities slot, preserving equality/inequality; no stat call in old code is patched. All marker and quarantine bytes are hex.',
                    'clock': 'time.time fixed to 1700000000 for activation receipts; stat clocks and inode identities are real',
                    'injections': 'Per-case injections enumerate all decision-affecting patches; queue/current wrappers execute real implementations. Synthetic registries are subsets of current signed names.'},
                'cases': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--write', action='store_true', help='显式重生成冻结 oracle')
    mode.add_argument('--check', action='store_true', help='重跑旧 Python 对拍（默认）')
    args = parser.parse_args()
    if args.write:
        value = generate()
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        FIXTURE.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf8')
        print('WROTE S3b legacy Python oracle: %d cases' % len(value['cases']))
        return 0
    if not FIXTURE.is_file():
        parser.error('冻结 oracle 缺失；显式执行 --write')
    expected = json.loads(FIXTURE.read_text(encoding='utf8'))
    actual = generate(expected['normalization']['frozenRoot'], expected)
    if actual != expected:
        before = json.dumps(expected, indent=2, ensure_ascii=False).splitlines()
        after = json.dumps(actual, indent=2, ensure_ascii=False).splitlines()
        print('\n'.join(list(difflib.unified_diff(before, after, fromfile='frozen', tofile='old-python-rerun'))[:240]))
        return 1
    print('PASS S3b legacy Python oracle: %d cases; all asset sha256 and frozen results match' % len(actual['cases']))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

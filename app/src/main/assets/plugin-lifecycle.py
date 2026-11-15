#!/usr/bin/env python3
"""插件安装预览、更新发现、单版本回退与安全启动；由 plugin-manager 注入既有安装器。"""
import base64
import concurrent.futures
import functools
import hashlib
import importlib.util
import json
import os
import re
import shlex
import shutil
import stat
import subprocess
import time
import urllib.parse
import uuid


@functools.lru_cache(maxsize=256)
def semver_request(action, left, right):
    """使用随包 npm SemVer；未知/非法范围不得冒充兼容。"""
    try:
        request = {'action': action, 'left': left, 'right': right, 'version': left, 'range': right}
        process = subprocess.run(['node', os.path.join(os.path.dirname(__file__), 'plugin-semver.cjs')],
                                 input=json.dumps(request), text=True, capture_output=True, timeout=15,
                                 env={key: value for key, value in os.environ.items() if key not in ('NODE_OPTIONS', 'NODE_PATH')})
        return json.loads(process.stdout) if process.returncode == 0 else None
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return None


def compare_versions(left, right):
    return semver_request('compare', str(left), str(right))


def accepts_version(installed, requirement):
    return semver_request('satisfies', str(installed), str(requirement))


class HistoryChange:
    def __init__(self, life, name, old, work, source):
        self.life, self.old = life, old
        self.target = life.history_path(name)
        self.saved = os.path.join(work, 'previous-history')
        self.moved_old = False
        self.moved_history = False
        self.created = False
        try:
            if os.path.lexists(self.target):
                os.replace(self.target, self.saved); self.moved_history = True
            os.makedirs(self.target)
            self.created = True
            os.replace(old, os.path.join(self.target, 'package')); self.moved_old = True
            pkg = life.read(os.path.join(self.target, 'package', 'package.json'), {})
            life.write(os.path.join(self.target, 'state.json'), {'name': name, 'source': source,
                       'version': pkg.get('version', ''), 'savedAt': int(time.time())})
        except Exception:
            self.undo(); raise

    def undo(self):
        if self.moved_old and os.path.isdir(os.path.join(self.target, 'package')):
            os.replace(os.path.join(self.target, 'package'), self.old)
        if self.created and os.path.isdir(self.target):
            shutil.rmtree(self.target)
        if self.moved_history and os.path.exists(self.saved):
            os.replace(self.saved, self.target)


class Lifecycle:
    def __init__(self, manager):
        self.g = manager
        self.builtin = manager['builtin']
        self.local = manager['local']
        self.home = self.local(manager['DSH_HOME'])
        self.read, self.write = manager['read_json'], manager['write_json']

    def path(self, relative):
        path = os.path.join(self.home, relative)
        if os.path.commonpath([os.path.realpath(self.home), os.path.realpath(path)]) != os.path.realpath(self.home):
            raise ValueError('插件管理目录指向外部，已停止操作')
        return path

    def migrate_legacy_reviews(self, locked=False):
        """Activate only markers written by the removed review gate.

        Empty/user-disabled and safe-mode markers remain untouched. Invalid
        packages remain disabled so a broken old install cannot block startup.
        """
        def migrate():
            doc = self.builtin.read_manifest() or {}
            references = doc.get('dependencies') or {}
            activated, skipped, blocked = [], [], []
            for name, reference in references.items():
                if not self.builtin.valid_name(name) or name in self.builtin.OFFICIAL_BUNDLES or name in self.builtin.builtin_names():
                    continue
                marker = self.builtin.marker_path(name)
                if os.path.islink(marker) or not os.path.isfile(marker) or os.path.getsize(marker) != len(b'DSHA_REVIEW_REQUIRED\n'):
                    continue
                with open(marker, 'rb') as stream:
                    if stream.read() != b'DSHA_REVIEW_REQUIRED\n':
                        continue
                relative = os.path.join(self.g['PLUGIN_SRC'], name)
                directory = self.local(relative)
                if reference != 'link:' + relative.replace('\\', '/') or os.path.islink(directory) or not os.path.isdir(directory):
                    skipped.append(name); continue
                try:
                    package = self.g['plugin_package'](directory)
                    if package['name'] != name:
                        raise ValueError('插件名称与启用记录不一致：' + name)
                    content = self.g['dependencies']().current(directory)
                    if package['name'] != name or content['missing']:
                        skipped.append(name); continue
                    self.require_compatible(package, stage='enable')
                    self.queue_activation(name, content['sha256'], package['version'])
                    if self.builtin.enable_plugin(name):
                        skipped.append(name); continue
                    activated.append(name)
                except self.g['PluginPolicyError'] as error:
                    skipped.append(name); blocked.append({'name': name, 'errorCode': error.code, 'message': str(error)})
                except (OSError, ValueError):
                    skipped.append(name)
            if blocked:
                self.g['result']('partial', blocked[0]['message'], activated=activated, skipped=skipped,
                                 blockedPlugins=blocked, errorCode=blocked[0]['errorCode'], application='failed', stage='enable')
                return 1
            self.g['result']('ok', '已启用 %d 个旧版待审阅插件%s' %
                             (len(activated), ('；%d 个原件不完整，保持停用' % len(skipped)) if skipped else ''),
                             activated=activated, skipped=skipped)
            return 0
        if locked:
            return migrate()
        with self.builtin.operation_lock(self.g['check_cancel']):
            return migrate()

    def history_path(self, name):
        if not self.builtin.valid_name(name):
            raise ValueError('无效插件名')
        parent = self.path('plugin-history')
        os.makedirs(parent, exist_ok=True)
        return self.path('plugin-history/' + hashlib.sha256(name.encode()).hexdigest()[:32])

    def retain(self, name, old, work, source):
        return HistoryChange(self, name, old, work, source)

    def history_info(self, name):
        path = self.history_path(name)
        state = self.read(os.path.join(path, 'state.json'), {})
        return state if state.get('name') == name and os.path.isfile(os.path.join(path, 'package', 'package.json')) else {}

    def dsh_version(self):
        for path in ('/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json', '/root/dsh-src/package.json'):
            value = self.read(self.local(path), {})
            if value.get('name') == '@deepseek-ai/dsh':
                return value.get('version', '')
        return ''

    def metadata(self, root):
        return self.package_metadata(self.g['plugin_package'](root))

    def capabilities(self, root, package, candidate_sha='', snapshot=None):
        spec = importlib.util.spec_from_file_location('plugin_capabilities', os.path.join(os.path.dirname(__file__), 'plugin-capabilities.py'))
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        snapshot = snapshot or {}
        report = module.analyze(root, package, candidate_sha=candidate_sha,
                                lock_sha=snapshot.get('lockSha256', ''), resolved=snapshot.get('resolved', []))
        report['runtimeDsh'] = self.dsh_version()
        return report

    COMPATIBILITY_MESSAGES = {
        'compatible': '插件兼容性已核验',
        'unconstrained': '作者未声明 dsh 兼容范围',
        'exempted': '此插件版本的精确兼容豁免已生效',
        'incompatible': '插件与当前 dsh 版本不兼容',
        'unsupported-platform': '插件不支持当前运行平台',
        'check-failed': '插件兼容性检查失败，未执行操作',
    }

    def policy_error(self, code, message, stage='preflight', **detail):
        return self.g['PluginPolicyError'](code, message, stage=stage, **detail)

    def compatibility_reports(self, packages):
        """一次只读进程检查整批；固定脚本不继承预加载，不导入 app-boot/插件入口。"""
        if not isinstance(packages, list) or len(packages) > 512:
            raise self.policy_error('PLUGIN_COMPATIBILITY_CHECK_FAILED', self.COMPATIBILITY_MESSAGES['check-failed'])
        warnings = []
        def exemption_snapshot():
            filename = self.local(os.path.join(self.builtin.PROFILE, 'compatibility.json'))
            try: raw, evidence = self.file_evidence(filename)
            except FileNotFoundError: return None, {'missing': True}
            # 没有豁免文件从不授权；本操作创建原本缺失的 profile 父目录不等于授权记录变化。
            return raw, {'missing': True} if raw is None else evidence
        try:
            raw, exemption_evidence = exemption_snapshot()
            def unique(pairs):
                value = {}
                for key, item in pairs:
                    if key in value: raise ValueError('EXEMPTION_DUPLICATE_KEY')
                    value[key] = item
                return value
            exemptions = json.loads(raw, object_pairs_hook=unique) if raw is not None else {}
        except (OSError, ValueError, TypeError):
            exemptions = {}; warnings = ['EXEMPTION_UNREADABLE']; exemption_evidence = None
        request = {'action': 'compatibility', 'manifests': packages, 'exemptions': exemptions,
                   'exemptionWarnings': warnings,
                   'anchors': [self.local('/usr/local/lib/node_modules/@deepseek-ai/dsh/package.json'),
                               self.local('/root/dsh-src/package.json')]}
        environment = {key: value for key, value in os.environ.items() if key not in ('NODE_OPTIONS', 'NODE_PATH')}
        reports = None
        try:
            self.g['check_cancel']()
            encoded = json.dumps(request, ensure_ascii=True)
            if len(encoded) > 8 * 1024 * 1024: raise ValueError('COMPATIBILITY_INPUT_LIMIT')
            process = subprocess.run(['node', os.path.join(os.path.dirname(__file__), 'plugin-semver.cjs')],
                                     input=encoded, text=True, capture_output=True, timeout=15, env=environment)
            if process.returncode == 0 and len(process.stdout) <= 8 * 1024 * 1024:
                reports = json.loads(process.stdout)
            self.g['check_cancel']()
            if exemption_evidence is not None:
                _, after_exemptions = exemption_snapshot()
                if after_exemptions != exemption_evidence: reports = None
        except (OSError, ValueError, subprocess.TimeoutExpired):
            reports = None
        if not isinstance(reports, list) or len(reports) != len(packages):
            reports = [{'compatibility': 'check-failed', 'compatibilityCode': 'PLUGIN_COMPATIBILITY_CHECK_FAILED',
                        'requirements': {}, 'unmetRequirements': {}, 'installedDsh': '', 'runtimePlatform': '',
                        'versionExempted': False, 'exemptionWarnings': warnings} for _ in packages]
        for package, report in zip(packages, reports):
            if not isinstance(report, dict) or report.get('compatibility') not in self.COMPATIBILITY_MESSAGES:
                raise self.policy_error('PLUGIN_COMPATIBILITY_CHECK_FAILED', self.COMPATIBILITY_MESSAGES['check-failed'])
            report['compatibilityMessage'] = self.COMPATIBILITY_MESSAGES[report['compatibility']]
            report['requiredDsh'] = '; '.join(key + ': ' + value for key, value in report['requirements'].items())
            proof = {'manifest': package, 'runtime': report.pop('runtimeEvidence', ''),
                     'exemptionFile': exemption_evidence if exemption_evidence is not None else 'unreadable',
                     'requirements': report['requirements'], 'state': report['compatibility'],
                     'exempted': report['versionExempted'], 'platform': report['runtimePlatform']}
            report['compatibilityEvidence'] = self.review_digest(proof)
        return reports

    def require_compatible(self, package, expected=None, stage='preflight', report=None):
        report = report or self.compatibility_reports([package])[0]
        if report['compatibility'] not in ('compatible', 'unconstrained', 'exempted'):
            raise self.policy_error(report['compatibilityCode'], report['compatibilityMessage'], stage,
                                    compatibility=report['compatibility'], versionExempted=report['versionExempted'])
        if expected is not None and expected != report['compatibilityEvidence']:
            raise self.policy_error('PLUGIN_COMPATIBILITY_CHANGED', '插件兼容证据在准备后变化，请重新检查', stage)
        return report

    def package_metadata(self, pkg, compatibility=None):
        author = pkg.get('author', '')
        if isinstance(author, dict): author = author.get('name', '')
        report = compatibility or self.compatibility_reports([pkg])[0]
        return dict({'name': pkg['name'], 'version': str(pkg.get('version', '')), 'author': str(author)[:200] or '未注明',
                     'description': str(pkg.get('description', ''))[:1500], 'repository': self.g['repository_url'](pkg)}, **report)

    @staticmethod
    def _node_evidence(node):
        return [node.st_dev, node.st_ino, node.st_mode, node.st_size, node.st_mtime_ns, node.st_ctime_ns]

    def file_evidence(self, filename):
        """有界 NOFOLLOW 读；正文相同但 inode/ctime 换过也不是同一份来源。"""
        filename = os.path.abspath(filename)
        parent = os.path.dirname(filename)
        # 不跟随来源父链中的符号链接；合法包链接由 resolve_plugin_dir 先明确解析。
        chain = []; current = parent
        while True:
            node = os.lstat(current)
            if not stat.S_ISDIR(node.st_mode): raise ValueError('PLUGIN_EVIDENCE_PARENT')
            chain.append([current, node.st_dev, node.st_ino])
            if current == os.path.dirname(current): break
            current = os.path.dirname(current)
        try:
            fd = os.open(filename, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
        except FileNotFoundError:
            return None, {'path': filename, 'parents': chain, 'missing': True}
        with os.fdopen(fd, 'rb') as stream:
            before = os.fstat(stream.fileno())
            if not stat.S_ISREG(before.st_mode) or before.st_size > 1024 * 1024:
                raise ValueError('PLUGIN_EVIDENCE_TYPE_OR_LIMIT')
            data = stream.read(1024 * 1024 + 1)
            after = os.fstat(stream.fileno())
        if len(data) > 1024 * 1024 or self._node_evidence(before) != self._node_evidence(after) \
                or self._node_evidence(before) != self._node_evidence(os.lstat(filename)):
            raise ValueError('PLUGIN_EVIDENCE_CHANGED')
        for path, device, inode in chain:
            node = os.lstat(path)
            if not stat.S_ISDIR(node.st_mode) or (node.st_dev, node.st_ino) != (device, inode):
                raise ValueError('PLUGIN_EVIDENCE_PARENT_CHANGED')
        return data, {'path': filename, 'parents': chain, 'node': self._node_evidence(before),
                      'sha256': hashlib.sha256(data).hexdigest()}

    def target_snapshot(self, name):
        try:
            raw, source = self.file_evidence(self.local(self.g['SOURCES']))
            sources = json.loads(raw) if raw is not None else {}
            if not isinstance(sources, dict) or not isinstance(sources.get(name, ''), str):
                raise ValueError('PLUGIN_SOURCE_FORMAT')
            directory = self.g['resolve_plugin_dir'](name)
            installed = None
            if directory:
                raw, installed = self.file_evidence(os.path.join(os.path.realpath(directory), 'package.json'))
                package = json.loads(raw)
                if package.get('name') != name: raise ValueError('PLUGIN_TARGET_IDENTITY')
            else: package = {}
            return {'name': name, 'source': sources.get(name, ''), 'sourceFile': source,
                    'installedManifest': installed, 'version': package.get('version', '')}
        except (OSError, ValueError, TypeError, AttributeError):
            raise self.policy_error('PLUGIN_SOURCE_CHANGED', '插件来源或版本在准备后变化，请重新检查')

    def require_target(self, expected, stage='commit'):
        if not isinstance(expected, dict) or self.target_snapshot(expected.get('name', '')) != expected:
            raise self.policy_error('PLUGIN_SOURCE_CHANGED', '插件来源或版本在准备后变化，请重新检查', stage)

    def candidate_fingerprint(self, directory):
        # current() 的账本摘要包含安装绝对路径；候选搬到事务槽位时只归一化该候选内部节点。
        # 外部实际依赖路径仍保留，不能把不同共享依赖误当同一份字节。
        current = self.g['dependencies']().current(directory)
        root = os.path.realpath(directory)
        def location(value):
            return '@candidate' + value[len(root):] if value == root or value.startswith(root + os.sep) else value
        nodes = {}
        for path, row in current['nodes'].items():
            dependencies = {name: dict(edge, **({'target': location(edge['target'])} if 'target' in edge else {}))
                            for name, edge in row['dependencies'].items()}
            nodes[location(path)] = dict(row, dependencies=dependencies)
        return self.review_digest({'nodes': nodes, 'missing': current['missing']})

    def require_candidate(self, directory, item, fingerprint=None, stage='commit'):
        try:
            package = self.g['plugin_package'](directory)
            if package['name'] != item['name'] or package['version'] != item['version']:
                raise self.policy_error('PLUGIN_TARGET_VERSION_CHANGED', '下载包与指定的插件版本不一致', stage)
            if fingerprint is not None and self.candidate_fingerprint(directory) != fingerprint:
                raise self.policy_error('PLUGIN_CANDIDATE_CHANGED', '插件候选内容在准备后变化，请重新解析', stage)
            self.require_compatible(package, item.get('compatibilityEvidence'), stage)
            return package
        except self.g['PluginPolicyError']:
            raise
        except (OSError, ValueError, TypeError, KeyError):
            raise self.policy_error('PLUGIN_CANDIDATE_CHANGED', '插件候选内容在准备后变化，请重新解析', stage)

    def pending_guard(self):
        if self.g['transactions']().pending():
            raise self.g['PluginInstallPending']('插件事务尚未提交；未执行新操作，请先完成维护')

    @staticmethod
    def committed(stage):
        return {'application': 'restart-required', 'restartReason': 'no-hmr', 'stage': stage}

    @staticmethod
    def digest(path):
        digest = hashlib.sha256()
        with open(path, 'rb') as stream:
            for chunk in iter(lambda: stream.read(65536), b''):
                digest.update(chunk)
        return digest.hexdigest()

    def preview_path(self, key):
        if not re.fullmatch('[a-f0-9]{32}', key):
            raise ValueError('无效安装预览，请重新解析链接')
        return self.path('plugin-previews/' + key)

    def prune_previews(self):
        root = self.path('plugin-previews')
        os.makedirs(root, exist_ok=True)
        if len(os.listdir(root)) >= 64:
            raise ValueError('保留的插件预览已达上限，请先明确取消不再需要的预览')

    def inspect(self, request, emit=True):
        if isinstance(request, str):
            request = json.loads(request)
        if not isinstance(request, dict):
            raise ValueError('安装请求格式错误')
        command = request.get('command', '')
        if not isinstance(command, str) or len(command) > 8192:
            raise ValueError('安装链接过长或格式错误')
        parts = shlex.split(command)
        if not parts or parts[0] not in ('download', 'github', 'release', 'npm', 'file'):
            raise ValueError('链接只支持下载和插件包解析')
        expected_hash = request.get('sha256', '')
        if expected_hash and not re.fullmatch('[a-fA-F0-9]{64}', expected_hash):
            raise ValueError('插件校验码格式错误')
        self.prune_previews()
        key = uuid.uuid4().hex
        path = self.preview_path(key); os.makedirs(path)
        prepared = None

        def receive(archive, subdir='', source=''):
            nonlocal prepared
            self.g['progress']('verify', '正在核对插件摘要和声明…')
            if not os.path.isfile(archive) or os.path.getsize(archive) > self.g['MAX_DOWNLOAD']:
                raise ValueError('插件包不存在或超过 256 MiB')
            digest = self.digest(archive)
            if expected_hash and digest.lower() != expected_hash.lower():
                raise ValueError('插件 SHA-256 与网站提供的校验码不一致')
            destination = os.path.join(path, 'archive')
            shutil.copyfile(archive, destination)
            if self.digest(destination) != digest:
                raise ValueError('插件输入在复制时变化，未进入安装')
            staging = os.path.join(path, 'contents')
            self.g['extract_archive'](destination, staging)
            roots = self.g['find_plugin_roots'](staging, subdir)
            packages = [self.g['plugin_package'](root) for root in roots]
            reports = self.compatibility_reports(packages)
            # 首个 pnpm/生命周期脚本之前核验全部根，第二个包不兼容不能留下第一个包的脚本效果。
            for package, report in zip(packages, reports): self.require_compatible(package, report=report)
            items = [self.package_metadata(package, report) for package, report in zip(packages, reports)]
            names = [item['name'] for item in items]
            if len(set(names)) != len(names):
                raise ValueError('归档中包含同名插件')
            if request.get('name') and (len(items) != 1 or items[0]['name'] != request['name']):
                raise ValueError('下载包的名称与选中的插件不一致')
            if request.get('version') and (len(items) != 1 or items[0]['version'] != request['version']):
                raise self.policy_error('PLUGIN_TARGET_VERSION_CHANGED', '下载包与指定的插件版本不一致')
            if request.get('updateFor') and (len(items) != 1 or items[0]['name'] != request['updateFor']
                    or compare_versions(items[0]['version'], request.get('fromVersion', '')) != 1):
                raise self.policy_error('PLUGIN_UPDATE_VERSION_INVALID', '更新目标必须是更高的精确版本')
            expected_target = request.get('targetSnapshot')
            if expected_target is not None: self.require_target(expected_target, 'preflight')
            reviewed = []
            for plugin_root, item in zip(roots, items):
                excluded = {'node_modules'}
                if not os.path.lexists(os.path.join(plugin_root, 'pnpm-lock.yaml')):
                    excluded.add('pnpm-lock.yaml')
                code_before = self.g['dependencies']().tree(plugin_root, self.g['check_cancel'], excluded)[0]
                snapshot = self.g['prepare_dependencies'](plugin_root, self.g['plugin_package'](plugin_root), digest, bool(request.get('offline', False)))
                actual = self.metadata(plugin_root)
                self.require_compatible(self.g['plugin_package'](plugin_root), item['compatibilityEvidence'])
                if actual['name'] != item['name'] or actual['version'] != item['version'] or self.g['dependencies']().tree(plugin_root, self.g['check_cancel'], excluded)[0] != code_before:
                    raise ValueError('插件源码在依赖准备期间变化，未进入安装')
                item['dependencyState'] = snapshot['state']
                item['resolvedDependencies'] = snapshot.get('resolved', [])[:50]
                item['dependencyCount'] = len(snapshot.get('resolved', []))
                item['dependencyLockSha256'] = snapshot.get('lockSha256', '')
                item['packageManagerVersion'] = snapshot.get('managerVersion', 'not-executed')
                item['dependencyPreparation'] = 'package-manager-completed' if item['packageManagerVersion'] != 'not-executed' else 'existing-tree-verified'
                # 仅说明真实准备路径；不能由 pnpm 成功推导每个包的脚本都执行过。
                item['lifecycleScriptEvidence'] = 'not-observed'
                if snapshot.get('authorClaims'):
                    item['authorDependencyClaims'] = snapshot['authorClaims']
                reviewed.append({'path': os.path.relpath(plugin_root, path).replace(os.sep, '/'),
                                 'fingerprint': self.candidate_fingerprint(plugin_root),
                                 'snapshotSha256': self.digest(os.path.join(plugin_root, '.dsha-dependencies.json'))})
            if expected_target is not None: self.require_target(expected_target, 'preflight')
            targets = {name: (expected_target if expected_target and expected_target['name'] == name else self.target_snapshot(name)) for name in names}
            prepared = {'targetSnapshots': targets, 'previewId': key, 'source': self.g['dependencies']().source_label(request.get('source') or source), 'commandKind': parts[0], 'sha256': digest,
                        'items': items, 'createdAt': int(time.time()), 'subdir': subdir,
                        'updateFor': request.get('updateFor', ''), 'fromVersion': request.get('fromVersion', ''), 'reviewedRoots': reviewed,
                        'contentsSha256': self.g['dependencies']().tree(staging, self.g['check_cancel'])[0]}
            prepared['confirmationSha256'] = self.review_digest(prepared)
            self.write(os.path.join(path, 'preview.json'), prepared)
            return 0
        try:
            if parts[0] == 'download' and len(parts) == 2:
                self.g['cmd_download'](parts[1], consume=receive)
            elif parts[0] == 'github' and len(parts) in (3, 4):
                self.g['cmd_github'](*parts[1:], consume=receive)
            elif parts[0] == 'release' and len(parts) == 4:
                self.g['cmd_release'](*parts[1:], consume=receive)
            elif parts[0] == 'npm' and len(parts) == 2:
                self.g['cmd_npm'](parts[1], consume=receive)
            elif parts[0] == 'file' and len(parts) == 2:
                receive(self.local(parts[1]))
            else:
                raise ValueError('不支持的安装参数')
            if prepared is None:
                raise ValueError('未获得可安装的插件包')
            self.g['check_cancel']()
        except Exception as error:
            # 未闭合的包管理进程可能仍写候选；不得清理或改名其输入。
            if not isinstance(error, self.g['PackageCommandUnknown']) and not (isinstance(error, self.g['PackageCommandTimeout']) and not error.confirmed_exit):
                shutil.rmtree(path)
            raise
        if emit:
            self.g['result']('ok', '插件包已解析，正在提交安装', preview=prepared)
            return 0
        return prepared

    def show_preview(self, key):
        path = self.preview_path(key)
        preview = self.read(os.path.join(path, 'preview.json'), {})
        if not preview or self.review_digest(preview) != preview.get('confirmationSha256'):
            raise ValueError('预览记录不可读取或已变化，原件已保留')
        self.g['result']('ok', '插件包已准备，正在继续安装', preview=preview)
        return 0

    def pending_previews(self):
        root = self.path('plugin-previews')
        if not os.path.isdir(root):
            return [], 0
        names = os.listdir(root)
        if len(names) > 128:
            raise ValueError('保留的插件预览已达上限，请先明确取消不再需要的预览')
        rows = []; unreadable = 0
        for key in sorted(names):
            if not re.fullmatch('[a-f0-9]{32}', key):
                unreadable += 1; continue
            try:
                value = self.read(os.path.join(self.preview_path(key), 'preview.json'), {})
            except (ValueError, OSError):
                unreadable += 1; continue
            if not value or self.review_digest(value) != value.get('confirmationSha256'):
                unreadable += 1; continue
            rows.append({'previewId': key, 'action': value.get('action', 'install'),
                         'names': [item.get('name', '') for item in value.get('items', [])], 'createdAt': value.get('createdAt', 0)})
        return rows, unreadable

    def review_existing(self, name, action='enable', restored=None):
        if not self.builtin.valid_name(name) or action not in ('enable', 'rollback', 'restored'):
            raise ValueError('无效插件操作请求')
        if action == 'restored':
            directory = restored['directory']
        elif action == 'rollback':
            previous = self.history_info(name)
            if not previous:
                raise ValueError('没有可回退的上一版')
            directory = os.path.join(self.history_path(name), 'package')
        else:
            directory = self.g['resolve_plugin_dir'](name)
        if not directory:
            raise ValueError('找不到插件实体，请重新导入：' + name)
        current = self.g['dependencies']().current(directory)
        item = self.metadata(directory)
        self.require_compatible(self.g['plugin_package'](directory), report=item)
        target = self.target_snapshot(name)
        try:
            snapshot = self.g['dependencies']().inspect(directory)
            item['dependencyState'] = snapshot.get('state', 'legacy-unknown')
        except (ValueError, OSError):
            item['dependencyState'] = 'modified-original'
        resolved = [{'name': row['name'], 'version': row['version']} for row in current['nodes'].values()]
        item.update(resolvedDependencies=resolved[:50], dependencyCount=len(resolved), missingDependencies=current['missing'])
        self.prune_previews(); key = uuid.uuid4().hex; path = self.preview_path(key); os.makedirs(path)
        source = self.read(self.g['local'](self.g['SOURCES']), {}).get(name, '')
        preview = {'previewId': key, 'action': action, 'name': name, 'items': [item], 'sha256': current['sha256'],
                   'candidateFingerprint': self.candidate_fingerprint(directory),
                   'source': self.g['dependencies']().source_label(source), 'createdAt': int(time.time()), 'targetSnapshots': {name: target}}
        if restored is not None:
            preview.update(restoredGroup=restored['group'],restoredNode=restored['node'],source='恢复隔离区')
            item['existingConflict'] = bool(self.g['resolve_plugin_dir'](name))
        preview['confirmationSha256'] = self.review_digest(preview)
        self.write(os.path.join(path, 'preview.json'), preview)
        self.g['result']('ok', '插件和依赖已准备，正在启用', preview=preview)
        return 0

    def restored_directory(self, group, node):
        if str(uuid.UUID(group)) != group or not re.fullmatch('package-[a-f0-9]{20}', node):
            raise ValueError('隔离插件标识无效')
        root = self.local('/root/dsha-native-plugin-reviews')
        directory = os.path.join(root, group, 'packages', node)
        if os.path.commonpath([os.path.realpath(root), os.path.realpath(directory)]) != os.path.realpath(root):
            raise ValueError('隔离插件路径越界')
        return directory

    def review_restored(self, group, node):
        directory = self.restored_directory(group, node)
        package = self.g['plugin_package'](directory)
        if package['name'] in self.builtin.OFFICIAL_BUNDLES or package['name'] in self.builtin.builtin_names():
            raise ValueError('内置插件由签名应用维护，不能从恢复原件覆盖')
        return self.review_existing(package['name'], 'restored', {'group':group,'node':node,'directory':directory})

    @staticmethod
    def review_digest(preview):
        content = {key: value for key, value in preview.items() if key != 'confirmationSha256'}
        return hashlib.sha256(json.dumps(content, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()

    def discard_preview(self, key):
        path = self.preview_path(key)
        if os.path.isdir(path):
            shutil.rmtree(path)
        self.g['result']('ok', '已取消安装并清理临时包')
        return 0

    def install_preview(self, key, confirmation):
        path = self.preview_path(key)
        preview = self.read(os.path.join(path, 'preview.json'), {})
        if not preview:
            raise ValueError('预览记录不可读取或已变化，原件已保留')
        if not re.fullmatch('[a-f0-9]{64}', confirmation) or preview.get('confirmationSha256') != confirmation or self.review_digest(preview) != confirmation:
            raise ValueError('插件准备内容已变化，请重新解析')
        targets = preview.get('targetSnapshots')
        if not isinstance(targets, dict):
            raise self.policy_error('PLUGIN_SOURCE_CHANGED', '插件来源或版本在准备后变化，请重新检查')
        if preview.get('action') in ('enable', 'rollback', 'restored'):
            name = preview['name']
            directory = self.restored_directory(preview['restoredGroup'],preview['restoredNode']) if preview['action']=='restored' else os.path.join(self.history_path(name), 'package') if preview['action'] == 'rollback' else self.g['resolve_plugin_dir'](name)
            if not directory or self.g['dependencies']().current(directory)['sha256'] != preview['sha256']:
                raise ValueError('插件或依赖在准备后发生变化，请重新检查')
            if preview['items'][0].get('missingDependencies'):
                raise ValueError('插件缺少运行依赖，原件保持隔离，请先修复依赖')
            if preview['action'] == 'restored':
                if self.g['resolve_plugin_dir'](name):
                    raise ValueError('已存在同名插件，当前版本保持不变，请先处理冲突')
                with self.builtin.operation_lock(self.g['check_cancel']), self.g['transactions']().workspace() as work, self.g['committing']('正在启用恢复的插件…'):
                    self.pending_guard()
                    self.require_target(targets.get(name))
                    self.require_candidate(directory, preview['items'][0], preview.get('candidateFingerprint'))
                    if self.g['resolve_plugin_dir'](name):
                        raise self.policy_error('PLUGIN_SOURCE_CHANGED', '插件来源或版本在准备后变化，请重新检查', 'commit')
                    doc = self.builtin.read_manifest()
                    if doc is None:
                        raise ValueError('请先准备正常 Web 配置，再启用恢复的插件')
                    doc.setdefault('dependencies', {})[name] = 'link:' + directory
                    bundles = doc.setdefault('dsh', {}).setdefault('profile', {}).setdefault('bundles', [])
                    if name not in bundles: bundles.append(name)
                    doc['dsh']['profile']['patchReload'] = 'startup'
                    sources = self.read(self.local(self.g['SOURCES']), {}); sources[name] = 'restored:' + preview['restoredGroup']
                    tx = self.g['transactions'](); plan = tx.prepare(work,name,directory,doc,sources,None,link_target=directory,remove_marker=True)
                    link = os.path.join(self.local(self.builtin.NODE_MODULES),name)
                    try:
                        if os.path.lexists(link): raise ValueError('插件目标在准备后出现，当前内容未修改')
                        self.queue_activation(name,preview['sha256'],preview['items'][0]['version'],transaction=(tx,work,plan))
                        os.makedirs(os.path.dirname(link),exist_ok=True); os.symlink(directory,link,target_is_directory=True)
                        tx.apply_file(work,'marker',plan);tx.apply_file(work,'sources',plan);tx.apply_file(work,'manifest',plan);tx.mark(work,'committed')
                    except Exception:
                        tx.recover(work);raise
                self.g['result']('ok','恢复的插件已启用，原隔离副本保留；重启 Web 后确认加载', **self.committed('enable'))
            elif preview['action'] == 'rollback':
                self.rollback(name, preview['items'][0]['version'], prepared=True, expected_target=targets.get(name), expected_item=preview['items'][0], fingerprint=preview.get('candidateFingerprint'))
            else:
                with self.builtin.operation_lock(self.g['check_cancel']), self.g['committing']('正在启用插件…'):
                    self.pending_guard()
                    self.require_target(targets.get(name))
                    directory = self.g['resolve_plugin_dir'](name)
                    self.require_candidate(directory, preview['items'][0], preview.get('candidateFingerprint'))
                    self.queue_activation(name, preview['sha256'], preview['items'][0]['version'])
                    if self.builtin.enable_plugin(name):
                        raise ValueError('插件启用未完成，原件已保留')
                self.g['result']('ok', '插件已启用；重启 Web 后加载', **self.committed('enable'))
            shutil.rmtree(path)
            return 0
        if set(os.listdir(path)) - {'archive', 'contents', 'preview.json'}:
            raise ValueError('预览目录存在未确认文件，已保留原件')
        archive = os.path.join(path, 'archive')
        if not os.path.isfile(archive) or self.digest(archive) != preview.get('sha256'):
            raise ValueError('暂存包已变化，请重新解析链接')
        if preview.get('updateFor'):
            directory = self.g['resolve_plugin_dir'](preview['updateFor'])
            current = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
            if current.get('version') != preview.get('fromVersion'):
                raise self.policy_error('PLUGIN_TARGET_VERSION_CHANGED', '插件来源或版本在准备后变化，请重新检查')
        if self.g['dependencies']().tree(os.path.join(path, 'contents'), self.g['check_cancel'])[0] != preview.get('contentsSha256'):
            raise ValueError('准备内容已变化，请重新解析')
        roots = preview.get('reviewedRoots', [])
        if not roots or len(roots) != len(preview.get('items', [])) or len(roots) > 30:
            raise ValueError('安装预览缺少冻结依赖，请重新解析')
        installed = []
        for root, item in zip(roots, preview['items']):
            target = self.g['safe_target'](path, root['path'])
            self.require_target(targets.get(item['name']), 'preflight')
            self.require_candidate(target, item, root.get('fingerprint'), 'preflight')
            if self.digest(os.path.join(target, '.dsha-dependencies.json')) != root['snapshotSha256']:
                raise ValueError('插件依赖确认对象已变化')
            self.g['dependencies']().inspect(target)
        for root, item in zip(roots, preview['items']):
            target = self.g['safe_target'](path, root['path'])
            name = self.g['plugin_package'](target)['name']
            expected = preview.get('fromVersion') if name == preview.get('updateFor') else None
            try:
                installed.append(self.g['register_plugin'](target, preview.get('source', ''), expected_version=expected,
                                 expected_target=targets.get(name), expected_item=item, fingerprint=root.get('fingerprint'),
                                 related_targets={key: value for key, value in targets.items() if key not in installed and key != name}))
            except self.g['PluginPolicyError'] as error:
                if not installed: raise
                self.g['result']('partial', str(error), installed=installed, errorCode=error.code,
                                 application='failed', stage=error.stage, exitUnknown=False, executionUnknown=False)
                return 1
        shutil.rmtree(path)
        self.g['result']('ok', '插件已安装并启用；启动 Web 后即可使用', installed=installed, **self.committed('install'))
        return 0

    def activation_state(self):
        path = self.path('plugin-activations.json')
        value = self.read(path, {'format': 1, 'entries': {}})
        if value.get('format') != 1 or not isinstance(value.get('entries'), dict) or len(value['entries']) > 256:
            raise ValueError('插件加载状态记录无效，原记录已保留')
        if any(not self.builtin.valid_name(name) or not isinstance(row, dict) for name, row in value['entries'].items()):
            raise ValueError('插件加载状态记录无效，原记录已保留')
        return value

    def queue_activation(self, name, fingerprint, version, transaction=None, status='queued'):
        value = self.activation_state()
        if name not in value['entries'] and len(value['entries']) >= 256:
            raise ValueError('插件加载状态记录已达上限，原记录已保留')
        value['entries'][name] = {'status': status, 'fingerprint': fingerprint, 'version': version, 'confirmedAt': int(time.time())}
        # 在 bundles 启用之前落盘；被强杀后未确认的启用不会反复重试。
        if transaction is None:
            self.write(self.path('plugin-activations.json'), value)
        else:
            tx, work, plan = transaction
            tx.activation(work, plan, value)

    def loading(self, action, startup):
        if str(uuid.UUID(startup)) != startup or action not in ('begin', 'complete', 'failed'):
            raise ValueError('插件启动记录标识无效')
        failures, unattributed = [], []
        with self.builtin.operation_lock(self.g['check_cancel']):
            value = self.activation_state()
            doc = self.builtin.read_manifest() or {}
            enabled = set(doc.get('dsh', {}).get('profile', {}).get('bundles', []))
            def failed(name, entry, reason, status='failed', missing=None, message=''):
                if self.builtin.disable_plugin(name):
                    raise ValueError('无法保留插件停用状态，请先完成恢复：' + name)
                for field in ('evidenceReason', 'contentReason', 'attribution', 'observedAt', 'loadedAt'):
                    entry.pop(field, None)
                entry.update(status=status, reason=reason)
                if missing is not None: entry['missing'] = missing[:50]
                if message: entry['message'] = message[:400]
                failures.append({'name': name, 'reason': reason, 'missing': (missing or [])[:50]})
            try:
                for name, entry in value['entries'].items():
                    self.g['check_cancel']()
                    status = entry.get('status')
                    if action == 'begin' and status in ('queued', 'attempted', 'unknown'):
                        if name not in enabled:
                            entry['status'] = 'disabled'; continue
                        directory = self.g['resolve_plugin_dir'](name)
                        if not directory:
                            failed(name, entry, 'PLUGIN_ENTITY_MISSING', 'changed'); continue
                        try:
                            package = self.g['plugin_package'](directory)
                            if package['name'] != name:
                                failed(name, entry, 'PLUGIN_IDENTITY_CHANGED', message='插件名称与启用记录不一致'); continue
                            current = self.g['dependencies']().current(directory)
                        except (ValueError, OSError) as error:
                            failed(name, entry, 'PLUGIN_METADATA_OR_DEPENDENCY_INVALID', message=str(error)); continue
                        if current['missing']:
                            failed(name, entry, 'PLUGIN_DEPENDENCY_MISSING', missing=current['missing']); continue
                        fingerprint = current['sha256']
                        if entry.get('startup') == startup and fingerprint != entry.get('fingerprint'):
                            failed(name, entry, 'CONTENT_CHANGED_DURING_LOAD', 'changed'); continue
                        edited = bool(entry.get('fingerprint') and fingerprint != entry['fingerprint'])
                        # 同一次启动的未归因结果不因兼容重试变回已核验；真正的新启动才重新排入尝试。
                        if status == 'unknown' and entry.get('startup') == startup:
                            continue
                        for field in ('reason', 'evidenceReason', 'contentReason', 'attribution', 'observedAt', 'loadedAt'):
                            entry.pop(field, None)
                        entry.update(status='attempted', startup=startup, fingerprint=fingerprint,
                                     version=package['version'], locallyEdited=edited)
                    elif entry.get('startup') == startup and status == 'attempted':
                        # A5 尚未提供可信启动身份与部署内容绑定。浏览器 ready 不是所有 entry active，
                        # 整轮失败也不是每个 attempted 插件失败；sidecar 的 HOST 字符串本身不能授权停用。
                        reason = 'STARTUP_FAILURE_UNATTRIBUTED' if action == 'failed' else 'BROWSER_READY_UNVERIFIED'
                        entry.update(status='unknown', reason=reason, evidenceReason='NO_HOST_BINDING',
                                     contentReason='CONTENT_UNVERIFIED', attribution='unattributed',
                                     observedAt=int(time.time() * 1000))
                        entry.pop('loadedAt', None)
                        unattributed.append({'name': name, 'reason': reason, 'evidenceReason': 'NO_HOST_BINDING',
                                             'contentReason': 'CONTENT_UNVERIFIED'})
            finally:
                # Even a later plugin/disable failure must not discard earlier
                # receipts. Failure to persist still blocks this Web launch.
                self.write(self.path('plugin-activations.json'), value)
        self.g['result']('ok', '插件加载状态已更新' + ('；部分插件待修复：' + '、'.join(row['name'] for row in failures) if failures else ''),
                         failedPlugins=failures, unattributedPlugins=unattributed,
                         loadOutcome='unknown' if unattributed else 'recorded', stage='load-evidence')
        return 0

    def source_command(self, name):
        sources = self.read(self.local(self.g['SOURCES']), {})
        directory = self.g['resolve_plugin_dir'](name)
        pkg = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
        source = sources.get(name, '') or self.g['repository_url'](pkg)
        return self.command_from_source(source)

    def can_check_updates(self, source):
        try:
            return shlex.split(self.command_from_source(source))[0] in ('npm','github','release')
        except ValueError:
            return False

    def command_from_source(self, source):
        if not isinstance(source,str): raise ValueError('插件来源格式无效')
        if source.startswith('npm:'):
            match = re.fullmatch(r'((?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*)(?:@[^/]+)?', source[4:])
            if not match:
                raise ValueError('npm 来源无法识别，请重新导入')
            return 'npm ' + shlex.quote(match[1] + '@latest')
        uri = urllib.parse.urlsplit(source)
        if uri.hostname in ('registry.npmjs.org', 'registry.npmmirror.com') and '/-/' in uri.path:
            package = urllib.parse.unquote(uri.path.split('/-/')[0]).strip('/')
            if self.builtin.valid_name(package):
                return 'npm ' + shlex.quote(package + '@latest')
        pieces = uri.path.strip('/').split('/')
        if uri.hostname in ('github.com', 'www.github.com') and len(pieces) >= 2:
            owner, repository = pieces[:2]
            if len(pieces) > 2 and pieces[2] == 'releases':
                return 'release ' + shlex.join([owner, repository, 'latest'])
            if len(pieces) > 3 and pieces[2] == 'tree':
                return 'github ' + shlex.join([owner, repository, '/'.join(pieces[3:])])
            if len(pieces) > 2 and pieces[2] == 'archive':
                return 'download ' + shlex.quote(source)
            return 'github ' + shlex.join([owner, repository])
        if uri.scheme == 'https' and uri.hostname:
            return 'download ' + shlex.quote(source)
        raise ValueError('此插件没有可检查的来源，请从作者页面取得新包后导入')

    def remote_json(self, url):
        self.g['progress']('metadata', '正在读取版本信息…')
        with self.g['open_url'](url) as response:
            data = response.read(4 * 1024 * 1024 + 1)
        if len(data) > 4 * 1024 * 1024:
            raise ValueError('版本元数据过大')
        self.g['check_cancel']()
        return json.loads(data)

    def update_metadata(self, name, exact_version=''):
        command = self.source_command(name)
        parts = shlex.split(command)
        if exact_version and parts[0] != 'npm':
            raise self.policy_error('PLUGIN_UPDATE_SOURCE_UNSUPPORTED', '精确版本更新仅适用于 npm 来源')
        if parts[0] == 'npm':
            package = parts[1].removesuffix('@latest')
            if package != name:
                raise self.policy_error('PLUGIN_SOURCE_CHANGED', '插件来源或版本在准备后变化，请重新检查')
            pkg = self.remote_json('https://registry.npmjs.org/' + urllib.parse.quote(package, safe='') + '/' + urllib.parse.quote(exact_version or 'latest', safe=''))
            if pkg.get('name') != name:
                raise ValueError('来源返回的包名不一致')
            if exact_version and pkg.get('version') != exact_version:
                raise self.policy_error('PLUGIN_TARGET_VERSION_CHANGED', '下载包与指定的插件版本不一致')
            latest = self.package_metadata(pkg)
            self.require_compatible(pkg, report=latest)
            # 固定到已检查的版本；安装预览再次核对包名、版本与兼容声明。
            request = {'command': 'npm ' + shlex.quote(package + '@' + latest['version']),
                       'name': name, 'version': latest['version']}
            return latest, request
        if parts[0] == 'release':
            owner, repo = parts[1:3]
            release = self.remote_json('https://api.github.com/repos/%s/%s/releases/latest' % (owner, repo))
            version = str(release.get('tag_name', '')).removeprefix('v')
            if compare_versions(version, version) is None:
                raise ValueError('Release 标签不是版本号，请打开作者页面核对更新')
            archives = [a for a in release.get('assets', []) if re.search(r'\.(zip|tar|tar\.gz|tgz)$', a.get('name', ''), re.I)]
            if len(archives) != 1:
                raise ValueError('Release 未提供唯一插件归档，请从作者页面选择新包')
            asset = archives[0]
            request = {'command': 'download ' + shlex.quote(asset['browser_download_url']), 'name': name}
            # tag 可能不同于 package.version；暂作发现依据，预览时必须再次比较实际包版本。
            digest = asset.get('digest') or ''
            if re.fullmatch('sha256:[a-fA-F0-9]{64}', digest):
                request['sha256'] = digest[7:]
            return {'version': version, 'compatibilityMessage': '以下载后的插件声明为准'}, request
        if parts[0] == 'github':
            owner, repo = parts[1:3]
            revision, subdir = self.g['github_revision'](owner, repo, parts[3] if len(parts) == 4 else '')
            path = (subdir + '/' if subdir else '') + 'package.json'
            doc = self.remote_json('https://api.github.com/repos/%s/%s/contents/%s?ref=%s' %
                                   (owner, repo, urllib.parse.quote(path, safe='/'), urllib.parse.quote(revision, safe='')))
            if doc.get('encoding') != 'base64' or doc.get('size', 0) > 1024 * 1024:
                raise ValueError('仓库未提供可检查的插件版本，请选择具体插件目录')
            pkg = json.loads(base64.b64decode(doc['content']))
            if pkg.get('name') != name or not (pkg.get('dsh') or {}).get('bundle'):
                raise ValueError('仓库目录不是此插件，请使用具体插件目录链接')
            latest = self.package_metadata(pkg)
            self.require_compatible(pkg, report=latest)
            pinned = 'github ' + shlex.join([owner, repo, revision + ('/' + subdir if subdir else '')])
            source = 'https://github.com/%s/%s' % (owner, repo) + ('/tree/' + parts[3] if len(parts) == 4 else '')
            return latest, {'command': pinned, 'name': name, 'version': latest['version'], 'source': source}
        raise ValueError('此来源是固定归档，没有版本查询接口；请从作者页面取得新包后导入')

    def prepare_update(self, name, exact_version=''):
        if not self.builtin.valid_name(name):
            raise ValueError('无效插件名')
        target = self.target_snapshot(name)
        if exact_version:
            if semver_request('exact', exact_version, '') is not True or compare_versions(exact_version, target['version']) not in (1,):
                raise self.policy_error('PLUGIN_UPDATE_VERSION_INVALID', '更新目标必须是更高的精确版本')
            latest, request = self.update_metadata(name, exact_version)
            state = {'installedVersion': target['version'], 'request': request, 'targetSnapshot': target}
        else:
            state = self.read(self.path('plugin-updates.json'), {}).get(name, {})
            if not state.get('available') or not state.get('request') or not state.get('targetSnapshot') or time.time() - state.get('checkedAt', 0) > 3600:
                self.check_updates(name, emit=False)
                state = self.read(self.path('plugin-updates.json'), {}).get(name, {})
            if not state.get('available') or not state.get('request'):
                raise self.policy_error(state.get('errorCode') or 'PLUGIN_UPDATE_VERSION_INVALID',
                                        state.get('message') or '更新目标必须是更高的精确版本')
            self.require_target(state.get('targetSnapshot'), 'preflight')
            target = state['targetSnapshot']
        self.require_target(target, 'preflight')
        request = dict(state['request'], updateFor=name, fromVersion=state['installedVersion'], targetSnapshot=target)
        preview = self.inspect(request, False)
        actual = preview['items'][0]['version']
        comparison = compare_versions(actual, state['installedVersion'])
        if comparison != 1 or exact_version and actual != exact_version:
            # inspect 已成功闭合；只清本次编号预览，原下载归档与其它候选不受影响。
            preview_directory = self.preview_path(preview['previewId'])
            shutil.rmtree(preview_directory)
            raise self.policy_error('PLUGIN_UPDATE_VERSION_INVALID', '更新目标必须是更高的精确版本')
        self.g['result']('ok', '更新包已解析，正在安装并启用', preview=preview, stage='prepared')
        return 0

    def check_updates(self, name='', emit=True):
        doc = self.builtin.read_manifest() or {}
        candidates = [name] if name else list(doc.get('dependencies', {}))
        states = self.read(self.path('plugin-updates.json'), {})
        targets = []
        for item in candidates:
            self.g['check_cancel']()
            if item in self.builtin.OFFICIAL_BUNDLES or item in self.builtin.builtin_names():
                continue
            if not self.builtin.valid_name(item):
                continue
            directory = self.g['resolve_plugin_dir'](item)
            runtime = self.builtin.runtime_bundle_dir(item)
            if runtime and directory and os.path.realpath(runtime) == os.path.realpath(directory):
                continue
            pkg = self.read(os.path.join(directory, 'package.json'), {}) if directory else {}
            if not (pkg.get('dsh') or {}).get('bundle'):
                continue
            targets.append((item, str(pkg.get('version', ''))))

        # 仅远端只读查询并行；状态提交仍在原数据锁内，失败不覆盖其它插件结果。
        self.g['network']()
        def inspect(target):
            item, installed = target
            self.g['check_cancel']()
            status = {'name': item, 'installedVersion': installed, 'checkedAt': int(time.time()), 'available': False}
            try:
                target = self.target_snapshot(item)
                latest, request = self.update_metadata(item)
                self.require_target(target, 'check')
                comparison = compare_versions(latest['version'], status['installedVersion'])
                status.update(latestVersion=latest['version'], request=request,
                              available=comparison is not None and comparison > 0,
                              compatibility=latest['compatibilityMessage'], source=self.source_command(item), targetSnapshot=target)
                status['message'] = '有新版本' if status['available'] else '来源未提供更高版本' if comparison is not None else '版本格式不同，请手动核对'
            except self.g['PluginCancelled']:
                raise
            except Exception as error:
                status['message'] = str(error)
                if isinstance(error, self.g['PluginPolicyError']): status['errorCode'] = error.code
            return status

        completed = {}
        self.g['progress']('metadata', '正在并行检查插件版本…')
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
            futures = {pool.submit(inspect, target): target[0] for target in targets}
            try:
                for future in concurrent.futures.as_completed(futures):
                    self.g['check_cancel']()
                    status = future.result()
                    completed[status['name']] = status
                    self.g['progress']('metadata', '检查版本 %d/%d：%s' % (len(completed), len(targets), status['name']))
            finally:
                for future in futures:
                    future.cancel()
        checked = [completed[item] for item, _ in targets]
        with self.builtin.operation_lock(self.g['check_cancel']), self.g['committing']('正在保存版本检查结果…'):
            self.pending_guard()
            for checked_item in checked:
                if checked_item.get('available'):
                    try: self.require_target(checked_item.get('targetSnapshot'), 'check')
                    except self.g['PluginPolicyError'] as error:
                        checked_item.update(available=False, errorCode=error.code, message=str(error))
            latest_states = self.read(self.path('plugin-updates.json'), {})
            latest_states.update({item['name']: item for item in checked})
            self.write(self.path('plugin-updates.json'), latest_states)
        updates = sum(1 for item in checked if item['available'])
        if emit:
            self.g['result']('ok', '已检查 %d 个第三方插件，%d 个可更新；详情见插件卡片' % (len(checked), updates), updates=checked)
        return 0

    def rollback(self, name, expected='', prepared=False, expected_target=None, expected_item=None, fingerprint=None):
        if not prepared:
            return self.review_existing(name, 'rollback')
        if name in self.builtin.OFFICIAL_BUNDLES or name in self.builtin.builtin_names():
            raise ValueError('内置插件请通过应用更新维护')
        previous = self.history_info(name)
        if not previous:
            raise ValueError('没有可回退的上一版')
        if expected and previous.get('version') != expected:
            raise ValueError('上一版已发生变化，请刷新后重新确认')
        self.g['register_plugin'](os.path.join(self.history_path(name), 'package'), previous.get('source', ''), restoring=True,
                                 expected_target=expected_target, expected_item=expected_item, fingerprint=fingerprint)
        self.g['result']('ok', '已回退 ' + name + ' 至 ' + str(previous.get('version', '')) + '；启用状态保留，重启 Web 生效', **self.committed('rollback'))
        return 0

    def safe_mode(self, action):
        path = self.path('plugin-safe-mode.json')
        with self.builtin.operation_lock(self.g['check_cancel']), self.g['committing']('正在切换插件安全模式…'):
            state = self.read(path, {'active': False, 'names': []})
            doc = self.builtin.read_manifest() or {}
            if action == 'on':
                names = [n for n in doc.get('dsh', {}).get('profile', {}).get('bundles', [])
                         if self.builtin.valid_name(n) and n not in self.builtin.OFFICIAL_BUNDLES and n not in self.builtin.builtin_names()]
                remembered = list(dict.fromkeys(state.get('names', []) + names))
                fingerprints = dict(state.get('fingerprints') or {})
                self.write(path, {'active': True, 'names': remembered, 'fingerprints': fingerprints})
                for name in names:
                    if self.builtin.disable_plugin(name):
                        raise ValueError('无法停用第三方插件：' + name + '；其他插件保持已停用，可重试安全启动')
                    marker = self.builtin.marker_path(name)
                    os.makedirs(os.path.dirname(marker), exist_ok=True)
                    with open(marker, 'w', encoding='utf-8') as out:
                        out.write('DSHA_SAFE_MODE\n')
                    try:
                        directory = self.g['resolve_plugin_dir'](name)
                        fingerprints[name] = self.g['dependencies']().current(directory)['sha256'] if directory else ''
                    except (ValueError, OSError):
                        fingerprints[name] = ''
                self.write(path, {'active': True, 'names': remembered, 'fingerprints': fingerprints})
                self.g['result']('ok', '安全模式已启用，第三方插件暂时停用，数据保留；可重启 Web', safeMode=True, count=len(remembered), **self.committed('safe-mode'))
                return 0
            if action == 'off':
                restored = []
                remaining = []
                blocked = []
                for name in state.get('names', []):
                    if not self.builtin.valid_name(name):
                        continue
                    marker = self.builtin.marker_path(name)
                    if not os.path.isfile(marker):
                        continue
                    with open(marker, encoding='utf-8') as stream:
                        held = stream.read(128) == 'DSHA_SAFE_MODE\n'
                    # 用户在安全模式期间手动禁用的插件不被批量恢复覆盖。
                    if held and self.g['resolve_plugin_dir'](name):
                        try:
                            current = self.g['dependencies']().current(self.g['resolve_plugin_dir'](name))['sha256']
                            if current != (state.get('fingerprints') or {}).get(name):
                                remaining.append(name); continue
                            self.require_compatible(self.g['plugin_package'](self.g['resolve_plugin_dir'](name)), stage='enable')
                            if self.builtin.enable_plugin(name): remaining.append(name)
                            else: restored.append(name)
                        except self.g['PluginPolicyError'] as error:
                            remaining.append(name); blocked.append({'name': name, 'errorCode': error.code, 'message': str(error)})
                        except (ValueError, OSError):
                            remaining.append(name)
                self.write(path, {'active': bool(remaining), 'names': remaining, 'fingerprints': state.get('fingerprints', {})})
                if blocked:
                    self.g['result']('partial', blocked[0]['message'], safeMode=bool(remaining), restored=restored,
                                     blockedPlugins=blocked, errorCode=blocked[0]['errorCode'], application='failed', stage='safe-mode')
                    return 1
                self.g['result']('partial' if remaining else 'ok', '已恢复 %d 个插件的启用状态；重启 Web 生效%s' %
                                 (len(restored), ('；仍需修复：' + '、'.join(remaining)) if remaining else ''), safeMode=bool(remaining),
                                 application='failed' if remaining else 'restart-required', stage='safe-mode',
                                 **({} if remaining else {'restartReason': 'no-hmr'}))
                return 1 if remaining else 0
            if action == 'status':
                self.g['result']('ok', '安全模式已启用' if state.get('active') else '正常启动模式', safeMode=bool(state.get('active')))
                return 0
            raise ValueError('安全模式只支持 on / off / status')

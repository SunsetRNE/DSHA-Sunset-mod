#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""rc1 分代保护；宿主状态与用户可导入数据分离，失败不启动有副作用的导入。"""
import argparse, hashlib, json, os, re, shutil, stat, tempfile, time, traceback, uuid
from pathlib import Path
from collections import namedtuple

# 验证与发布共用同一次读取的不可变投影；不携带可再次解释的原始账本正文。
LedgerEvidence = namedtuple('LedgerEvidence',
    'state reason generation source_sha startup_id expected expected_source', defaults=(None,) * 5)

# The deployed patch registry supplies the runtime version for new records.
# Existing migration generations keep their original version and settings ledger.
# Stable asset marker: settings.yaml.imported is a retryable input after a
# pending section result; it never authorizes a false committed receipt.
RESTORED_SETTINGS_IMPORTED_FOR_RETRY = True
# 协议回执版本（DSHA_RC1_MIGRATION= 的 wire 版本），与私有业务记录 version=2、
# v5 用户归档格式互相独立。旧 v2 业务记录只能作为历史事实读取。
RESULT_VERSION = 3
CURRENT_RECHECK_VERSION = '0.2.2-rc.5'
GENERATION_ID = re.compile(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}')
MAX_FILES = 100000
MAX_BYTES = 8 * 1024 * 1024 * 1024
SECTION_STATES = ('verified', 'pending', 'writing', 'unknown', 'conflict')
PROFILE_INPUT_NAMES = ('package.json', 'compatibility.json', 'pnpm-lock.yaml', 'pnpm-workspace.yaml', 'cordis.patch.yml')
V4_LOG_NAMES = ('session.v4.jsonl', 'session.v4.jsonl.zstd')
EMPTY_SHA256 = hashlib.sha256(b'').hexdigest()


class MigrationSourceError(ValueError):
    def __init__(self, reason, logical, actual, operation):
        super().__init__(reason)
        self.logical, self.actual, self.operation = str(logical), str(actual), operation


def require_regular(logical, actual, operation):
    node = os.stat(actual)
    if not stat.S_ISREG(node.st_mode):
        raise MigrationSourceError('MIGRATION_SOURCE_NOT_REGULAR', logical, actual, operation)
    return node


def failure_details(error, dsh, operation, logical=None, actual=None):
    logical = getattr(error, 'logical', logical)
    actual = getattr(error, 'actual', actual)
    try: path = Path(logical).relative_to(dsh).as_posix() if logical else None
    except ValueError: path = str(logical)
    return dict(operation=getattr(error, 'operation', operation), path=path,
                mapping=str(actual) if actual else None, errorType=type(error).__name__,
                # Kept only in the private migration record; never snapshot file contents.
                traceback=traceback.format_exc(limit=8)[-8192:])


def digest(path):
    result = hashlib.sha256()
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''): result.update(chunk)
    return result.hexdigest()


def write_json(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix='.migration-', dir=str(Path(path).parent))
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        if os.path.lexists(temp): os.unlink(temp)


def read_json(path):
    with open(path, encoding='utf-8') as stream: return json.load(stream)


_STARTUP_ID = None
WARNINGS_LIMIT = 32


def bounded_warnings(values):
    """协议回执严格有界：超过 32 条时保留 31 条 + 截断标记（总数仍为 32）。"""
    return values if len(values) <= WARNINGS_LIMIT else values[:WARNINGS_LIMIT - 1] + ['WARNINGS_TRUNCATED']


def emit(phase, status, **data):
    """唯一结构化结果通道：一次调用只输出一条 DSHA_RC1_MIGRATION 行。

    进度、checkpoint 修复等诊断只能进入本次结果的 warnings 或普通非协议日志，
    不能输出第二条协议回执（Java parser 会拒绝重复回执）。
    """
    payload = dict(resultVersion=RESULT_VERSION, phase=phase, status=status,
                   sourceObjectIdentity='unknown', **data)
    warnings = payload.get('warnings')
    if isinstance(warnings, list):
        payload['warnings'] = bounded_warnings(warnings)
    if _STARTUP_ID: payload.setdefault('startupId', _STARTUP_ID)
    payload.setdefault('verificationScope', 'none')
    payload.setdefault('contentCopyStatus', 'unknown')
    payload.setdefault('settingsImportStatus', 'unknown')
    payload.setdefault('executionState', 'UNKNOWN')
    print('DSHA_RC1_MIGRATION=' + json.dumps(payload, ensure_ascii=False))


def settings_sha256(doc):
    """迁移时点的 settings 输入摘要；无 settings 来源时返回 None。"""
    for row in doc.get('sources', []) + doc.get('snapshotInventory', []):
        if row.get('kind') == 'settings':
            value = row.get('sha256')
            if not isinstance(value, str) or not re.fullmatch('[a-f0-9]{64}', value): return None
            return value
    return None


def context_proof(generation, input_digest, settings_sha):
    """Java/Python/CJS 共用的 startupContext 业务摘要（不含观察时间）。"""
    value = {'generation': generation, 'inputDigest': input_digest, 'settingsSha256': settings_sha}
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def startup_context(generation, startup_id, input_digest, settings_sha, mode):
    return dict(version=RESULT_VERSION, startupId=startup_id, generation=generation,
                inputFingerprint=input_digest, settingsSha256=settings_sha, mode=mode,
                proofDigest=context_proof(generation, input_digest, settings_sha))


def reuse_checkpoint(folder):
    """读取 publish_reuse 写下的宿主小 checkpoint；缺失或损坏一律 None，不新建。"""
    path = folder / 'reuse.json'
    try:
        if not path.is_file() or path.is_symlink(): return None
        value = read_json(path)
    except (OSError, ValueError, UnicodeError):
        return None
    return value if isinstance(value, dict) else None


def trusted_expected_sections(names):
    """期望分节集合的结构校验；非法返回 None，不能当完成证据。"""
    if not isinstance(names, list): return None
    if not all(isinstance(name, str) for name in names): return None
    if len(set(names)) != len(names): return None
    return set(names)


def checkpoint_expected_sections(checkpoint, doc, sha):
    """checkpoint 只有带 provenance、同代次、同 settings 摘要时才是独立期望集合。

    旧版本曾把 ledger.sections 的当前键回填进 settingsSections；那种无 provenance 的
    集合只是账本自证，不能当完成证据。
    """
    if not isinstance(checkpoint, dict) or not isinstance(sha, str): return None
    if checkpoint.get('settingsSectionsSource') != 'ledger-expected': return None
    if checkpoint.get('generation') != doc.get('generation'): return None
    if checkpoint.get('settingsSha256') != sha: return None
    return trusted_expected_sections(checkpoint.get('settingsSections'))


def ledger_state(folder, doc, startup_id=None, checkpoint=None):
    """兼容只读调用：状态与发布证据均来自同一投影规则。"""
    evidence = ledger_evidence(folder, doc, checkpoint)
    return evidence.state, evidence.reason


def ledger_evidence(folder, doc, checkpoint=None):
    """一次读取并绑定 generation/sourceSha、期望集合、终态与写入启动身份。

    缺失或矛盾只返回 unknown，不给 publisher 可转存的集合；fallback 只使用已验证
    的同代同来源 checkpoint，不从现存 sections 猜期望集合。
    """
    sha = settings_sha256(doc)
    generation = doc.get('generation')
    if sha is None and not any(row.get('kind') == 'settings' for row in doc.get('sources', [])):
        return LedgerEvidence('not-required', None, generation, sha)
    path = folder / 'settings-result.json'
    if not path.is_file():
        return LedgerEvidence('unknown', 'SETTINGS_LEDGER_MISSING')
    try:
        result = read_json(path)
    except (OSError, ValueError, UnicodeError):
        return LedgerEvidence('unknown', 'SETTINGS_LEDGER_UNREADABLE')
    if not isinstance(result, dict):
        return LedgerEvidence('unknown', 'SETTINGS_LEDGER_FORMAT')
    if result.get('generation') != generation or result.get('sourceSha256') != sha:
        return LedgerEvidence('unknown', 'SETTINGS_LEDGER_IDENTITY')
    sections = result.get('sections')
    if not isinstance(sections, dict):
        return LedgerEvidence('unknown', 'SETTINGS_LEDGER_FORMAT')
    states = []
    for value in sections.values():
        state = value.get('status') if isinstance(value, dict) else None
        if state not in SECTION_STATES: return LedgerEvidence('unknown', 'SETTINGS_LEDGER_SECTION')
        states.append(state)
    overall = result.get('status')
    if 'unknown' in states or 'writing' in states or overall in ('unknown', 'writing'):
        return LedgerEvidence('unknown', 'SETTINGS_WRITE_OUTCOME_UNKNOWN')
    if 'expectedSections' in result:
        expected = trusted_expected_sections(result.get('expectedSections'))
        if expected is None: return LedgerEvidence('unknown', 'SETTINGS_LEDGER_SECTION')
        expected_source = 'ledger-expected'
    else:
        expected = checkpoint_expected_sections(checkpoint, doc, sha)
        if expected is None: return LedgerEvidence('unknown', 'SETTINGS_EXPECTED_SECTIONS_MISSING')
        expected_source = 'checkpoint'
    if set(sections) != expected:
        return LedgerEvidence('unknown', 'SETTINGS_SECTION_MISSING')
    state = 'verified' if overall == 'verified' and all(state == 'verified' for state in states) else 'pending'
    return LedgerEvidence(state, None, generation, sha, result.get('startupId'),
                          frozenset(expected), expected_source)


def import_status(state, scope, ledger_startup_id=None, startup_id=None):
    if state == 'not-required': return 'not-required'
    if state == 'verified':
        return 'verified-current' if startup_id and ledger_startup_id == startup_id else 'verified-historical'
    return state


def within(root, path):
    return os.path.commonpath((root, path)) == root


class Resolver:
    """批准根内逐链接解析。禁止 realpath 隐式跨 guest 外部挂载。"""
    def __init__(self, dsh, approved=()):
        self.roots = [os.path.abspath(dsh)] + [os.path.abspath(p) for p in approved]
    def resolve(self, path):
        path = os.path.abspath(path); links = []; visited = set()
        for _ in range(41):
            if not any(within(root, path) for root in self.roots): raise ValueError('LINK_OUTSIDE_APPROVED_ROOT')
            parts = Path(path).parts; cursor = Path(parts[0]); followed = False
            for index, part in enumerate(parts[1:], 1):
                cursor /= part
                if cursor.is_symlink():
                    key = str(cursor)
                    if key in visited: raise ValueError('LINK_LOOP')
                    visited.add(key); target = os.readlink(cursor)
                    links.append({'path':key, 'target':target})
                    # 历史 Android 公开路径使用本次显式映射，不接受任意 /data 别名。
                    if target.startswith('/storage/emulated/0/Documents/dshdata'):
                        target = '/sdcard/Documents/dshdata' + target[len('/storage/emulated/0/Documents/dshdata'):]
                    path = os.path.abspath(os.path.join(str(cursor.parent), target, *parts[index+1:]))
                    followed = True; break
            if not followed: return Path(path), links
        raise ValueError('LINK_LOOP')
    def files(self, path, skip=(), unlinked=False):
        count = 0; ancestors = set()
        def visit(logical):
            nonlocal count
            actual, links = self.resolve(logical)
            if unlinked and links: raise ValueError('RC5_RECHECK_LINK')
            node = os.lstat(actual)
            if stat.S_ISDIR(node.st_mode):
                identity = (node.st_dev, node.st_ino)
                if identity in ancestors: raise ValueError('DIRECTORY_LINK_LOOP')
                ancestors.add(identity)
                for child in sorted(os.listdir(actual)):
                    if child not in skip: yield from visit(Path(logical) / child)
                ancestors.remove(identity)
            elif stat.S_ISREG(node.st_mode):
                count += 1
                if count > MAX_FILES: raise ValueError('MIGRATION_FILE_LIMIT')
                yield Path(logical), actual, links
            else: raise ValueError('MIGRATION_SPECIAL_FILE')
        if os.path.lexists(path): yield from visit(path)


def quick(dsh, resolver):
    """普通启动只读取两个小配置和宿主恢复代次；不遍历会话。"""
    source = {}
    for name in ('settings.yaml', 'settings.yaml.imported'):
        if os.path.lexists(dsh / name):
            actual, _ = resolver.resolve(dsh / name)
            if require_regular(dsh / name, actual, 'settings-input').st_size > 4 * 1024 * 1024: raise ValueError('SETTINGS_SIZE_LIMIT')
            source[name] = digest(actual)
    restore = dsh / '.dsha-rc1-restore-generation'
    if restore.exists():
        actual, _ = resolver.resolve(restore)
        if require_regular(restore, actual, 'restore-generation').st_size > 4096: raise ValueError('RESTORE_GENERATION_FORMAT')
        source['restore'] = digest(actual)
    st = os.stat(dsh)
    return dict(dataRoot={'path':str(dsh), 'device':str(st.st_dev), 'inode':str(st.st_ino)}, inputs=source)


def same_input(old, current):
    if not isinstance(old,dict) or not isinstance(current,dict): return False
    if old.get('dataRoot') != current.get('dataRoot'): return False
    a, b = old.get('inputs', {}), current.get('inputs', {})
    if not isinstance(a,dict) or not isinstance(b,dict): return False
    if set(a) - {'settings.yaml','settings.yaml.imported','restore'} or set(b) - {'settings.yaml','settings.yaml.imported','restore'}: return False
    if any(not isinstance(value,str) or not re.fullmatch('[a-f0-9]{64}',value) for value in [*a.values(),*b.values()]): return False
    # 上游合法 settings→imported rename 是同一份输入，不是新恢复。
    # 两项同时存在时必须逐项相等；不能忽略 imported 的独立变化。
    if {'settings.yaml','settings.yaml.imported'} <= set(a) or {'settings.yaml','settings.yaml.imported'} <= set(b): return a == b
    normalize = lambda obj: {'settings':obj.get('settings.yaml', obj.get('settings.yaml.imported')), 'restore':obj.get('restore')}
    return normalize(a) == normalize(b)


def snapshot_row(folder, dsh, logical, actual, links, kind, ordinal):
    relative = logical.relative_to(dsh).as_posix()
    before = require_regular(logical, actual, 'snapshot'); destination = folder / 'snapshots' / str(ordinal)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with open(actual, 'rb') as source, open(destination, 'xb') as target:
        opened = os.fstat(source.fileno())
        if not stat.S_ISREG(opened.st_mode) or (before.st_dev, before.st_ino) != (opened.st_dev, opened.st_ino):
            raise MigrationSourceError('SOURCE_CHANGED_BEFORE_SNAPSHOT', logical, actual, 'snapshot')
        sha = hashlib.sha256(); total = 0
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            total += len(chunk)
            if total > MAX_BYTES: raise ValueError('MIGRATION_BYTE_LIMIT')
            target.write(chunk); sha.update(chunk)
        target.flush(); os.fsync(target.fileno())
    after = os.stat(actual)
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns): raise ValueError('SOURCE_CHANGED_DURING_SNAPSHOT')
    if digest(destination) != sha.hexdigest(): raise ValueError('SNAPSHOT_READBACK_FAILED')
    return dict(kind=kind, path=relative, size=total, sha256=sha.hexdigest(), snapshot=destination.relative_to(folder).as_posix(), links=links,
                mapping=str(actual), sourceIdentity=[str(before.st_dev),str(before.st_ino)], type='file')


def inventory(dsh, resolver, current_layout_only=False):
    inputs = [(dsh/name, 'settings', ()) for name in ('settings.yaml','settings.yaml.imported')]
    inputs += [(dsh/'profiles','profile', ('node_modules','.pnpm-store','.cache')), (dsh/'.agent-presets','preset', ('node_modules','.pnpm-store','.cache')), (dsh/'sessions','session', ())]
    entries = []; total = 0
    for source, kind, skip in inputs:
        for logical, actual, links in resolver.files(source, skip, unlinked=current_layout_only):
            if kind == 'profile' and logical.name not in PROFILE_INPUT_NAMES: continue
            before = os.lstat(actual) if current_layout_only else require_regular(logical, actual, 'inventory')
            if current_layout_only and (not stat.S_ISREG(before.st_mode) or before.st_nlink != 1):
                raise ValueError('RC5_RECHECK_LINK')
            total += before.st_size
            if len(entries) >= MAX_FILES: raise ValueError('MIGRATION_FILE_LIMIT')
            if total > MAX_BYTES: raise ValueError('MIGRATION_BYTE_LIMIT')
            # A read-only layout check classifies active files. A live profile or
            # V4 log can change without invalidating its independent old copy.
            sha = None if current_layout_only else digest(actual)
            if current_layout_only and kind == 'session' and logical.name == 'session.lock' and before.st_size == 0:
                sha = EMPTY_SHA256
            after = os.lstat(actual) if current_layout_only else os.stat(actual)
            identity = lambda node: [str(node.st_dev), str(node.st_ino), node.st_size, node.st_mtime_ns, node.st_ctime_ns]
            if identity(before) != identity(after): raise ValueError('SOURCE_CHANGED_DURING_INVENTORY')
            entries.append(dict(path=logical.relative_to(dsh).as_posix(), mapping=str(actual), kind=kind, links=links, size=before.st_size, sha256=sha, identity=identity(before)))
    fingerprint = hashlib.sha256(json.dumps(entries,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return entries, fingerprint


def reusable_rows(folder, doc, entries):
    expected = {entry['path']: entry for entry in entries}; reusable = {}
    for row in doc.get('snapshotInventory', []) + doc.get('sources', []):
        entry = expected.get(row.get('path'))
        if not entry or row.get('sha256') != entry['sha256'] or row.get('sourceIdentity') != entry['identity'][:2] or row.get('links') != entry['links']: continue
        name = row.get('snapshot', '')
        if not re.fullmatch(r'snapshots/[0-9]+(?:-[a-f0-9]{32})?',name): continue
        source = folder/name
        if source.is_file() and not source.is_symlink() and source.stat().st_size == entry['size'] and digest(source) == entry['sha256']:
            reusable[row['path']] = row
    return reusable


def snapshot_issue(folder, row, independent=False):
    name = row.get('snapshot') if isinstance(row, dict) else None
    if not isinstance(name, str) or not re.fullmatch(r'snapshots/[0-9]+(?:-[a-f0-9]{32})?', name):
        return 'SNAPSHOT_PATH_INVALID'
    snapshot = folder/name
    if snapshot.parent.is_symlink():
        return 'SNAPSHOT_MISSING'
    try:
        before = os.lstat(snapshot)
    except FileNotFoundError:
        return 'SNAPSHOT_MISSING'
    if not stat.S_ISREG(before.st_mode) or (independent and before.st_nlink != 1):
        return 'SNAPSHOT_MISSING'
    size = row.get('size')
    identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns, value.st_nlink)
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0) | getattr(os, 'O_BINARY', 0)
    with os.fdopen(os.open(snapshot, flags), 'rb') as stream:
        if (identity(before) != identity(os.fstat(stream.fileno())) or
                (isinstance(size, int) and before.st_size != size)):
            return 'SNAPSHOT_CHANGED'
        found = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            found.update(chunk)
        if (found.hexdigest() != row.get('sha256') or identity(before) != identity(os.fstat(stream.fileno()))
                or identity(before) != identity(os.lstat(snapshot))):
            return 'SNAPSHOT_CHANGED'
    return None


def absent(path):
    try:
        os.lstat(path)
    except FileNotFoundError:
        return True
    return False


def current_only_sources(dsh, rows):
    if not isinstance(rows, list):
        return False
    seen = set()
    v4 = {}
    leases = set()
    for row in rows:
        path = row.get('path') if isinstance(row, dict) else None
        if (not isinstance(path, str) or '\\' in path or path in seen
                or row.get('links') != [] or row.get('mapping') != str(dsh/path)):
            return False
        parts = path.split('/')
        if len(parts) < 2 or any(part in ('', '.', '..') for part in parts):
            return False
        seen.add(path)
        if row.get('kind') == 'profile' and parts[0] == 'profiles' and parts[-1] in PROFILE_INPUT_NAMES:
            continue
        if row.get('kind') != 'session' or parts[0] != 'sessions':
            return False
        parent = tuple(parts[:-1])
        if parts[-1] in V4_LOG_NAMES:
            v4[parent] = v4.get(parent, 0) + 1
            continue
        # rc5 POSIX session lease: an empty regular file in the upstream
        # project/session layout, beside exactly one current log, owns no data.
        if (parts[-1] != 'session.lock' or len(parts) != 4
                or not (parts[1] == '_no-cwd' or re.fullmatch(r'--[A-Za-z0-9._~\-]+--', parts[1]))
                or not re.fullmatch(r'(?:[A-Za-z0-9._-]|~[0-9A-F]{4})+', parts[2])
                or type(row.get('size')) is not int or row['size'] != 0 or row.get('sha256') != EMPTY_SHA256):
            return False
        leases.add(parent)
    return all(v4.get(parent) == 1 for parent in leases)


def independent_snapshots(folder, rows):
    snapshots = set()
    for row in rows:
        snapshot = row.get('snapshot')
        if (not isinstance(snapshot, str) or snapshot in snapshots or type(row.get('size')) is not int
                or snapshot_issue(folder, row, independent=True)):
            return False
        snapshots.add(snapshot)
    return True


def committed_receipt(receipt, generation):
    return (isinstance(receipt, dict) and receipt.get('version') == 2
            and receipt.get('generation') == generation and receipt.get('status') == 'committed'
            and all(receipt.get(key) is True for key in ('sourcePreserved', 'settingsImported', 'protectionComplete')))


def generation_record(folder, name):
    path = folder/name
    try:
        if path.is_file() and not path.is_symlink():
            return read_json(path)
    except (OSError, ValueError, UnicodeError):
        pass
    return None


def native_restore_changed(dsh, current, stamp):
    restore_sha = stamp['inputs'].get('restore')
    old_inputs = current.get('inputs')
    old_root = current.get('dataRoot')
    if (not isinstance(old_inputs, dict) or not isinstance(old_root, dict) or not restore_sha
            or restore_sha == old_inputs.get('restore') or old_root == stamp['dataRoot']):
        return False
    path = dsh/'.dsha-rc1-restore-generation'
    try:
        if not stat.S_ISREG(os.lstat(path).st_mode):
            return False
        flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0) | getattr(os, 'O_BINARY', 0)
        with os.fdopen(os.open(path, flags), 'rb') as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                return False
            marker = stream.read(37)
        return bool(GENERATION_ID.fullmatch(marker.decode('ascii')) and hashlib.sha256(marker).hexdigest() == restore_sha)
    except (OSError, UnicodeError):
        return False


def known_current_mirror(dsh, folder, generation):
    # Native restore retains the prior guest mirror locally. It is evidence only if
    # both copied records equal a fully committed, current-only host generation.
    mirror = dsh/'.dsha-rc1-migration'
    current_mirror = mirror/'prepare.json'
    if (mirror.is_symlink() or not absent(mirror/'legacy-agent-presets')
            or current_mirror.is_symlink() or not current_mirror.is_file()
            or digest(current_mirror) != digest(folder/'prepare.json')):
        return False
    old_prepare, old_receipt = folder/'legacy-prepare.json', folder/'legacy-receipt.json'
    public_receipt = mirror/'receipt.json'
    if all(absent(path) for path in (old_prepare, old_receipt, public_receipt)):
        return True
    if any(path.is_symlink() or not path.is_file() for path in (old_prepare, old_receipt, public_receipt)):
        return False
    old = read_json(old_prepare)
    prior = old.get('generation') if isinstance(old, dict) else None
    if not isinstance(prior, str) or prior == generation or not GENERATION_ID.fullmatch(prior):
        return False
    authority = folder.parent/prior
    if authority.is_symlink() or not authority.is_dir() or not absent(authority/'settings-result.json'):
        return False
    for copied, path in ((old_prepare, authority/'prepare.json'), (old_receipt, authority/'receipt.json')):
        if path.is_symlink() or not path.is_file() or digest(copied) != digest(path):
            return False
    receipt = read_json(old_receipt)
    rows = old.get('sources')
    if not (old.get('version') == 2 and old.get('dshVersion') == CURRENT_RECHECK_VERSION
            and old.get('dshHome') == str(dsh)
            and old.get('protectionComplete') is True and old.get('status') == 'prepared'
            and old.get('presets') == [] and old.get('snapshotInventory') == []
            and current_only_sources(dsh, rows)
            and digest(public_receipt) == digest(old_receipt)
            and committed_receipt(receipt, prior) and receipt.get('dshVersion') == CURRENT_RECHECK_VERSION
            and receipt.get('settingsStatus') == 'verified'):
        return False
    if receipt.get('sessionsStatus') == 'no-legacy-input':
        return True
    # R10 counted the retained empty lease as a legacy session. Accept only
    # that known record shape with its own independent prior copies verified.
    return (receipt.get('sessionsStatus') == 'preserved-awaiting-runtime-open'
            and receipt.get('sessionCompatibilityVerified') is False
            and any(row['path'].endswith('/session.lock') for row in rows)
            and independent_snapshots(authority, rows))


def verified_native_empty_reset(dsh, folder, prepared, stamp, proof):
    """The app proved its old reset journal. Bind it to the same imported inode and empty answer."""
    match = re.fullmatch(r'([0-9]{1,20}):([0-9]{1,20}):([0-9]{1,20}):([0-9]{1,20}):([a-f0-9-]{36})',
                         proof or '')
    if not match or not GENERATION_ID.fullmatch(match[5]):
        return False
    empty = hashlib.sha256(b'{}\n').hexdigest()
    inputs = stamp.get('inputs', {})
    if ('settings.yaml' in inputs or inputs.get('settings.yaml.imported') != empty
            or prepared.get('inputs', {}).get('settings.yaml') != empty
            or 'settings.yaml.imported' in prepared.get('inputs', {})
            or type(prepared.get('createdAt')) is not int
            or prepared['createdAt'] < int(match[4]) // 1_000_000_000):
        return False
    rows = prepared.get('sources')
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        return False
    sources = [row for row in rows if row.get('kind') == 'settings']
    if len(sources) != 1:
        return False
    source = sources[0]
    if (source.get('path') != 'settings.yaml' or source.get('sha256') != empty
            or source.get('size') != 3 or source.get('links') != [] or source.get('type') != 'file'
            or source.get('mapping') != str(dsh/'settings.yaml')
            or source.get('sourceIdentity') != [match[1], match[2]]):
        return False
    try:
        node = os.lstat(dsh/'settings.yaml.imported')
        if (not stat.S_ISREG(node.st_mode) or node.st_nlink != 1 or node.st_size != 3
                or [str(node.st_dev), str(node.st_ino), str(node.st_mtime_ns // 1_000_000_000)] != list(match.groups()[:3])):
            return False
        ledger_path = folder/'settings-result.json'
        ledger_meta = os.lstat(ledger_path)
        if not stat.S_ISREG(ledger_meta.st_mode) or ledger_meta.st_nlink != 1:
            return False
        ledger = read_json(ledger_path)
        return (isinstance(ledger, dict) and ledger.get('version') == 2
                and ledger.get('generation') == prepared.get('generation')
                and ledger.get('startupId') == prepared.get('startupId')
                and ledger.get('sourceSha256') == empty and ledger.get('status') == 'verified'
                and ledger.get('expectedSections') == [] and ledger.get('sections') == {})
    except (OSError, ValueError, UnicodeError):
        return False


def readonly_current_recheck(dsh, state, folder, current, prepared, stamp, resolver, active_version,
                             native_reset_proof=''):
    """Complete neither historical execution nor a settings write. Prove this boot needs neither."""
    if active_version != CURRENT_RECHECK_VERSION or prepared.get('dshVersion') != CURRENT_RECHECK_VERSION:
        return 'LEGACY_COMPLETION_RECEIPT_MISSING'
    visible_settings = 'settings.yaml' in stamp['inputs'] or 'settings.yaml.imported' in stamp['inputs']
    reset = visible_settings and verified_native_empty_reset(dsh, folder, prepared, stamp, native_reset_proof)
    if visible_settings and not reset:
        if ('settings.yaml' not in stamp['inputs']
                and stamp['inputs'].get('settings.yaml.imported') == hashlib.sha256(b'{}\n').hexdigest()):
            return 'RC5_NATIVE_RESET_PROOF_INVALID' if native_reset_proof else 'RC5_NATIVE_RESET_PROOF_MISSING'
        return 'RC5_RECHECK_LEGACY_INPUTS'
    generation = current['generation']
    if (folder.parent.is_symlink() or folder.is_symlink() or prepared.get('version') != 2
            or prepared.get('generation') != generation or current.get('dshHome') != str(dsh)
            or prepared.get('dshHome') != str(dsh) or not same_input(prepared, stamp)
            or prepared.get('presets') != [] or prepared.get('snapshotInventory') != []):
        return 'RC5_RECHECK_PREPARE_UNVERIFIED'
    rows = prepared.get('sources')
    current_rows = lambda items: [row for row in items if row.get('kind') != 'settings'] if reset else items
    if not current_only_sources(dsh, current_rows(rows)):
        return 'RC5_RECHECK_LEGACY_INPUTS'
    try:
        must_be_absent = ('receipt.json', 'reuse.json') if reset else ('settings-result.json', 'receipt.json', 'reuse.json')
        if not all(absent(folder/name) for name in must_be_absent):
            return 'RC5_RECHECK_WRITE_EVIDENCE'
        if any((dsh/name).is_symlink() for name in ('profiles', '.agent-presets', 'sessions', '.dsha-rc1-restore-generation')):
            return 'RC5_RECHECK_INPUT_CHANGED'
        pending = state/'pending.json'
        expected_pending_stamp = ({'dataRoot': prepared['dataRoot'], 'inputs': prepared['inputs']}
                                  if reset else stamp)
        if (pending.is_symlink() or not pending.is_file()
                or read_json(pending) != dict(generation=generation, inputDigest=prepared.get('inputDigest'),
                                              **expected_pending_stamp)):
            return 'RC5_RECHECK_PREPARE_UNVERIFIED'
        if not known_current_mirror(dsh, folder, generation):
            return 'RC5_RECHECK_LEGACY_RECORD'
        entries, _ = inventory(dsh, resolver, current_layout_only=True)
        if not current_only_sources(dsh, current_rows(entries)):
            return 'RC5_RECHECK_LEGACY_INPUTS'
        if not independent_snapshots(folder, rows):
            return 'RC5_RECHECK_SNAPSHOT_UNVERIFIED'
        if (quick(dsh, resolver) != stamp or runtime_version(dsh.parent) != active_version
                or not all(absent(folder/name) for name in must_be_absent)
                or (reset and not verified_native_empty_reset(dsh, folder, prepared, stamp, native_reset_proof))
                or not known_current_mirror(dsh, folder, generation)):
            return 'RC5_RECHECK_INPUT_CHANGED'
    except (OSError, ValueError, UnicodeError):
        return 'RC5_RECHECK_UNAVAILABLE'
    return None


def inert_directory(root, path):
    cursor = root
    for part in path.relative_to(root).parts:
        cursor /= part
        if os.path.lexists(cursor):
            node = os.lstat(cursor)
            if not stat.S_ISDIR(node.st_mode): raise ValueError('MIGRATION_CANDIDATE_PARENT_LINK_OR_TYPE')
        else: cursor.mkdir()



def state_path(root, state_root):
    # 单测默认位于临时 root 旁；生产必须由 Java 指定宿主私有绑定。
    return Path(state_root) if state_root else Path(root)/'.dsha-rc1-host-state'


def runtime_version(root):
    """Read the already deployed managed registry; never guess from an old user receipt."""
    registry = Path(root) / '.dsh' / 'runtime-patches.json'
    try:
        if not stat.S_ISDIR(os.lstat(registry.parent).st_mode):
            raise ValueError('MIGRATION_RUNTIME_REGISTRY_PARENT')
        before = os.lstat(registry)
        if not stat.S_ISREG(before.st_mode): raise ValueError('MIGRATION_RUNTIME_REGISTRY_TYPE')
        descriptor = os.open(registry, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
        with os.fdopen(descriptor, 'rb') as stream:
            node = os.fstat(stream.fileno())
            identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)
            if not stat.S_ISREG(node.st_mode) or identity(before) != identity(node) or node.st_size > 2 * 1024 * 1024:
                raise ValueError('MIGRATION_RUNTIME_REGISTRY_TYPE')
            payload = stream.read(2 * 1024 * 1024 + 1)
            if len(payload) > 2 * 1024 * 1024: raise ValueError('MIGRATION_RUNTIME_REGISTRY_LIMIT')
            if identity(node) != identity(os.fstat(stream.fileno())) or identity(node) != identity(os.lstat(registry)):
                raise ValueError('MIGRATION_RUNTIME_REGISTRY_CHANGED')
        registry = json.loads(payload.decode('utf-8'))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError('MIGRATION_RUNTIME_REGISTRY_UNAVAILABLE') from error
    version = registry.get('dshVersion') if isinstance(registry, dict) else None
    if (not isinstance(registry, dict) or registry.get('schema') != 1
            or not isinstance(version, str)
            or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version)):
        raise ValueError('MIGRATION_RUNTIME_REGISTRY_VERSION')
    return version


def host_state(root, state):
    """区分真正首次迁移与权威宿主状态缺失；后者只读对账，不新建代次。

    仅失败/未完成的代次（protectionComplete 非真、receipt 未 committed）允许按
    pending 代次继续；已有完成代次却缺 current.json 表示权威索引丢失。
    """
    if not state.is_dir():
        return 'absent'
    generations = state / 'generations'
    completed = False
    if generations.is_dir():
        try:
            folders = list(generations.iterdir())
        except OSError:
            return 'unreadable'
        if len(folders) > 256:
            return 'unreadable'
        for folder in folders:
            if not folder.is_dir(): continue
            for name in ('prepare.json', 'receipt.json'):
                path = folder / name
                if not path.is_file() or path.is_symlink(): continue
                try: value = read_json(path)
                except (OSError, ValueError, UnicodeError): return 'unreadable'
                if not isinstance(value, dict): continue
                if name == 'prepare.json' and value.get('protectionComplete') is True and value.get('status') == 'prepared':
                    completed = True
                if name == 'receipt.json' and value.get('status') == 'committed':
                    completed = True
    return 'missing-current' if completed else 'absent'


def prepare(root, state_root=None, startup_id='manual', approved=(), native_reset_proof=''):
    global _STARTUP_ID; _STARTUP_ID = startup_id
    dsh = Path(os.path.abspath(os.path.join(root, '.dsh'))); state = state_path(root, state_root)
    if not dsh.exists():
        emit('prepare', 'skipped', reason='DSH_MISSING', executionState='NOT_SENT')
        return 0
    if dsh.is_symlink(): raise ValueError('DSH_HOME_LINK_NOT_APPROVED')
    active_version = runtime_version(root)
    resolver = Resolver(dsh, approved); stamp = quick(dsh, resolver)
    state.mkdir(parents=True, exist_ok=True)
    if state.is_symlink(): raise ValueError('STATE_ROOT_LINK')
    current = None
    if (state/'current.json').is_symlink():
        emit('prepare', 'unknown', reason='HOST_STATE_INVALID', executionState='NOT_SENT')
        return 1
    if (state/'current.json').is_file():
        try: current = read_json(state/'current.json')
        except (OSError, ValueError, UnicodeError):
            emit('prepare', 'unknown', reason='HOST_STATE_UNREADABLE', executionState='NOT_SENT')
            return 1
        if not isinstance(current, dict) or current.get('version') != 2 or not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', str(current.get('generation',''))):
            emit('prepare', 'unknown', reason='HOST_STATE_INVALID', executionState='NOT_SENT')
            return 1
    if current is None:
        # current.json 缺失但仍有代次/pending 痕迹：权威索引丢失，不能当首次迁移重建。
        prior = host_state(root, state)
        if prior != 'absent':
            emit('prepare', 'unknown', reason='HOST_STATE_MISSING', executionState='NOT_SENT')
            return 1
    if current:
        same_generation = same_input(current, stamp)
        folder = state/'generations'/current['generation']
        if folder.parent.is_symlink() or folder.is_symlink():
            emit('prepare', 'unknown', reason='HOST_STATE_INVALID', executionState='NOT_SENT')
            return 1
        prepared = generation_record(folder, 'prepare.json')
        receipt = generation_record(folder, 'receipt.json')
        if not same_generation and not native_restore_changed(dsh, current, stamp):
            # A new settings fingerprint is insufficient to grant a new write
            # ticket while the prior generation's outcome is unresolved.
            if (not committed_receipt(receipt, current['generation']) or not isinstance(prepared, dict)
                    or not same_input(prepared, current)
                    or ledger_state(folder, prepared, checkpoint=reuse_checkpoint(folder))[0] not in ('verified', 'not-required')):
                current['startupId'] = startup_id
                current['startupContext'] = startup_context(current['generation'], startup_id, None, None, 'history-only')
                write_json(state/'current.json', current)
                emit('prepare', 'pending', reason='RC5_RECHECK_INPUT_CHANGED', generation=current['generation'],
                     verificationScope='historical', executionState='NOT_SENT', runtimeDshVersion=active_version,
                     continuation='旧代完成情况未确认，保留变化后的输入；不自动补导入')
                return 0
    if current and same_generation:
        if not isinstance(prepared, dict):
            emit('prepare', 'unknown', reason='REUSE_RECORD_UNAVAILABLE', generation=current['generation'], executionState='NOT_SENT')
            return 1
        if prepared.get('protectionComplete') is True and prepared.get('status') == 'prepared':
            # 历史完成的普通启动：只重读小输入与账本身份，不重扫或重新复制历史来源。
            settings_state, settings_reason = ledger_state(folder, prepared, checkpoint=reuse_checkpoint(folder))
            sha = settings_sha256(prepared)
            # 无论本次是否完成，都把 durable 交接降为 history-only：崩溃残留的
            # write-allowed 票据不能在下一次普通启动继续写配置。
            current['startupId'] = startup_id
            current['startupContext'] = startup_context(current['generation'], startup_id,
                prepared.get('inputDigest'), sha, 'history-only')
            write_json(state/'current.json', current)
            # protectionComplete 只证明内容副本已建立，不能代替完成回执。
            if not committed_receipt(receipt, current['generation']):
                reason = readonly_current_recheck(dsh, state, folder, current, prepared, stamp, resolver,
                                                 active_version, native_reset_proof)
                if reason is None:
                    # Allow this startup from fresh readback. The persisted context
                    # remains history-only and ordinary finalize still owns the receipt.
                    current['startupContext']['readOnlyRecheck'] = True
                    write_json(state/'current.json', current)
                    emit('prepare', 'prepared', generation=current['generation'], protectionComplete=True,
                         verificationScope='current', contentCopyStatus='verified',
                         settingsImportStatus='not-required', executionState='COMPLETED',
                         dshVersion=active_version, runtimeDshVersion=active_version,
                         reason=('RC5_NATIVE_EMPTY_RESET_REVERIFIED_READ_ONLY' if
                                 'settings.yaml.imported' in stamp['inputs'] else
                                 'RC5_CURRENT_INPUTS_REVERIFIED_READ_ONLY'),
                         continuation='现有副本已读回，未派发设置导入')
                    return 0
                emit('prepare', 'pending', generation=current['generation'], cached=True,
                     verificationScope='historical', contentCopyStatus='verified',
                     settingsImportStatus=import_status(settings_state, 'historical'),
                     executionState='NOT_SENT', dshVersion=prepared.get('dshVersion'),
                     runtimeDshVersion=active_version,
                     reason=settings_reason or reason,
                     continuation='旧代尚未提交完成回执，保留现场；不自动补导入')
                return 0
            if settings_state not in ('verified', 'not-required'):
                emit('prepare', 'unknown' if settings_state == 'unknown' else 'pending',
                     generation=current['generation'], cached=True,
                     verificationScope='historical', contentCopyStatus='verified',
                     settingsImportStatus=import_status(settings_state, 'historical'),
                     executionState='NOT_SENT', dshVersion=prepared.get('dshVersion'),
                     runtimeDshVersion=active_version,
                     reason=settings_reason or 'SETTINGS_PENDING_READBACK_OR_REVIEW',
                     continuation='旧代设置尚未完成核验，保留现场；不自动补导入')
                return 0
            emit('prepare', 'historical-complete', generation=current['generation'], cached=True,
                 verificationScope='historical', contentCopyStatus='verified',
                 settingsImportStatus=import_status(settings_state, 'historical'),
                 executionState='NOT_SENT', dshVersion=prepared.get('dshVersion'),
                 runtimeDshVersion=active_version,
                 continuation='保留数据 → rc1 迁移记录（历史作用域，本次未重读全部副本）')
            return 0
    # Full inventory only for a new/incomplete migration. Completed ordinary startup remains a small read.
    try:
        entries, fingerprint = inventory(dsh, resolver)
    except Exception as error:
        emit('prepare', 'failed', stage='inventory', reason=str(error), errorType=type(error).__name__,
             contentCopyStatus='unknown', executionState='COMPLETED')
        return 1
    pending = None
    try:
        if (state/'pending.json').is_file() and not (state/'pending.json').is_symlink():
            pending = read_json(state/'pending.json')
    except (OSError, ValueError, UnicodeError):
        pending = None
    reuse = pending and isinstance(pending, dict) and same_input(pending,stamp) and pending.get('inputDigest') == fingerprint and re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', str(pending.get('generation','')))
    generation = pending['generation'] if reuse else str(uuid.uuid4())
    folder = state/'generations'/generation
    if folder.is_symlink(): raise ValueError('MIGRATION_GENERATION_LINK')
    previous = {}
    try:
        if reuse and (folder/'prepare.json').is_file() and not (folder/'prepare.json').is_symlink():
            previous = read_json(folder/'prepare.json')
            if not isinstance(previous, dict): previous = {}
    except (OSError, ValueError, UnicodeError):
        previous = {}
    reusable = reusable_rows(folder, previous, entries) if previous.get('inputDigest') == fingerprint and not previous.get('protectionComplete') else {}
    # Two copies for presets (snapshot + inert converted candidate), plus bounded record/publication reserve.
    needed = sum(entry['size'] for entry in entries if entry['path'] not in reusable) + sum(entry['size'] for entry in entries if entry['kind']=='preset') + 8*1024*1024
    if shutil.disk_usage(state).free < needed:
        emit('prepare', 'failed', generation=generation if reuse else None, protectionComplete=False, stage='space-preflight', reason='MIGRATION_SPACE_REQUIRED', requiredBytes=needed, contentCopyStatus='unknown', executionState='NOT_SENT')
        return 1
    folder.mkdir(parents=True, exist_ok=True)
    doc = dict(version=2, dshVersion=active_version, generation=generation, startupId=startup_id, dshHome=str(dsh), status='preparing', protectionComplete=False, createdAt=previous.get('createdAt',int(time.time())), **stamp, inputDigest=fingerprint, snapshotInventory=list(reusable.values()), sources=[], presets=[], sessions=[], warnings=[])
    write_json(folder/'prepare.json', doc)
    write_json(state/'pending.json',dict(generation=generation,inputDigest=fingerprint,**stamp))
    operation, logical, actual = 'snapshot', None, None
    try:
        for entry in entries:
            logical, actual = dsh/entry['path'], Path(entry['mapping'])
            row = reusable.get(entry['path'])
            if row is None:
                ordinal = str(len(doc['sources']))
                if os.path.lexists(folder/'snapshots'/ordinal): ordinal += '-'+uuid.uuid4().hex
                row = snapshot_row(folder, dsh, logical, actual, entry['links'], entry['kind'], ordinal)
                if row['sha256'] != entry['sha256'] or row['sourceIdentity'] != entry['identity'][:2]: raise ValueError('SOURCE_CHANGED_AFTER_INVENTORY')
            doc['sources'].append(row)
            if entry['kind']=='session': doc['sessions'].append({'path':row['path'],'sha256':row['sha256'],'size':row['size'],'compatibility':'awaiting-runtime-open'})
            if len(doc['sources']) % 32 == 0: write_json(folder/'prepare.json',doc)
        write_json(folder/'prepare.json',doc)
        # 旧 v1 记录只留作来源证据，不能授予当前代次完整保护/导入成功。
        for name in ('prepare.json','receipt.json'):
            old = dsh/'.dsha-rc1-migration'/name
            if old.is_file() and not old.is_symlink():
                operation, logical, actual = 'legacy-record-copy', old, old
                saved = folder/('legacy-'+name)
                if os.path.lexists(saved):
                    if saved.is_symlink() or not saved.is_file() or digest(saved) != digest(old): raise ValueError('LEGACY_RECORD_CHANGED')
                else: shutil.copyfile(old, saved)
        preset_groups = {}
        for row in doc['sources']:
            if row['kind'] != 'preset': continue
            parts = Path(row['path']).parts
            if len(parts) < 3:
                # A .agent-presets root file is metadata/unknown input, not a preset
                # directory. Its verified snapshot is retained above. Treating its
                # filename as a preset used to copy a file onto candidate/".".
                doc['warnings'].append('PRESET_ROOT_FILE_PRESERVED:' + row['path'])
                continue
            preset_groups.setdefault(parts[1],[]).append(row)
        for name, rows in preset_groups.items():
            sha = hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()
            candidate = dsh/'.dsha-rc1-migration/legacy-agent-presets'/name/(sha[:16]+'-'+generation[:8])
            if candidate.is_symlink(): raise ValueError('PRESET_CANDIDATE_LINK')
            inert_directory(dsh,candidate)
            for row in rows:
                relative = Path(*Path(row['path']).parts[2:]); destination = candidate/relative
                operation, logical, actual = 'preset-candidate-copy', dsh/row['path'], folder/row['snapshot']
                inert_directory(candidate,destination.parent)
                if os.path.lexists(destination):
                    if destination.is_symlink() or not destination.is_file() or digest(destination) != row['sha256']: raise ValueError('PRESET_CANDIDATE_CHANGED')
                else: shutil.copyfile(folder/row['snapshot'], destination)
            # Keep an inert, byte-identical original. The explicit native
            # restore action parses YAML with legacy-preset-convert.cjs before
            # creating a current bundle; never embed arbitrary YAML by indent.
            doc['presets'].append(dict(id=name,source='.agent-presets/'+name,candidate=candidate.relative_to(dsh).as_posix(),bundle=None,sha256=sha,target='@deepseek-ai/dsh-agent-preset',activated=False,requiresReview=True,generation=generation))
        operation, logical, actual = 'record-commit', None, folder
        doc.update(status='prepared',protectionComplete=True); write_json(folder/'prepare.json',doc)
        sha = settings_sha256(doc)
        current = dict(version=2,generation=generation,startupId=startup_id,dshHome=str(dsh),
                       startupContext=startup_context(generation, startup_id, fingerprint, sha,
                                                      'write-allowed' if sha else 'history-only'),
                       **stamp)
        write_json(state/'current.json',current)
        # 公开的兼容索引只供只读清单，不作为控制依据。
        inert_directory(dsh,dsh/'.dsha-rc1-migration')
        write_json(dsh/'.dsha-rc1-migration/prepare.json',doc)
        settings_present = any(row['kind']=='settings' for row in doc['sources'])
        emit('prepare', 'prepared', generation=generation, protectionComplete=True,
             verificationScope='current', contentCopyStatus='verified',
             settingsImportStatus='pending' if settings_present else 'not-required',
             executionState='COMPLETED', dshVersion=active_version, runtimeDshVersion=active_version,
             settings=sum(x['kind']=='settings' for x in doc['sources']),presets=len(doc['presets']),sessions=len(doc['sessions']),
             continuation='保留数据 → rc1 迁移记录')
        return 0
    except Exception as error:
        doc.update(status='failed',protectionComplete=False); doc['warnings'].append(str(error) if isinstance(error,ValueError) else type(error).__name__)
        doc['failure'] = failure_details(error, dsh, operation, logical, actual)
        write_json(folder/'prepare.json',doc)
        emit('prepare', 'failed', generation=generation, protectionComplete=False, stage=operation, reason=doc['warnings'][-1], path=doc['failure']['path'], errorType=doc['failure']['errorType'], diagnostic='generations/'+generation+'/prepare.json', contentCopyStatus='failed', executionState='COMPLETED', continuation='按迁移记录中的操作和路径检查；原件未删除')
        return 1


def publish_reuse(folder, prepared, current, receipt, active_version, evidence):
    """Bounded host checkpoint; legacy or damaged receipts grant no fast path.

    返回诊断字符串（不输出协议回执）；调用方把诊断并入唯一最终结果的 warnings。
    """
    generation = current.get('generation')
    if not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}',str(generation)): return None
    if any(row.get('version') != 2 or row.get('generation') != generation for row in (prepared,current,receipt)): return None
    if prepared.get('dshVersion') != active_version or receipt.get('dshVersion') != active_version: return None
    if prepared.get('status') != 'prepared' or prepared.get('protectionComplete') is not True or not same_input(prepared,current): return None
    if receipt.get('status') != 'committed' or any(receipt.get(key) is not True for key in ('sourcePreserved','settingsImported','protectionComplete')): return None
    sha = settings_sha256(prepared)
    # 只能消费同次验证得到的绑定快照；未知/未完成状态不覆盖任何已有 checkpoint。
    if not isinstance(evidence, LedgerEvidence) or evidence.state not in ('verified', 'not-required'): return None
    if evidence.generation != generation or evidence.source_sha != sha: return None
    if evidence.state == 'verified':
        # fallback 已验证的旧 checkpoint 就是证据；没有新 ledger.expected 时保持它原样。
        if evidence.expected_source == 'checkpoint': return None
        if evidence.expected_source != 'ledger-expected' or evidence.expected is None: return None
    checkpoint = {key:prepared[key] for key in ('version','generation','dshVersion','dshHome','dataRoot','inputs','status','protectionComplete','inputDigest')}
    checkpoint['settingsSha256'] = sha
    if evidence.state == 'verified':
        checkpoint['settingsSections'] = sorted(evidence.expected)
        checkpoint['settingsSectionsSource'] = 'ledger-expected'
    target = folder/'reuse.json'
    if os.path.lexists(target):
        if target.is_symlink() or not target.is_file(): raise ValueError('REUSE_CHECKPOINT_TYPE')
        if target.stat().st_size <= 65536:
            try:
                if read_json(target) == checkpoint: return None
            except (ValueError,UnicodeError):
                write_json(target,checkpoint)
                return 'REUSE_CHECKPOINT_FORMAT'
    write_json(target,checkpoint)
    return None


def finalize(root, state_root=None, startup_id='manual', approved=(), native_reset_proof=''):
    global _STARTUP_ID; _STARTUP_ID = startup_id
    dsh=Path(os.path.abspath(os.path.join(root,'.dsh'))); state=state_path(root,state_root)
    if not (state/'current.json').is_file():
        emit('finalize', 'unknown', reason='HOST_STATE_MISSING', executionState='COMPLETED'); return 1
    active_version=runtime_version(root)
    try: current=read_json(state/'current.json')
    except (OSError, ValueError, UnicodeError):
        emit('finalize', 'unknown', reason='HOST_STATE_UNREADABLE', executionState='COMPLETED'); return 1
    if not isinstance(current, dict) or current.get('version') != 2 or not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', str(current.get('generation',''))):
        emit('finalize', 'unknown', reason='HOST_STATE_INVALID', executionState='COMPLETED'); return 1
    folder=state/'generations'/current['generation']
    try: doc=read_json(folder/'prepare.json')
    except (OSError, ValueError, UnicodeError):
        emit('finalize', 'unknown', reason='PREPARE_RECORD_UNAVAILABLE', generation=current['generation'], executionState='COMPLETED'); return 1
    if current.get('startupId') != startup_id:
        # 本次 prepare 的票据没有绑定到该启动：不沿用旧 durable startupId，也不补派 prepare。
        emit('finalize', 'unknown', reason='STALE_STARTUP_BINDING', generation=current['generation'], executionState='COMPLETED'); return 1
    warnings=[]
    # 完成代次普通重启不反复哈希历史。首次 finalize 或 pending 明确迁移才核验。
    old = generation_record(folder, 'receipt.json')
    if committed_receipt(old, current['generation']):
        # 先按旧 checkpoint 读账本状态，再发布新 checkpoint：同一次调用里 publish_reuse
        # 写下的期望集合不能反过来证明本次账本已完成（自证）。
        evidence = ledger_evidence(folder, doc, reuse_checkpoint(folder))
        settings_state, settings_reason = evidence.state, evidence.reason
        note = publish_reuse(folder,doc,current,old,active_version,evidence)
        warnings = ([note] if note else []) + ([settings_reason] if settings_reason else [])
        extra = {'warnings': warnings} if warnings else {}
        emit('finalize', 'historical-complete', generation=current['generation'], cached=True,
             verificationScope='historical', contentCopyStatus='verified',
             settingsImportStatus=import_status(settings_state, 'historical'),
             executionState='NOT_SENT', dshVersion=old.get('dshVersion'), runtimeDshVersion=active_version,
             continuation='保留数据 → rc1 迁移记录（历史作用域）', **extra)
        return 0
    resolver=Resolver(dsh,approved); preserved=True
    context = current.get('startupContext')
    readonly_recheck = isinstance(context, dict) and context.get('readOnlyRecheck') is True
    if readonly_recheck:
        try:
            stamp = quick(dsh, resolver)
            no_write_to_finish = (context.get('mode') == 'history-only' and same_input(current, stamp)
                and readonly_current_recheck(dsh, state, folder, current, doc, stamp, resolver, active_version,
                                             native_reset_proof) is None)
        except (OSError, ValueError, UnicodeError):
            no_write_to_finish = False
        if not no_write_to_finish:
            emit('finalize', 'unknown', reason='RC5_RECHECK_SCOPE_CHANGED', generation=current['generation'],
                 executionState='NOT_SENT')
            return 1
    for row in (() if readonly_recheck else doc['sources']):
        if snapshot_issue(folder, row):
            preserved=False; warnings.append('SNAPSHOT_CHANGED:'+row['path']); continue
        try:
            logical=dsh/row['path']
            if row['path']=='settings.yaml' and not os.path.lexists(logical): logical=dsh/'settings.yaml.imported'
            actual,links=resolver.resolve(logical)
            # profile 配置由 settings 正式写入。独立原件仍须在快照中，当前目标允许改变。
            if row['kind']=='profile': continue
            # 当前 V4 会话可续写，不承诺运行中旧快照等于活动文件；旧代日志不可改写。
            if row['kind']=='session' and re.search(r'session\.v4\.',row['path']): continue
            identity = [str(os.stat(actual).st_dev), str(os.stat(actual).st_ino)]
            # 内容副本与来源对象身份分开：同一路径对象变化只记线索，不否决已读回副本。
            if row.get('sourceIdentity') and identity != row['sourceIdentity']:
                warnings.append('SOURCE_IDENTITY_CHANGED:'+row['path']); continue
            if digest(actual)!=row['sha256']: warnings.append('SOURCE_CHANGED:'+row['path'])
        except (OSError,ValueError): warnings.append('SOURCE_MISSING_OR_CHANGED:'+row['path'])
    evidence = ledger_evidence(folder, doc, reuse_checkpoint(folder))
    settings_state, settings_reason = evidence.state, evidence.reason
    settings_scope = 'current'
    if settings_state == 'verified':
        # 写入身份也使用同次账本快照，避免再次读入另一份账本后错标 current。
        same_startup = evidence.startup_id == startup_id
        import_status_value = 'verified-current' if same_startup else 'verified-historical'
        settings_scope = 'current' if same_startup else 'historical'
    else:
        import_status_value = {'not-required':'not-required','pending':'pending'}.get(settings_state,'unknown')
        if settings_state == 'pending': warnings.append('SETTINGS_PENDING_READBACK_OR_REVIEW')
        elif settings_state == 'unknown': warnings.append(settings_reason or 'SETTINGS_WRITE_OUTCOME_UNKNOWN')
    if settings_state not in ('verified','not-required') and settings_state != 'pending' and settings_reason:
        if settings_reason not in warnings: warnings.append(settings_reason)
    imported = settings_state in ('verified','not-required')
    old_sessions = [] if readonly_recheck or current_only_sources(dsh, doc['sources']) else [
        row for row in doc['sessions'] if not re.search(r'session\.v4\.', row['path'])]
    status='failed' if not preserved else ('pending' if not imported else 'committed')
    receipt=dict(version=2,dshVersion=doc.get('dshVersion'),runtimeDshVersion=active_version,generation=current['generation'],startupId=startup_id,status=status,sourcePreserved=preserved,protectionComplete=preserved,settingsImported=imported,settingsStatus='verified' if imported else settings_state,settingsScope=settings_scope,verificationScope='current',sourceObjectIdentity='unknown',sessionsStatus='preserved-awaiting-runtime-open' if old_sessions else 'no-legacy-input',sessionCompatibilityVerified=False if old_sessions else None,presets=len(doc['presets']),sessions=len(doc['sessions']),warnings=warnings,verifiedAt=int(time.time()))
    write_json(folder/'receipt.json',receipt); write_json(dsh/'.dsha-rc1-migration/receipt.json',receipt)
    if status == 'committed':
        note = publish_reuse(folder,doc,current,receipt,active_version,evidence)
        if note and note not in warnings: warnings.append(note)
    emit('finalize', status, generation=current['generation'], cached=False,
         verificationScope='current', contentCopyStatus='verified' if preserved else 'failed',
         settingsImportStatus=import_status_value, executionState='COMPLETED',
         dshVersion=doc.get('dshVersion'), runtimeDshVersion=active_version,
         warnings=warnings, continuation='保留数据 → rc1 迁移记录；待审阅设置/预设不影响进入已就绪网页')
    return 0 if preserved else 1


def verify_existing(root, state_root=None, startup_id='manual', approved=()):
    global _STARTUP_ID; _STARTUP_ID = startup_id
    """同代只读核对：读回既有副本与账本，不写入任何迁移记录，不授予恢复/删除权限。"""
    dsh=Path(os.path.abspath(os.path.join(root,'.dsh'))); state=state_path(root,state_root)
    if not (state/'current.json').is_file():
        emit('verify-existing', 'unknown', reason='HOST_STATE_MISSING', executionState='COMPLETED'); return 1
    try: current=read_json(state/'current.json')
    except (OSError, ValueError, UnicodeError):
        emit('verify-existing', 'unknown', reason='HOST_STATE_UNREADABLE', executionState='COMPLETED'); return 1
    if not isinstance(current, dict) or current.get('version') != 2 or not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', str(current.get('generation',''))):
        emit('verify-existing', 'unknown', reason='HOST_STATE_INVALID', executionState='COMPLETED'); return 1
    generation=current['generation']; folder=state/'generations'/generation
    try: doc=read_json(folder/'prepare.json')
    except (OSError, ValueError, UnicodeError):
        emit('verify-existing', 'unknown', reason='PREPARE_RECORD_UNAVAILABLE', generation=generation, executionState='COMPLETED'); return 1
    warnings=[]; verified=0; failed=0; unknown=0
    for row in doc.get('sources', []):
        try:
            issue = snapshot_issue(folder, row)
            if issue:
                failed += 1
                warnings.append(issue if issue == 'SNAPSHOT_PATH_INVALID' else issue+':'+str(row.get('path')))
                continue
            verified += 1
        except OSError:
            unknown += 1; warnings.append('SNAPSHOT_UNREADABLE:'+str(row.get('path')))
    # 当前小输入只用于标注代次绑定；变化只记线索，不重做迁移。
    try:
        stamp = quick(dsh, Resolver(dsh, approved))
        if not same_input(current, stamp): warnings.append('CURRENT_INPUTS_CHANGED')
    except (OSError, ValueError): warnings.append('CURRENT_INPUTS_UNREADABLE')
    settings_state, settings_reason = ledger_state(folder, doc, startup_id, reuse_checkpoint(folder))
    if settings_reason and settings_reason not in warnings: warnings.append(settings_reason)
    status = 'historical-complete' if failed == 0 and unknown == 0 and doc.get('protectionComplete') is True else 'unknown'
    copy = 'verified' if status == 'historical-complete' else ('failed' if failed else ('unknown' if unknown else 'unknown'))
    emit('verify-existing', status, generation=generation, cached=False, verificationScope='current',
         contentCopyStatus=copy, settingsImportStatus=import_status(settings_state, 'historical'),
         executionState='COMPLETED', verifiedSnapshots=verified, failedSnapshots=failed,
         unknownSnapshots=unknown, warnings=warnings,
         continuation='只读核对结果；不授予恢复、删除或重新导入')
    return 0 if status == 'historical-complete' else 1


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('command',choices=('prepare','finalize','verify-existing')); parser.add_argument('--root',default='/root'); parser.add_argument('--state-root',default='/run/dsha-rc1-state'); parser.add_argument('--startup-id',required=True); parser.add_argument('--approved-root',action='append',default=[]); parser.add_argument('--native-reset-proof',default=''); args=parser.parse_args()
    handlers={'prepare':prepare,'finalize':finalize,'verify-existing':verify_existing}
    kwargs = {} if args.command == 'verify-existing' else {'native_reset_proof': args.native_reset_proof}
    try: return handlers[args.command](args.root,args.state_root,args.startup_id,args.approved_root,**kwargs)
    except Exception as error:
        emit(args.command, 'unknown', reason=str(error) if isinstance(error,ValueError) else type(error).__name__, operation=getattr(error,'operation',args.command), path=getattr(error,'logical',None), contentCopyStatus='unknown'); return 1

if __name__=='__main__': raise SystemExit(main())

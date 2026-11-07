#!/usr/bin/env python3
"""Generate an isolated x86_64 debug runtime from verified offline inputs."""
import argparse
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import posixpath
import re
import shutil
import struct
import tarfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
OUTPUT = ROOT / 'app/build/generated/emulatorAssets'
NATIVE = ROOT / 'app/build/emulator-native/jniLibs/x86_64'
PREFIX = 'usr/local/lib/node_modules/@deepseek-ai/dsh'
SPECIAL_MODULES = {
    '@deepseek-ai/dsh-client-modules/lib/index.js',
    '@deepseek-ai/dsh-llm-deepseek/lib/index.js',
    '@deepseek-ai/dsh-client-ui-conversation/lib/client.js',
    '@deepseek-ai/dsh-storage-json/lib/index.js',
    '@deepseek-ai/dsh-api-workspace-controller/lib/index.js',
    '@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js',
    '@deepseek-ai/dsh-session-format-v2-to-v3/lib/index.js',
}


def digest(path):
    hasher = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(chunk)
    return hasher.hexdigest()


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def safe_name(name):
    value = name.removeprefix('./').rstrip('/')
    if not value or value == '.':
        return ''
    parts = PurePosixPath(value).parts
    if value.startswith('/') or '\\' in value or re.match(r'^[A-Za-z]:', value) or '..' in parts:
        raise ValueError('EMULATOR_ARCHIVE_PATH:' + value)
    return value


def add_bytes(archive, name, data, mode=0o644):
    item = tarfile.TarInfo(name)
    item.size, item.mode, item.uid, item.gid, item.mtime = len(data), mode, 0, 0, 0
    item.uname = item.gname = 'root'
    archive.addfile(item, io.BytesIO(data))


def check_elf(data, name):
    if data[:4] == b'\x7fELF' and (len(data) < 20 or struct.unpack_from('<H', data, 18)[0] != 62):
        raise ValueError('EMULATOR_FOREIGN_ELF:' + name)


def add_configured_base(configured, archive):
    status = None
    host_prefix = '/data/local/tmp/dsha-x86-20261031/rootfs/'
    with tarfile.open(configured) as source:
        members = {safe_name(item.name): item for item in source.getmembers()}
        for item in members.values():
            name = safe_name(item.name)
            if not name:
                continue
            if name.startswith(('etc/ssh/ssh_host_', 'var/cache/apt/', 'var/lib/apt/lists/', 'root/.ssh/', 'root/.dsh/')) or name in ('etc/machine-id', '.l2s') or name.startswith('.l2s/'):
                continue
            # split 基础层只携带 npm；corepack 不属于当前宿主工具契约。
            modules = 'usr/local/lib/node_modules/'
            if name.startswith(modules) and not (name == modules + 'npm' or name.startswith(modules + 'npm/')):
                continue
            if name in ('usr/local/bin/corepack', 'usr/local/bin/pnpm', 'usr/local/bin/pnpx', 'usr/local/bin/yarn', 'usr/local/bin/yarnpkg'):
                continue
            item.name = name
            item.uid = item.gid = item.mtime = 0
            item.uname = item.gname = 'root'
            if item.isfile():
                data = source.extractfile(item).read()
                check_elf(data[:20], name)
                if name == 'var/lib/dpkg/status':
                    status = data
                archive.addfile(item, io.BytesIO(data))
            elif item.islnk():
                target = members[safe_name(item.linkname)]
                add_bytes(archive, name, source.extractfile(target).read(), item.mode)
            elif item.issym() and item.linkname.startswith('/data/local/tmp/'):
                if not item.linkname.startswith(host_prefix):
                    raise ValueError('EMULATOR_HOST_LINK:' + name)
                resolved = item.linkname[len(host_prefix):]
                seen = set()
                while True:
                    if resolved in seen:
                        raise ValueError('EMULATOR_LINK_CYCLE:' + name)
                    seen.add(resolved)
                    target = members.get(safe_name(resolved))
                    if target is None:
                        raise ValueError('EMULATOR_L2S_TARGET_MISSING:' + name)
                    if target.isfile():
                        add_bytes(archive, name, source.extractfile(target).read(), target.mode)
                        break
                    if not target.issym() or not target.linkname.startswith(host_prefix):
                        raise ValueError('EMULATOR_L2S_TARGET_TYPE:' + name)
                    resolved = target.linkname[len(host_prefix):]
            elif item.isdir():
                archive.addfile(item)
            elif item.issym():
                # 审查 P2-11：走出 /data/local/tmp 白名单的软链在此前分支未接住时，
                # 生成阶段直接拒绝（应用端解包会按 guest 根解析，这里不再兜底放行）。
                raise ValueError('EMULATOR_ROOTFS_LINK:' + name + ' -> ' + item.linkname)
            else:
                raise ValueError('EMULATOR_ROOTFS_TYPE:' + name)
    if status is None:
        raise ValueError('EMULATOR_DPKG_STATUS_MISSING')
    return status


def add_dsh_overlay(source, output, dsh_builder, version):
    source = source.resolve()
    try:
        source.relative_to(ROOT / 'app/build')
    except ValueError:
        # 文档允许直接传 tools/dsh-runtime/node_modules（npm install 的原树）；
        # 只要是仓库内受管路径即可，不再强制放 app/build 下。
        source.relative_to(ROOT / 'tools/dsh-runtime')
    metadata = json.loads((source / '@deepseek-ai/dsh/package.json').read_text(encoding='utf-8'))
    if metadata.get('version') != version:
        raise ValueError('EMULATOR_DSH_VERSION')
    chains, _ = dsh_builder.load_chains(ASSETS, version)
    foreign = set()
    for path in source.rglob('package.json'):
        package = json.loads(path.read_text(encoding='utf-8'))
        if any(dsh_builder._platform_excluded(package.get(key), expected) for key, expected in (('os', 'linux'), ('cpu', 'x64'), ('libc', 'glibc'))):
            foreign.add(path.parent.relative_to(source))
    links = {'usr/local/bin/dsh': '../lib/node_modules/@deepseek-ai/dsh/lib/bin.js'}
    seen = set()
    overlay_path = output / 'dsh-runtime.bin'
    temporary = overlay_path.with_suffix('.tmp')
    with tarfile.open(temporary, 'w:gz', compresslevel=1) as archive:
        for directory in ('usr', 'usr/local', 'usr/local/lib', 'usr/local/lib/node_modules', 'usr/local/bin', 'usr/local/share'):
            item = tarfile.TarInfo(directory)
            item.type, item.mode = tarfile.DIRTYPE, 0o755
            item.uid = item.gid = item.mtime = 0
            archive.addfile(item)
        for path in sorted(source.rglob('*'), key=lambda item: dsh_builder.archive_path_key(item, source)):
            relative = path.relative_to(source)
            if any(relative == parent or parent in relative.parents for parent in foreign):
                continue
            omitted = dsh_builder.omitted(relative)
            if omitted and omitted not in ('foreign-prebuild', 'foreign-platform'):
                continue
            if 'prebuilds' in relative.parts:
                index = relative.parts.index('prebuilds') + 1
                if index < len(relative.parts) and relative.parts[index] != 'linux-x64':
                    continue
            archive_path = dsh_builder.target_path(relative)
            if archive_path == PREFIX + '/node_modules/npm' or archive_path.startswith(PREFIX + '/node_modules/npm/'):
                continue
            if path.is_symlink():
                resolved = path.resolve(strict=True).relative_to(source)
                links[archive_path] = posixpath.relpath(dsh_builder.target_path(resolved), posixpath.dirname(archive_path))
                continue
            if path.is_dir():
                # split extractor requires matching scaffold modes in base/overlay.
                item = tarfile.TarInfo(archive_path)
                item.type, item.mode = tarfile.DIRTYPE, 0o755
                item.uid = item.gid = item.mtime = 0
                archive.addfile(item)
                continue
            if not path.is_file():
                raise ValueError('EMULATOR_DSH_SPECIAL_FILE')
            data = path.read_bytes()
            check_elf(data[:20], relative.as_posix())
            if data[:2] == b'MZ':
                continue
            relative_name = relative.as_posix()
            if relative_name in dsh_builder.PATCHES or relative_name in SPECIAL_MODULES:
                data = dsh_builder.patched_content(relative, data)
            if relative_name in chains:
                data = dsh_builder.apply_stable_patches(data, chains[relative_name])
                seen.add(relative_name)
            if path.name == 'package.json':
                parent = path.parent.parent
                if parent.name.startswith('@'):
                    parent = parent.parent
                if parent == source:
                    package = json.loads(data)
                    package_name = package.get('name', '')
                    if package_name not in (dsh_builder.PACKAGE, 'npm') and re.fullmatch(r'(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*', package_name):
                        alias = 'usr/local/lib/node_modules/' + package_name
                        links[alias] = posixpath.relpath(PREFIX + '/node_modules/' + package_name, posixpath.dirname(alias))
                    binaries = package.get('bin', {})
                    if isinstance(binaries, str):
                        binaries = {package_name.split('/')[-1]: binaries}
                    for executable, target in binaries.items() if isinstance(binaries, dict) else ():
                        if not executable or '/' in executable or '\\' in executable or not isinstance(target, str):
                            continue
                        entry = (path.parent / target).resolve(strict=True).relative_to(source)
                        destination = PREFIX + '/node_modules/.bin/' + executable
                        links.setdefault(destination, posixpath.relpath(dsh_builder.target_path(entry), posixpath.dirname(destination)))
            add_bytes(archive, archive_path, data, 0o755 if data.startswith((b'#!', b'\x7fELF')) else 0o644)
        if seen != set(chains):
            raise ValueError('EMULATOR_DSH_PATCH_MODULE_MISSING')
        for package, directory in (('dsha-runtime-fs', 'runtime-fs'), ('dsha-session-compat', 'session-compat'), ('dsha-client-combo-cache', 'client-combo-cache')):
            for filename in ('package.json', 'index.js'):
                add_bytes(archive, PREFIX + '/node_modules/' + package + '/' + filename, (ASSETS / directory / filename).read_bytes())
        add_bytes(archive, 'usr/local/share/dsha/dsh-runtime.version', (version + '\n').encode())
        for name, target in sorted(links.items()):
            item = tarfile.TarInfo(name)
            item.type, item.linkname, item.mode = tarfile.SYMTYPE, target, 0o777
            item.uid = item.gid = item.mtime = 0
            archive.addfile(item)
    temporary.replace(overlay_path)


def package_proofs(path):
    records = {}
    prefix = PREFIX + '/node_modules/'
    with tarfile.open(path) as archive:
        for item in archive:
            if not item.name.startswith(prefix):
                continue
            parts = item.name[len(prefix):].split('/')
            count = 2 if parts[0].startswith('@') else 1
            if len(parts) <= count:
                continue
            package, relative = '/'.join(parts[:count]), '/'.join(parts[count:])
            if item.isfile():
                content = hashlib.sha256(archive.extractfile(item).read()).hexdigest()
                value = relative + '\0FILE\0' + str(item.mode & 0o777) + '\0' + content + '\n'
            elif item.issym():
                value = relative + '\0LINK\0' + item.linkname + '\n'
            elif item.isdir():
                continue
            else:
                raise ValueError('EMULATOR_PACKAGE_PROOF_TYPE')
            records.setdefault(package, []).append((relative, value))
    return {'version': 1, 'archiveSha256': digest(path), 'packages': {
        name: hashlib.sha256(''.join(value for _, value in sorted(rows, key=lambda row: row[0].encode('utf-16be'))).encode()).hexdigest()
        for name, rows in sorted(records.items())}}


def validate_split(base, runtime):
    prefix = 'usr/local/lib/node_modules/'
    with tarfile.open(base) as archive:
        names = {safe_name(item.name) for item in archive}
    modules = {name[len(prefix):].split('/')[0] for name in names if name.startswith(prefix)}
    if modules != {'npm'}:
        raise ValueError('EMULATOR_SPLIT_BASE_MODULES:' + repr(sorted(modules)))
    with tarfile.open(runtime) as archive:
        names = {safe_name(item.name) for item in archive}
    required = {PREFIX + '/lib/bin.js', 'usr/local/bin/dsh', 'usr/local/share/dsha/dsh-runtime.version'}
    if not required.issubset(names) or any(name.startswith(prefix + 'npm/') for name in names):
        raise ValueError('EMULATOR_SPLIT_RUNTIME_CONTRACT')


def recovery(output, version):
    builder = load_module('emulator_recovery_builder', ROOT / 'tools/prepare-recovery-assets.py')
    archives = [output / 'offline-rootfs.bin', output / 'dsh-runtime.bin']
    files, links = builder.describe(archives)
    original, patched = builder.build_browser_overlay(archives[1], output, version)
    by_path = {row['path']: row for row in files}
    overlays = []
    for name, path in builder.BROWSER_TARGETS.items():
        data = patched[name]
        asset = 'recovery-overlay/' + name
        destination = output / asset
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        by_path[path] = {'path': path, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)}
        overlays.append({'path': path, 'asset': asset, 'originalSha256': hashlib.sha256(original[name]).hexdigest(), 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data)})
    dependencies = builder.capsule_dependencies(output, by_path, links)
    files = sorted(by_path.values(), key=lambda row: row['path'])
    launcher_names = ('libproot.so', 'libprootloader.so', 'libtalloc.so', 'libandroidshmem.so')
    contract = {
        'schema': 1,
        'dshVersion': version,
        'baseVersion': (ROOT / 'app/src/main/assets/offline-rootfs.version').read_text(encoding='utf-8').strip(),
        # 调试专用：这里的 recovery 契约由模拟器归档生成，未经 tools/recovery-runtime/lock.json
        # 的独立应急锁校验；不能当作独立应急 DSH 的受管契约分发。
        'debugOnly': True,
        'archives': [
            {'asset': 'recovery-rootfs.bin', 'sha256': digest(archives[0])},
            {'asset': 'recovery-dsh-runtime.bin', 'sha256': digest(archives[1])},
        ],
        'expandedBytes': sum(row['bytes'] for row in files),
        'files': files,
        'links': links,
        'overlays': overlays,
        'overlayInputs': {name: digest(output / name) for name in builder.input_assets(version)},
        'capsuleDependencies': dependencies,
        'agentSha256': digest(ASSETS / 'recovery-agent.js'),
        'profileHashes': {name: digest(ASSETS / name) for name in ('recovery-profile-package.json', 'recovery-profile.patch.yml')},
        'launcherHashes': {'standard': {name: digest(NATIVE / name) for name in launcher_names}},
    }
    identity = hashlib.sha256(json.dumps(contract, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    descriptor = json.dumps({'id': identity, **contract}, ensure_ascii=False, separators=(',', ':')) + '\n'
    builder.validate_descriptor_budget(descriptor.encode('utf-8'))
    (output / 'recovery-runtime.json').write_text(descriptor, encoding='utf-8')
    write_json(output / 'recovery-asset-locations.json', {
        'schema': 1,
        'runtimeId': identity,
        'archives': [
            {'asset': row['asset'], 'source': source.name, 'sha256': row['sha256']}
            for row, source in zip(contract['archives'], archives)
        ],
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configured-rootfs', type=Path, required=True)
    parser.add_argument('--node-modules', type=Path, required=True)
    args = parser.parse_args()
    configured = args.configured_rootfs.resolve()
    configured.relative_to(ROOT / 'app/build')
    native_receipt = NATIVE.parent.parent / 'receipt.json'
    if not native_receipt.is_file():
        raise ValueError('EMULATOR_NATIVE_RECEIPT_MISSING')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    assets = ROOT / 'app/build/generated/standardAssets'
    if not assets.is_dir():
        # emulator 模式把 buildDirectory 改到 app/build/emulator-target，
        # prepareStandardAssets 的输出也会跟着过去；两处都找不到时给出可执行指引。
        fallback = ROOT / 'app/build/emulator-target/generated/standardAssets'
        if fallback.is_dir():
            assets = fallback
        else:
            raise ValueError(
                'EMULATOR_STANDARD_ASSETS_MISSING: 先跑一次不带 -Pdsha.emulatorX64 的'
                ' :app:prepareStandardAssets（或完整 Gradle 构建），再把生成的'
                ' app/build/generated/standardAssets 交给本脚本')
    shutil.copytree(assets, OUTPUT, dirs_exist_ok=True)
    # x86 调试资产逐项替换为本架构 ELF，禁止把 ARM SSH 工具混入调试包。
    # container-ssh 资产来自 PR #9：它还没合入时 standardAssets 里没有这棵树，
    # 此处显式跳过并说明，而不是 FileNotFoundError。
    ssh_directory = OUTPUT / 'container-ssh'
    ssh_manifest_path = ssh_directory / 'manifest.json'
    if not ssh_manifest_path.is_file():
        print('EMULATOR_CONTAINER_SSH_ASSETS_MISSING: standardAssets 里没有 container-ssh'
              '（来自 PR #9）；跳过 x86 SSH 三件套替换，调试包内不会带 sshd。')
    else:
        ssh_manifest = json.loads(ssh_manifest_path.read_text(encoding='utf-8'))
        ssh_manifest['architecture'] = 'x86_64'
        with tarfile.open(configured) as configured_archive:
            members = {safe_name(item.name): item for item in configured_archive}
            ssh_paths = {'sshd': 'usr/sbin/sshd', 'ssh-keygen': 'usr/bin/ssh-keygen',
                         'lib/libwrap.so.0': 'usr/lib/x86_64-linux-gnu/libwrap.so.0'}
            for row in ssh_manifest['files']:
                name = ssh_paths[row['target']]
                seen = set()
                while not members[name].isfile():
                    if name in seen or not members[name].issym():
                        raise ValueError('EMULATOR_SSH_LINK:' + name)
                    seen.add(name)
                    name = posixpath.normpath(posixpath.join(posixpath.dirname(name), members[name].linkname))
                data = configured_archive.extractfile(members[name]).read()
                check_elf(data[:20], name)
                (ssh_directory / row['asset']).write_bytes(data)
                row['sha256'], row['bytes'] = hashlib.sha256(data).hexdigest(), len(data)
        write_json(ssh_directory / 'manifest.json', ssh_manifest)
    # P1-8：DSH 运行时依赖 rootfs 内 /usr/local/bin/node（x86_64 ELF）。
    # 文档自建的 x86 rootfs 若没有 node，装到模拟器才会在启动时报错；这里提前拒绝。
    with tarfile.open(configured) as configured_archive:
        members = {safe_name(item.name): item for item in configured_archive}
        node = members.get('usr/local/bin/node')
        if node is None:
            raise ValueError(
                'EMULATOR_NODE_MISSING: x86 rootfs 缺 usr/local/bin/node —— 从 Node 官方'
                ' linux-x64 包解出后放入 rootfs，或 apt install nodejs 后重建归档')
        if not node.isfile():
            raise ValueError('EMULATOR_NODE_TYPE: usr/local/bin/node 不是常规文件')
        check_elf(configured_archive.extractfile(node).read()[:20], 'usr/local/bin/node')
        # 随包 Python 资产在 x86 线是空归档哨兵：rootfs 必须自带 python3.12，
        # 否则部署会在「安装 Python 与 pnpm」阶段失败——提前到这里拒绝。
        python = members.get('usr/bin/python3.12')
        encodings = members.get('usr/lib/python3.12/encodings/__init__.py')
        if python is None or not python.isfile() or encodings is None:
            raise ValueError(
                'EMULATOR_PYTHON_MISSING: x86 rootfs 缺 usr/bin/python3.12（或 encodings）'
                ' —— x86 线不随包 Python，rootfs 必须自带（apt install python3.12）')
    dsh_builder = load_module('emulator_dsh_builder', ROOT / 'tools/build-dsh-runtime.py')
    version = json.loads((ROOT / 'tools/dsh-runtime/package.json').read_text(encoding='utf-8'))['dependencies']['@deepseek-ai/dsh']
    rootfs_output = OUTPUT / 'offline-rootfs.bin'
    temporary = rootfs_output.with_suffix('.tmp')
    with gzip.GzipFile(filename=str(temporary), mode='wb', compresslevel=1, mtime=0) as raw:
        with tarfile.open(fileobj=raw, mode='w') as archive:
            status = add_configured_base(configured, archive)
    temporary.replace(rootfs_output)
    dsh_output = OUTPUT / 'dsh-runtime.bin'
    add_dsh_overlay(args.node_modules, OUTPUT, dsh_builder, version)
    validate_split(rootfs_output, dsh_output)
    write_json(OUTPUT / 'managed-package-proofs.json', package_proofs(dsh_output))
    package_lock = hashlib.sha256(status).hexdigest()
    tools_path = OUTPUT / 'ubuntu-tools.bin'
    tools_tmp = tools_path.with_suffix('.tmp')
    with gzip.GzipFile(filename=str(tools_tmp), mode='wb', mtime=0) as raw:
        with tarfile.open(fileobj=raw, mode='w') as archive:
            add_bytes(archive, 'var/lib/dpkg/status', status)
            add_bytes(archive, 'root/.dsha-ubuntu-tools-version', (package_lock + chr(10)).encode())
    tools_tmp.replace(tools_path)
    # 审查 P2-3：configured-overlay 契约里 packageLockSha256 承载的是 dpkg status
    # 摘要（ColdToolsInstaller 把它当版本标记自洽使用），不是标准 ubuntu-tools
    # 锁摘要——与标准 APK 的审计口径不同；调试包不进升级审计，字段附 note 声明。
    write_json(OUTPUT / 'ubuntu-tools.manifest.json', {'schema': 1, 'archiveSha256': digest(tools_path), 'statusSha256': package_lock, 'packageLockSha256': package_lock, 'contract': 'configured-overlay-v1', 'fieldNote': 'packageLockSha256 here is the dpkg status digest (self-consistent version marker), not the standard ubuntu-tools lock digest', 'packages': sum(1 for paragraph in status.split(b'\n\n') if paragraph.startswith(b'Package:'))})
    (OUTPUT / 'ubuntu-tools.layout').write_text('configured-overlay-v1\n', encoding='ascii')
    for name in ('glibc-python.bin', 'python-support.bin'):
        # 空归档哨兵：表示「本 ABI 不随包 Python，rootfs 必须自带」。同样固定 mtime。
        sentinel_tmp = (OUTPUT / name).with_suffix('.tmp')
        with gzip.GzipFile(filename=str(sentinel_tmp), mode='wb', mtime=0) as raw:
            with tarfile.open(fileobj=raw, mode='w'):
                pass
        sentinel_tmp.replace(OUTPUT / name)
    for name in ('glibc-python.tar.gz', 'python-support.tar.gz'):
        (OUTPUT / name).unlink(missing_ok=True)
    (OUTPUT / 'offline-rootfs.layout').write_text('split-runtime-v1\n', encoding='ascii')
    (OUTPUT / 'offline-rootfs.version').write_text('10\n', encoding='ascii')
    (OUTPUT / 'offline-rootfs.sha256').write_text(digest(rootfs_output) + '\n', encoding='ascii')
    (OUTPUT / 'dsh-runtime.sha256').write_text(digest(dsh_output) + '\n', encoding='ascii')
    expanded = sum(item.size for name in ('offline-rootfs.bin', 'dsh-runtime.bin') for item in tarfile.open(OUTPUT / name) if item.isfile())
    base_archive = tarfile.open(OUTPUT / 'offline-rootfs.bin')
    runtime_archive = tarfile.open(dsh_output)
    base_members = {item.name.removeprefix('./').rstrip('/'): item for item in base_archive}
    runtime_members = {item.name.removeprefix('./').rstrip('/'): item for item in runtime_archive}
    if 'usr/local/lib/node_modules/npm' not in base_members or any(name.startswith('usr/local/lib/node_modules/') and not name.startswith('usr/local/lib/node_modules/npm') for name in base_members):
        raise ValueError('EMULATOR_SPLIT_BASE_MODULES')
    if 'usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js' not in runtime_members:
        raise ValueError('EMULATOR_SPLIT_OVERLAY_MISSING_DSH')
    for path in ('usr', 'usr/local', 'usr/local/lib', 'usr/local/lib/node_modules', 'usr/local/bin', 'usr/local/share'):
        if path not in runtime_members or base_members[path].mode != runtime_members[path].mode:
            raise ValueError('EMULATOR_SPLIT_DIRECTORY_MODE:' + path)
    base_archive.close()
    runtime_archive.close()
    (OUTPUT / 'offline-rootfs.bytes').write_text(str(expanded) + '\n', encoding='ascii')
    contract = load_module('emulator_input_contract', ROOT / 'tools/runtime_input_contract.py')
    spec = contract.load(ROOT)
    descriptor = json.loads((ASSETS / 'runtime-descriptor.json').read_text(encoding='utf-8'))
    launchers = {name: digest(path) for name, path in contract.launcher_paths(ROOT, spec).items() if '/jniLibs/' not in name}
    launchers.update({'emulator-native/' + path.name: digest(path) for path in sorted(NATIVE.glob('*.so'))})
    for name in ('tools/prepare-emulator-assets.py', 'tools/build-emulator-native.py'):
        launchers['generator:' + name] = digest(ROOT / name)
    mapped = {'glibc-python.tar.gz': 'glibc-python.bin', 'adb-wheels.tar.gz': 'adb-wheels.bin'}
    inputs = {}
    for name in descriptor['inputs']:
        path = OUTPUT / mapped.get(name, name)
        if not path.is_file():
            path = ASSETS / name
        inputs[name] = digest(path)
    descriptor.update(launcherContract='DSHA_X86_64_DEBUG_V1', launcherInputs=launchers, inputs=inputs)
    fields = {key: descriptor[key] for key in ('baseVersion', 'dshVersion', 'launcherContract', 'launcherInputs', 'bridgeProtocol', 'dataRead', 'dataWrite', 'inputs')}
    descriptor['runtimeId'] = hashlib.sha256(json.dumps(fields, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    write_json(OUTPUT / 'runtime-descriptor.json', descriptor)
    recovery(OUTPUT, version)
    files = {path.relative_to(OUTPUT).as_posix(): digest(path) for path in sorted(OUTPUT.rglob('*')) if path.is_file()}
    receipt = {'schema': 1, 'abi': 'x86_64', 'debugOnly': True, 'runtimeId': descriptor['runtimeId'], 'configuredRootfsSha256': digest(configured), 'dshRecipe': dsh_builder.recipe_inputs(), 'files': files, 'native': json.loads(native_receipt.read_text())}
    write_json(OUTPUT.parent / 'emulator-assets-receipt.json', receipt)
    print('PASS x86_64 emulator assets', descriptor['runtimeId'], len(files), expanded)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""从实际 APK 核验受管资产名与字节，挡住 AGP 对 .gz 的 gunzip 回归。

只读 APK 中央目录、仓库文本与本次构建的生成资产目录，不安装、不执行、不联网：
- 受管清单保留源固定输入名，APK 成员名按 tools/asset-deployment.json 的
  sourceTransforms 改名（`gh-cli.tar.gz` -> `gh-cli.bin`，同 glibc-python/adb-wheels）；
  源名一旦重新出现在 APK 里，说明资产合并又走了会被 gunzip 的 .tar.gz 路径。
- managed-runtime-inputs.json 的 installs / assetFiles / assetTrees 逐项核对：
  APK 成员存在，且字节摘要等于比对基准。单纯改名来源按源固定输入逐字节比对；
  重编码归档额外验证 CRC64 XZ、压缩摘要和完整 tar 字节与锁定 gzip 输入一致。
  descriptor 的 generatedFiles（离线 rootfs、gecko 兼容脚本等）按本次构建的
  `app/build/generated/standardAssets` 比对；其余普通受管文件按仓库源文件比对。
- gh 归档额外核对固定成员大小与摘要（tools/gh-cli/packages.lock.json
  与受管安装行共用同一摘要），并断言没有被改名成 `gh-cli.tar`。

本门禁在 build-signed / test-package 构建之后运行，因此要求生成资产目录存在。
"""
from verification_require import require
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import zipfile
from rootfs_archive import CHUNK as ARCHIVE_CHUNK
from rootfs_archive import RootfsInput, XZ_MAGIC, open_rootfs_tar

ROOT = Path(__file__).resolve().parents[1]
ASSETS = 'app/src/main/assets'
GENERATED = 'app/build/generated/standardAssets'
GH_SOURCE = 'gh-cli.tar.gz'
GH_PACKAGED = 'gh-cli.bin'
CHUNK = 1024 * 1024
LIMIT = 2 * 1024 ** 3


def stream_digest(stream, limit=LIMIT):
    value, total = hashlib.sha256(), 0
    for block in iter(lambda: stream.read(CHUNK), b''):
        total += len(block)
        require(total <= limit, 'APK_ASSET_LIMIT')
        value.update(block)
    return value.hexdigest()


def file_digest(path):
    with path.open('rb') as stream:
        return stream_digest(stream)


def deployment_module():
    spec = importlib.util.spec_from_file_location(
        'managed_assets_deployment', ROOT / 'tools/asset_deployment.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def managed_members(root=ROOT):
    """返回 (APK 成员名 -> 比对基准文件, 改名计划的源名列表, 受管清单)。"""
    root = Path(root)
    assets = root / ASSETS
    generated_root = root / GENERATED
    deployment = deployment_module().load_manifest(root / 'tools/asset-deployment.json')
    managed = json.loads((assets / 'managed-runtime-inputs.json').read_text(encoding='utf-8'))
    transforms = deployment.get('sourceTransforms', {})
    recompressed = set(deployment.get('recompressedArchives', ()))
    generated = set(deployment.get('generatedFiles', ()))
    members, sources = {}, set()

    def reference(packaged, source_name):
        # 源固定输入按 sourceTransforms 逐字节复制到 APK；生成物以本次构建的
        # 生成资产目录为准（离线 rootfs 会被重新打包，不能拿源输入当基准）。
        if source_name in transforms and source_name not in recompressed:
            return assets / source_name
        if packaged in generated:
            candidate = generated_root / packaged
            require(candidate.is_file(), 'APK_GENERATED_ASSETS_MISSING: ' + packaged)
            return candidate
        return assets / source_name

    def add(name):
        source = assets / name
        require(source.is_file(), 'MANAGED_ASSET_SOURCE_MISSING: ' + name)
        packaged = transforms.get(name, name)
        members[packaged] = reference(packaged, name)
        if name in transforms:
            sources.add(name)

    members['managed-runtime-inputs.json'] = assets / 'managed-runtime-inputs.json'
    require(members['managed-runtime-inputs.json'].is_file(), 'MANAGED_INPUTS_MISSING')
    for row in managed['installs']:
        add(row['asset'])
    for name in managed['assetFiles']:
        add(name)
    for name in sorted(recompressed):
        add(name)
        sidecar = transforms[name] + '.sha256'
        members[sidecar] = reference(sidecar, sidecar)
    for tree in managed['assetTrees']:
        directory = assets / tree
        require(directory.is_dir(), 'MANAGED_ASSET_TREE_MISSING: ' + tree)
        for path in sorted(directory.rglob('*')):
            if path.is_file():
                members[path.relative_to(assets).as_posix()] = path
        built = generated_root / tree
        if built.is_dir():
            for path in sorted(built.rglob('*')):
                if path.is_file():
                    members.setdefault(path.relative_to(generated_root).as_posix(), path)
    return members, sorted(sources), managed


def tar_digest(stream):
    with io.BufferedReader(stream, ARCHIVE_CHUNK) as raw, io.BufferedReader(
            RootfsInput(raw), ARCHIVE_CHUNK) as decoded:
        return stream_digest(decoded)


def verify_recompressed(archive, packaged, source, source_sha):
    require(file_digest(source) == source_sha, 'APK_TOOL_ARCHIVE_SOURCE_SHA256: ' + packaged)
    name = 'assets/' + packaged
    packed = archive.getinfo(name)
    require(packed.compress_type == zipfile.ZIP_STORED, 'APK_TOOL_ARCHIVE_ZIP_METHOD: ' + packaged)
    with archive.open(name) as stream:
        require(stream.read(len(XZ_MAGIC)) == XZ_MAGIC, 'APK_TOOL_ARCHIVE_NOT_XZ: ' + packaged)
    expected = archive.read(name + '.sha256')
    with archive.open(name) as stream:
        shipped = stream_digest(stream)
        require(expected == (shipped + '\n').encode('ascii'), 'APK_TOOL_ARCHIVE_SHA256: ' + packaged)
    with source.open('rb') as original:
        original_tar_sha = tar_digest(original)
    with archive.open(name) as stream:
        require(tar_digest(stream) == original_tar_sha, 'APK_TOOL_ARCHIVE_TAR_DIGEST: ' + packaged)
    return {'carrierSha256': shipped, 'carrierBytes': packed.file_size, 'tarSha256': original_tar_sha,
            'sourceSha256': source_sha, 'sourceBytes': source.stat().st_size}


def verify(apk, root=ROOT):
    apk = Path(apk)
    root = Path(root)
    require(apk.is_file(), 'APK_MISSING: ' + str(apk))
    members, transforms, managed = managed_members(root)
    deployment = deployment_module().load_manifest(root / 'tools/asset-deployment.json')
    recoded_sources = deployment.get('recompressedArchives', ())
    input_pins = {}
    if recoded_sources:
        descriptor = json.loads((root / ASSETS / 'runtime-descriptor.json').read_text(encoding='utf-8'))
        input_pins = descriptor['inputs']
    recompressed = {}
    lock = json.loads((root / 'tools/gh-cli/packages.lock.json').read_text(encoding='utf-8'))
    gh_rows = [row for row in managed['installs'] if row.get('asset') == GH_SOURCE]
    require(len(gh_rows) == 1, 'MANAGED_GH_ROW')
    require(gh_rows[0].get('extract', {}).get('sha256') == lock['binarySha256'],
            'MANAGED_GH_MEMBER_DIGEST')
    with zipfile.ZipFile(apk) as archive:
        names = {info.filename for info in archive.infolist()}
        for name in sorted(members):
            member = 'assets/' + name
            require(member in names, 'APK_MANAGED_ASSET_MISSING: ' + member)
            with archive.open(member) as stream:
                require(stream_digest(stream) == file_digest(members[name]),
                        'APK_MANAGED_ASSET_DIGEST: ' + member)
        # 改名计划的源名不得进入 APK；否则 AGP 会 gunzip 并去掉扩展名（T78 结论 B）。
        for name in transforms:
            require('assets/' + name not in names, 'APK_TRANSFORM_SOURCE_LEAK: ' + name)
        for name in recoded_sources:
            packaged = deployment['sourceTransforms'][name]
            if name == GH_SOURCE:
                require(input_pins.get(name) == lock['sha256'], 'APK_GH_ARCHIVE_SOURCE_PIN')
            recompressed[packaged] = verify_recompressed(
                archive, packaged, root / ASSETS / name, input_pins.get(name))
        for leaked in ('assets/gh-cli.tar.gz', 'assets/gh-cli.tar'):
            require(leaked not in names, 'APK_GH_ARCHIVE_GUNZIPPED: ' + leaked)
        gh = 'assets/' + GH_PACKAGED
        require(gh in names, 'APK_GH_ARCHIVE_MISSING: ' + gh)
        if GH_PACKAGED not in recompressed:
            with archive.open(gh) as stream:
                require(stream.read(2) == b'\x1f\x8b', 'APK_GH_ARCHIVE_NOT_GZIP')
        with archive.open(gh) as stream, open_rootfs_tar(stream) as tar:
            found = False
            for item in tar:
                if item.name.removeprefix('./') != lock['member']:
                    continue
                require(not found and item.isfile(), 'APK_GH_MEMBER_MISSING')
                require(item.size == lock['binaryBytes'], 'APK_GH_MEMBER_BYTES')
                require(stream_digest(tar.extractfile(item)) == lock['binarySha256'],
                        'APK_GH_MEMBER_DIGEST')
                found = True
            require(found, 'APK_GH_MEMBER_MISSING')
    return {'apk': apk.name, 'apkBytes': apk.stat().st_size,
            'managedMembers': len(members),
            'generatedReferences': sum(1 for path in members.values()
                                       if (root / GENERATED) in path.parents),
            'recompressedArchives': recompressed,
            'gh': {'member': lock['member'], 'bytes': lock['binaryBytes'],
                   'sha256': lock['binarySha256']}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', type=Path, nargs='+')
    args = parser.parse_args(argv)
    for apk in args.apks:
        print(json.dumps(verify(apk), ensure_ascii=False, sort_keys=True))
    print('PASS managed APK assets, transform names and pinned gh member')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

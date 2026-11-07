#!/usr/bin/env python3
"""模拟器平台包补齐脚本的纯逻辑负控：锁定来源、完整性、tar 成员越界与链接。

不下载、不联网、不碰真实 npm：锁与缓存全部用临时目录伪造。
"""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

import importlib.util

_script = Path(__file__).resolve().parent / 'prepare-emulator-platform-packages.py'
_spec = importlib.util.spec_from_file_location('prepare_emulator_platform_packages', _script)
prep = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(prep)


class EmulatorPlatformPackagesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_locked_version_and_integrity_are_required(self):
        # package.json 声明的范围值不允许直接用：锁里没有该包必须拒绝。
        tree = self.root / 'tree'
        (tree / '@deepseek-ai/dsh').mkdir(parents=True)
        (tree / 'package.json').write_text('{}', encoding='utf-8')
        self.root.joinpath('tree/@deepseek-ai/dsh/package.json').write_text(
            json.dumps({'optionalDependencies': {'@img/sharp-linux-x64': '^0.35.0'}}),
            encoding='utf-8')
        prep.LOCK = self.root / 'lock.json'
        self.root.joinpath('lock.json').write_text(
            json.dumps({'packages': {'node_modules/@deepseek-ai/dsh': {'version': '1'}}}),
            encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'EMULATOR_PACKAGE_NOT_LOCKED'):
            prep.required_packages(tree)

    def test_locked_entry_provides_exact_version_and_integrity(self):
        tree = self.root / 'tree2'
        (tree / '@deepseek-ai/dsh').mkdir(parents=True)
        self.root.joinpath('tree2/@deepseek-ai/dsh/package.json').write_text(
            json.dumps({'optionalDependencies': {'@img/sharp-linux-x64': '^0.1.0'}}),
            encoding='utf-8')
        prep.LOCK = self.root / 'lock2.json'
        self.root.joinpath('lock2.json').write_text(json.dumps({'packages': {
            'node_modules/@deepseek-ai/dsh': {'version': '1'},
            'node_modules/@img/sharp-linux-x64': {
                'version': '0.35.5', 'integrity': 'sha512-AAAA'}}}), encoding='utf-8')
        need = prep.required_packages(tree)
        self.assertEqual(need['@img/sharp-linux-x64']['version'], '0.35.5')
        self.assertEqual(need['@img/sharp-linux-x64']['integrity'], 'sha512-AAAA')

    def test_installed_package_is_not_required(self):
        tree = self.root / 'tree3'
        (tree / '@deepseek-ai/dsh').mkdir(parents=True)
        (tree / '@img/sharp-linux-x64').mkdir(parents=True)
        self.root.joinpath('tree3/@deepseek-ai/dsh/package.json').write_text(
            json.dumps({'optionalDependencies': {'@img/sharp-linux-x64': '^0.1.0'}}),
            encoding='utf-8')
        prep.LOCK = self.root / 'lock3.json'
        self.root.joinpath('lock3.json').write_text(json.dumps({'packages': {}}), encoding='utf-8')
        self.assertEqual(prep.required_packages(tree), {})

    def test_musl_variants_are_skipped(self):
        self.assertEqual(prep.linux_variants({
            '@img/sharp-linux-x64': '^1', '@img/sharp-linuxmusl-x64': '^1',
            '@img/sharp-linux-arm64': '^1', 'left-pad': '^1'}), ['@img/sharp-linux-x64'])

    def test_integrity_mismatch_is_rejected_before_unpack(self):
        archive = self.root / 'fake.tgz'
        archive.write_bytes(b'not the locked bytes')
        with self.assertRaisesRegex(RuntimeError, 'integrity'):
            prep.verify_integrity(archive, 'sha512-' + base64.b64encode(
                hashlib.sha512(b'locked bytes').digest()).decode('ascii'))
        prep.verify_integrity(archive, 'sha512-' + base64.b64encode(
            hashlib.sha512(b'not the locked bytes').digest()).decode('ascii'))

    def test_tar_member_traversal_is_rejected(self):
        target = self.root / 'target'
        target.mkdir()
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            data = b'escape'
            info = tarfile.TarInfo('package/../../escape.txt')
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        buffer.seek(0)
        with tarfile.open(fileobj=buffer, mode='r') as source:
            with self.assertRaisesRegex(RuntimeError, '越界'):
                list(prep.safe_members(source, target))
        self.assertFalse((self.root / 'escape.txt').exists())

    def test_symlink_members_are_rejected(self):
        target = self.root / 'target2'
        target.mkdir()
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            info = tarfile.TarInfo('package/link')
            info.type = tarfile.SYMTYPE
            info.linkname = '/etc'
            archive.addfile(info)
        buffer.seek(0)
        with tarfile.open(fileobj=buffer, mode='r') as source:
            with self.assertRaisesRegex(RuntimeError, '链接'):
                list(prep.safe_members(source, target))

    def test_regular_files_are_rebased_out_of_package_prefix(self):
        target = self.root / 'target3'
        staging = self.root / 'staging3'
        staging.mkdir()
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode='w') as archive:
            data = b'content'
            info = tarfile.TarInfo('package/lib/index.js')
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        buffer.seek(0)
        with tarfile.open(fileobj=buffer, mode='r') as source:
            for member in prep.safe_members(source, staging):
                source.extract(member, staging)
        self.assertEqual((staging / 'lib/index.js').read_bytes(), b'content')


if __name__ == '__main__':
    unittest.main()

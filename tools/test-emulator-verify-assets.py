#!/usr/bin/env python3
"""verify-emulator-assets.py 的负控：receipt/成员集/字节/ELF 架构任一不符都必须拒绝。

这些分支平时只在「有人手工改过产物」时才走到，没有负控就等于没验证。
脚本按模块常量读路径，测试里把 ASSETS/NATIVE 指到临时目录再调用 verify()。
"""
import hashlib
import importlib.util
import json
import pathlib
import struct
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def load_verify():
    spec = importlib.util.spec_from_file_location('verify_emulator', ROOT / 'tools/verify-emulator-assets.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def x86_64_elf(marker=b'\x01'):
    head = bytearray(b'\x7fELF\x02\x01\x01\x00' + b'\x00' * 10)
    head += struct.pack('<H', 62)
    return bytes(head) + marker


class VerifyAssetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        base = pathlib.Path(self.temp.name)
        self.assets = base / 'emulatorAssets'
        self.native = base / 'jniLibs' / 'x86_64'
        self.assets.mkdir(parents=True)
        self.native.mkdir(parents=True)
        (self.assets / 'a.bin').write_bytes(b'asset')
        (self.native / 'libproot.so').write_bytes(x86_64_elf())
        self.module = load_verify()
        self.module.ASSETS = self.assets
        self.module.NATIVE = self.native
        self.receipt_path = base / 'emulator-assets-receipt.json'

    def tearDown(self):
        self.temp.cleanup()

    def write_receipt(self, **overrides):
        receipt = {
            'schema': 1,
            'abi': 'x86_64',
            'debugOnly': True,
            'files': {'a.bin': hashlib.sha256(b'asset').hexdigest()},
            'native': {
                'libraries': {
                    'libproot.so': {'sha256': hashlib.sha256(x86_64_elf()).hexdigest()}
                }
            },
            'dshRecipe': {},
        }
        receipt.update(overrides)
        self.receipt_path.write_text(json.dumps(receipt), encoding='utf-8')
        return receipt

    def test_missing_receipt_is_rejected(self):
        with self.assertRaises(ValueError) as caught:
            self.module.verify()
        self.assertIn('EMULATOR_ASSETS_NOT_PREPARED', str(caught.exception))

    def test_wrong_target_is_rejected(self):
        self.write_receipt(abi='arm64-v8a')
        with self.assertRaises(ValueError) as caught:
            self.module.verify()
        self.assertIn('EMULATOR_RECEIPT_TARGET', str(caught.exception))

    def test_member_set_mismatch_is_rejected(self):
        self.write_receipt(files={'a.bin': hashlib.sha256(b'asset').hexdigest(), 'ghost.bin': '0' * 64})
        with self.assertRaises(ValueError) as caught:
            self.module.verify()
        self.assertIn('EMULATOR_ASSET_MEMBER_SET', str(caught.exception))

    def test_changed_bytes_are_rejected(self):
        self.write_receipt()
        (self.assets / 'a.bin').write_bytes(b'tampered')
        with self.assertRaises(ValueError) as caught:
            self.module.verify()
        self.assertIn('EMULATOR_ASSET_CHANGED', str(caught.exception))

    def test_arm_library_in_x86_directory_is_rejected(self):
        arm = bytearray(b'\x7fELF\x02\x01\x01\x00' + b'\x00' * 10) + struct.pack('<H', 183)
        arm = bytes(arm)
        (self.native / 'libproot.so').write_bytes(arm)
        self.write_receipt(
            native={'libraries': {'libproot.so': {'sha256': hashlib.sha256(arm).hexdigest()}}})
        with self.assertRaises(ValueError) as caught:
            self.module.verify()
        self.assertIn('EMULATOR_ELF_ARCHITECTURE', str(caught.exception))


if __name__ == '__main__':
    unittest.main()

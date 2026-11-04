#!/usr/bin/env python3
"""校验真实 SSH 载荷，以及改字节、错架构和缺许可证的负控。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('container_ssh_builder', ROOT / 'tools/prepare-container-ssh.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class ContainerSshAssetsTest(unittest.TestCase):
    def setUp(self):
        parent = ROOT / 'app/build/container-ssh-test'
        parent.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=parent)
        self.addCleanup(self.temp.cleanup)
        self.assets = Path(self.temp.name) / 'assets'
        shutil.copytree(builder.ASSETS, self.assets)
        self.lock = builder.checked_lock(builder.LOCK)

    def test_current_locked_payload_and_licenses_verify(self):
        self.assertEqual(3, builder.check_assets(self.assets, self.lock)['files'])

    def test_changed_payload_is_rejected(self):
        payload = self.assets / self.lock['files'][0]['asset']
        data = bytearray(payload.read_bytes())
        data[-1] ^= 1
        payload.write_bytes(data)
        with self.assertRaisesRegex(ValueError, 'SSH_ASSET_DIGEST'):
            builder.check_assets(self.assets, self.lock)

    def test_wrong_architecture_is_rejected_even_with_matching_digest(self):
        row = self.lock['files'][0]
        payload = self.assets / row['asset']
        data = bytearray(payload.read_bytes())
        data[18] = 62
        payload.write_bytes(data)
        row['sha256'] = hashlib.sha256(data).hexdigest()
        manifest = json.loads((self.assets / 'manifest.json').read_text(encoding='utf-8'))
        manifest['files'] = self.lock['files']
        (self.assets / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'SSH_ASSET_ARCHITECTURE'):
            builder.check_assets(self.assets, self.lock)

    def test_unregistered_extra_payload_is_rejected(self):
        (self.assets / 'unexpected.bin').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, 'SSH_ASSET_MEMBER_SET'):
            builder.check_assets(self.assets, self.lock)

    def test_changed_license_is_rejected(self):
        name = next(iter(self.lock['licenses']))
        (self.assets / name).write_bytes(b'license missing')
        with self.assertRaisesRegex(ValueError, 'SSH_ASSET_LICENSE'):
            builder.check_assets(self.assets, self.lock)


if __name__ == '__main__':
    unittest.main(verbosity=2)
#!/usr/bin/env python3
"""逐字节核验独立 x86 调试输入，缺失或混入 ARM 直接拒绝打包。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/build/generated/emulatorAssets'
NATIVE = ROOT / 'app/build/emulator-native/jniLibs/x86_64'


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            value.update(block)
    return value.hexdigest()


def verify():
    receipt_path = ASSETS.parent / 'emulator-assets-receipt.json'
    if not receipt_path.is_file():
        raise ValueError('EMULATOR_ASSETS_NOT_PREPARED: run tools/prepare-emulator-assets.py')
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    if receipt.get('schema') != 1 or receipt.get('abi') != 'x86_64' or receipt.get('debugOnly') is not True:
        raise ValueError('EMULATOR_RECEIPT_TARGET')
    actual = {path.relative_to(ASSETS).as_posix() for path in ASSETS.rglob('*') if path.is_file()}
    if actual != set(receipt['files']):
        raise ValueError('EMULATOR_ASSET_MEMBER_SET')
    for name, expected in receipt['files'].items():
        path = ASSETS / name
        if path.is_symlink() or not path.resolve().is_relative_to(ASSETS.resolve()) or digest(path) != expected:
            raise ValueError('EMULATOR_ASSET_CHANGED:' + name)
    for name, expected in receipt['native']['libraries'].items():
        path = NATIVE / name
        if digest(path) != expected['sha256']:
            raise ValueError('EMULATOR_NATIVE_CHANGED:' + name)
        header = path.read_bytes()[:20]
        machine = 3 if name == 'libprootloader32.so' else 62
        if header[:4] != b'\x7fELF' or struct.unpack_from('<H', header, 18)[0] != machine:
            raise ValueError('EMULATOR_ELF_ARCHITECTURE:' + name)
    descriptor = json.loads((ASSETS / 'runtime-descriptor.json').read_text(encoding='utf-8'))
    fields = ('baseVersion', 'dshVersion', 'launcherContract', 'launcherInputs', 'bridgeProtocol', 'dataRead', 'dataWrite', 'inputs')
    identity = hashlib.sha256(json.dumps({key: descriptor[key] for key in fields}, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    if descriptor['launcherContract'] != 'DSHA_X86_64_DEBUG_V1' or descriptor['runtimeId'] != identity or identity != receipt['runtimeId']:
        raise ValueError('EMULATOR_DESCRIPTOR_IDENTITY')
    source_assets = {
        'tools/prepare-emulator-assets.py': ROOT / 'tools/prepare-emulator-assets.py',
        'tools/build-emulator-native.py': ROOT / 'tools/build-emulator-native.py',
    }
    mapped_assets = {
        'glibc-python.tar.gz': 'glibc-python.bin',
        'adb-wheels.tar.gz': 'adb-wheels.bin',
    }
    for name, expected in descriptor['inputs'].items():
        path = source_assets.get(name, ASSETS / mapped_assets.get(name, name))
        if not path.is_file() or digest(path) != expected:
            raise ValueError('EMULATOR_DESCRIPTOR_ASSET:' + name)
    spec = importlib.util.spec_from_file_location('emulator_input_contract', ROOT / 'tools/runtime_input_contract.py')
    contract = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(contract)
    paths = contract.launcher_paths(ROOT)
    for name, expected in descriptor['launcherInputs'].items():
        if name.startswith('emulator-native/'):
            path = NATIVE / name.split('/', 1)[1]
        elif name.startswith('generator:'):
            path = ROOT / name[len('generator:'):]
        else:
            if name not in paths:
                raise ValueError('EMULATOR_UNKNOWN_LAUNCHER:' + name)
            path = paths[name]
        if digest(path) != expected:
            raise ValueError('EMULATOR_SOURCE_CHANGED:' + name)
    print('PASS x86_64 debug asset integrity', identity, len(actual))


if __name__ == '__main__':
    verify()

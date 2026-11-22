#!/usr/bin/env python3
"""S3a 真锁互操作；固定 Node 归档 + 真实 guest Python + 已编译 Kotlin 适配器。

完整运行须先编译 PluginLockFixture，再传 --kotlin-classpath。所有写入仅临时目录。
"""
import argparse
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import selectors
import subprocess
import sys
import tarfile
import tempfile
import unittest
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
OPTIONS = None


def fixed_node_module():
    lock = json.loads((ROOT / 'tools/dsh-runtime/package-lock.json').read_text())
    record = lock['packages']['node_modules/@deepseek-ai/dsh-atomic-write']
    cache = ROOT / 'app/build/plugin-lock-interop'
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / 'atomic-write.tgz'
    if not archive.exists():
        with urllib.request.urlopen(record['resolved'], timeout=30) as response:
            data = response.read(2 * 1024 * 1024)
        archive.write_bytes(data)
    data = archive.read_bytes()
    algorithm, expected = record['integrity'].split('-', 1)
    if algorithm != 'sha512' or base64.b64encode(hashlib.sha512(data).digest()).decode() != expected:
        raise AssertionError('NODE_LOCK_ARCHIVE_INTEGRITY')
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as package:
        manifest = package.extractfile('package/package.json').read()
        source = package.extractfile('package/lib/index.js').read()
    metadata = json.loads(manifest)
    if metadata['version'] != record['version'] or metadata['name'] != '@deepseek-ai/dsh-atomic-write':
        raise AssertionError('NODE_LOCK_PACKAGE_IDENTITY')
    # 只选择两份已验证成员，不对下载包做路径提取，也不运行生命周期脚本。
    (cache / 'package.json').write_bytes(manifest)
    module = cache / 'index.js'
    module.write_bytes(source)
    print('NODE_LOCK_SOURCE', record['version'], hashlib.sha256(source).hexdigest(), flush=True)
    return module


def identity(path):
    node = os.stat(path)
    return f'{node.st_dev}:{node.st_ino}'


def wire(fields):
    digest = hashlib.sha256(b''.join(key.encode() + b'\0' + value.encode() + b'\0'
                                    for key, value in fields.items())).hexdigest()
    entries = dict(fields, fingerprint=digest)
    return ''.join(key + '=' + base64.b64encode(value.encode()).decode() + '\n'
                   for key, value in entries.items()) + 'END\n'


class Interop(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.node_module = fixed_node_module()
        spec = importlib.util.spec_from_file_location('s3a_worker_tools', ROOT / 'tools/fixtures/plugin-lock-worker.py')
        worker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(worker)
        cls.tools_sha256 = worker.tools_digest(ASSETS)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='s3a-lock-')
        self.base = Path(self.temp.name)
        self.root = self.base / 'guest'
        self.home = self.root / 'root/.dsh'
        self.profile = self.home / 'profiles/web'
        self.profile.mkdir(parents=True)
        self.manifest = self.profile / 'package.json'
        self.manifest.write_text('{}\n')
        self.lock = Path(str(self.manifest) + '.lock')
        self.cancel = self.base / 'cancel'
        self.children = []
        self.fields = {
            'schema': 'DSHA_PLUGIN_INVOCATION_V1',
            'hostRoot': str(self.root), 'hostData': str(self.home),
            'rootIdentity': identity(self.root), 'dataIdentity': identity(self.home),
            'guestHome': '/root/.dsh', 'profile': 'web', 'startupId': 'fixture-startup',
            'operationId': 'fixture-operation', 'generation': '7', 'cancelRevision': '0',
            'toolsSha256': self.tools_sha256,
            'taskId': '', 'bindingCount': '0',
            'budget.CAS': '900000', 'budget.REGISTER': '90000', 'budget.LEGACY': '3000000',
        }

    def tearDown(self):
        # 只结束本夹具创建并记录的精确子进程；临时目录从不接触用户 profile。
        for child in self.children:
            if child.poll() is None:
                child.stdin.close()
                try:
                    child.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=3)
            child.stdout.close()
            child.stderr.close()
            if not child.stdin.closed:
                child.stdin.close()
        self.temp.cleanup()

    def start(self, kind='python', timeout=.3, mode='both', fields=None, announce_wait=False):
        worker = [sys.executable, '-B', str(ROOT / 'tools/fixtures/plugin-lock-worker.py'),
                  str(ASSETS), '--timeout', str(timeout), '--mode', mode, '--cancel', str(self.cancel)]
        if announce_wait:
            worker.append('--announce-wait')
        if kind == 'node':
            command = ['node', str(ROOT / 'tools/fixtures/plugin-lock-worker.mjs'),
                       str(self.node_module), str(self.manifest), str(int(timeout * 1000)), str(announce_wait).lower()]
        elif kind == 'kotlin':
            if not OPTIONS.kotlin_classpath:
                self.fail('完整互操作要求 --kotlin-classpath；不得把未跑 Kotlin 写成通过')
            command = [OPTIONS.java, '-cp', OPTIONS.kotlin_classpath,
                       'com.deepseekharness.app.plugins.PluginLockFixture'] + worker
        else:
            command = worker
        child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, text=True, bufsize=1)
        self.children.append(child)
        if kind != 'node':
            child.stdin.write(wire(fields or self.fields))
            child.stdin.flush()
        return child

    def line(self, child, timeout=8):
        with selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ)
            self.assertTrue(selector.select(timeout), '未在预算内收到锁状态')
        line = child.stdout.readline().strip()
        if not line:
            child.wait(timeout=3)
            self.fail(f'锁进程退出 {child.returncode}: {child.stderr.read()}')
        return line

    def held(self, child):
        self.assertTrue(self.line(child).startswith('HELD'))

    def release(self, child):
        child.stdin.write('RELEASE\n')
        child.stdin.flush()
        self.assertEqual('RELEASED', self.line(child))
        self.assertEqual(0, child.wait(timeout=5))

    def timeout(self, child):
        self.assertEqual('TIMEOUT', self.line(child))
        self.assertEqual(3, child.wait(timeout=5))

    def test_python_blocks_real_node_and_releases(self):
        first = self.start()
        self.held(first)
        self.timeout(self.start('node'))
        self.release(first)
        second = self.start('node')
        self.held(second)
        self.release(second)

    def test_node_blocks_python_without_data_lock(self):
        first = self.start('node')
        self.held(first)
        self.timeout(self.start())
        self.assertEqual(f'{first.pid}\n', self.lock.read_text())
        self.release(first)
        second = self.start()
        self.held(second)
        self.release(second)

    def test_python_data_flock_alone_blocks_combined_backend(self):
        first = self.start(mode='data')
        self.held(first)
        self.timeout(self.start())
        kotlin = self.start('kotlin')
        self.assertNotEqual(0, kotlin.wait(timeout=8))
        self.assertIn('PLUGIN_LOCK_PROBE_NOT_ACQUIRED', kotlin.stderr.read())
        self.assertFalse(self.lock.exists(), 'data-only 持有者必须在 profile 取锁前挡住 Kotlin')
        # Node 不取 data flock，这是被实测的未覆盖写者范围，而非统一双锁的宣称。
        node = self.start('node')
        self.held(node)
        self.release(node)
        self.release(first)

    def test_kotlin_broker_blocks_python_and_node(self):
        first = self.start('kotlin')
        self.held(first)
        self.timeout(self.start(mode='data'))
        self.timeout(self.start())
        self.timeout(self.start('node'))
        self.release(first)
        second = self.start()
        self.held(second)
        self.release(second)

    def test_kotlin_wait_timeout_does_not_remove_live_node_lock(self):
        node = self.start('node')
        self.held(node)
        contender = self.start('kotlin')
        self.assertNotEqual(0, contender.wait(timeout=8))
        self.assertIn('PLUGIN_LOCK_PROBE_NOT_ACQUIRED', contender.stderr.read())
        self.assertEqual(f'{node.pid}\n', self.lock.read_text())
        self.release(node)

    def test_python_death_releases_flock_and_node_takes_dead_pid_record(self):
        first = self.start()
        self.held(first)
        first.kill()
        first.wait(timeout=3)
        self.assertTrue(self.lock.exists())
        second = self.start('node')
        self.held(second)
        self.release(second)
        third = self.start()
        self.held(third)
        self.release(third)

    def test_node_death_is_taken_over_by_python(self):
        first = self.start('node')
        self.held(first)
        first.kill()
        first.wait(timeout=3)
        second = self.start()
        self.held(second)
        self.release(second)

    def test_kotlin_parent_exit_closes_broker_pipe(self):
        first = self.start('kotlin')
        self.held(first)
        first.kill()
        first.wait(timeout=3)
        second = self.start(timeout=3)
        self.held(second)
        self.release(second)

    def test_unknown_and_live_records_are_never_removed(self):
        for record in ('', 'unfinished', '0\n', f'{os.getpid()}\n'):
            self.lock.write_text(record)
            self.timeout(self.start())
            self.timeout(self.start('node'))
            self.assertEqual(record, self.lock.read_text())
            self.lock.unlink()

    def test_concurrent_stale_takeover_serializes_new_holders(self):
        dead = subprocess.Popen([sys.executable, '-c', 'pass'])
        dead.wait(timeout=3)
        self.lock.write_text(f'{dead.pid}\n')
        first, second = self.start(timeout=4), self.start('node', timeout=4)
        with selectors.DefaultSelector() as selector:
            selector.register(first.stdout, selectors.EVENT_READ, first)
            selector.register(second.stdout, selectors.EVENT_READ, second)
            ready = selector.select(5)
            self.assertEqual(1, len(ready), '两个竞争者同时宣称持锁')
            winner = ready[0][0].data
        self.held(winner)
        loser = second if winner is first else first
        self.assertEqual(f'{winner.pid}\n', self.lock.read_text())
        self.release(winner)
        self.held(loser)
        self.assertEqual(f'{loser.pid}\n', self.lock.read_text())
        self.release(loser)

    def test_cancellation_during_profile_wait_preserves_holder(self):
        for waiting in ('python', 'node'):
            self.cancel.unlink(missing_ok=True)
            holder = self.start('node' if waiting == 'python' else 'python')
            self.held(holder)
            contender = self.start(waiting, timeout=4, announce_wait=True)
            self.assertEqual('CONTENDED', self.line(contender))
            if waiting == 'python':
                self.cancel.touch()
            else:
                contender.stdin.write('CANCEL\n')
                contender.stdin.flush()
            self.assertEqual('CANCELLED', self.line(contender))
            self.assertEqual(2, contender.wait(timeout=5))
            self.assertEqual(f'{holder.pid}\n', self.lock.read_text())
            self.release(holder)

    def test_cancellation_during_data_wait_preserves_holder(self):
        holder = self.start(mode='data')
        self.held(holder)
        contender = self.start(timeout=4, announce_wait=True)
        self.assertEqual('CONTENDED', self.line(contender))
        self.cancel.touch()
        self.assertEqual('CANCELLED', self.line(contender))
        self.assertEqual(2, contender.wait(timeout=5))
        self.cancel.unlink()
        self.release(holder)

    def test_abandoned_takeover_claim_does_not_authorize_deletion(self):
        dead = subprocess.Popen([sys.executable, '-c', 'pass'])
        dead.wait(timeout=3)
        record = f'{dead.pid}\n'
        self.lock.write_text(record)
        claim = Path(str(self.lock) + '.takeover-' + hashlib.sha256(record.encode()).hexdigest()[:16])
        claim.write_text(record)
        self.timeout(self.start())
        self.timeout(self.start('node'))
        self.assertEqual(record, self.lock.read_text())
        self.assertEqual(record, claim.read_text())

    def test_new_python_process_imports_frozen_home_without_shared_singleton(self):
        first = self.start(mode='profile')
        self.held(first)
        second_home = self.root / 'root/second'
        (second_home / 'profiles/web').mkdir(parents=True)
        fields = dict(self.fields, hostData=str(second_home), dataIdentity=identity(second_home), guestHome='/root/second')
        second = self.start(mode='profile', fields=fields)
        self.held(second)
        self.assertEqual(f'{first.pid}\n', self.lock.read_text())
        self.assertEqual(f'{second.pid}\n', (second_home / 'profiles/web/package.json.lock').read_text())
        self.release(second)
        self.release(first)

    def test_stable_binding_same_profile_inode_but_parent_data_lock_differs(self):
        stable = self.base / 'stable/dsh'
        stable.parent.mkdir()
        self.home.rename(stable)
        self.home.symlink_to(stable, target_is_directory=True)
        self.fields['hostData'] = str(stable)
        self.fields['dataIdentity'] = identity(stable)
        self.fields['bindingCount'] = '1'
        # fields 顺序必须与 Kotlin 冻结协议一致。
        for key in ['budget.CAS', 'budget.REGISTER', 'budget.LEGACY']:
            self.fields.pop(key)
        self.fields['binding.0.host'] = str(stable)
        self.fields['binding.0.guest'] = '/root/.dsh'
        self.fields.update({'budget.CAS': '900000', 'budget.REGISTER': '90000', 'budget.LEGACY': '3000000'})
        first = self.start('kotlin')
        line = self.line(first)
        self.assertTrue(line.startswith('HELD\t'))
        self.assertEqual(identity(self.lock), identity(stable / 'profiles/web/package.json.lock'))
        guest_lock = self.root / 'root/.dsha-data.lock'
        naive_host_lock = stable.parent / '.dsha-data.lock'
        naive_host_lock.touch()
        self.assertNotEqual(identity(guest_lock), identity(naive_host_lock))
        self.timeout(self.start('node'))
        self.release(first)

    def test_changed_tool_bytes_reject_before_acquiring_any_lock(self):
        fields = dict(self.fields, toolsSha256='0' * 64)
        child = self.start(fields=fields)
        self.assertNotEqual(0, child.wait(timeout=5))
        self.assertIn('TOOLS_CHANGED', child.stderr.read())
        self.assertFalse(self.lock.exists())
        self.assertFalse((self.root / 'root/.dsha-data.lock').exists())


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--kotlin-classpath', required=True)
    parser.add_argument('--java', default='java')
    OPTIONS, remaining = parser.parse_known_args()
    unittest.main(argv=[sys.argv[0]] + remaining)

#!/usr/bin/env python3
"""S3a 隔离锁探针；只取既有锁，不执行插件写入。输入必须经私有 stdin。"""
import argparse
import base64
import hashlib
import importlib.util
import os
from pathlib import Path
import sys
from contextlib import contextmanager
import time


class Cancelled(Exception):
    pass


def identity(path):
    node = os.stat(path)
    return f'{node.st_dev}:{node.st_ino}'


def tools_digest(assets):
    names = {path.name for path in assets.glob('plugin-*.py')}
    names.update(('register-builtin-plugins.py', 'materialize-cas-links.py', 'builtin-plugins.json'))
    rows = b''.join(name.encode() + b'\0' + hashlib.sha256((assets / name).read_bytes()).hexdigest().encode() + b'\n'
                    for name in sorted(names))
    return hashlib.sha256(rows).hexdigest()


def read_context():
    fields = {}
    for _ in range(128):
        line = sys.stdin.readline(16385)
        if line == 'END\n':
            break
        key, encoded = line.rstrip('\n').split('=', 1)
        if key in fields or len(line) > 16384:
            raise ValueError('CONTEXT_FIELD')
        fields[key] = base64.b64decode(encoded, validate=True).decode('utf-8')
    else:
        raise ValueError('CONTEXT_LIMIT')
    expected = fields.pop('fingerprint')
    actual = hashlib.sha256(b''.join(key.encode() + b'\0' + value.encode() + b'\0'
                                     for key, value in fields.items())).hexdigest()
    if actual != expected or fields['schema'] != 'DSHA_PLUGIN_INVOCATION_V1':
        raise ValueError('CONTEXT_FINGERPRINT')
    fields['fingerprint'] = actual
    return fields


def resolve(context, guest):
    selected = (context['hostRoot'], '/')
    for index in range(int(context['bindingCount'])):
        host, target = context[f'binding.{index}.host'], context[f'binding.{index}.guest']
        if (guest == target or guest.startswith(target.rstrip('/') + '/')) and len(target) >= len(selected[1]):
            selected = host, target
    suffix = guest if selected[1] == '/' else guest[len(selected[1]):]
    return selected[0].rstrip('/') + ('/' + suffix.lstrip('/') if suffix else '')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('assets', type=Path)
    parser.add_argument('--mode', choices=('both', 'profile', 'data'), default='both')
    parser.add_argument('--timeout', type=float, default=2)
    parser.add_argument('--cancel', type=Path)
    parser.add_argument('--announce-wait', action='store_true')
    args = parser.parse_args()
    context = read_context()
    root, home = context['hostRoot'], context['guestHome']
    source = args.assets / 'register-builtin-plugins.py'
    if tools_digest(args.assets) != context['toolsSha256']:
        raise ValueError('TOOLS_CHANGED')
    if identity(root) != context['rootIdentity'] or identity(context['hostData']) != context['dataIdentity']:
        raise ValueError('ROOT_CHANGED')
    if context['profile'] != 'web' or os.path.realpath(resolve(context, home)) != os.path.realpath(context['hostData']):
        raise ValueError('DATA_BINDING')
    os.environ['DSHA_TEST_ROOT'] = root
    os.environ['DSH_HOME'] = home
    spec = importlib.util.spec_from_file_location('s3a_builtin', source)
    builtin = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builtin)
    # 新进程、import 前固定 DSH_HOME；不修改已导入模块的路径单例。
    data_lock = os.path.join(os.path.dirname(builtin.local(home)), '.dsha-data.lock')
    profile_lock = builtin.local(builtin.MANIFEST) + '.lock'
    expected_data = resolve(context, os.path.dirname(home) + '/.dsha-data.lock')
    expected_profile = resolve(context, home + '/profiles/web/package.json.lock')

    cancel_checks = 0

    def check_cancel():
        nonlocal cancel_checks
        cancel_checks += 1
        # 第三次回调意味着至少一次实际竞争失败；不凭启动后睡眠推断已进入等待。
        if args.announce_wait and cancel_checks == 3:
            print('CONTENDED', flush=True)
        if args.cancel and args.cancel.exists():
            raise Cancelled('CANCELLED')

    @contextmanager
    def data_only():
        import fcntl
        with open(data_lock, 'a') as lock:
            deadline = time.monotonic() + args.timeout
            while True:
                check_cancel()
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise TimeoutError('PLUGIN_LOCK_TIMEOUT')
                    time.sleep(.02)
            try:
                yield
            finally:
                fcntl.flock(lock, fcntl.LOCK_UN)

    lock = (builtin.operation_lock(check_cancel, args.timeout) if args.mode == 'both' else
            builtin.profile_writer_lock(check_cancel, args.timeout) if args.mode == 'profile' else data_only())
    try:
        with lock:
            check_cancel()
            if identity(root) != context['rootIdentity'] or identity(context['hostData']) != context['dataIdentity']:
                raise ValueError('ROOT_CHANGED_AFTER_WAIT')
            if tools_digest(args.assets) != context['toolsSha256']:
                raise ValueError('TOOLS_CHANGED_AFTER_WAIT')
            if args.mode != 'profile' and identity(data_lock) != identity(expected_data):
                raise ValueError('DATA_LOCK_OBJECT_MISMATCH')
            if args.mode != 'data' and identity(profile_lock) != identity(expected_profile):
                raise ValueError('PROFILE_LOCK_OBJECT_MISMATCH')
            data_id = identity(data_lock) if args.mode != 'profile' else '-'
            profile_id = identity(profile_lock) if args.mode != 'data' else '-'
            print(f"HELD\t{context['fingerprint']}\t{data_id}\t{profile_id}", flush=True)
            # EOF 也是释放；父 Kotlin 退出后不会遗留活着的 broker 持锁。
            sys.stdin.readline()
        print('RELEASED', flush=True)
        return 0
    except Cancelled:
        print('CANCELLED', flush=True)
        return 2
    except TimeoutError:
        print('TIMEOUT', flush=True)
        return 3


if __name__ == '__main__':
    sys.exit(main())

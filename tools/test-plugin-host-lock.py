#!/usr/bin/env python3
"""POSIX: 编译生产 flock 函数，验证与 Python 的真实跨进程双向互斥。"""
import ctypes
import errno
import fcntl
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def run():
    if os.name != 'posix':
        raise SystemExit('POSIX_REQUIRED: run this gate in Linux/WSL; Windows is not flock evidence')
    source = Path(__file__).resolve().parent / 'termux-jni/dsha-plugin-lock.c'
    checks = []
    with tempfile.TemporaryDirectory(prefix='dsha-plugin-host-lock-') as temporary:
        root = Path(temporary)
        wrapper = root / 'lock-test.c'
        wrapper.write_text('#define DSHA_PLUGIN_LOCK_TEST\n#include "'
                           + source.as_posix() + '"\nint test_lock(int fd) {'
                           ' return dsha_plugin_try_exclusive(fd); }\n')
        library = root / 'lock-test.so'
        subprocess.run(['cc', '-shared', '-fPIC', '-O2', '-Wall', '-Wextra', '-Werror',
                        str(wrapper), '-o', str(library)], check=True)
        native = ctypes.CDLL(str(library)).test_lock
        native.argtypes = [ctypes.c_int]
        native.restype = ctypes.c_int
        data = root / '.dsha-data.lock'
        data.write_bytes(b'')
        descriptor = os.open(data, os.O_RDWR | os.O_NOFOLLOW)
        try:
            assert native(-1) == -errno.EBADF
            checks.append('invalid-fd-rejected')
            folder = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                assert native(folder) == -errno.EINVAL
            finally:
                os.close(folder)
            checks.append('nonregular-fd-rejected')
            # 子进程的 Python 先持锁，生产 C 函数必须无等待地拒绝。
            holder = subprocess.Popen([sys.executable, '-u', '-c',
                'import fcntl,sys; f=open(sys.argv[1],"r+"); '
                'fcntl.flock(f,fcntl.LOCK_EX); print("held",flush=True); sys.stdin.read(1)',
                str(data)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
            try:
                assert holder.stdout.readline().strip() == 'held'
                assert native(descriptor) in (-errno.EAGAIN, -errno.EWOULDBLOCK)
                checks.append('python-lock-blocks-native')
            finally:
                holder.communicate('x', timeout=10)
            assert holder.returncode == 0
            assert native(descriptor) == 0
            # 当前 native FD 持锁，独立 Python 进程不能进入；不依赖同进程锁行为。
            probe = '''import fcntl,sys
with open(sys.argv[1],"r+") as f:
 try: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
 except BlockingIOError: print("blocked")
 else: print("acquired")
'''
            result = subprocess.run([sys.executable, '-c', probe, str(data)],
                                    text=True, capture_output=True, check=True)
            assert result.stdout.strip() == 'blocked'
            checks.append('native-lock-blocks-python')
            second = os.open(data, os.O_RDWR | os.O_NOFOLLOW)
            try:
                assert native(second) in (-errno.EAGAIN, -errno.EWOULDBLOCK)
            finally:
                os.close(second)
            checks.append('independent-native-open-is-blocked')
        finally:
            os.close(descriptor)
        result = subprocess.run([sys.executable, '-c', probe, str(data)],
                                text=True, capture_output=True, check=True)
        assert result.stdout.strip() == 'acquired'
        checks.append('java-owner-equivalent-fd-close-releases-flock')
        # 原 profile O_EXCL 协议保留；真实 PID 记录不允许覆盖或接管。
        profile = root / 'package.json.lock'
        profile.write_text(str(os.getpid()) + '\n')
        before = profile.read_bytes()
        try:
            os.open(profile, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
            raise AssertionError('existing profile lock was replaced')
        except FileExistsError:
            pass
        assert profile.read_bytes() == before
        checks.append('profile-exclusive-create-preserves-existing-holder')
        print(json.dumps({'checks': checks, 'passed': len(checks), 'platform': sys.platform,
                          'productionSource': str(source)}, indent=2))


if __name__ == '__main__':
    run()

"""Run the same synthetic ARM binaries on the isolated build377 Android emulator."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import zipfile


E = Path(__file__).resolve().parent
ROOT = E.parents[2]
SDK = ROOT/'cache/build372-android-jni/sdk'
STATE = ROOT/'cache/build377-proot-emulator'
ADB = SDK/'platform-tools/adb.exe'
NDK = ROOT/'toolchains/current/android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin'
LIBS = ('libproot.so', 'libprootloader.so', 'libprootloader32.so', 'libtalloc.so', 'libandroidshmem.so')
LAUNCH = json.loads((E/'launch-r1.json').read_bytes())
FLAGS = [ADB, '-H', '127.0.0.1', '-P', str(LAUNCH['privatePort']), '-s', LAUNCH['serial']]
ENV = dict(os.environ, ANDROID_USER_HOME=str(STATE/'user'), ADB_MDNS_AUTO_CONNECT='0',
           ANDROID_ADB_SERVER_PORT=str(LAUNCH['privatePort']), ADB_VENDOR_KEYS=str(STATE/'user/adbkey'))
ENV.pop('ADB_SERVER_SOCKET', None)


def run(args, check=True, timeout=35):
    return subprocess.run(args, env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
                          encoding='utf-8', errors='replace', check=check)


def adb(*args, **options):
    return run([*FLAGS, *args], **options)


def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--apk', type=Path)
    source.add_argument('--candidate', type=Path)
    parser.add_argument('--attempt', required=True)
    args = parser.parse_args()
    assert args.attempt.isalnum()
    avd = [line for line in adb('emu', 'avd', 'name').stdout.splitlines() if line]
    assert avd == ['DSHA_build377_proot_api30_arm64','OK'], avd
    build = adb('shell', 'getprop ro.build.fingerprint; getprop ro.product.cpu.abilist; getprop ro.build.version.sdk; '
                'uname -m; id; getenforce; grep Seccomp /proc/self/status').stdout.strip()
    assert 'sdk_gphone_arm64' in build and 'aarch64' in build, build
    start = time.perf_counter()
    wd = E/args.attempt
    wd.mkdir()
    host = wd/'input'
    host.mkdir()
    for name in LIBS:
        target = host/name
        if args.apk:
            with zipfile.ZipFile(args.apk) as package:
                target.write_bytes(package.read('lib/arm64-v8a/'+name))
        else:
            loc = args.candidate if name != 'libandroidshmem.so' else ROOT/'source/build377-local/app/src/main/jniLibs/arm64-v8a'
            target.write_bytes((loc/name).read_bytes())
    metadata = dict(emulator=avd, environment=build, serial=LAUNCH['serial'], port=LAUNCH['privatePort'],
                    at=time.time(), sourceApk=str(args.apk) if args.apk else None,
                    apkSha256=digest(args.apk) if args.apk else None,
                    sha256={name:digest(host/name) for name in LIBS}, commandLog=[], cases=[])
    receipt=wd/'cases.json'

    def save():
        metadata['elapsedSeconds'] = time.perf_counter()-start
        receipt.write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

    def recorded(argv):
        p = run(argv, check=False)
        metadata['commandLog'].append(dict(argv=list(map(str,argv)), result=p.stdout, status=p.returncode))
        save()
        p.check_returncode()
        return p.stdout

    fixture = host/'root'
    fixture.mkdir()
    for bits, triple in [(64, 'aarch64-linux-android30'), (32, 'armv7a-linux-androideabi30')]:
        recorded([NDK/'clang.exe', '--target='+triple, '-O2', '-static', E/'guest.c', '-o', fixture/f'guest{bits}'])
        recorded([NDK/'llvm-readelf.exe', '--file-header', '--program-headers', fixture/f'guest{bits}'])
    (fixture/'proof.txt').write_bytes(b'dsha-local-test\n')
    remote = '/data/local/tmp/dsha377_'+args.attempt
    recorded([*FLAGS, 'shell', 'mkdir '+remote])
    recorded([*FLAGS, 'push', str(host)+'/.', remote+'/'])
    readback = recorded([*FLAGS, 'shell', f'cd {remote} && chmod 755 lib*.so root/guest* && mkdir tmp && '
                         'ln -s libtalloc.so libtalloc.so.2 && ln -s libandroidshmem.so libandroid-shmem.so && sha256sum lib*.so'])
    actual_hashes = {name:sha for sha, name in re.findall(r'(?m)^([a-f0-9]{64})\s+(lib[^\s]+)\s*$', readback)}
    assert {name:actual_hashes.get(name) for name in LIBS} == metadata['sha256'], actual_hashes
    prefix = (f'export LD_LIBRARY_PATH={remote}; export PROOT_LOADER={remote}/libprootloader.so; '
              f'export PROOT_LOADER_32={remote}/libprootloader32.so; export PROOT_TMP_DIR={remote}/tmp; '
              f'cd {remote}; ')
    def test(name, cmd, marker, negate=False):
        t = time.perf_counter()
        p = adb('shell', prefix+cmd, check=False, timeout=40)
        passed = (p.returncode != 0 and marker not in p.stdout) if negate else (p.returncode == 0 and marker in p.stdout)
        metadata['cases'].append(dict(name=name, command=cmd, exit=p.returncode, seconds=time.perf_counter()-t,
                                      marker=marker, negative=negate, passed=passed, output=p.stdout))
        if 'firstResultSeconds' not in metadata: metadata['firstResultSeconds']=time.perf_counter()-start
        save()
    test('version', './libproot.so --version', 'proot')
    for nosec in [True,False]:
        mode = 'compat' if nosec else 'normal'
        env = 'export PROOT_NO_SECCOMP=1; ' if nosec else 'unset PROOT_NO_SECCOMP; '
        test('bionic-shell-'+mode, env+"./libproot.so -0 -r / -w / /system/bin/sh -c 'printf DSHA_BIONIC_SHELL_OK'", 'DSHA_BIONIC_SHELL_OK')
        for bits in [64,32]:
            test(f'static-{bits}-{mode}', env+f'./libproot.so -0 -r root -w / /guest{bits}', f'DSHA_PROOT_GUEST abi={bits} uid=0 proof=ok')
    for bits, key in [(64, 'PROOT_LOADER'), (32, 'PROOT_LOADER_32')]:
        test(f'missing-{bits}-loader', f'export PROOT_NO_SECCOMP=1; export {key}={remote}/absent; '
             f'./libproot.so -0 -r root -w / /guest{bits}', f'DSHA_PROOT_GUEST abi={bits}', negate=True)
    metadata['passed'] = all(r['passed'] for r in metadata['cases'])
    save()
    print(args.attempt, [(r['name'],r['passed']) for r in metadata['cases']])
    raise SystemExit(0 if metadata['passed'] else 1)


if __name__ == '__main__':
    main()

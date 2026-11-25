"""Rebuild Standard PRoot and Samba talloc from the accompanying original inputs."""
import argparse
import concurrent.futures
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
NDK = '26.3.11579264'
INPUTS = {
    'proot-5.1.107.91.zip': 'a7bc2fab34bf9a39073e8291f08a662e848c61a67494e59f5f84f5ca10690128',
    'talloc-2.4.3.tar.gz': 'dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd',
    'libandroid-shmem_0.7_aarch64.deb': '0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6',
    'libandroid-shmem-0.7.tar.gz': '48e80a854df55dd8eb2a5026cb3cc90a10165aae68c591acdabad24488ce621f',
}
LIBRARIES = ('libproot.so', 'libprootloader.so', 'libprootloader32.so', 'libtalloc.so')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def prepare(work):
    inputs = HERE / 'inputs'
    for name, expected in INPUTS.items():
        if digest(inputs / name) != expected:
            raise ValueError('SOURCE_INPUT_DIGEST: ' + name)
    # Only pinned upstream archives are read. Extract exactly the source files used to compile.
    with zipfile.ZipFile(inputs / 'proot-5.1.107.91.zip') as archive:
        for name in archive.namelist():
            if name.startswith('proot-5.1.107.91/src/'):
                archive.extract(name, work)
    with tarfile.open(inputs / 'talloc-2.4.3.tar.gz') as archive:
        for name in ('talloc.c', 'talloc.h'):
            (work / name).write_bytes(archive.extractfile('talloc-2.4.3/' + name).read())
    shutil.copyfile(HERE / 'talloc-config/replace.h', work / 'replace.h')
    # Termux's binary package supplies both its NDK overlay header and its link-time ELF.
    with (inputs / 'libandroid-shmem_0.7_aarch64.deb').open('rb') as deb:
        if deb.read(8) != b'!<arch>\n':
            raise ValueError('DEBIAN_AR_MAGIC')
        while header := deb.read(60):
            size = int(header[48:58])
            if header[:16].rstrip(b' /') == b'data.tar.xz':
                break
            deb.seek(size + size % 2, os.SEEK_CUR)
        else:
            raise ValueError('DEBIAN_DATA_MISSING')
        with tarfile.open(fileobj=io.BytesIO(deb.read(size)), mode='r:xz') as archive:
            for member, name in (('lib/libandroid-shmem.so', 'libandroid-shmem.so'),
                                 ('include/sys/shm.h', 'include/sys/shm.h')):
                target = work / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile('./data/data/com.termux/files/usr/' + member).read())
    (work / 'build.h').write_text('#define HAVE_PROCESS_VM 1\n#define HAVE_SECCOMP_FILTER 1\n', encoding='ascii')
    (work / 'objects').mkdir()
    src = 'proot-5.1.107.91/src'
    # DSHA's original Android build documented this named-fd crash in canonicalize().
    # Preserve the narrow fix; the other runtime assertions remain enabled.
    canon = work / src / 'path/canon.c'
    data = canon.read_bytes()
    old = b'assert(status != 1 || sscanf(guest_path, "/proc/%*d/fd/%d", &status) == 1);'
    if data.count(old) != 1:
        raise ValueError('CANON_PATCH_CONTEXT')
    canon.write_bytes(data.replace(old, b'/* DSHA 2026-11-25: Android named fds may remain symlinks. */'))
    return src


def compile_libraries(ndk, work, jobs):
    if not re.search(r'^Pkg.Revision\s*=\s*' + re.escape(NDK) + r'\s*$',
                     (ndk / 'source.properties').read_text(), re.MULTILINE):
        raise ValueError('EXPECTED_NDK_' + NDK)
    host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
    tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    suffix = '.exe' if os.name == 'nt' else ''
    clang = str(tools / ('clang' + suffix))
    src = prepare(work)
    target = '--target=aarch64-linux-android26'
    cflags = ['-O2', '-g0', '-D_GNU_SOURCE', '-D_FILE_OFFSET_BITS=64']
    link = ['-Wl,-z,noexecstack,-z,max-page-size=16384,--build-id=none']

    def run(args):
        return subprocess.run(args, cwd=work, check=True, stdout=subprocess.PIPE, text=True).stdout

    run([clang, target, *cflags, '-fPIC', '-shared', *link, '-Wl,-soname,libtalloc.so.2',
         'talloc.c', '-o', 'libtalloc.so'])
    for abi, address, loader in ((target, '0x2000000000', 'libprootloader.so'),
                                 ('--target=armv7a-linux-androideabi26', '0x20000000', 'libprootloader32.so')):
        run([clang, abi, '-O2', '-g0', '-fno-stack-protector', '-fPIC', '-ffreestanding', '-static',
             '-nostdlib', '-I' + src, '-Wl,-Ttext=' + address, *link,
             src + '/loader/assembly.S', src + '/loader/loader.c', '-o', loader])
    symbols = run([str(tools / ('llvm-readelf' + suffix)), '--wide', '-s', 'libprootloader.so'])
    offsets = {name: int(re.search(r'\s\d+:\s+([0-9a-f]+).*\s' + name + r'\s*$', symbols, re.M)[1], 16)
               for name in ('_start', 'pokedata_workaround')}
    (work / 'loader-info.c').write_text('#include <unistd.h>\nconst ssize_t offset_to_pokedata_workaround=%d;\n'
                                        % (offsets['pokedata_workaround'] - offsets['_start']), encoding='ascii')
    flags = [clang, target, *cflags, '-fPIE', '-DARG_MAX=131072', '-DVERSION="v5.1.107.91-dsha"',
             '-DPROOT_UNBUNDLE_LOADER="/data/data/com.termux/files/usr/libexec/proot"',
             '-DWITH_LIBANDROID_SHMEM', '-Iinclude', '-I.', '-I' + src]
    makefile = (work / src / 'GNUmakefile').read_text(encoding='utf-8')
    objects = re.findall(r'([\w/-]+\.o)', makefile.split('OBJECTS +=', 1)[1].split('define define_from_arch', 1)[0])

    def compile_object(name):
        output = 'objects/' + name.replace('/', '_')
        # This upstream translation unit calls strcmp/memset without including their declarations.
        headers = ['-include', 'string.h'] if name == 'extension/ashmem_memfd/ashmem_memfd.o' else []
        run([*flags, *headers, '-c', src + '/' + name[:-2] + '.c', '-o', output])
        return output

    with concurrent.futures.ThreadPoolExecutor(jobs) as executor:
        built = list(executor.map(compile_object, objects))
    run([*flags, '-c', 'loader-info.c', '-o', 'objects/loader-info.o'])
    run([*flags, '-pie', *link, *built, 'objects/loader-info.o', '-L.', '-l:libtalloc.so',
         '-l:libandroid-shmem.so', '-ldl', '-o', 'libproot.so'])
    for name in LIBRARIES:
        run([str(tools / ('llvm-strip' + suffix)), '--strip-unneeded', name])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='new directory for the four native files')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('--output must be a new directory')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent, prefix='compile-') as tmp:
        work = Path(tmp)
        compile_libraries(args.ndk.resolve(), work, args.jobs)
        stage = work / 'result'
        stage.mkdir()
        for name in LIBRARIES:
            shutil.move(work / name, stage / name)
        stage.rename(args.output)
    print(json.dumps({name: digest(args.output / name) for name in LIBRARIES}, indent=2))


if __name__ == '__main__':
    main()

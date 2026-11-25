"""Classify only packaged native members and exact upstream license bytes. No APK is modified."""
import argparse
from collections import Counter
import ctypes
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from urllib.request import urlopen
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--strip', type=Path, required=True)
args = parser.parse_args()
start = time.perf_counter()
free = shutil.disk_usage(args.root).free
sha = lambda data: hashlib.sha256(data).hexdigest()
revision = '14b72a764490d0280eee3915ecfa3e8a132b392e'
prefix = 'https://raw.githubusercontent.com/coderredlab/proroot/'
refs = ['65a28b7f8af182f3bde0c75156c39a30b82bc0ec', '2f175a7e36dbeb2548ef771d2091257da0442488', 'c04b1ed1563d432f9ec727272979de98f68c8031', revision]
licenses = {}
for ref in refs:
    with urlopen(prefix + ref + '/LICENSE', timeout=30) as response:
        data = response.read()
    licenses[ref] = dict(url=prefix + ref + '/LICENSE', sha256=sha(data), bytes=len(data), mentionsLibldlinux=b'libldlinux' in data, requiresAttribution=b'must include attribution' in data)
original = {}
stripped = {}
with tempfile.TemporaryDirectory(dir=args.out.parent) as tmp:
    for name in ['libproroot.so', 'libproroot-bridge.so', 'libproroot-runtime.so', 'libproroot-stub-loader.so', 'libproroot-linker.so']:
        with urlopen(prefix + revision + '/arm64-v8a/' + name, timeout=30) as response:
            data = response.read()
        original[name] = sha(data)
        path = Path(tmp) / name
        path.write_bytes(data)
        subprocess.run([args.strip, '--strip-unneeded', path], check=True)
        stripped[name] = sha(path.read_bytes())
rows = []
first = None
paths = sorted((args.root / 'release').rglob('*.apk')) + sorted((args.root / 'release-experimental').rglob('*.apk'))
for path in paths:
    with path.open('rb') as f:
        apksha = hashlib.file_digest(f, 'sha256').hexdigest()
    with zipfile.ZipFile(path) as z:
        natives = {m.filename:sha(z.read(m)) for m in z.infolist() if m.filename.startswith('lib/') and 'proroot' in m.filename.lower() and 'dsha-' not in m.filename}
        texts = []
        for m in z.infolist():
            if m.filename.startswith('assets/') and m.file_size <= 102400 and any(word in m.filename.lower() for word in ('license','notice','copying')):
                d = sha(z.read(m))
                if d in {r['sha256'] for r in licenses.values()}:
                    texts.append(dict(member=m.filename, sha256=d))
    names = {Path(n).name:v for n,v in natives.items()}
    if not natives: state='no-matching-native-name'
    elif names == original and len(natives)==5: state='v1.2.8-original'
    elif names == stripped and len(natives)==5: state='v1.2.8-llvm-strip-unneeded'
    else: state='other-or-mixed'
    rows.append(dict(path=path.relative_to(args.root).as_posix(), bytes=path.stat().st_size, sha256=apksha, memberHashes=natives, recognizedNotice=texts, state=state))
    if first is None: first=time.perf_counter()-start
result=dict(scope='local APKs in release and release-experimental; ZIP native entries plus named assets up to 100 KiB; no remote distribution or complete legal determination', firstApkSeconds=first,totalSeconds=time.perf_counter()-start,freeStart=free,freeEnd=shutil.disk_usage(args.root).free, upstreamLicenseHistory=licenses, original=original, stripped=stripped, counts=dict(Counter(r['state'] for r in rows)), withNotice=sum(bool(r['recognizedNotice']) for r in rows), rows=rows)
args.out.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
print(result['counts'], 'recognized notices', result['withNotice'], 'elapsed',round(result['totalSeconds'],3))

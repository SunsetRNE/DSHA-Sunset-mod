"""Read an exact signed candidate APK back and bind it to the R4 browser inputs."""
from pathlib import Path
import datetime as dt
import hashlib
import json
import sys
import tarfile
import time
import zipfile

apk=Path(sys.argv[1]).resolve()
out=Path(sys.argv[2])
expected=json.loads((Path(__file__).resolve().parent/'r1/comparison.json').read_text('utf-8'))
def sha(stream): return hashlib.file_digest(stream,'sha256').hexdigest()
start=time.monotonic()
begin=dt.datetime.now(dt.timezone.utc).isoformat()
with apk.open('rb') as f: apk_hash=sha(f)
rows={}
with zipfile.ZipFile(apk) as z:
    runtime='assets/dsh-runtime.bin'
    with z.open(runtime) as f: runtime_sha=sha(f)
    assert runtime_sha==expected['archiveSha256'],runtime_sha
    for row in expected['earlierSettingsBindings']:
        name=row['name']
        if name.startswith('dsh-launch-target/'):
            with z.open('assets/builtin-plugins/'+name) as f: digest=sha(f)
            assert digest==row['r4'],name
            rows[name]=digest
    names=dict(expected['runtimeOverlay'])
    prefix='usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/'
    with z.open(runtime) as f, tarfile.open(fileobj=f,mode='r|gz') as tf:
        for member in tf:
            if not member.name.startswith(prefix): continue
            name=member.name[len(prefix):]
            if name not in names: continue
            actual=sha(tf.extractfile(member))
            assert actual==names.pop(name),name
            rows[name]=actual
            if not names: break
    assert not names,names
assert len(rows)==40
record={'apk':str(apk),'apkSha256':apk_hash,'apkBytes':apk.stat().st_size,'archiveSha256':runtime_sha,
        'verified':len(rows),'members':rows,'startedUtc':begin,'completedUtc':dt.datetime.now(dt.timezone.utc).isoformat(),
        'elapsedMs':round((time.monotonic()-start)*1000)}
with out.open('x',encoding='utf-8') as f:
    json.dump(record,f,indent=2,ensure_ascii=False)
    f.write('\n')
print('40 matched (18 original settings entries and both R4 transport modules included)',apk_hash)

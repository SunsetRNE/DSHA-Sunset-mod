"""Compare filenames, Git blobs and vendor notes at the upstream license-change boundaries."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import re
import tempfile
import subprocess
import zipfile
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import argparse

p=argparse.ArgumentParser()
p.add_argument('--root',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--strip',type=Path,required=True)
a=p.parse_args()
revs=['65a28b7f8af182f3bde0c75156c39a30b82bc0ec','2f175a7e36dbeb2548ef771d2091257da0442488','c04b1ed1563d432f9ec727272979de98f68c8031','14b72a764490d0280eee3915ecfa3e8a132b392e']
def fetch(ref):
    url='https://api.github.com/repos/coderredlab/proroot/git/trees/'+ref+'?recursive=1'
    try:
        with urlopen(Request(url,headers={'User-Agent':'dsha-local-provenance'}),timeout=20) as r: tree=json.load(r)
    except HTTPError as e: return dict(ref=ref,url=url,http=e.code)
    return dict(ref=ref,url=url,truncated=tree['truncated'],elfFiles=[dict(path=i['path'],bytes=i['size'],gitBlob=i['sha']) for i in tree['tree'] if i['path'].endswith('.so')])
with concurrent.futures.ThreadPoolExecutor(4) as pool: upstream=list(pool.map(fetch,revs))
history=json.loads((a.output.parent/'local-history-r1.json').read_text(encoding='utf8'))
rows=[]
with urlopen('https://raw.githubusercontent.com/coderredlab/proroot/'+revs[0]+'/arm64-v8a/libldlinux.so',timeout=20) as r:
    original=r.read()
with tempfile.TemporaryDirectory(dir=a.output.parent) as tmp:
    elf=Path(tmp)/'historical-linker'
    elf.write_bytes(original)
    subprocess.run([a.strip,'--strip-unneeded',elf],check=True)
    stripped=elf.read_bytes()
sizes={len(original),len(stripped)}
hashes={hashlib.sha256(v).hexdigest() for v in (original,stripped)}
byteMatches=[]
memberCount=0
for row in history['rows']:
    path=a.root/row['path']
    with zipfile.ZipFile(path) as apk:
        matches=[n for n in apk.namelist() if any(v in n.lower() for v in ('ldlinux','ld-linux'))]
        for member in apk.infolist():
            memberCount+=1
            if member.file_size in sizes and hashlib.sha256(apk.read(member)).hexdigest() in hashes:
                byteMatches.append(dict(path=row['path'],member=member.filename))
    if matches: rows.append(dict(path=row['path'],members=matches))
result=dict(upstream=upstream,scannedLocalApks=len(history['rows']),directZipMembersContainingLdLinux=rows,scope='Top-level ZIP paths + upstream trees, not contents nested inside guest rootfs or derivative-work analysis')
result.update(historicalRawSha256=hashlib.sha256(original).hexdigest(),historicalStrippedSha256=hashlib.sha256(stripped).hexdigest(),
              historicalRawBytes=len(original), historicalStrippedBytes=len(stripped), directZipMembers=memberCount,
              historicalContentMatchesAnyName=byteMatches)
a.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf8')
print('local matches',len(rows),'trees',[(r['ref'][:8],len(r.get('elfFiles',[])),[(i['path'],i['gitBlob'][:9]) for i in r.get('elfFiles',[]) if 'ldlinux' in i['path']]) for r in upstream])

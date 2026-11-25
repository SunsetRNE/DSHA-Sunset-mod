"""Bind the checked final archive and web assets to the user's selected signed APK and source commit."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('repo',type=Path);p.add_argument('apk',type=Path);p.add_argument('commit');p.add_argument('out',type=Path);a=p.parse_args()
inputs=Path(__file__).parent/'distribution/latest/sources.json'
def hash_file(file):
    with file.open('rb') as s:return hashlib.file_digest(s,'sha256').hexdigest()
commit=subprocess.check_output(['git','-C',str(a.repo),'rev-parse',a.commit+'^{commit}'],text=True).strip()
assets=a.repo/'app/src/main/assets'
checked=[*sorted(assets.glob('web-brand-*-patch.json')),*sorted(assets.glob('lan-mux-*-patch.json')),*sorted((assets/'web-brand').glob('*')),assets/'web-default-brand-patch.json',assets/'runtime-patches.json',assets/'managed-runtime-inputs.json']
rows={}
with zipfile.ZipFile(a.apk) as z:
    for f in checked:
        member='assets/'+f.relative_to(assets).as_posix()
        assert z.namelist().count(member)==1,member
        signed=z.read(member)
        committed=subprocess.check_output(['git','-C',str(a.repo),'show',commit+':'+f.relative_to(a.repo).as_posix()])
        assert signed==committed and signed==f.read_bytes(),member
        rows[member]=hashlib.sha256(signed).hexdigest()
    with z.open('assets/dsh-runtime.bin') as entry:
        digest=hashlib.file_digest(entry,'sha256').hexdigest()
    assert digest==json.loads(inputs.read_bytes())['archiveSha256'],digest
result={'sourceCommit':commit,'apk':str(a.apk.resolve()),'bytes':a.apk.stat().st_size,'sha256':hash_file(a.apk),'runtimeSha256':digest,'inputs':rows}
a.out.write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
print(commit,result['sha256'],digest,len(rows))

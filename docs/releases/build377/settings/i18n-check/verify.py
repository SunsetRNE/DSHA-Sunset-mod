import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

here=Path(__file__).resolve().parent
workspace=here.parents[3]
root=workspace/'source/build377-local'
out=here/sys.argv[1]
out.mkdir()
times=[]
def run(name,cmd):
    start=dt.datetime.now(dt.timezone.utc)
    begin=time.monotonic()
    result=subprocess.run([str(x) for x in cmd],cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (out/(name+'.log')).write_bytes(result.stdout)
    times.append(dict(name=name,command=list(map(str,cmd)),startedUtc=start.isoformat(),
            finishedUtc=dt.datetime.now(dt.timezone.utc).isoformat(),ms=round((time.monotonic()-begin)*1000),exit=result.returncode))
    (out/'commands.json').write_text(json.dumps(times,ensure_ascii=False,indent=2),encoding='utf-8')
    assert result.returncode==0,name
    return result.stdout.decode('utf-8')
python=[sys.executable,'-X','utf8','-B']
run('checker-tests',python+['tools/test-ui-i18n.py'])
run('xml-consistency',python+['tools/localize-android-ui.py','--check'])
run('catalog-tests',python+['tools/test-ui-language-catalog.py'])
generated=out/'generated'
run('generate',python+['tools/prepare-ui-languages.py','--output',generated])
worker=json.loads((workspace/'artifacts/build376-local/windows-r2/worker-inputs.json').read_text(encoding='utf-8-sig'))
java=Path(worker['javaExecutable'])
cp=os.pathsep.join([*worker['classpath'],str(workspace/'toolchains/current/android-sdk/platforms/android-34/android.jar')])
classes=out/'classes'
classes.mkdir()
base=root/'app/src/main/java/com/deepseekharness/app'
sources=[*generated.rglob('*.java'),base/'ui/WebSettingsDocument.java',base/'transfer/SelectedDocumentSave.java',
         base/'backup/DocumentStreams.java',base/'util/UiText.java',here/'VerifyMessages.java']
args=['--release','17','-encoding','UTF-8','-d',classes,'-cp',cp,*sources]
def argfile(name,args):
    file=out/(name+'.args')
    file.write_text('\n'.join(json.dumps(str(a),ensure_ascii=False) for a in args),encoding='utf-8')
    return '@'+str(file)
run('compile',[java.with_name('javac.exe'),argfile('compile',args)])
sys.path.insert(0,str(root/'tools'))
from ui_language_catalog import LANGUAGES,load_catalog
_,catalog=load_catalog(root)
rows=run('messages',[java,argfile('messages',['-ea','-Dfile.encoding=UTF-8','-cp',str(classes)+os.pathsep+cp,'VerifyMessages',*LANGUAGES])]).splitlines()
assert len(rows)==len(LANGUAGES)==10
checks=[]
for row in rows:
    lang,rescue,title=row.split('\t')
    a=base64.b64decode(rescue).decode(); b=base64.b64decode(title).decode()
    filename='AB'*16+'.bin'
    assert a.count(filename)==1 and a==catalog[lang]['x377RetainedOriginal'].replace('%s',filename)
    assert b==catalog[lang]['x377ReadOnlyWorkbenchTitle']
    checks.append(dict(language=lang,recovery=a,title=b))
(out/'texts.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
run('source-inventory',python+['tools/check-ui-i18n.py','--report',out/'after-377.json'])
print('PASS',len(rows),'languages and gate checks')

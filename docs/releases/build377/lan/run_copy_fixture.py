"""Direct, offline Robolectric runs. Keep every round (including baseline and fixture errors)."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

here = Path(__file__).resolve().parent
root = here.parents[2]
trial, version = sys.argv[1:]
out = here / trial
out.mkdir()
classes = out / 'classes'
classes.mkdir()
(out / 'tmp').mkdir()
candidate = root / 'source/build377-local'
src = root / ('source/build376-local' if version == 'baseline' else 'source/build377-local')
worker = json.loads((root / 'artifacts/build376-local/windows-r2/worker-inputs.json').read_text(encoding='utf-8-sig'))
java = Path(worker['javaExecutable'])
cp = os.pathsep.join(worker['classpath'])
package=src/'app/src/main/java/com/deepseekharness/app'
sources = [package/'ui/LaunchFragment.java',root/'artifacts/build375-local/tests/MatrixJUnit.java']
if version=='baseline':
    original=(src/'app/src/test/java/com/deepseekharness/app/ui/LaunchAccessAddressesTest.java').read_text(encoding='utf8')
    supplied=(candidate/'app/src/test/java/com/deepseekharness/app/ui/LaunchAccessAddressesTest.java').read_text(encoding='utf8')
    start=supplied.index('  @Test\n  public void retiredLanCopyButtonNeverCopiesTheLocalAuthUrl')
    end=supplied.index('\n  }',start)+len('\n  }')
    method=supplied[start:end].replace('assertFalse(clipboard().hasPrimaryClip());','System.out.println("baseline returned local credential = " + (clipboard().hasPrimaryClip() && LOCAL.equals(clipboard().getPrimaryClip().getItemAt(0).getText().toString())));\n    assertFalse(clipboard().hasPrimaryClip());')
    source=out/'LaunchAccessAddressesTest.java'
    source.write_text(original.rstrip()[:-1]+method+'\n}\n',encoding='utf8')
    sources.append(source)
else:
    sources.extend(package/p for p in ['LanProxyService.java','LanAuth.java','LanRequestOrigin.java','AndroidLanIdentity.java','LanTlsIdentity.java','util/LanNetworkState.java','util/LanAccessPresentation.java','util/LanFailureResponse.java','util/LanPortPolicy.java'])
    sources.append(candidate/'app/src/test/java/com/deepseekharness/app/ui/LaunchAccessAddressesTest.java')
targets=['LaunchAccessAddressesTest#retiredLanCopyButtonNeverCopiesTheLocalAuthUrl']
def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()

def execute(step, exe, args):
    argfile = out / (step + '.args')
    argfile.write_text('\n'.join(json.dumps(str(x), ensure_ascii=False) for x in args), encoding='utf-8')
    start = time.monotonic()
    begin = now()
    with (out / (step + '.log')).open('wb') as log:
        result = subprocess.run([exe, '@' + str(argfile)], cwd=worker['workingDirectory'], stdout=log, stderr=subprocess.STDOUT)
    return {'step':step, 'command':[str(exe), '@'+str(argfile)], 'startedUtc':begin, 'completedUtc':now(),
            'durationMs':round((time.monotonic()-start)*1000), 'exit':result.returncode}

args = ['--release','17','-encoding','UTF-8','-cp',cp+os.pathsep+str(root/'toolchains/current/android-sdk/platforms/android-34/android.jar'),'-d',classes,*sources]
rounds = [execute('compile',java.with_name('javac.exe'),args)]
if rounds[-1]['exit']==0:
    key = 'com.deepseekharness.app.ui.'
    rounds.append(execute('junit',java,['-ea','-Xmx1024m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8',
        '--add-opens=java.base/java.io=ALL-UNNAMED','-Djava.io.tmpdir='+str(out/'tmp'),
        *['-D'+k+'='+str(v).lower() if isinstance(v,bool) else '-D'+k+'='+str(v) for k,v in worker['testSystemProperties'].items()],
        '-cp',str(classes)+os.pathsep+cp,'MatrixJUnit',*[key+t for t in targets]]))
(out/'receipt.json').write_text(json.dumps({'version':version,'rounds':rounds,'files':{str(x):sha(x) for x in sources}},indent=2),encoding='utf-8')
print([(r['step'],r['exit']) for r in rounds])
sys.exit(rounds[-1]['exit'])

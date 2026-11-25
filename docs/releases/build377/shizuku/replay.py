"""Run from this directory. Uses pinned offline Robolectric and the saved test class path, no Gradle or device."""
import json, os, subprocess, sys, time
from pathlib import Path
base=Path("F:/DSHA_RESTART")
ev=Path(__file__).resolve().parent
stage=Path(sys.argv[1])
assert stage.name in ('before','after')
out=ev/stage
out.mkdir(exist_ok=True)
classes=out/'classes'
classes.mkdir(exist_ok=True)
tmp=out/'tmp'
tmp.mkdir(exist_ok=True)
work=base/'source'/('build376-local' if stage.name=='before' else 'build377-local')
tools=base/'toolchains/current'
jdk=tools/'jdk-17/bin'
src=work/'app/src/main/aidl'
gen=out/'generated'
aidl=[str(tools/'android-sdk/build-tools/36.0.0/aidl.exe'),'--lang=java','--min_sdk_version=30','-I'+str(src),'--out='+str(gen),str(src/'com/deepseekharness/app/IShellService.aidl')]
subprocess.run(aidl,check=True)
original=base/'artifacts/build376-local/lan-android-r1'
def read_args(filename):
 return [json.loads(l) for l in (original/filename).read_text(encoding='utf-8-sig').splitlines() if l.strip()]
def arglist(name,args):
 f=out/(name+'.args')
 f.write_text('\n'.join(json.dumps(str(a),ensure_ascii=False) for a in args if a)+'\n',encoding='utf8')
 return '@'+str(f)
def run(command,log):
 start=time.perf_counter()
 with (out/log).open('w',encoding='utf8') as stream:
  process=subprocess.run(command,stdout=stream,stderr=subprocess.STDOUT)
 print(stage, log, process.returncode, round(time.perf_counter()-start,3))
 if process.returncode:
  print((out/log).read_text(encoding='utf8',errors='replace')[-2200:])
 return process.returncode
c=read_args('javac.args')
cp=c[c.index('-cp')+1]
def adapted(cp):
 parts=cp.replace('build375-tests-r14','build376-local').split(';')
 # Prepend the exact APK's Java code and use its compiled, unmodified dependencies.
 old=base/'source/build376-local/app/build/intermediates'
 deps=[old/'javac/standardDebug/compileStandardDebugJavaWithJavac/classes',old/'javac/standardDebugUnitTest/compileStandardDebugUnitTestJavaWithJavac/classes',old/'built_in_kotlinc/standardDebug/compileStandardDebugKotlin/classes']
 return ';'.join([str(classes),*map(str,deps),*parts])
c=['--release','17','-encoding','UTF-8','-cp',adapted(cp),'-d',classes]
app=Path('com/deepseekharness/app')
srcmain=work/'app/src/main/java'/app
c+=[gen/app/'IShellService.java',srcmain/'ShellService.java',srcmain/'ShizukuShell.java',base/'source/build377-local/app/src/test/java'/app/'ShizukuServiceLifecycleTest.java',base/'artifacts/build375-local/tests/MatrixJUnit.java']
if run([str(jdk/'javac.exe'),arglist('compile',c)],'compile.txt'):sys.exit(1)
j=read_args('junit.args')
j=j[:j.index('-cp')+2]
j[-1]=adapted(j[-1])
j=[('-Djava.io.tmpdir='+str(tmp)) if a.startswith('-Djava.io.tmpdir=') else a for a in j]
j+=['MatrixJUnit','com.deepseekharness.app.ShizukuServiceLifecycleTest']
sys.exit(run([str(jdk/'java.exe'),arglist('junit',j)],'junit.txt'))

from pathlib import Path
import subprocess,sys,time,json,os,datetime
E=Path(__file__).resolve().parent
W=E.parents[2]/'source/build377-local'
D=W/'app/build/intermediates'
flavor='standardDebug'
tasks='StandardDebug'
prod=D/'javac'/flavor/('compile'+tasks+'JavaWithJavac')/'classes'
kt=D/'built_in_kotlinc'/flavor/('compile'+tasks+'Kotlin')/'classes'
runtime_r=D/'compile_and_runtime_r_class_jar'/flavor/('process'+tasks+'Resources')/'R.jar'
compile_r=D/'compile_r_class_jar'/flavor/('generate'+tasks+'RFile')/'R.jar'
android=Path('F:/DSHA/_toolchains/android-sdk/platforms/android-37.0/android.jar')
out=E/sys.argv[1]
checks=[prod/'com/deepseekharness/app/HttpShellService.class',prod/'com/deepseekharness/app/vscreen/PhysicalScreenManager.class', kt/'com/deepseekharness/app/capabilities/CapabilityBroker.class']
started=time.monotonic()
now=lambda:datetime.datetime.now().astimezone().isoformat(timespec='milliseconds')
while not all(p.is_file() for p in checks) or not (runtime_r.is_file() or compile_r.is_file()):
 if time.monotonic()-started>600:raise SystemExit('current Gradle classes not available after 10 minutes')
 time.sleep(2)
resource=runtime_r if runtime_r.is_file() else compile_r
args=[sys.executable,str(W/'tools/test-physical-screen-flow.py'),'--dependency-reference',str(E/'flow-deps.json'),'--production-classes',str(prod),'--resource-jar',str(resource),'--kotlin-classes',str(kt),'--android-jar',str(android),'--out',str(out),'--junit-timeout','150']
env=os.environ.copy();env['JAVA_HOME']='F:/DSHA/_toolchains/jdk-17';env['PYTHONUTF8']='1'
info={'started':now(),'waitedForClassesSeconds':round(time.monotonic()-started,2),'cwd':str(W),'argv':args,'firstTestArtifact':None}
start=time.monotonic()
with out.with_suffix('.log').open('wb') as log:
 proc=subprocess.Popen(args,cwd=W,env=env,stdout=log,stderr=subprocess.STDOUT)
 while proc.poll() is None:
  if not info['firstTestArtifact']:
   result=next((x for x in ['native-screenshot.json','mcp-node-chain.json'] if (out/x).is_file()),None)
   if result:info['firstTestArtifact']={'at':now(),'artifact':result,'elapsedSeconds':round(time.monotonic()-start,3)}
  time.sleep(.05)
info.update(finished=now(),elapsedSeconds=round(time.monotonic()-start,3),exitCode=proc.returncode)
out.with_suffix('.json').write_text(json.dumps(info,indent=2),encoding='utf-8')
print(json.dumps(info,ensure_ascii=False))
raise SystemExit(proc.returncode)

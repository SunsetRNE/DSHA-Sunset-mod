from pathlib import Path
import subprocess,json,datetime,time,sys
E=Path(__file__).resolve().parent
W=E.parents[2]/'source/build377-local'
ROOT=E.parents[2]
baseline='12efafd7d3530b412d48ccb66fb657c6bd7cc8ac'
now=lambda:datetime.datetime.now().astimezone().isoformat(timespec='milliseconds')
matrix=ROOT/'artifacts/build375-local/tests/MatrixJUnit.java'
inputs=json.loads((ROOT/'artifacts/build376-formal-20261125/targeted-r1/worker-inputs.json').read_text(encoding='utf-8-sig'))
java=Path('F:/DSHA/_toolchains/jdk-17/bin/java.exe')
rt=ROOT/'cache/robolectric/4.15.1-frameworks/android-all-14-robolectric-10818077.jar'
base='com/deepseekharness/app/'
main='app/src/main/java/'+base
test='app/src/test/java/'+base
changed=['screen/ScreenReply','HttpShellService','util/PrivilegedScreenRoutes','vscreen/VirtualScreenCore','vscreen/PhysicalScreenManager','screen/ScreenRequestContext','screen/MainScreenActions','screen/ScreenScreenshotCapture','screen/ScreenTargetResolver','util/VirtualScreenWindowEvidence','vscreen/PhysicalScreenCore','vscreen/PhysicalScreenDisplaySession']
target=['screen/ScreenScreenshotPrivacyTest','screen/ScreenTargetResolverEvidenceTest','util/VirtualScreenWindowEvidenceTest','util/VirtualScreenObservationHintTest','vscreen/PhysicalScreenManagerTest','util/ScreenOwnershipTest','util/ScreenCaptureFailureTest','screen/ScreenCaptureCallbackQueueTest','screen/ScreenActivityRecorderTest','util/MainScreenWindowPolicyTest','util/PrivilegedScreenRoutesTest','vscreen/VirtualScreenManagerRevocationTest']
phase=sys.argv[1]
tag=sys.argv[2]
out=E/tag;out.mkdir(exist_ok=True)
classes=out/'classes';classes.mkdir(exist_ok=True)
def args(path,items):
 path.write_text('\n'.join('"'+str(x).replace('\\','/').replace('"','\\"')+'"' for x in items),encoding='utf-8')
def run(name,binary,params):
 p=out/(name+'.args');args(p,params)
 cwd=Path(inputs['workingDirectory']) if name=='junit' else W
 start=time.perf_counter()
 info={'started':now(),'command':[str(binary),'@'+str(p)],'cwd':str(cwd),'firstFinished':None}
 with (out/(name+'.log')).open('w',encoding='utf-8') as log:
  proc=subprocess.Popen([str(binary),'@'+str(p)],cwd=cwd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  for data in proc.stdout:
   line=data.decode('utf-8',errors='replace')
   log.write(line);log.flush()
   if line.startswith('FINISH') and info['firstFinished'] is None: info['firstFinished']={'at':now(),'elapsedSeconds':round(time.perf_counter()-start,3),'line':line.rstrip()}
  code=proc.wait()
 info.update(finished=now(),elapsedSeconds=round(time.perf_counter()-start,3),exitCode=code)
 (out/(name+'.json')).write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf-8')
 print(json.dumps(info,ensure_ascii=False),flush=True)
 return code
cp=inputs['classpath']
sources=[matrix,E/'Comparison.java']
tests=['com.deepseekharness.app.screen.Comparison']
if phase=='after':
 sources += [W/(main+name+'.java') for name in changed]+[W/(test+name+'.java') for name in target];tests+=['com.deepseekharness.app.'+name.replace('/','.') for name in target]
elif phase=='before':
 for name in changed:
  rel=main+name+'.java';p=out/rel;p.parent.mkdir(parents=True,exist_ok=True)
  p.write_bytes(subprocess.check_output(['git','show',baseline+':'+rel],cwd=W))
  sources.append(p)
else: raise ValueError(phase)
code=run('compile',java.with_name('javac.exe'),['--release','17','-encoding','UTF-8','-cp',';'.join([str(rt)]+cp),'-d',classes]+sources)
if not code: code=run('junit',java,['-ea','-Xmx1200m','-XX:ActiveProcessorCount=2','-Dfile.encoding=UTF-8','--add-opens=java.base/java.io=ALL-UNNAMED','-Drobolectric.dependency.dir='+str(rt.parent),'-Drobolectric.offline=true','-Dscreen.comparison='+phase,'-cp',';'.join([str(classes)]+cp),'MatrixJUnit']+tests)
sys.exit(code)

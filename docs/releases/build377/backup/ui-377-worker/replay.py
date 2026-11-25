from pathlib import Path
import sys,json,subprocess,os,datetime,hashlib
e=Path(__file__).resolve().parent
new=Path('F:/DSHA_RESTART/source/build377-local')
worker=e.parents[1]/'windows-r2/worker-inputs.json'
x=json.loads(worker.read_text(encoding='utf-8-sig'))
name=sys.argv[1]
d=e/name;d.mkdir()
src=new/'app/src/test/java/com/deepseekharness/app/ui/NativeDataChoicesTest.java'
mode=sys.argv[2] if len(sys.argv)>2 else 'compiled'
classpath=x['classpath']
if mode=='compiled':
    classpath=[str(e/'original-test-classes'),*classpath]
java=Path(x['javaExecutable'])
fq='com.deepseekharness.app.ui.NativeDataChoicesTest'
def run(stage,args,tool):
    argfile=d/(stage+'.args')
    argfile.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in args)+'\n',encoding='utf8')
    t=datetime.datetime.now().astimezone()
    r=subprocess.run([tool,'@'+str(argfile)],cwd=x['workingDirectory'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    (d/(stage+'.log')).write_bytes(r.stdout)
    (d/(stage+'.json')).write_text(json.dumps({'start':str(t),'end':str(datetime.datetime.now().astimezone()),'exitCode':r.returncode,'workerInputs':str(worker),'command':[str(tool),'@'+str(argfile)],'cwd':x['workingDirectory']},indent=2),encoding='utf8')
    print(stage,r.returncode,flush=True)
    if r.returncode: sys.exit(r.returncode)
if mode!='compiled':
    text=src.read_text(encoding='utf8')
    if mode=='withIdle':
        text=subprocess.check_output(['git','show',x['commit']+':'+src.relative_to(new).as_posix()],cwd=new).decode('utf8')
        text=text.replace('AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();',
          'ShadowLooper.idleMainLooper();\n    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();')
    patched=d/'NativeDataChoicesTest.java';patched.write_text(text,encoding='utf8')
    classes=d/'classes';classes.mkdir()
    # javac resolves the API that the runtime worker obtains via Robolectric's sandbox.
    android=Path('F:/DSHA_RESTART/toolchains/current/android-sdk/platforms/android-37.0/android.jar')
    run('compile',['-encoding','UTF-8','--release','17','-d',classes,'-classpath',os.pathsep.join([str(android),*classpath]),patched],java.with_name('javac.exe'))
    classpath=[str(classes),*classpath]
run('junit',['-ea','-Xmx2g','-XX:ActiveProcessorCount=2',*['-D'+k+'='+v for k,v in x['testSystemProperties'].items()],'-classpath',os.pathsep.join(classpath),'org.junit.runner.JUnitCore',fq],java)

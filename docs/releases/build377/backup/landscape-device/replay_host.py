from pathlib import Path
import sys,json,subprocess,os,datetime,hashlib
e=Path(__file__).resolve().parent
new=Path('F:/DSHA_RESTART/source/build377-local')
worker=e.parents[1]/'windows-r5/worker-inputs.json'
x=json.loads(worker.read_text(encoding='utf-8-sig'))
name=sys.argv[1]
d=e/name;d.mkdir()
src=new/'app/src/test/java/com/deepseekharness/app/ui/NativeDataChoicesTest.java'
mode=sys.argv[2] if len(sys.argv)>2 else 'compiled'
classpath=x['classpath']

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
classes=d/'classes';classes.mkdir()
testSource=d/'NativeDataChoicesTest.java';testSource.write_bytes(src.read_bytes())
if mode=='instrument':
 text=testSource.read_text(encoding='utf8').replace('pass < 2','pass < 6')
 key='panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());'
 text=text.replace(key,key+'''
      View bar=(View)dialog.getButton(-1).getParent(); View foot=(View)bar.getParent();
      View fields=find(decor,v->v instanceof PortablePasswordFields);
      View parent=(View)((View)fields.getParent()).getParent();
      if (heightDp==136 || widthDp==280) System.out.println("LAYOUT w="+widthDp+" h="+heightDp+" pass="+pass+" bar="+bar.getHeight()+"/"+bar.getMinimumHeight()+" footer="+foot.getHeight()+"/"+foot.getMinimumHeight()+" form="+parent.getHeight()+" requested="+panel.isLayoutRequested()+" barReq="+bar.isLayoutRequested());''')
 testSource.write_text(text,encoding='utf8')

sources=[testSource]
if mode in ('candidate','instrument'):
    production=new/'app/src/main/java/com/deepseekharness/app/ui/NativeDataActivity.java'
    for name in ('NativeDataActivity','DshaDialogBuilder','PortablePasswordFields'):
        actual=production.parent/(name+'.java')
        copy=d/actual.name;copy.write_bytes(actual.read_bytes());sources.append(copy)
android=Path('F:/DSHA_RESTART/toolchains/current/android-sdk/platforms/android-37.0/android.jar')
run('compile',['-encoding','UTF-8','--release','17','-d',classes,'-classpath',os.pathsep.join([str(android),*classpath]),*sources],java.with_name('javac.exe'))
classpath=[str(classes),*classpath]
run('junit',['-ea','-Xmx2g','-XX:ActiveProcessorCount=2',*['-D'+k+'='+v for k,v in x['testSystemProperties'].items()],'-classpath',os.pathsep.join(classpath),'org.junit.runner.JUnitCore',fq],java)

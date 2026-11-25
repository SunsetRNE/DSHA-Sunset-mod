from pathlib import Path
import sys
import subprocess,time,datetime,json
E=Path(__file__).resolve().parent
jdk=Path('F:/DSHA/_toolchains/jdk-17/bin')
def now():return datetime.datetime.now().astimezone().isoformat(timespec='milliseconds')
for mode,orig in [('before','before-r2'),('after','window-scope-final')]:
 out=E/('mirror-'+mode+('-'+sys.argv[1] if len(sys.argv)>1 else ''));out.mkdir(exist_ok=True)
 existing=E/orig
 comp=(existing/'compile.args').read_text(encoding='utf-8').splitlines()
 appcp=comp[comp.index('"-cp"')+1]
 cp='"'+str(existing/'classes').replace('\\','/')+';'+appcp[1:]
 (out/'compile.args').write_text('\n'.join(['"--release"','"17"','"-encoding"','"UTF-8"','"-cp"',cp,'"-d"','"'+out.as_posix()+'"','"'+(E/'MirrorComparison.java').as_posix()+'"']),encoding='utf-8')
 run=(existing/'junit.args').read_text(encoding='utf-8').splitlines()
 i=run.index('"-cp"')+1;run[i]='"'+out.as_posix()+';'+run[i][1:]
 run=run[:run.index('"MatrixJUnit"')+1]+['"com.deepseekharness.app.vscreen.MirrorComparison"']
 (out/'junit.args').write_text('\n'.join(run),encoding='utf-8')
 info={'started':now(),'firstResult':None,'commands':[]}
 start=time.perf_counter()
 for name,stage in [('javac','compile'),('java','junit')]:
  cwd=Path(json.loads((existing/(stage+'.json')).read_text(encoding='utf-8'))['cwd'])
  command=[str(jdk/(name+'.exe')),'@'+str(out/(stage+'.args'))]
  proc=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,cwd=cwd)
  with (out/(stage+'.log')).open('wb') as log:
   for line in proc.stdout:
    log.write(line)
    if line.startswith(b'SYNTHETIC_MIRROR') and not info['firstResult']:info['firstResult']={'at':now(),'elapsedSeconds':round(time.perf_counter()-start,3)}
  code=proc.wait()
  info['commands'].append({'cwd':str(cwd),'argv':command,'exit':code,'finished':now(),'elapsedSeconds':round(time.perf_counter()-start,3)})
  if code: break
 (out/'metadata.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf-8')
 print(mode,code,(out/(stage+'.log')).read_text(encoding='utf-8',errors='replace')[-500:])
 if code:raise SystemExit(code)

from pathlib import Path
import json,subprocess,zipfile,hashlib,sys
p=Path(__file__).resolve().parent
root=Path('F:/DSHA_RESTART');repo=root/'source/build377-local';jd=root/'toolchains/current/jdk-17/bin'; sdk=root/'toolchains/current/android-sdk'
c=[json.loads(v) for v in (root/'artifacts/build377-local/shizuku/after/compile.args').read_text(encoding='utf8').splitlines() if v.strip()]
cp=c[c.index('-cp')+1].replace('build376-local','build377-local')
c=['--release','17','-encoding','UTF-8','-cp',cp,'-d',str(p/'classes'),str(repo/'app/src/main/java/com/deepseekharness/app/runtime/IsolatedInstallProcess.java'),*map(str,(p/'generated/com/deepseekharness/app/util').glob('*.java'))]
def args(name,argv): 
 f=p/(name+'.args');f.write_text('\n'.join(json.dumps(s,ensure_ascii=False) for s in argv)+'\n',encoding='utf8');return '@'+str(f)
with (p/'compile.log').open('wb') as o:r=subprocess.run([str(jd/'javac.exe'),args('compile',c)],stdout=o,stderr=subprocess.STDOUT)
if r.returncode: print((p/'compile.log').read_text(encoding='utf8',errors='replace')[-4200:]);sys.exit(r.returncode)
with zipfile.ZipFile(p/'overlay-classes.jar','w',zipfile.ZIP_DEFLATED) as out:
 for f in (p/'classes').rglob('*.class'):out.write(f,f.relative_to(p/'classes').as_posix())
javaargs=['-cp',str(sdk/'build-tools/36.0.0/lib/d8.jar'),'com.android.tools.r8.D8','--min-api','30','--lib',str(sdk/'platforms/android-37.0/android.jar'),'--output',str(p/'dex'),str(p/'overlay-classes.jar')]
with (p/'dex.log').open('wb') as o:r=subprocess.run([str(jd/'java.exe'),args('d8',javaargs)],stdout=o,stderr=subprocess.STDOUT)
if r.returncode:print((p/'dex.log').read_text(encoding='utf8',errors='replace')[-2500:]);sys.exit(r.returncode)
outpath=p/'ack-diag-overlay.jar'
with zipfile.ZipFile(outpath,'w',zipfile.ZIP_STORED) as out:
 for f in (p/'dex').glob('*.dex'):out.write(f,f.name)
print(outpath,outpath.stat().st_size,hashlib.sha256(outpath.read_bytes()).hexdigest())

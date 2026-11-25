from pathlib import Path
import json,subprocess,sys
p=Path(__file__).resolve().parent
root=Path('F:/DSHA_RESTART');repo=root/'source/build377-local';jdk=root/'toolchains/current/jdk-17/bin'
def getline(f):return [json.loads(v) for v in f.read_text(encoding='utf8').splitlines() if v.strip()]
def run(exe,argv,tag):
 f=p/(tag+'.args');f.write_text('\n'.join(json.dumps(s,ensure_ascii=False) for s in argv)+'\n',encoding='utf8')
 with (p/(tag+'.log')).open('wb') as o:r=subprocess.run([str(jdk/(exe+'.exe')),'@'+str(f)],stdout=o,stderr=subprocess.STDOUT,cwd=repo)
 print((p/(tag+'.log')).read_text(encoding='utf8',errors='replace')[-1850:]);return r.returncode
c=getline(p/'compile.args')
cp=str(p/'classes')+';'+c[c.index('-cp')+1]
names=['runtime.IsolatedInstallProcessTest','util.ColdInstallPlanTest','util.ProcessBirthHandshakeTest','util.BoundedProcessRunnerTest']
c=['--release','17','-encoding','UTF-8','-d',str(p/'classes'),'-cp',cp,str(root/'artifacts/build375-local/tests/MatrixJUnit.java'),*map(str,(repo/'app/src/test/java/com/deepseekharness/app'/Path(n.replace('.','/')+'.java') for n in names))]
if run('javac',c,'test-compile'):sys.exit(1)
j=getline(root/'artifacts/build377-local/shizuku/after/junit.args')
j[j.index('-cp')+1]=str(p/'classes')+';'+j[j.index('-cp')+1].replace('build376-local','build377-local')
j=j[:j.index('MatrixJUnit')+1]+['com.deepseekharness.app.'+n for n in names]
sys.exit(run('java',j,'junit'))

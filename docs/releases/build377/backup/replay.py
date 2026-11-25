"""Offline Windows host replay. All data under the new round directory are synthetic."""
from pathlib import Path
import ctypes, datetime, hashlib, json, os, shutil, subprocess, sys, tarfile, time
from ctypes import wintypes
root=Path('F:/DSHA_RESTART')
evidence=Path(__file__).resolve().parent
old=root/'source/build376-local'
new=root/'source/build377-local'
run=evidence/(sys.argv[1] if len(sys.argv)>1 else 'round-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
assert run.resolve().parent==evidence
run.mkdir()
cache=root/'toolchains/current/gradle-user-home/caches/modules-2/files-2.1'
base=old/'app/build/intermediates'
support=[base/'javac/standardDebug/compileStandardDebugJavaWithJavac/classes',
         base/'javac/standardDebugUnitTest/compileStandardDebugUnitTestJavaWithJavac/classes',
         base/'built_in_kotlinc/standardDebug/compileStandardDebugKotlin/classes',
         old/'app/src/test/resources']
for jar in ('bcprov-jdk15to18-1.85.2.jar','kotlin-stdlib-2.2.10.jar','gson-2.13.1.jar','junit-4.13.2.jar','hamcrest-core-1.3.jar'):
    support.append(next(cache.rglob(jar)))
java=root/'toolchains/current/jdk-17/bin/java.exe'
javac=java.with_name('javac.exe')
ops=[]
def command(args,stage):
    start=time.monotonic()
    result=subprocess.run(list(map(str,args)),capture_output=True,cwd=new)
    item=dict(stage=stage,seconds=time.monotonic()-start,exit_code=result.returncode,argv=list(map(str,args)))
    ops.append(item)
    (run/(stage+'.log')).write_bytes(result.stdout+result.stderr)
    print(stage,item['exit_code'],round(item['seconds'],3),flush=True)
    (run/'commands.json').write_text(json.dumps(ops,ensure_ascii=False,indent=2),encoding='utf8')
    if result.returncode: raise SystemExit(result.returncode)
def sha(f):
    with f.open('rb') as s:return hashlib.file_digest(s,'sha256').hexdigest()
def cpp(paths):return os.pathsep.join(map(str,paths))
def disk(f):
    import msvcrt
    class FileStandardInfo(ctypes.Structure):
        _fields_=[("allocation", ctypes.c_int64),("eof", ctypes.c_int64),("links", wintypes.DWORD),
                  ("delete_pending", ctypes.c_ubyte),("directory", ctypes.c_ubyte)]
    info=FileStandardInfo()
    with f.open('rb') as fp:
        ok=ctypes.windll.kernel32.GetFileInformationByHandleEx(
            wintypes.HANDLE(msvcrt.get_osfhandle(fp.fileno())),1,ctypes.byref(info),ctypes.sizeof(info))
        if not ok: raise ctypes.WinError()
    assert info.eof==f.stat().st_size
    return {'logical':info.eof,'allocated_windows':info.allocation,'free_same_volume':shutil.disk_usage(f.parent).free}
def inspect(f):
    marker=[b'SYNTHETIC_377_CHAT_CREDENTIAL_IN_USER_TEXT',b'SYNTHETIC_377_ATTACHMENT_PRIVATE_VALUE']
    members={}
    with tarfile.open(f,'r|gz') as t:
        for m in t:
            matches=[False,False];first=b'';tail=b''; data=t.extractfile(m)
            while buf:=data.read(65536):
                if not first:first=buf[:8]
                block=tail+buf
                for i,v in enumerate(marker): matches[i]|=v in block
                tail=buf[-64:]
            members[m.name]=dict(length=m.size,synthetic_chat_marker=matches[0],synthetic_attachment_marker=matches[1],first_eight_hex=first.hex())
    return members
classes=run/'classes';classes.mkdir()
files=['BackupArchive','PortableBackupCrypto','PortableBackupEnvelope','PasswordFreeBackupExport','RestoreStaging']
prod=new/'app/src/main/java/com/deepseekharness/app/backup'
tests=new/'app/src/test/java/com/deepseekharness/app/backup'
sources=[prod/(n+'.java') for n in files]+[tests/'PasswordFreeBackupExportTest.java',evidence/'BackupReplay.java']
command([javac,'--release','17','-encoding','UTF-8','-classpath',cpp(support),'-d',classes,*sources],'compile-377')
newcp=cpp([classes,*support]);oldcp=cpp([*support,classes])
old_inputs={str(f.relative_to(new)):sha(old/f.relative_to(new)) for f in sources[:-1]}
new_inputs={str(f.relative_to(new)):sha(f) for f in sources[:-1]}
report={'start':datetime.datetime.now().astimezone().isoformat(),'base_head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=old,text=True).strip(),'free_start':shutil.disk_usage(run).free,'old_inputs':old_inputs,'new_inputs':new_inputs,'exports':{}}
(run/'report.json').write_text(json.dumps(report,indent=2),encoding='utf8')
main='com.deepseekharness.app.backup.BackupReplay'
for name,cp,protected in [('376',oldcp,False),('377',newcp,True)]:
    d=run/('source-'+name);d.mkdir()
    command([java,'-Xmx32m','-cp',cp,main,'create',d,str(protected).lower()],'export-'+name)
    f=d/'export.tar.gz'
    members=inspect(f)
    assert members['portable.dshbak']['synthetic_chat_marker'] != protected
    assert members['portable.dshbak']['synthetic_attachment_marker'] != protected
    assert set(members)=={'portable.dshbak','README.txt'}
    report['exports'][name]={'sha256':sha(f),'storage':disk(f),'members':members}
for reader,source,protected in [('377','376',False),('377','377',True),('376','377',True)]:
    d=run/('restore-'+reader+'-from-'+source);d.mkdir()
    command([java,'-Xmx32m','-cp',newcp if reader=='377' else oldcp,main,'read',d,run/('source-'+source)/'export.tar.gz',str(protected).lower(),str(reader=='376').lower()],d.name)
    write=(run/('export-'+source+'.log')).read_text(encoding='utf8')
    read=(run/(d.name+'.log')).read_text(encoding='utf8')
    def val(out,name):return next(x.split(' | ')[-1].strip() for x in out.splitlines() if ' | '+name+' | ' in x)
    assert val(write,'attachment_sha256')==val(read,'recovered_attachment_sha256')
for name,cp in [('376',oldcp),('377',newcp)]:
    command([java,'-Xmx128m','-cp',cp,'org.junit.runner.JUnitCore',*['com.deepseekharness.app.backup.'+n+'Test' for n in ['PortableBackupCrypto','PortableBackupEnvelope','PasswordFreeBackupExport','BackupArchive']]],'junit-'+name)
report['free_end']=shutil.disk_usage(run).free
report['end']=datetime.datetime.now().astimezone().isoformat()
(run/'report.json').write_text(json.dumps(report,indent=2),encoding='utf8')

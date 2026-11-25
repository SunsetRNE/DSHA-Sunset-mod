from pathlib import Path
import hashlib,json,os,queue,re,shlex,subprocess,sys,threading,time,uuid,zipfile
e=Path(__file__).resolve().parent; root=e.parents[2]; apk=root/'artifacts/build377-local/signed/dsha-0.2.2-rc.5.apk'
adb=[str(root/'cache/build372-android-jni/sdk/platform-tools/adb.exe'),'-H','127.0.0.1','-P','5077','-s','emulator-5672']
trial=sys.argv[1]; assert trial.isalnum()
out=e/('launcher-'+trial);out.mkdir()
start=time.monotonic()
def cmd(*args, check=True):
 p=subprocess.run([*adb,*args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',errors='replace',timeout=45)
 if check:p.check_returncode()
 return p.stdout.strip()
assert 'DSHA_build377_proot_api30_arm64' in cmd('emu','avd','name').splitlines()
assert hashlib.sha256(apk.read_bytes()).hexdigest()=='2e6f0324ebe4b52b143dcb2bf04800b1ce73c106a53b567de1afdafca5dba3d9'
with zipfile.ZipFile(apk) as z: (out/'exact.so').write_bytes(z.read('lib/arm64-v8a/libdsha-session.so'))
observe=root/'artifacts/build377-local/cold-stream/observearm-r1/libdsha-session.so'
env=cmd('shell','getprop ro.build.fingerprint; getprop ro.product.cpu.abilist; getprop ro.dalvik.vm.native.bridge; uname -m; getenforce; id')
assert 'arm64-v8a' in env and 'uid=2000' in env and 'Enforcing' in env
base='/data/local/tmp/dsha377_birth_'+uuid.uuid4().hex
cmd('shell','mkdir -m 700 '+base)
cmd('push',str(out/'exact.so'),base+'/exact');cmd('push',str(observe),base+'/observed')
hashes=cmd('shell',f'chmod 755 {base}/exact {base}/observed; sha256sum {base}/exact {base}/observed')
report={'apk':str(apk),'environment':env,'deviceHashes':hashes,'cases':[]}
def save():
 report['seconds']=round(time.monotonic()-start,3); (out/'results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
save()
for idx,(name,code) in enumerate([('exact',0),('observed',0),('exact',17),('observed',17)]):
 marker=base+f'/entered{idx}'
 body=f'echo enter > {marker}; echo SHELL_ENTER; IFS= read -r word || {{ echo READ_EOF; exit 125; }}; echo MARK_$word; exit {code}'
 shellcmd=f'echo HARNESS_PARENT=$$; '+shlex.join([base+'/'+name,'--birth-handshake','/system/bin/sh','-c',body])+'; rc=$?; echo LEADER_EXIT=$rc; exit $rc'
 p=subprocess.Popen([*adb,'shell','-T',shellcmd],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,bufsize=0)
 lines=[]; events=queue.Queue()
 def reader():
  for b in p.stdout:
   line=b.decode('utf-8','replace').strip()
   if line: lines.append(line);events.put(line)
  events.put(None)
 threading.Thread(target=reader,daemon=True).start()
 row=dict(mode=name,expectedGuestCode=code,command=shellcmd,lines=lines,harnessForce=False)
 report['cases'].append(row); save()
 deadline=time.monotonic()+35; birth=None; parent=None
 while time.monotonic()<deadline:
  line=events.get(timeout=max(.05,deadline-time.monotonic()))
  if line is None: break
  if line.startswith('HARNESS_PARENT='): parent=int(line.split('=')[1])
  if line.startswith('DSHA_SESSION_STAT_V1 '):
   birth=line.split(' ',1)[1];break
 assert birth and parent,lines
 leader=int(birth.split(' ',1)[0]); fields=birth.rsplit(')',1)[1].strip().split()
 assert int(fields[1])==parent and int(fields[2])==leader and int(fields[3])==leader
 obs=cmd('shell',f"cat /proc/{leader}/stat; ps -A -o PID,PPID,NAME | awk '$2 == {leader} {{print $1}}'")
 row.update(birthPid=leader,parent=parent,beforeStart=obs,starttime=fields[19])
 assert obs.splitlines()[0].rsplit(')',1)[1].strip().split()[19]==fields[19]
 p.stdin.write(b'DSHA_START\n');p.stdin.close()
 rc=p.wait(timeout=40)
 while not p.stdout.closed and not events.empty() and events.get_nowait() is not None: pass
 time.sleep(.1)
 kids=[int(i) for i in obs.splitlines()[-1].split() if i.isdecimal()]
 assert kids,obs
 childtest='; '.join(f'test -e /proc/{i}/stat; echo childPresentCode=$?' for i in kids)
 finish=cmd('shell',f'cat {marker}; test -e /proc/{leader}/stat; echo leaderPresentCode=$?; '+childtest)
 row.update(finalState=finish,adbReturn=rc,guestReached='MARK_DSHA_START' in lines)
 expectNatural=bool(rc==137 and row['guestReached'] and 'enter\nleaderPresentCode=1' in finish and all(l=='childPresentCode=1' for l in finish.splitlines()[2:]))
 if name=='observed': expectNatural=expectNatural and any(f'exited=1 code={code} signaled=0' in line for line in lines)
 row['pass']=expectNatural
 save()
 assert expectNatural,row
report['status']='PASS';save();print(out/'results.json')

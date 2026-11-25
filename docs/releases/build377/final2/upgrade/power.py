import argparse,ctypes
from direct_host import *
from measure_install import boot
def disk_usage():
 files=[f for f in WORK.rglob('*') if f.is_file() and not f.is_symlink()]
 return {'fileCount':len(files),'logicalBytes':sum(f.stat().st_size for f in files),'allocatedBytes':sum(allocated(f) for f in files),'sameVolumeFreeBytes':shutil.disk_usage(WORK).free}
def identity():
 value=adb('shell','settings get secure android_id | sha256sum').split()[0]
 assert re.fullmatch(r'[0-9a-f]{64}',value)
 return value
def shutdown(rec,final):
 if final:assert rec['status']=='UPGRADED'
 else:assert rec['status']=='BASELINE_READY'
 assert checked_name()==AVD
 device=identity()
 if final:assert rec['initialGuestAndroidIdHash']==device
 else:rec['initialGuestAndroidIdHash']=device
 begin=time.monotonic()
 entry={'at':now(),'name':AVD,'serial':SERIAL,'guestAndroidIdSha256':device,'hostBefore':host(),'purpose':'close-after-upgrade' if final else 'free-RAM-and-preserve-baseline'}
 entry['guestShutdown']=adb('emu','kill',ok=(0,1))
 for i in range(75):
  if not any(opened(p) for p in (PORT,PORT+1)):break
  time.sleep(.5)
 assert not opened(PORT) and not opened(PORT+1),'own AVD did not exit normally'
 if opened(SERVER):entry['serverShutdown']=data([ADB,'-H','127.0.0.1','-P',str(SERVER),'kill-server'],ok=(0,1))
 for i in range(15):
  if not opened(SERVER):break
  time.sleep(.3)
 entry['listeningAfter']={str(p):opened(p) for p in (PORT,PORT+1,SERVER)}
 assert not any(entry['listeningAfter'].values())
 entry.update(completed=now(),seconds=round(time.monotonic()-begin,3),hostAfter=host(),privateAvdStorage=disk_usage())
 rec.setdefault('shutdowns',[]).append(entry);rec['status']='PASS' if final else 'BASELINE_SUSPENDED'
 put('direct.json',rec);print(rec['status'],entry['seconds'],entry['listeningAfter'],entry['hostAfter'])
def resume(rec,apk,sha):
 assert rec['status']=='BASELINE_SUSPENDED' and digest(apk)==sha and sha!=rec['baselineExpected']
 assert not any(opened(p) for p in (PORT,PORT+1,SERVER))
 avd_dir=WORK/'avd'/f'{AVD}.avd'
 config_file=avd_dir/'config.ini'
 def properties(path):return dict(tuple(field.strip() for field in line.split('=',1)) for line in path.read_text(encoding='utf-8-sig').splitlines() if '=' in line)
 declared=properties(E/'guest-config.ini');active=properties(config_file)
 assert digest(E/'guest-config.ini')==rec['configSha256']
 changed={k:(declared.get(k),active.get(k)) for k in sorted(set(declared)|set(active)) if declared.get(k)!=active.get(k)}
 if changed:
  assert set(changed)=={'disk.dataPartition.size'} and changed['disk.dataPartition.size'][0]=='4G'
  actual=int(changed['disk.dataPartition.size'][1])
  assert actual==(avd_dir/'userdata-qemu.img').stat().st_size and actual>=4*1024**3
 rec['configOnResume']={'at':now(),'path':str(config_file),'initialSha256':rec['configSha256'],'activeSha256':digest(config_file),'propertiesChangedByEmulator':changed,
     'note':'Raw equality check initially stopped before restarting. The emulator rewrote spacing and its data partition size. All other values match; the recorded size matches the original retained userdata file.'}
 put('resume-config.json',rec['configOnResume'])
 # The emulator must reopen the same userdata disk. No wipe, snapshot load, or alternate image is permitted.
 assert (WORK/'avd'/f'{AVD}.avd'/'userdata-qemu.img').exists()
 args=rec['launchArgs'];assert args[0]==str(QEMU) and args[2]==AVD and '-no-snapshot' in args and '-wipe-data' not in args
 data([ADB,'-L',f'tcp:localhost:{SERVER}','--one-device',USB,'start-server'])
 with (E/'restart.stdout.log').open('wb') as out,(E/'restart.stderr.log').open('wb') as err:
  proc=subprocess.Popen(args,env=env(),stdout=out,stderr=err,stdin=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
 parent={'status':'RESTARTING','pid':proc.pid,'launched':now(),'hostAtStart':host()}
 rec['restart']=parent;put('direct.json',rec)
 boot(parent)
 assert parent['environment']==rec['environment'] and identity()==rec['initialGuestAndroidIdHash']
 parent['sameAndroidIdSha256']=rec['initialGuestAndroidIdHash'];parent['status']='READY'
 rec['status']='BASELINE_READY';put('direct.json',rec)
 print('Resumed',AVD,'same synthetic system identity and data directory',parent['bootCompletedObservedSecondsSinceLaunch'])
if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('op',choices=('suspend','resume','close'));ap.add_argument('--apk',type=Path);ap.add_argument('--sha256');a=ap.parse_args()
 r=get_record()
 if a.op=='resume':resume(r,a.apk.resolve(strict=True),a.sha256)
 else:shutdown(r,a.op=='close')

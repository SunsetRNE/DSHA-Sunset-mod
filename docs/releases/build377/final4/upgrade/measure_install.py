import argparse,datetime,hashlib,json,os,re,subprocess,time
from direct_host import *
PKG='dsh.android.client'
MEDIA_DIR='/sdcard/Android/media/'+PKG
MARK=MEDIA_DIR+'/direct-final4-synced-fixture.txt'
CONTENT='DSHA-SYNTHETIC-376-TO-FINAL4-377-SYNCED'
M_HASH=hashlib.sha256((CONTENT+'\n').encode()).hexdigest()
BASE=ROOT/'artifacts/build376-formal-20261125/signed/dsha-0.2.2-rc.5.apk'
def boot(rec):
 start=time.monotonic();deadline=start+240
 while time.monotonic()<deadline:
  if adb('get-state',ok=(0,1),timeout=9)=='device' and adb('shell','getprop','sys.boot_completed',ok=(0,1),timeout=10)=='1':break
  time.sleep(2)
 else:raise RuntimeError('bounded boot wait expired')
 checked_name()
 physical={k:adb('shell','getprop',k) for k in ('ro.kernel.qemu','ro.boot.qemu','ro.build.version.sdk','ro.product.cpu.abi','ro.product.cpu.abilist','ro.dalvik.vm.native.bridge','ro.build.fingerprint')}
 assert physical['ro.build.version.sdk']=='30' and physical['ro.product.cpu.abi']=='x86_64' and '1' in (physical['ro.kernel.qemu'],physical['ro.boot.qemu'])
 rec['environment']=physical;rec['bootVerified']=now()
 rec['bootCompletedObservedSecondsSinceLaunch']=(datetime.datetime.fromisoformat(rec['bootVerified'])-datetime.datetime.fromisoformat(rec['launched'])).total_seconds()
 rec['bootWaitThisProcessSeconds']=round(time.monotonic()-start,3)
def state():
 path=adb('shell','pm','path',PKG,ok=(0,1))
 if not path:return None
 paths=[l.removeprefix('package:') for l in path.splitlines()]
 assert len(paths)==1 and re.fullmatch(r'/data/app/[-\w~/+.=]+/base\.apk',paths[0]),paths
 base=paths[0];meta=adb('shell',"dumpsys package dsh.android.client | grep -E 'versionCode=|versionName=|firstInstallTime=|lastUpdateTime=|primaryCpuAbi=|userId='")
 def field(k,p=r'[^\r\n]+'):return re.search(r'^\s*'+k+'=('+p+r')',meta,flags=re.M)[1].strip()
 uid=adb('shell','cmd','package','list','packages','-U',PKG)
 uid=int(re.fullmatch(r'package:dsh\.android\.client uid:(\d+)',uid)[1])
 assert uid==int(field('userId',r'\d+'))
 h=adb('shell','sha256sum',base).split()[0]
 assert re.fullmatch(r'[0-9a-f]{64}',h)
 blocks=adb('shell',"stat -c '%s %b %B' "+base).split()
 assert len(blocks)==3
 length,count,blockSize=map(int,blocks)
 return {'observed':now(),'versionCode':int(field('versionCode',r'\d+')),'versionName':field('versionName'),'applicationUid':uid,'firstInstallTime':field('firstInstallTime'),'lastUpdateTime':field('lastUpdateTime'),'abi':field('primaryCpuAbi'),'path':base,'onDeviceSha256':h,'logicalBytes':length,'allocatedBytes':count*blockSize,'statBlockBytes':blockSize}
def get_bytes(state):
 args=[str(ADB),'-H','127.0.0.1','-P',str(SERVER),'-s',SERIAL,'exec-out','cat',state['path']]
 start=time.monotonic();h=hashlib.sha256();total=0;first=None
 with subprocess.Popen(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env(),creationflags=subprocess.CREATE_NO_WINDOW) as proc:
  while block:=proc.stdout.read(1024*1024):
   if first is None:first=round(time.monotonic()-start,3)
   h.update(block);total+=len(block)
  err=proc.stderr.read().decode(errors='replace').strip();exitcode=proc.wait(timeout=30)
 assert not exitcode and total==state['logicalBytes'] and h.hexdigest()==state['onDeviceSha256'],(exitcode,total,err)
 return {'command':args,'sha256':h.hexdigest(),'bytes':total,'firstMiBSeconds':first,'completeSeconds':round(time.monotonic()-start,3),'exit':exitcode}
def markers(create=False):
 evidence={}
 if create:
  evidence['appOpBefore']=adb('shell','cmd','appops','get','--user','0',PKG,'VIBRATE')
  adb('shell','cmd','appops','set','--user','0',PKG,'VIBRATE','ignore')
  adb('shell','mkdir','-p',MEDIA_DIR)
  assert adb('shell','ls',MARK,ok=(0,1))==''
  adb('shell',f"printf '%s\\n' '{CONTENT}' > {MARK}")
  adb('shell','sync',timeout=60)
  evidence['explicitSyncAt']=now()
 evidence['appOp']=adb('shell','cmd','appops','get','--user','0',PKG,'VIBRATE')
 assert re.search(r'\bVIBRATE:\s*ignore',evidence['appOp'],re.I),evidence
 evidence['markerPath']=MARK
 evidence['markerBytes']=int(adb('shell',"stat -c %s "+MARK))
 evidence['markerSha256']=adb('shell','sha256sum',MARK).split()[0]
 assert evidence['markerBytes']==len((CONTENT+'\n').encode()) and evidence['markerSha256']==M_HASH
 evidence['guestIdHash']=adb('shell','settings get secure android_id | sha256sum').split()[0]
 assert re.fullmatch(r'[0-9a-f]{64}',evidence['guestIdHash'])
 return evidence
def saved_space():
 v=adb('shell','df','-k','/data').splitlines();line=v[-1].split()
 return {'availableBytes':int(line[3])*1024,'filesystem':line[0],'blockBytes':1024}
def run():
 ap=argparse.ArgumentParser();ap.add_argument('phase',choices=('baseline','upgrade'));ap.add_argument('--apk',type=Path);ap.add_argument('--sha256');args=ap.parse_args()
 rec=get_record();phase=args.phase;apk=BASE if phase=='baseline' else args.apk.resolve(strict=True)
 expected=rec['baselineExpected'] if phase=='baseline' else args.sha256
 assert re.fullmatch(r'[a-f0-9]{64}',expected)
 if phase=='baseline': assert rec['status']=='BOOTING'
 else:assert rec['status']=='BASELINE_READY' and expected not in {rec['baselineExpected'],'72917c42dee81df41e26f2d7977ba735b0e3c0c94a93803561272d6f6facbeaa','292f89da4d199c7364aba77b441370f7c75e276d65f11ecc82c29a6f60a45ba8'} and apk.name.endswith('.apk')
 assert digest(apk)==expected
 started=time.monotonic();row={'phase':phase,'started':now(),'input':{'path':str(apk),'sha256':expected,'logicalBytes':apk.stat().st_size,'allocatedBytes':allocated(apk)},'hostBefore':host()}
 rec['stages'][phase]=row
 try:
  if phase=='baseline':boot(rec)
  else:checked_name()
  row['guestSpaceBefore']=saved_space()
  row['before']=state()
  if phase=='baseline':assert row['before'] is None
  else:
   orig=rec['stages']['baseline']['after'];previous=row['before']
   assert all(previous[k]==orig[k] for k in ('onDeviceSha256','applicationUid','firstInstallTime','versionCode','path'))
   row['markersBefore']=markers()
   assert row['markersBefore']['appOp']==rec['stages']['baseline']['markersAfter']['appOp']
   assert row['markersBefore']['markerSha256']==rec['stages']['baseline']['markersAfter']['markerSha256']
   assert row['markersBefore']['guestIdHash']==rec['stages']['baseline']['markersAfter']['guestIdHash']
  start=time.monotonic();params=['install','--no-incremental']+(['-r'] if phase=='upgrade' else [])+[str(apk)]
  installed=adb(*params,timeout=190)
  row['installResponse']=installed;row['usableAt']=now();row['firstPackageManagerSuccessSeconds']=round(time.monotonic()-start,3)
  assert 'Success' in installed,installed
  row['after']=state();s=row['after'];assert s['onDeviceSha256']==expected and s['versionCode']==(376 if phase=='baseline' else 377)
  row['hostReadback']=get_bytes(s);row['markersAfter']=markers(phase=='baseline')
  row['guestSpaceAfter']=saved_space();row['hostAfter']=host()
  if phase=='baseline':rec['initialGuestAndroidIdHash']=row['markersAfter']['guestIdHash']
  if phase=='upgrade':
   orig=rec['stages']['baseline']['after'];mb=row['markersBefore'];ma=row['markersAfter']
   checks={'samePackage':PKG,'versionCodeTransition':[orig['versionCode'],s['versionCode']],'matchingBaselineAndFinalDigests':True,
           'uidPreserved':orig['applicationUid']==s['applicationUid'],'firstInstallTimePreserved':orig['firstInstallTime']==s['firstInstallTime'],
           'packageAppOpPreserved':mb['appOp']==ma['appOp'],'syncedMarkerPreservedAcrossUpgrade':mb['markerSha256']==ma['markerSha256']==M_HASH,
           'guestIdentityPreserved':mb['guestIdHash']==ma['guestIdHash']==rec['stages']['baseline']['markersAfter']['guestIdHash'],
           'lastUpdateTimeAdvanced':s['lastUpdateTime']>orig['lastUpdateTime'],'installCount':2}
   assert all(checks[k] for k in ('uidPreserved','firstInstallTimePreserved','packageAppOpPreserved','syncedMarkerPreservedAcrossUpgrade','guestIdentityPreserved','lastUpdateTimeAdvanced')),checks
   rec['checks']=checks
  row['completedAt']=now();row['completedSecondsFromInstall']=round(time.monotonic()-start,3);row['completedSecondsFromPhaseStart']=round(time.monotonic()-started,3)
  row['ok']=True;rec['status']='BASELINE_READY' if phase=='baseline' else 'UPGRADED'
 except Exception as err:
  row['ok']=False;row['failure']=repr(err);row['failedAt']=now();raise
 finally:put('direct.json',rec)
 print(rec['status'],s['versionCode'],s['applicationUid'],s['firstInstallTime'],row['markersAfter'],row['completedSecondsFromInstall'])
if __name__=='__main__':run()

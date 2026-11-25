from direct_host import *
from measure_install import state,PKG,markers
r=get_record();assert r['status']=='BASELINE_READY' and checked_name()==AVD
s=state();original=r['stages']['baseline']['after']
for k in ('versionCode','onDeviceSha256','applicationUid','firstInstallTime'):assert s[k]==original[k]
prev=r['prewarmFirstObservation'];assert prev['versionCode']==376 and prev['sameMarker'] and prev['platformWriteSettingsAvailable']
adb('shell','cmd','appops','set','--user','0',PKG,'VIBRATE','ignore')
write=adb('shell','cmd','appops','write-settings')
adb('shell','sync',timeout=50)
m=markers();base=r['stages']['baseline']['markersAfter']
for k in ('markerBytes','markerSha256','appOp','guestIdHash'):assert m[k]==base[k]
records=[json.loads(line) for line in (E/'commands.jsonl').read_text(encoding='utf-8').splitlines()]
assert len([x for x in records if 'install' in x['cmd']])==1
r['prewarm']={'at':now(),'verifiedOldPackage':s,'markers':m,'installCount':1,'hostAfter':host(),'packageSettingWriteResult':write,'appOpSurvivedGuestRestart':False,'note':'The synchronized 40-byte file survived; the original package AppOp reverted before any 377 installation. Only this same setting was established again on 376, persisted with cmd appops write-settings and read back. The upgrade comparison uses the separately verified pre-upgrade state.'}
put('direct.json',r);print('PREWARM READY',s['firstInstallTime'],m['markerSha256'],m['appOp'],write)

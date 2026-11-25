from direct_host import *
from measure_install import state,PKG,MARK,M_HASH
r=get_record();assert r['status']=='BASELINE_READY' and checked_name()==AVD
s=state();assert s['versionCode']==376 and s['onDeviceSha256']==r['baselineExpected']
ids=adb('shell','settings get secure android_id | sha256sum').split()[0];assert ids==r['initialGuestAndroidIdHash']
raw=adb('shell','cmd','appops','get','--user','0',PKG,'VIBRATE')
h=adb('shell','sha256sum',MARK,ok=(0,1));stats=adb('shell','stat -c %s '+MARK,ok=(0,1))
help_text=adb('shell','cmd','appops','help',ok=(0,1,255))
r['prewarmFirstObservation']={'at':now(),'versionCode':s['versionCode'],'sha256':s['onDeviceSha256'],'uid':s['applicationUid'],'firstInstallTime':s['firstInstallTime'],'guestIdHash':ids,'vibrate':raw,'markerSha256':h.split()[0] if h else None,'markerBytes':stats,'sameMarker':h.startswith(M_HASH),'platformWriteSettingsAvailable':'write-settings' in help_text}
put('direct.json',r);print(r['prewarmFirstObservation'])

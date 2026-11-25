from direct_host import *
from measure_install import *
r=get_record();assert r['status']=='BASELINE_READY';checked_name()
original=r['stages']['baseline']['after']; s=state()
for k in ('onDeviceSha256','versionCode','applicationUid','firstInstallTime'):assert s[k]==original[k]
appeared=adb('shell','ls','-l',SYNC_MEDIA,ok=(0,1))
assert not appeared
first=adb('shell','sha256sum',MEDIA).split()[0];assert first==hashlib.sha256(b'').hexdigest()
adb('shell',f"printf '%s\\n' '{SYNC_CONTENT}' > {SYNC_MEDIA}");adb('shell','sync',timeout=50)
m=markers();assert m['syncedFixtureSha256']==SYNC_HASH and m['sharedMediaFixtureSha256']==first
r['verifiedPreUpgradeMarkers']=m;r['verifiedPreUpgradeMarkersAt']=now();r['preUpgradeMarkerObservation']={'stage':'original 376 after guest restart and before any 377 installation','firstMediaHashBeforeGuestShutdown':r['stages']['baseline']['markersAfter']['sharedMediaFixtureSha256'],'firstMediaHashAfterGuestRestart':first,'firstFileBytesAfterGuestRestart':0,'appOpBeforeAndAfterGuestRestart':m['appOp'],'newFile':SYNC_MEDIA,'syncedContentBytes':len((SYNC_CONTENT+'\n').encode()),'syncedSha256':SYNC_HASH,'note':'First file retained verbatim at zero bytes after restart. Second independent file was written under the still-installed 376, synced explicitly and re-read before the single direct upgrade. Cause of the first empty file is unverified.'}
put('direct.json',r);print('Pre-upgrade synced marker:',SYNC_HASH)

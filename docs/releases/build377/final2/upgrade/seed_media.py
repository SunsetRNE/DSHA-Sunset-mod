from direct_host import *
from measure_install import CONTENT, MEDIA_DIR, MEDIA, markers
rec = get_record()
assert rec['status']=='BASELINE_READY'
checked_name()
try:
 adb('shell','mkdir','-p',MEDIA_DIR)
 adb('shell', f"printf '%s\\n' '{CONTENT}' > {MEDIA}")
except RuntimeError as error:
 rec['sharedMediaFixtureWriteError']=str(error)
rec['stages']['baseline']['markersAfter'].update(markers())
rec['stages']['baseline']['supplementedFixtureAt']=now()
put('direct.json',rec)
print(rec['stages']['baseline']['after'],rec['stages']['baseline']['markersAfter'],rec['stages']['baseline']['firstPackageManagerSuccessSeconds'],rec['stages']['baseline']['completedSecondsFromInstall'])

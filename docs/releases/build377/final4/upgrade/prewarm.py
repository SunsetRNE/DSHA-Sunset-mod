from direct_host import *
from power import resume
from measure_install import state,markers
r=get_record()
print('Host before',host(),flush=True)
assert r['status']=='BASELINE_SUSPENDED'
resume(r)
start=time.monotonic()
checked_name()
old=r['stages']['baseline']['after'];s=state()
for k in ('versionCode','onDeviceSha256','firstInstallTime','applicationUid','path'):assert old[k]==s[k]
m=markers();base=r['stages']['baseline']['markersAfter']
for k in ('markerBytes','markerSha256','appOp','guestIdHash'): assert m[k]==base[k]
rows=[json.loads(line) for line in (E/'commands.jsonl').read_text(encoding='utf-8').splitlines()]
assert len([x for x in rows if 'install' in x['cmd']])==1
r['prewarm']={'at':now(),'verifiedOldPackage':s,'markers':m,'elapsedSecondsAfterBoot':round(time.monotonic()-start,3),'installCount':1,'hostAfter':host()}
put('direct.json',r)
print('READY_WITH_376',s['onDeviceSha256'],m['markerSha256'],m['appOp'],flush=True)

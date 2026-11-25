"""Probe all 8 UI combinations on the one locked system emulator. Stop at the first unmet assertion."""
from pathlib import Path
import sys,json,subprocess,time,datetime
e=Path(__file__).resolve().parent
tag=sys.argv[1];d=e/tag;d.mkdir()
build=e/sys.argv[2]; modes=[('rescue','1.3','1'),('normal','1.3','0'),('normal','1.0','1'),('rescue','1.0','0'),
                           ('normal','1.3','1'),('rescue','1.3','0'),('rescue','1.0','1'),('normal','1.0','0')]
if len(sys.argv)>3:modes=modes[:int(sys.argv[3])]
out={'start':datetime.datetime.now().astimezone().isoformat(),'fixture':json.loads((build/'result.json').read_text(encoding='utf8'))['fixtureApkSha256'],'rows':[]}
def record(): (d/'matrix.json').write_text(json.dumps(out,indent=2),encoding='utf8')
def run(args,log):
 rr=subprocess.run([sys.executable,'-X','utf8',*map(str,args)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 (d/(log+'.log')).write_bytes(rr.stdout)
 print(log,rr.returncode,flush=True)
 if rr.returncode:raise RuntimeError(log+' failed')
record()
try:
 run([e/'device_cases.py','install',build/'probe.apk'],'install')
 for flow,scale,rot in modes:
  dest=tag+'-'+flow+'-'+scale+'-'+rot
  run([e/'device_cases.py','run','After',flow,dest,scale,rot,'submit-with-ime','stable'],dest)
  run([e/'analyze.py',dest],dest+'-pixels')
  case=json.loads((e/dest/'result.json').read_text(encoding='utf8'));stats=json.loads((e/dest/'pixels.json').read_text(encoding='utf8'))
  assert case.get('createDocument') and case.get('returned','').startswith('org.dsha.fixture.shortbackup/'),(dest,case.get('outcome'))
  row={'flow':flow,'scale':scale,'rotation':rot,'source':dest,'seconds':case['seconds'],'frames':[]}
  out['rows'].append(row)
  for i in stats:
   if not i['ime']:continue
   focus=i.get('focus',{});geom=next(w for w in case['rows'] if w['label']==i['label'])
   case_view={'stage':i['label'],'ink':[i[k]['ink'] for k in ('choose','cancel')],'inkRows':[i[k]['rows'] for k in ('choose','cancel')],
              'sameGlyphMasks':all(i[k]['sameInk'] for k in ('choose','cancel')),
              'buttonFullHeights':[i[k]['fullHeight'] for k in ('choose','cancel')],'buttonVisibleHeights':[i[k]['visibleHeight'] for k in ('choose','cancel')],
              'focus':focus.get('hint'),'visibleArea':focus.get('visibleArea'),'fullArea':focus.get('area'),'maskedGlyphs':focus.get('visibleGlyphRuns'),
              'imeFullscreen':geom.get('imeFullscreen'),'editorAction':(geom.get('editor') or {}).get('actionCode')}
   row['frames'].append(case_view);record()
   assert geom['rotation']==rot and abs(float(geom['font'])-float(scale))<0.001,case_view
   assert geom.get('imeFullscreen')==False and geom['chooseClear'] and geom['cancelClear'],case_view
   assert case_view['sameGlyphMasks'] and case_view['buttonVisibleHeights']==case_view['buttonFullHeights'],case_view
   assert focus.get('length')==24 and focus.get('visibleArea')==focus.get('area') and focus.get('visibleGlyphRuns',0)>=8,case_view
  assert {'Backup password','Confirm backup password'}=={z['focus'] for z in row['frames']},dest
  print('PASS',dest,len(row['frames']),flush=True)
 out['status']='passed'
except Exception as err:
 out['status']='failed';out['reason']=str(err);record();raise
finally:
 out['end']=datetime.datetime.now().astimezone().isoformat();record()

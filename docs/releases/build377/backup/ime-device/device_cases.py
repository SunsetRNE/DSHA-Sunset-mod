"""Run only the tagged separate backup dialog fixture on the designated API 30 AVD."""
from pathlib import Path
import sys,json,subprocess,time,datetime,re,secrets,string,hashlib
from xml.etree import ElementTree as ET
e=Path(__file__).resolve().parent
root=Path('F:/DSHA_RESTART');adb=[str(root/'cache/build372-android-jni/sdk/platform-tools/adb.exe'),'-H','127.0.0.1','-P','5078','-s','emulator-5684']
pkg='org.dsha.fixture.imebackup'
def run(cmd):
 r=subprocess.run(cmd,capture_output=True,timeout=60)
 if r.returncode:raise RuntimeError(r.stderr.decode('utf8','replace')[:500])
 return r.stdout
def shell(s):return run([*adb,'shell',s]).decode('utf8','replace').strip()
def call(*a):return run([*adb,*map(str,a)]).decode('utf8','replace').strip()
assert 'DSHA_build377_lan_api30_x86' in call('emu','avd','name').splitlines()
assert shell('getprop ro.build.version.sdk')=='30'
action=sys.argv[1]
if action=='install':
 f=Path(sys.argv[2]);print(call('install','-r',f));quit()
def activity():
 m=re.search(r'mResumedActivity:.*? u0 ([\w.$]+/[\w.$]+)',shell('dumpsys activity activities | grep mResumedActivity | head -1'))
 return m[1] if m else ''
def wait(cond,seconds=10):
 until=time.monotonic()+seconds
 while time.monotonic()<until:
  if cond():return True
  time.sleep(.12)
 return False
def ime():
 return 'mInputShown=true' in shell("dumpsys input_method | grep -m 1 'mInputShown=true' || true")
def ime_frame():
 m=re.search(r'ITYPE_IME frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\].*?visible=true',shell("dumpsys window displays | grep 'ITYPE_IME frame=' | head -2"))
 return list(map(int,m.groups())) if m else None
def views():
 assert activity().startswith(pkg+'/'),activity()
 f='/data/local/tmp/dsha377-ime-isolated-view.xml'
 shell('umask 077; uiautomator dump '+f)
 data=shell('cat '+f)
 return [n for n in ET.fromstring(data).iter('node') if n.get('package')==pkg]
def getbox(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(n):
 b=getbox(n);shell(f'input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}')
def lookup(predicate):
 return next((n for n in views() if predicate(n)),None)
def find_scrolling(predicate):
 for _ in range(6):
  ns=views();n=next((n for n in ns if predicate(n)),None)
  if n is not None:return n
  scrolls=[n for n in ns if n.get('scrollable')=='true' and n.get('class','').endswith('ScrollView') and not n.get('resource-id','').endswith('/buttonPanel')]
  if not scrolls:break
  b=getbox(max(scrolls,key=lambda n:getbox(n)[3]-getbox(n)[1]))
  frame=ime_frame()
  if frame:b[3]=min(b[3],frame[1])
  if b[3]-b[1]<20:break
  x=(b[0]+b[2])//2
  shell(f'input swipe {x} {b[3]-6} {x} {b[1]+6} 380')
  time.sleep(.25)
 raise AssertionError('control not reachable from form scroll')
def back_ime():
 if ime():
  shell('input keyevent KEYCODE_BACK')
  assert wait(lambda:not ime())
def getsetting(k):return shell('settings get system '+k)
def setting(k,v):shell('settings delete system '+k if v=='null' else 'settings put system '+k+' '+v)
if action=='restore':
 cfg=json.loads(Path(sys.argv[2]).read_text(encoding='utf8'))
 for k,v in cfg.items():setting(k,v)
 print('restored',cfg);quit()
mode,flow,attempt=sys.argv[2:5];assert mode in ('Before','After') and flow in ('normal','rescue')
d=e/attempt;d.mkdir()
orig={k:getsetting(k) for k in ['accelerometer_rotation','user_rotation','font_scale']}
(d/'orig-settings.json').write_text(json.dumps(orig,indent=2),encoding='utf8')
report={'startUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mode':mode,'flow':flow,
 'package':pkg,'device':call('emu','avd','name'),'rows':[]}
start=time.monotonic()
def save(): (d/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
def sample(label,shot=False):
 ns=views();im=ime();frame=ime_frame() if im else None
 def btn(num):
  n=next((n for n in ns if n.get('resource-id')=='android:id/button'+str(num)),None)
  return None if n is None else getbox(n)
 pos,neg=btn(1),btn(2)
 def clear(b):return b is not None and (not im or frame is not None and b[3]<=frame[1])
 wins=shell('dumpsys window windows')
 capture=[];wanted=False
 for line in wins.splitlines():
  if re.search(r'Window #\d+ Window\{',line):wanted=pkg+'/' in line
  if wanted and re.search(r'Window #|mAttrs=|mFrame=|Requested w=|mInputMethodTarget=',line):capture.append(line.strip())
 row={'label':label,'time':round(time.monotonic()-start,3),'ime':im,'imeFrame':frame,'choose':pos,'cancel':neg,
      'chooseClear':clear(pos),'cancelClear':clear(neg),'font':getsetting('font_scale'),'rotation':getsetting('user_rotation'),'windows':capture,
      'size':shell('wm size; wm density')}
 if im:
  state=shell('dumpsys input_method')
  matcher=re.search(r'(?:mCurrentTextBoxAttribute|mInputEditorInfo):\s*inputType=(0x[0-9a-f]+) imeOptions=(0x[0-9a-f]+)',state,re.I)
  if matcher:row['editor']={'inputType':matcher[1],'imeOptions':matcher[2],'actionCode':int(matcher[2],16)&255}
  m=re.search(r'mInFullscreenMode=(true|false)',state)
  row['imeFullscreen']=None if not m else m[1]=='true'
 if shot:(d/(label+'.png')).write_bytes(run([*adb,'exec-out','screencap','-p']))
 report['rows'].append(row);save();print(label,im,frame,pos,neg,clear(pos),clear(neg),flush=True);return row
def start_activity():
 shell('am force-stop '+pkg)
 cls='com.deepseekharness.app.ui.NativeDataIme'+mode
 before=call('shell','am','start','-n',pkg+'/'+cls,'--es','data_mode','rescue' if flow=='rescue' else 'backup')
 assert wait(lambda:activity()==pkg+'/'+cls),before+' '+activity()
 if flow=='normal':
  first=find_scrolling(lambda n:n.get('text')=='Export application data');tap(first)
 assert wait(lambda:lookup(lambda n:n.get('resource-id','').endswith('/alertTitle')) is not None)
def password_field(hint, value):
 control=find_scrolling(lambda n:n.get('class')=='android.widget.EditText' and n.get('password')=='true' and n.get('text')==hint)
 tap(control)
 assert wait(ime)
 call('shell','input','text',value)
 time.sleep(.6)
try:
 scale=sys.argv[5] if len(sys.argv)>5 else 'default'
 rot=sys.argv[6] if len(sys.argv)>6 else '0'
 if scale!='default':setting('font_scale',scale)
 setting('accelerometer_rotation','0');setting('user_rotation',rot);time.sleep(1)
 start_activity()
 no=sample('shown',True)
 assert not no['ime']
 secret=''.join(secrets.choice(string.ascii_letters+string.digits) for _ in range(24))
 password_field('Backup password',secret);sample('first-ime',True);back_ime()
 password_field('Confirm backup password',secret);del secret
 s=sample('second-ime',True);assert s['ime'] and s['imeFrame']
 finish=sys.argv[7] if len(sys.argv)>7 else 'submit-with-ime'
 assert finish in ('submit-with-ime','hidden-submit','cancel-dialog')
 if finish=='hidden-submit':
  back_ime();s=sample('keyboard-hidden-for-submit',True);assert not s['ime']
 if not s['chooseClear'] or not s['cancelClear']:
  if mode=='After':raise AssertionError('candidate still covered by keyboard')
  report['outcome']='old controls covered';save()
 else:
  if finish=='cancel-dialog':
   n=lookup(lambda n:n.get('resource-id')=='android:id/button2');assert ime();tap(n)
   assert wait(lambda:lookup(lambda v:v.get('resource-id','').endswith('/alertTitle')) is None)
   assert activity().endswith('NativeDataIme'+mode)
   action='Read-only rescue export' if flow=='rescue' else 'Export application data'
   n=find_scrolling(lambda v:v.get('text')==action);tap(n)
   for hint in ('Backup password','Confirm backup password'):
    assert find_scrolling(lambda v:v.get('password')=='true' and v.get('text')==hint) is not None
   report['outcome']='IME visible Cancel dismissed dialog; same Activity reopened with two empty password fields';save()
   raise SystemExit(0)
  n=lookup(lambda n:n.get('resource-id')=='android:id/button1')
  assert ime()==(finish=='submit-with-ime');tap(n)
  assert wait(lambda:'documentsui' in activity() and 'PickActivity' in activity()),activity()
  report['picker']=activity();report['createDocument']='android.intent.action.CREATE_DOCUMENT' in shell("dumpsys activity activities | grep 'android.intent.action.CREATE_DOCUMENT' | head -4")
  assert report['createDocument']
  shell('input keyevent KEYCODE_BACK')
  assert wait(lambda:activity().startswith(pkg+'/'));report['returned']=activity();report['outcome']=('IME visible click' if finish=='submit-with-ime' else 'IME dismissed click')+' reached system CREATE_DOCUMENT and returned on cancellation'
except Exception as ex:
 report['error']=str(ex);raise
finally:
 report['seconds']=round(time.monotonic()-start,3);save()
 shell('am force-stop '+pkg)
 for k,v in orig.items():setting(k,v)

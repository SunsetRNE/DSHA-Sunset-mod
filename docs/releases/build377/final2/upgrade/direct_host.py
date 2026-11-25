import datetime,hashlib,json,os,socket,subprocess,sys,time,shutil,re
from pathlib import Path
ROOT=Path('F:/DSHA_RESTART')
E=Path(__file__).resolve().parent
WORK=ROOT/'cache/build377-direct-upgrade-final2'
CACHE=ROOT/'cache/build372-android-jni'
SDK=CACHE/'sdk';EMU=CACHE/'stable/emulator'
ADB=SDK/'platform-tools/adb.exe'
QEMU=EMU/'qemu/windows-x86_64/qemu-system-x86_64-headless.exe'
PORT=5692;SERVER=5091;SERIAL=f'emulator-{PORT}';AVD='DSHA_377_direct_upgrade_API30_x86_64'
USB='UNMATCHED_USB_DSHA377_DIRECT_5692'
def now():return datetime.datetime.now().astimezone().isoformat(timespec='milliseconds')
def env():
 e=dict(os.environ,ANDROID_HOME=str(SDK),ANDROID_SDK_ROOT=str(SDK),ANDROID_USER_HOME=str(WORK/'user'),ANDROID_EMULATOR_HOME=str(WORK/'user'),ANDROID_AVD_HOME=str(WORK/'avd'),ANDROID_SDK_HOME=str(WORK/'user'),ANDROID_ADB_SERVER_PORT=str(SERVER),ANDROID_ADB_SERVER_ADDRESS='127.0.0.1',ADB_SERVER_SOCKET=f'tcp:localhost:{SERVER}',ANDROID_SERIAL=SERIAL,ADB_MDNS_AUTO_CONNECT='0',ANDROID_I_WANT_MY_TCG='yes',TMP=str(WORK/'tmp'),TEMP=str(WORK/'tmp'),ADB_VENDOR_KEYS=str(WORK/'user/adbkey'))
 e['PATH']=os.pathsep.join([str(EMU/x) for x in ['.','lib64','lib64/qt/lib','lib64/gles_swiftshader']]+[e.get('PATH','')]);return e
def opened(port):
 with socket.socket() as s:s.settimeout(.15);return s.connect_ex(('127.0.0.1',port))==0
def put(fname,value):
 p=E/fname;tmp=p.with_suffix(p.suffix+'.tmp');tmp.write_text(json.dumps(value,indent=2,ensure_ascii=False),encoding='utf-8');os.replace(tmp,p)
def get_record():return json.loads((E/'direct.json').read_text(encoding='utf-8'))
def data(cmd,*,timeout=40,ok=(0,),env0=None):
 started=time.monotonic()
 r=subprocess.run([str(s) for s in cmd],capture_output=True,env=env0 or env(),timeout=timeout,creationflags=subprocess.CREATE_NO_WINDOW)
 row={'at':now(),'cmd':[str(s) for s in cmd],'seconds':round(time.monotonic()-started,3),'exit':r.returncode,'stdout':r.stdout.decode('utf-8',errors='replace').strip(),'stderr':r.stderr.decode('utf-8',errors='replace').strip()}
 with (E/'commands.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
 if r.returncode not in ok:raise RuntimeError(f"{row['cmd'][1:]} failed {r.returncode}: {row['stderr'][:220]}")
 return row['stdout']
def adb(*args,**kwargs):
 if not opened(SERVER):raise RuntimeError('Private ADB server is absent')
 return data([ADB,'-H','127.0.0.1','-P',str(SERVER),'-s',SERIAL,*args],**kwargs)
def checked_name():
 name=[line for line in adb('emu','avd','name').splitlines() if line.strip()]
 if name!=[AVD,'OK']:raise RuntimeError('Unexpected AVD identity '+repr(name))
 return name[0]
def digest(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def host():
 import ctypes
 class Mem(ctypes.Structure):
  _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(n,ctypes.c_ulonglong) for n in ['total','free','page','availPage','virtual','availVirtual','extended']]
 m=Mem();m.length=ctypes.sizeof(m);assert ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(m))
 return {'at':now(),'freeRamBytes':m.free,'totalRamBytes':m.total,'FfreeBytes':shutil.disk_usage(ROOT).free}
# Use the same FILE_STANDARD_INFO.AllocationSize measurement as the formal APK release.
import importlib.util
_storage_spec=importlib.util.spec_from_file_location("direct_storage",ROOT/'artifacts/build377-local/measure_storage.py')
_storage=importlib.util.module_from_spec(_storage_spec);_storage_spec.loader.exec_module(_storage)
def allocated(p):return _storage.measure_file(p)['allocatedBytes']
if __name__=='__main__':
 assert all(not opened(p) for p in (PORT,PORT+1,SERVER))
 assert WORK.exists() and not any(WORK.iterdir())
 for d in ['user','tmp','avd',f'avd/{AVD}.avd']:(WORK/d).mkdir(parents=True,exist_ok=True)
 avdpath=WORK/'avd'/f'{AVD}.avd'
 template=CACHE/'avd/DSHA_build372_jni_api30.avd/config.ini'
 config=template.read_text(encoding='utf-8-sig').replace('DSHA_build372_jni_api30',AVD).replace('hw.cpu.ncore=4','hw.cpu.ncore=2').replace('hw.ramSize=4096','hw.ramSize=2048').replace('disk.dataPartition.size=8G','disk.dataPartition.size=4G').replace('hw.lcd.width=720','hw.lcd.width=540').replace('hw.lcd.height=1280','hw.lcd.height=960').replace('hw.lcd.density=320','hw.lcd.density=240')
 (avdpath/'config.ini').write_text(config,encoding='utf-8')
 (WORK/'avd'/f'{AVD}.ini').write_text(f'avd.ini.encoding=UTF-8\npath={avdpath}\ntarget=android-30\n',encoding='utf-8')
 (E/'guest-config.ini').write_text(config,encoding='utf-8')
 rec={'status':'PREPARING','created':now(),'hostBefore':host(),'avd':AVD,'serial':SERIAL,'privateAdbPort':SERVER,'consolePort':PORT,'transportPort':PORT+1,'work':str(WORK),'ramMiB':2048,'cpuCores':2,'configSha256':digest(avdpath/'config.ini'),'systemImage':(SDK/'system-images/android-30/google_apis/x86_64/source.properties').read_text(encoding='utf-8').strip(),'baselineExpected':'b9ec7b9a45b1c3463b5ea820cd36b9a32d4aa0228f372fee96080714fc3397cc','stages':{}}
 put('direct.json',rec)
 key=WORK/'user/adbkey'
 subprocess.run([ADB,'keygen',key],env=env(),capture_output=True,check=True,creationflags=subprocess.CREATE_NO_WINDOW)
 data([ADB,'-L',f'tcp:localhost:{SERVER}','--one-device',USB,'start-server'])
 assert opened(SERVER)
 args=[QEMU,'-avd',AVD,'-port',str(PORT),'-no-window','-no-audio','-no-boot-anim','-no-snapshot','-no-metrics','-gpu','swiftshader_indirect','-feature','-Vulkan','-accel','auto','-cores','2','-memory','2048','-dns-server','127.0.0.1']
 with (E/'emulator.stdout.log').open('wb') as out,(E/'emulator.stderr.log').open('wb') as err:
  proc=subprocess.Popen(args,env=env(),stdout=out,stderr=err,stdin=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
 rec.update(status='BOOTING',launched=now(),pid=proc.pid,launchArgs=[str(x) for x in args])
 put('direct.json',rec)
 print('Booting private AVD',AVD,proc.pid,'RAM MiB',2048,'console',PORT,'ADB',SERVER)

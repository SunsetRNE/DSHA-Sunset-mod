import os, socket, subprocess, sys, json, time
from pathlib import Path
root=Path('F:/DSHA_RESTART'); cache=root/'cache/build372-android-jni'; work=root/'cache/build377-proot-emulator'; out=root/'artifacts/build377-local/proot-emulator'
sdk=cache/'sdk'; emulator=cache/'stable/emulator'; adb=sdk/'platform-tools/adb.exe'; qemu=emulator/'qemu/windows-x86_64/qemu-system-aarch64-headless.exe'
avd='DSHA_build377_proot_api30_arm64'; port=5672; adbport=5077
for p in (port,port+1,adbport):
    with socket.socket() as sock: assert sock.connect_ex(('127.0.0.1',p))!=0,(p,'busy')
for sub in ['user','tmp','avd',f'avd/{avd}.avd']: (work/sub).mkdir(parents=True,exist_ok=True)
avdpath=work/'avd'/f'{avd}.avd'
config=(cache/'avd/DSHA_build372_jni_api30_arm64.avd/config.ini').read_text(encoding='utf-8-sig').replace('DSHA_build372_jni_api30_arm64',avd)
(avdpath/'config.ini').write_text(config,encoding='utf-8')
(work/'avd'/f'{avd}.ini').write_text(f'avd.ini.encoding=UTF-8\npath={avdpath}\ntarget=android-30\n',encoding='utf-8')
env=dict(os.environ,ANDROID_HOME=str(sdk),ANDROID_SDK_ROOT=str(sdk),ANDROID_USER_HOME=str(work/'user'),ANDROID_EMULATOR_HOME=str(work/'user'),ANDROID_AVD_HOME=str(work/'avd'),ANDROID_SDK_HOME=str(work/'user'),ANDROID_ADB_SERVER_PORT=str(adbport),ANDROID_ADB_SERVER_ADDRESS='127.0.0.1',ADB_SERVER_SOCKET=f'tcp:localhost:{adbport}',ANDROID_SERIAL=f'emulator-{port}',ADB_MDNS_AUTO_CONNECT='0',ANDROID_I_WANT_MY_TCG='yes',TMP=str(work/'tmp'),TEMP=str(work/'tmp'))
# Only a deliberately unmatched USB serial is accepted by this server. The guest uses local TCP.
key=work/'user'/'adbkey'
if not key.exists(): subprocess.run([adb,'keygen',key],env=env,stdout=subprocess.DEVNULL,check=True)
env['ADB_VENDOR_KEYS']=str(key)
env['PATH']=os.pathsep.join(str(emulator/x) for x in ['.','lib64','lib64/qt/lib','lib64/gles_swiftshader'])+os.pathsep+env.get('PATH','')
with (out/'private-server.log').open('w') as log: subprocess.run([adb,'-L',f'tcp:localhost:{adbport}','--one-device','NO_PHYSICAL_DEVICE_build377','start-server'],env=env,stdout=log,stderr=log,check=True)
args=[qemu,'-avd',avd,'-port',str(port),'-no-window','-no-audio','-no-boot-anim','-no-snapshot','-no-metrics','-gpu','swiftshader_indirect','-feature','-Vulkan,-GLDMA,-GLDMA2,-GLDirectMem,-HostComposition,-VirtioGpuNext,-WindowsHypervisorPlatform','-accel','off','-cores','4','-memory','2560','-verbose','-show-kernel','-shell-serial','file:'+str(out/'kernel-r1.log'),'-qemu','-machine','type=virt']
with (out/'stdout-r1.log').open('w') as stdout, (out/'stderr-r1.log').open('w') as stderr: proc=subprocess.Popen(args,env=env,stdout=stdout,stderr=stderr,stdin=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
(out/'launch-r1.json').write_text(json.dumps(dict(pid=proc.pid,serial=env['ANDROID_SERIAL'],privatePort=adbport,started=time.time(),args=list(map(str,args)),avdDir=str(avdpath)),indent=2))
print('Started private emulator',proc.pid,port,adbport)

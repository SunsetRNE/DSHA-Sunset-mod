"""Build a separate fixture and exercise the system DocumentsUI on the isolated API 30 AVD."""
from pathlib import Path
import datetime, hashlib, json, os, re, secrets, subprocess, sys, time, uuid, zipfile
from xml.etree import ElementTree as ET

here=Path(__file__).resolve().parent
root=here.parents[2]
src=root/"source/build377-local"
tc=root/"toolchains/current"
bt=tc/"android-sdk/build-tools/36.0.0"
sdk=tc/"android-sdk/platforms/android-34/android.jar"
jdk=tc/"jdk-17/bin"
round_id=sys.argv[1]
assert re.fullmatch("r[0-9]+",round_id)
out=here/("android-"+round_id)
out.mkdir(exist_ok=False)
pkg="org.dsha.fixture.saf377"+round_id
environment=dict(os.environ,JAVA_HOME=str(jdk.parent))
adb=[root/"cache/build372-android-jni/sdk/platform-tools/adb.exe","-H","127.0.0.1","-P","5078","-s","emulator-5684"]
def run(args,timeout=100,check=True):
    result=subprocess.run(list(map(str,args)),cwd=out,env=environment,capture_output=True,timeout=timeout)
    data=(result.stdout+result.stderr).decode("utf-8","replace")
    if check and result.returncode: raise RuntimeError(data)
    return data
assert "DSHA_build377_lan_api30_x86" in run([*adb,"emu","avd","name"]).splitlines()
started=datetime.datetime.now(datetime.timezone.utc).isoformat()
w=json.loads((root/"artifacts/build376-formal-20261125/targeted-r1/worker-inputs.json").read_text(encoding="utf-8-sig"))
previous=Path(next(x for x in w["classpath"] if "runtime_app_classes_jar" in x))
classes=out/"classes"
classes.mkdir()
declared=["transfer/SelectedDocumentSave.java","backup/DocumentStreams.java","backup/AndroidBackupFileSystem.java","backup/BackupFileSystem.java","backup/BackupControl.java","backup/BackupLimits.java","util/FileIntegrity.java","util/FileKind.java"]
declared.append("util/LinuxOpenFlags.java")
sources=[src/"app/src/main/java/com/deepseekharness/app"/x for x in declared]
sources=[p for p in sources if p.is_file()]
sources.append(here/"SafProbeActivity.java")
run([jdk/"javac.exe","-proc:none","-encoding","UTF-8","-source","17","-target","17","-cp",str(sdk)+";"+str(previous),"-d",classes,*sources])
program=out/"input.jar"
with zipfile.ZipFile(program,"w",zipfile.ZIP_DEFLATED) as z,zipfile.ZipFile(previous) as prior:
    emitted=set()
    for f in classes.rglob("*.class"):
        key=f.relative_to(classes).as_posix()
        emitted.add(key);z.write(f,key)
    include=["DshaDocumentsProvider","DocumentRowsSnapshot","util/FileKind","util/UiText","util/UiLanguagePreference","util/SystemLanguage","util/UiMessages","util/UiStateText","util/DocumentPaths","util/RuntimeWorkPort","core/RuntimeTasks","backup/UserDataLayout","util/RuntimeTaskRegistry"]
    for name in prior.namelist():
        path=name.removeprefix("com/deepseekharness/app/")
        if name not in emitted and any(path==base+".class" or path.startswith(base+"$") for base in include):
            z.writestr(name,prior.read(name))
run([bt/"d8.bat","--release","--min-api","30","--lib",sdk,"--output",out/"dex.jar",program])
manifest=out/"AndroidManifest.xml"
manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}">
<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="30"/>
<queries><provider android:authorities="com.android.externalstorage.documents"/><provider android:authorities="com.android.providers.downloads.documents"/></queries>
<application android:label="DSHA synthetic SAF test" android:debuggable="true" android:allowBackup="false">
<activity android:name="org.dsha.fixture.saf.SafProbeActivity" android:exported="true"/></application></manifest>''',encoding="utf-8")
unsigned=out/"unsigned.apk"
run([bt/"aapt2.exe","link","-I",sdk,"--manifest",manifest,"-o",unsigned])
with zipfile.ZipFile(unsigned,"a") as z,zipfile.ZipFile(out/"dex.jar") as dex:
    for name in dex.namelist():
        if name.endswith(".dex"):z.writestr(name,dex.read(name))
ks=root/"cache/build377-lan-emulator/user"/(pkg+".p12")
assert not ks.exists()
environment["SAF377_PROBE_PASS"]=secrets.token_hex(24)
run([jdk/"keytool.exe","-genkeypair","-keystore",ks,"-storepass:env","SAF377_PROBE_PASS","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=SAF synthetic only","-validity","30"])
apk=out/"fixture.apk"
run([bt/"apksigner.bat","sign","--ks",ks,"--ks-pass","env:SAF377_PROBE_PASS","--out",apk,unsigned])
name="saf377-"+uuid.uuid4().hex[:12]+".bin"
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
(out/"build.json").write_text(json.dumps({"start":started,"inputs":{str(p):sha(p) for p in sources},"priorClassesSha":sha(previous),"apkSha256":sha(apk),"package":pkg,"newDocument":name},indent=2),encoding="utf-8")
print(run([*adb,"install",apk]),flush=True)
print(run([*adb,"shell","am","start","-W","-n",pkg+"/org.dsha.fixture.saf.SafProbeActivity","--es","name",name]),flush=True)
def snapshot(name_xml):
    remote="/data/local/tmp/"+pkg+".xml"
    run([*adb,"shell","uiautomator","dump",remote],timeout=20)
    data=run([*adb,"exec-out","cat",remote])
    (out/name_xml).write_text(data,encoding="utf-8")
    return ET.fromstring(data)
ui=snapshot("picker.xml")
assert any(n.get("text","")==name for n in ui.iter("node")),"unique synthetic name not on picker"
button=next(n for n in ui.iter("node") if n.get("package","") in ("com.android.documentsui","com.google.android.documentsui") and n.get("text","").upper() in ("SAVE","保存") and n.get("enabled")=="true")
x,y,r,b=map(int,re.findall(r"\d+",button.get("bounds")))
print("pressed system Save",run([*adb,"shell","input","tap",str((x+r)//2),str((y+b)//2)]),flush=True)
for i in range(60):
    data=run([*adb,"exec-out","run-as",pkg,"cat","files/"+name+".json"],check=False)
    try: result=json.loads(data)
    except ValueError: time.sleep(.4);continue
    if not result.get("passed") and "failure" not in result: time.sleep(.4);continue
    (out/"result.json").write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding="utf-8")
    print(json.dumps(result,indent=2,ensure_ascii=False));sys.exit(0 if result.get("passed") else 1)
raise RuntimeError("probe result timeout")

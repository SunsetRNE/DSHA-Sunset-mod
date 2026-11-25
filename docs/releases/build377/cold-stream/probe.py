from pathlib import Path
import hashlib,json,os,secrets,subprocess,sys,zipfile
e=Path(__file__).resolve().parent
root=e.parents[2]
tc=root/"toolchains/current"
jdk=tc/"jdk-17/bin"
sdk=tc/"android-sdk/platforms/android-34/android.jar"
bt=tc/"android-sdk/build-tools/36.0.0"
apk=root/"artifacts/build377-local/signed/dsha-0.2.2-rc.5.apk"
digest="2e6f0324ebe4b52b143dcb2bf04800b1ce73c106a53b567de1afdafca5dba3d9"
assert hashlib.sha256(apk.read_bytes()).hexdigest()==digest
stage,attempt=sys.argv[1:3]; assert stage in ("host","android","process","observearm","observex86","spawnarm","spawnx86","ackoverlay") and attempt.isalnum()
out=e/(stage+"-"+attempt);out.mkdir()
cl=out/"classes";cl.mkdir()
env=dict(os.environ,JAVA_HOME=str(jdk.parent))
def run(args,timeout=120):
    r=subprocess.run(list(map(str,args)),cwd=out,env=env,capture_output=True,timeout=timeout)
    data=(r.stdout+r.stderr).decode("utf-8","replace")
    if r.returncode:raise RuntimeError(data)
    return data
if stage=="host":
    w=json.loads((root/"artifacts/build377-local/windows-r3/worker-inputs.json").read_text(encoding="utf-8-sig"))
    cp=";".join([cl.as_posix()]+[Path(x).as_posix() for x in w["classpath"]])
    def j(binary,args):
        file=out/(binary+".args");file.write_text("\n".join('"'+str(x).replace("\\","/")+'"' for x in args))
        return run([jdk/(binary+".exe"),"@"+str(file)])
    j("javac",["-proc:none","-encoding","UTF-8","-classpath",cp,"-d",cl,e/"HostColdProbe.java"])
    report=j("java",["-classpath",cp,"com.deepseekharness.app.runtime.HostColdProbe",apk,out/"tar"])
else:
    adb=[root/"cache/build372-android-jni/sdk/platform-tools/adb.exe","-H","127.0.0.1","-P","5078","-s","emulator-5684"]
    assert "DSHA_build377_lan_api30_x86" in run([*adb,"emu","avd","name"]).splitlines()
    pkg="org.dsha.fixture.exactcold"+stage+attempt
    entry="ExactCold" if stage=="android" else "ProcessCold"
    run([jdk/"javac.exe","-encoding","UTF-8","-source","17","-target","17","-cp",sdk,"-d",cl,e/(entry+".java")])
    run([jdk/"jar.exe","--create","--file",out/"probe.jar","-C",cl,"."])
    run([bt/"d8.bat","--release","--min-api","30","--lib",sdk,"--output",out/"dex.jar",out/"probe.jar"])
    m=out/"AndroidManifest.xml";m.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="30"/><queries><package android:name="dsh.android.client"/></queries><application android:allowBackup="false" android:extractNativeLibs="true" android:label="Exact archive fixture"/><instrumentation android:name="org.dsha.fixture.exactcold.ExactCold" android:targetPackage="{pkg}"/></manifest>''',encoding="utf-8")
    unsigned=out/"unsigned.apk";run([bt/"aapt2.exe","link","-I",sdk,"--manifest",m,"-o",unsigned])
    if stage!="android":
        m.write_text(m.read_text().replace("org.dsha.fixture.exactcold.ExactCold","org.dsha.fixture.exactcold.ProcessCold"),encoding="utf-8")
        run([bt/"aapt2.exe","link","-I",sdk,"--manifest",m,"-o",unsigned])
    with zipfile.ZipFile(unsigned,"a") as z,zipfile.ZipFile(out/"dex.jar") as dex,zipfile.ZipFile(apk) as product:
        for name in dex.namelist():
            if name.endswith(".dex"): z.writestr(name,dex.read(name))
        if stage not in ("observex86","spawnx86"):
            name="lib/arm64-v8a/libtermux.so"; z.writestr(name,product.read(name))
        if stage in ("process","ackoverlay"):
            name="lib/arm64-v8a/libdsha-session.so";z.writestr(name,product.read(name))
            if stage=="ackoverlay":
                overlay=Path(sys.argv[3]).resolve(strict=True); expected=sys.argv[4].lower()
                assert hashlib.sha256(overlay.read_bytes()).hexdigest()==expected
                if overlay.suffix==".dex":
                    z.write(overlay,"classes2.dex")
                else:
                    with zipfile.ZipFile(overlay) as delta:
                        assert [n for n in delta.namelist() if n.endswith(".dex")]==["classes.dex"]; z.writestr("classes2.dex",delta.read("classes.dex"))
        elif stage.startswith("observe"):
            source=root/"source/build377-local/tools/native-session/session-launcher.c"
            data=source.read_text()
            for needle,extra in [
             ("    pid_t child = fork();",'    dprintf(2,"OBS_PRE_FORK p=%d\\n",getpid());\n    pid_t child = fork();\n    if(child>0)dprintf(2,"OBS_PARENT_FORK child=%d\\n",child);'),
             ("        struct sigaction ordinary", '        (void)!write(2,"OBS_CHILD_ENTER\\n",16);\n        struct sigaction ordinary'),
             ("        execvp(argv[command]", '        (void)!write(2,"OBS_CHILD_PRE_EXEC\\n",19);\n        execvp(argv[command]'),
             ("        if (waitpid(child, &status, 0) == child) break;",'        if (waitpid(child, &status, 0) == child) { dprintf(2,"OBS_WAIT raw=%d exited=%d code=%d signaled=%d signal=%d\\n",status,WIFEXITED(status),WIFEXITED(status)?WEXITSTATUS(status):-1,WIFSIGNALED(status),WIFSIGNALED(status)?WTERMSIG(status):-1); break; }'),
            ]:
                assert data.count(needle)==1;data=data.replace(needle,extra)
            duplicate=out/"observed-launcher.c";duplicate.write_text(data)
            abi,arch=("arm64-v8a","aarch64") if stage=="observearm" else ("x86_64","x86_64")
            bins=tc/"android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin"
            target=out/"libdsha-session.so"
            run([bins/"clang.exe","--target="+arch+"-linux-android23","-std=c11","-D_GNU_SOURCE","-O2","-fPIE","-pie","-fstack-protector-strong","-D_FORTIFY_SOURCE=2","-Wl,-z,relro","-Wl,-z,now","-Wl,-z,max-page-size=16384","-Wl,-z,common-page-size=4096",duplicate,"-o",target])
            run([bins/"llvm-strip.exe","--strip-unneeded",target]);z.write(target,"lib/"+abi+"/libdsha-session.so")
        elif stage in ("spawnarm","spawnx86"):
            arm=stage=="spawnarm";abi,arch=("arm64-v8a","aarch64") if arm else ("x86_64","x86_64")
            expected="c5b2e88dd678a89f8cb074b417fe555786d2301028a995d9154404a6ee17cdb5" if arm else "27acf2d90d75210b5bc1cd55715f7d220407ea0146f37f49e79819c13922c682"
            candidate=root/"artifacts/build377-local/cold-process-io"/arch/"libdsha-session.so"
            assert hashlib.sha256(candidate.read_bytes()).hexdigest()==expected
            z.write(candidate,"lib/"+abi+"/libdsha-session.so")
        if stage in ("spawnx86","observex86"):
            native=root/"source/build377-local/tools/termux-jni/dsha-process.c"
            bins=tc/"android-sdk/ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin"
            shim=out/"libtermux.so"
            run([bins/"clang.exe","--target=x86_64-linux-android30","-shared","-fPIC","-O2","-D_GNU_SOURCE","-Wl,-soname,libtermux.so",native,"-o",shim]);z.write(shim,"lib/x86_64/libtermux.so")
    ks=root/"cache/build377-lan-emulator/user"/(pkg+".p12");assert not ks.exists()
    env["COLD377_PROBE_PASS"]=secrets.token_hex(18)
    run([jdk/"keytool.exe","-genkeypair","-keystore",ks,"-alias","fixture","-storepass:env","COLD377_PROBE_PASS","-keyalg","RSA","-keysize","2048","-dname","CN=Cold probe only","-validity","30"])
    signed=out/"fixture.apk"
    run([bt/"apksigner.bat","sign","--ks",ks,"--ks-pass","env:COLD377_PROBE_PASS","--out",signed,unsigned]); print(run([*adb,"install","--no-incremental",signed]),flush=True)
    report=run([*adb,"shell","am","instrument","-w","-r",pkg+"/org.dsha.fixture.exactcold."+entry],timeout=180)
(out/"result.txt").write_text(report,encoding="utf-8");print(report,flush=True)
if stage=="android":assert "INSTRUMENTATION_CODE: -1" in report

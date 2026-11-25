"""Build an isolated two-Activity IME fixture using the actual signed app's DEX and resources."""
from pathlib import Path
import sys,subprocess,zipfile,hashlib,json,re,shutil,os
e=Path(__file__).resolve().parent
root=Path('F:/DSHA_RESTART');src=root/'source/build377-local'
ev=root/'artifacts/build377-local';sdk=root/'toolchains/current/android-sdk'
build=sdk/'build-tools/36.0.0';android=sdk/'platforms/android-37.0/android.jar'
java=root/'toolchains/current/jdk-17/bin/java.exe'
inp=ev/'signed-retry/dsha-0.2.2-rc.5.apk'
def sha(f):
 with Path(f).open('rb') as data:return hashlib.file_digest(data,'sha256').hexdigest()
assert sha(inp)=='72917c42dee81df41e26f2d7977ba735b0e3c0c94a93803561272d6f6facbeaa'
d=e/sys.argv[1]; d.mkdir()
pkg='org.dsha.fixture.imebackup'
w=json.loads((ev/'windows-r4/worker-inputs.json').read_text(encoding='utf-8-sig'))
cp=[str(android),str(src/'app/build/intermediates/compile_and_runtime_r_class_jar/standardRelease/processStandardReleaseResources/R.jar'),*w['classpath']]
log=[]
def cmd(name,args):
 a=list(map(str,args));r=subprocess.run(a,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,cwd=src)
 (d/(name+'.log')).write_bytes(r.stdout)
 log.append(dict(step=name,argv=a,code=r.returncode))
 (d/'commands.json').write_text(json.dumps(log,indent=2),encoding='utf8')
 if r.returncode:
  print(name,r.stdout.decode('utf8','replace')[-1500:]);sys.exit(r.returncode)
sf=src/'app/src/main/java/com/deepseekharness/app/ui'; javaSources=[]
for side in ('Before','After'):
 p=sf/'NativeDataActivity.java'
 old=subprocess.check_output(['git','show','5d952daf:'+p.relative_to(src).as_posix()],cwd=src).decode('utf8')
 text=old if side=='Before' else p.read_text(encoding='utf8')
 cls='NativeDataIme'+side
 text=text.replace('NativeDataActivity',cls)
 # Both views use the production style and own the same native system picker contracts.
 text=text.replace('    super.onCreate(saved);','    setTheme(R.style.Theme_DeepseekHarness);\n    super.onCreate(saved);')
 if side=='After':
  marker='  private void promptExport(';i=text.index(marker);j=text.index('  private void clearExportPrompt(',i)
  text=text[:i]+text[i:j].replace('new DshaDialogBuilder(this)','new ImeProbeDialogBuilder(this)')+text[j:]
  if len(sys.argv)>2 and sys.argv[2]=='imeFlags':
   text=text[:i]+text[i:].replace('PortablePasswordFields fields = new PortablePasswordFields(this);','ImeProbePasswordFields fields = new ImeProbePasswordFields(this);')

 f=d/(cls+'.java');f.write_text(text,encoding='utf8');javaSources.append(f)
helper=d/'ImeProbeDialogBuilder.java'
helper.write_text((sf/'DshaDialogBuilder.java').read_text(encoding='utf8').replace('DshaDialogBuilder','ImeProbeDialogBuilder'),encoding='utf8');javaSources.append(helper)
if len(sys.argv)>2 and sys.argv[2]=='imeFlags':
 helper=d/'ImeProbePasswordFields.java'
 t=(sf/'PortablePasswordFields.java').read_text(encoding='utf8').replace('PortablePasswordFields','ImeProbePasswordFields')
 t=t.replace('    addView(first);\n    addView(confirm);','''    for (EditText field : new EditText[] {first, confirm}) {
      field.setImeOptions(field.getImeOptions() | EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
      addView(field);
    }''')
 helper.write_text(t,encoding='utf8');javaSources.append(helper)
a=d/'ImeFixtureApp.java'
a.write_text("""package com.deepseekharness.app.ui;
public final class ImeFixtureApp extends com.deepseekharness.app.DshaApp {
 @Override public void onCreate() { com.deepseekharness.app.data.PortableSettings.initialize(this); com.deepseekharness.app.util.UiText.setLanguage("en"); registerActivityLifecycleCallbacks(new ModernAndroidUi()); }
}""",encoding='utf8');javaSources.append(a)
mf=d/'AndroidManifest.xml'
mf.write_text(f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}" android:versionCode="1" android:versionName="377-ime">
<uses-sdk android:minSdkVersion="30" android:targetSdkVersion="37"/>
<application android:name="com.deepseekharness.app.ui.ImeFixtureApp" android:label="DSHA IME fixture" android:allowBackup="false">
<activity android:name="com.deepseekharness.app.ui.NativeDataImeBefore" android:exported="true" android:windowSoftInputMode="adjustResize"/>
<activity android:name="com.deepseekharness.app.ui.NativeDataImeAfter" android:exported="true" android:windowSoftInputMode="adjustResize"/>
</application></manifest>""",encoding='utf8')
cmd('aapt',[build/'aapt2.exe','link','-I',android,'--manifest',mf,'-o',d/'manifest.apk'])
classes=d/'classes';classes.mkdir()
args=['--release','17','-encoding','UTF-8','-cp',os.pathsep.join(cp),'-d',classes,*javaSources]
f=d/'compile.args';f.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in args),encoding='utf8')
cmd('javac',[java.with_name('javac.exe'),'@'+str(f)])
extra=d/'dex';extra.mkdir()
args=['--min-api','30','--lib',android,'--classpath',w['classpath'][4],'--output',extra,*classes.rglob('*.class')]
f=d/'dex.args';f.write_text('\n'.join('"'+str(v).replace('\\','/')+'"' for v in args),encoding='utf8')
cmd('d8',[java,'-cp',build/'lib/d8.jar','com.android.tools.r8.D8',*args])
unaligned=d/'probe-raw.apk'
with zipfile.ZipFile(unaligned,'w') as out,zipfile.ZipFile(d/'manifest.apk') as man,zipfile.ZipFile(inp) as base:
 out.writestr('AndroidManifest.xml',man.read('AndroidManifest.xml'))
 for i in base.infolist():
  if re.fullmatch(r'classes\d*\.dex',i.filename) or i.filename=='resources.arsc' or i.filename.startswith('res/'):
   out.writestr(i,base.read(i))
 out.write(extra/'classes.dex','classes5.dex')
aligned=d/'probe-aligned.apk';apk=d/'probe.apk'
cmd('zipalign',[build/'zipalign.exe','-p','4',unaligned,aligned])
ks=e/'fixture-test-only.p12'
if not ks.exists():cmd('fixture-key',[java.with_name('keytool.exe'),'-genkeypair','-alias','probe','-keyalg','RSA','-keysize','2048','-validity','100','-dname','CN=Isolated synthetic IME probe','-keystore',ks,'-storepass','probe-fixture-only','-keypass','probe-fixture-only','-storetype','PKCS12'])
cmd('sign-fixture',[java,'-jar',build/'lib/apksigner.jar','sign','--ks',ks,'--ks-pass','pass:probe-fixture-only','--out',apk,aligned])
cmd('verify-fixture',[java,'-jar',build/'lib/apksigner.jar','verify','--verbose','--print-certs',apk])
(d/'result.json').write_text(json.dumps(dict(baseApkSha256=sha(inp),fixtureApkSha256=sha(apk),inputs={str(f):sha(f) for f in javaSources},sourceHash=sha(sf/'NativeDataActivity.java'),helperHash=sha(sf/'DshaDialogBuilder.java'),package=pkg,additionalDex=sha(extra/'classes.dex')),indent=2),encoding='utf8')
print(apk,sha(apk))

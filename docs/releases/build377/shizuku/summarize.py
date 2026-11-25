from pathlib import Path
import datetime, json, hashlib, subprocess
ev=Path(__file__).resolve().parent
root=Path('F:/DSHA_RESTART')
before=root/'source/build376-local'
after=root/'source/build377-local'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def semantic(path):
 s=path.read_text(encoding='utf8')
 return hashlib.sha256(s[s.index('package com.deepseekharness.app;'):].encode('utf8')).hexdigest()
files=['app/src/main/java/com/deepseekharness/app/ShellService.java','app/src/main/java/com/deepseekharness/app/ShizukuShell.java','app/src/main/aidl/com/deepseekharness/app/IShellService.aidl','tools/aidl-stub/com/deepseekharness/app/IShellService.java','app/src/test/java/com/deepseekharness/app/ShizukuServiceLifecycleTest.java','tools/aidl-stub/README.md','tools/java-format.lock.json','tools/java-format/README.md','tools/test-java-format.py']
sdk=root/'toolchains/current/gradle-user-home/caches/modules-2/files-2.1/dev.rikka.shizuku/shared/13.1.5/8c4e73ce466cd27aff10565d2ec49809c258f974/shared-13.1.5.aar'
baselineDigest='b9ec7b9a45b1c3463b5ea820cd36b9a32d4aa0228f372fee96080714fc3397cc'
official=root/'release/0.2.2-rc.5'
paths=[official/'dsha-0.2.2-rc.5.apk', *official.glob('history/*build376*/dsha-0.2.2-rc.5.apk')]
apk=next(p for p in paths if p.is_file() and sha(p)==baselineDigest)
g=ev/'after/generated/com/deepseekharness/app/IShellService.java'
fallback=after/files[3]
wire=semantic(g);assert wire==semantic(fallback)
summary={'baseCommit':subprocess.check_output(['git','-C',str(after),'rev-parse','HEAD'],text=True).strip(), 'baselineApk':{'sha256':sha(apk),'bytes':apk.stat().st_size},'sourceFiles':{s:{'before':sha(before/s) if (before/s).is_file() else None,'after':sha(after/s)} for s in files},'sdk':{'aar':str(sdk),'sha256':sha(sdk),'wireDestroy':16777115},'aidl':{'flags':['--lang=java','--min_sdk_version=30'],'sdk':'36.0.0','fallbackFileSha256':sha(fallback),'generatedFileSha256':sha(g),'sourceAfterPackageWithLfSha256':wire},'actualHost':'Windows 11 x64, JDK Temurin 17.0.20.1+1, SDK 34 / Robolectric 4.15.1','rounds':{}}
for stage in ('before','after','mixed'):
 for log in sorted((ev/stage).glob('junit*.txt')):
  lines=log.read_text(encoding='utf8',errors='replace').splitlines()
  starts=[l.split('\t')[1] for l in lines if l.startswith('START\t')]
  ends=[l.split('\t')[1] for l in lines if l.startswith('FINISH\t')]
  cases={l.split('\t')[2].split('.')[-1]:l.split('\t')[1] for l in lines if l.startswith('CASE\t')}
  summary['rounds'][str(log.relative_to(ev))]={'firstStartUtc':starts[0],'firstMethodFinishUtc':ends[0],'lastMethodFinishUtc':ends[-1],'cases':cases,'total':next(l for l in reversed(lines) if l.startswith('TOTAL\t'))}
(ev/'receipt.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({k:summary[k] for k in ('baseCommit','baselineApk','aidl')},indent=2))

from pathlib import Path
import json, subprocess,sys
ev = Path(__file__).resolve().parent
d=ev/'mixed'
d.mkdir(exist_ok=True)
jdk=Path('F:/DSHA_RESTART/toolchains/current/jdk-17/bin')
q='com/deepseekharness/app/IShellService.java'
legacy=(ev/'before/generated'/q).read_text(encoding='utf8')
# Separate Java type names in this test VM only; preserve the actual Binder interface descriptor.
legacy=legacy.replace('package com.deepseekharness.app;', 'package fixture.pre;').replace('com.deepseekharness.app.IShellService','fixture.pre.IShellService').replace('"fixture.pre.IShellService"','"com.deepseekharness.app.IShellService"')
old=d/'fixture/pre/IShellService.java';old.parent.mkdir(parents=True,exist_ok=True);old.write_text(legacy,encoding='utf8')
name='com.deepseekharness.app.MixedClientsTest'
for exe,original,file in [('javac','compile.args','compile.txt'),('java','junit.args','junit.txt')]:
 args=[json.loads(line) for line in (ev/'after'/original).read_text(encoding='utf8').splitlines() if line.strip()]
 args[args.index('-cp')+1]=str(d)+';'+args[args.index('-cp')+1]
 if exe=='javac':
  args=args[:args.index('-d')]+['-d',str(d),str(old),str(ev/'MixedClientsTest.java')]
 else:args[-1]=name
 target=d/original;target.write_text('\n'.join(json.dumps(a,ensure_ascii=False) for a in args),encoding='utf8')
 with (d/file).open('wb') as stream: result=subprocess.run([str(jdk/(exe+'.exe')),'@'+str(target)],stdout=stream,stderr=subprocess.STDOUT)
 if result.returncode: print((d/file).read_text(encoding='utf8',errors='replace')[-1800:]);sys.exit(result.returncode)
print((d/'junit.txt').read_text(encoding='utf8',errors='replace')[-650:])

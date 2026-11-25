"""Exercise two legitimate loopback services with one cookie jar. Record only credential presence."""
import hashlib, json, os, subprocess, sys, time
from pathlib import Path
from playwright.sync_api import sync_playwright

e=Path(__file__).resolve().parent
root=e.parents[2]; src=root/"source/build377-local"
phase=sys.argv[1]; assert phase in ("before","after","ticket")
build=Path(sys.argv[2]); out=e/("dual-"+phase+"-"+str(int(time.time()))); out.mkdir()
tc=root/"toolchains/current"; jdk=tc/"jdk-17/bin"
dep=tc/"gradle-user-home/caches/modules-2/files-2.1"
jars=[next((dep/p).rglob("*.jar")) for p in ["junit/junit/4.13.2","org.hamcrest/hamcrest-core/1.3","org.bouncycastle/bcprov-jdk15to18/1.85.2"]]
cp=os.pathsep.join(map(str,[out,build/"classes",*jars]))
proxyfile="app/src/main/java/com/deepseekharness/app/LanProxyService.java"
source=(subprocess.check_output(["git","show","6e5042cb:"+proxyfile],cwd=src).decode("utf8") if phase=="before" else (src/proxyfile).read_text(encoding="utf8"))
lines=source.splitlines()
idx=next(i for i,line in enumerate(lines) if "if (!run.sessions.accepts(token))" in line or "boolean hasSession = run.sessions.accepts(token);" in line)
lines.insert(idx,'        System.out.println("OBS " + (websocket ? "wss" : "http") + " basic=" + !request.value("Authorization").isEmpty() + " own_session=" + run.sessions.accepts(token));')
instrumented=out/"LanProxyService.java"; instrumented.write_text("\n".join(lines),encoding="utf8")
password=(src/"app/src/main/java/com/deepseekharness/app/util/LanPassword.java").read_text(encoding="utf8")
password=password.replace("byte[] expected = Base64.getDecoder().decode(parts[1]);","byte[] expected = Base64.getDecoder().decode(parts[1]); System.out.println(\"KDF login\");")
pwdfile=out/"LanPassword.java";pwdfile.write_text(password,encoding="utf8")
subprocess.run([jdk/"javac.exe","--release","17","-encoding","UTF8","-cp",cp,"-d",out,instrumented,pwdfile,e/"BrowserLanBackend.java"],check=True)
envs=[]
record={"phase":phase,"inputProxySha256":hashlib.sha256(source.encode("utf8")).hexdigest(),"browsers":{}}
def ws(p,custom=False):
    if phase=="ticket":
        return p.evaluate("""async custom=>{
          const path=custom?'/protocol':'/api/remote.mux';
          const res=await fetch('/__dsha/lan/ws-ticket',{method:'POST',credentials:'same-origin',redirect:'error',
            cache:'no-store',headers:{'X-Dsha-WebSocket-Path':path}});
          if(!res.ok || res.headers.get('X-Dsha-Lan-Transport')!=='v1')throw new Error('ticket rejected '+res.status);
          const {protocol}=await res.json();
          return await new Promise((resolve)=>{
            const offered=custom?['app.other','app.echo',protocol]:['dsha-lan-v1',protocol];
            const s=new WebSocket(location.origin.replace('https:','wss:')+path,offered);
            s.onopen=()=>s.send('synthetic-echo');
            s.onmessage=e=>{const ok=e.data==='synthetic-echo' && s.protocol===(custom?'app.echo':'dsha-lan-v1');
               s.close();resolve(ok)};
            s.onerror=()=>resolve(false);
            setTimeout(()=>resolve(false),4000);
          })
        }""",custom)
    return p.evaluate("""() => new Promise((resolve)=>{
      const s=new WebSocket(location.origin.replace('https:','wss:')+'/upgrade');
      s.onopen=()=>{resolve(true);s.close()}; s.onerror=()=>resolve(false);
      setTimeout(()=>resolve(false),3000)
    })""")
try:
    for i in range(2):
        proc=subprocess.Popen([jdk/"java.exe","-cp",cp,"com.deepseekharness.app.BrowserLanBackend"],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True,encoding='utf8')
        port,pin,fingerprint=proc.stdout.readline().strip().split()
        envs.append((proc,"https://127.0.0.1:"+port,pin))
    origins=[x[1] for x in envs]
    os.environ["PLAYWRIGHT_BROWSERS_PATH"]=str(root/"cache/build377-lan-browsers")
    with sync_playwright() as pw:
        for kind in (sys.argv[3:] or ["chromium","firefox"]):
            chromium=root/"cache/playwright-1.64.1/chromium_headless_shell-1248/chrome-headless-shell-win64/chrome-headless-shell.exe"
            firefox=Path(os.environ["LOCALAPPDATA"])/"ms-playwright/firefox-1538/firefox/firefox.exe"
            if kind=="chromium":
                b=pw.chromium.launch(executable_path=str(chromium),args=["--disable-background-networking","--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1","--ignore-certificate-errors-spki-list="+",".join(x[2] for x in envs)])
            elif kind=="firefox":
                b=pw.firefox.launch(executable_path=str(firefox))
            else:
                assert kind=="webkit"
                b=pw.webkit.launch()
            ctx=b.new_context(http_credentials={"username":"dsha","password":"fixture密码123"},ignore_https_errors=(kind!="chromium"))
            ctx.route("**/*",lambda r:r.continue_() if any(r.request.url.startswith(origin+"/") for origin in origins) else r.abort())
            first,second=ctx.new_page(),ctx.new_page()
            rec={"version":b.version}
            record["browsers"][kind]=rec
            for page,origin in zip((first,second),origins):
                assert page.goto(origin,timeout=10000).status==200
                assert "verified-browser-backend" in page.locator("body").inner_text()
            rec["afterLoginNames"]=[c["name"] for c in ctx.cookies()]
            rec["wssAfterBothLogins"]=[ws(first),ws(second)]
            rec["alternateReloads"]=[p.reload(timeout=10000).status for p in (first,second,first,second)]
            rec["wssAfterReloads"]=[ws(first,True),ws(second,True)]
            if phase in ("after","ticket"):
                assert len(rec["afterLoginNames"])==2 and all(n.startswith("__Host-dsha_lan_") for n in rec["afterLoginNames"])
                assert rec["wssAfterBothLogins"]==[True,True] and rec["wssAfterReloads"]==[True,True],rec
            b.close()
            for proc,_,_ in envs:
                # A marker in each child output separates browser runs without recording headers.
                proc.stdin.write(kind+"\n");proc.stdin.flush()
finally:
    for i,(proc,origin,pin) in enumerate(envs):
        outtext,_=proc.communicate("done\n",timeout=10)
        (out/f"service-{i+1}.log").write_text(outtext,encoding="utf8")
    (out/"report.json").write_text(json.dumps(record,indent=2),encoding="utf8")
    print(out,record)

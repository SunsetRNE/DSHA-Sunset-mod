// Real pinned DSH server/client and production trial POST. Isolated homes only.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {spawn} from 'node:child_process';
import {createHash,randomBytes} from 'node:crypto';

const root=path.resolve(import.meta.dirname,'..'),assets=path.join(root,'app/src/main/assets');
const pointer=JSON.parse(fs.readFileSync(path.join(root,'app/build/test-runtimes/current.json'),'utf8'));
const runtime=process.env.DSHA_TEST_MANAGED_RUNTIME||pointer.managed;
const recipe=JSON.parse(fs.readFileSync(path.join(assets,'literal-loopback-online-patch.json'),'utf8'));
const proof=JSON.parse(fs.readFileSync(path.join(runtime,'dsha-test-runtime.json'),'utf8'));
assert.equal(proof.dshVersion,recipe.dshVersion);assert.equal(proof.kind,'managed');
const moduleSource=fs.readFileSync(path.join(runtime,'node_modules',recipe.module),'utf8');
let official=moduleSource;
for(const patch of [...recipe.patches].reverse())if(official.includes(patch.after))official=official.replace(patch.after,patch.before);
assert.equal(createHash('sha256').update(official).digest('hex'),recipe.sourceSha256,'bind official module input without changing shared runtime');
const require=createRequire(import.meta.url);
let playwright;try{playwright=require(process.env.DSHA_PLAYWRIGHT||'playwright');}catch{playwright=require(path.join(process.env.USERPROFILE||'','.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'));}
const parent=path.resolve(process.env.DSHA_LOOPBACK_EVIDENCE||path.join(root,'app/build/literal-loopback-online-browser'));
fs.mkdirSync(parent,{recursive:true});
const selected=process.argv.includes('--case')?process.argv[process.argv.indexOf('--case')+1]:'all';
const cases=[
 {name:'baseline-offline',offline:true,baseline:true,accepted:false},
 {name:'patched-online',accepted:true},
 {name:'patched-offline',offline:true,accepted:true},
 {name:'remote-offline',offline:true,remote:true,accepted:false},
 {name:'loader-pending',offline:true,loaderPending:true,accepted:false},
 {name:'wrong-nonce',offline:true,wrongNonce:true,accepted:false},
].filter(row=>selected==='all'||selected===row.name);
assert(cases.length,'unknown case');
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const sha=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const summary={runtime,archiveSha256:proof.archiveSha256,pageSha256:sha(path.join(assets,'runtime-trial-page.js')),pluginSha256:sha(path.join(assets,'runtime-trial-plugin.js')),recipeSha256:sha(path.join(assets,'literal-loopback-online-patch.json')),cases:[]};

for(const spec of cases){
 const owned=fs.mkdtempSync(path.join(parent,spec.name+'-')),nonce=randomBytes(16).toString('hex');
 const user=path.join(owned,'isolated-user-home'),home=path.join(owned,'home'),profileName='dsha-recovery-'+nonce.slice(0,16),profile=path.join(home,'profiles',profileName),plugin=path.join(owned,'plugin');
 const put=(file,value)=>{fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,typeof value==='string'?value:JSON.stringify(value));};
 fs.mkdirSync(user,{recursive:true});
 put(path.join(plugin,'package.json'),{name:'dsha-runtime-check',version:'1.0.0',type:'module',main:'index.js',dsh:{bundle:{patch:'./cordis.patch.yml'}}});
 fs.copyFileSync(path.join(assets,'runtime-trial-plugin.js'),path.join(plugin,'index.js'));
 let trialPage=fs.readFileSync(path.join(assets,'runtime-trial-page.js'),'utf8');
 if(spec.wrongNonce){const anchor='body:JSON.stringify({nonce:nonce,ready:ready';assert.equal(trialPage.split(anchor).length,2);trialPage=trialPage.replace(anchor,'body:JSON.stringify({nonce:"'+ 'f'.repeat(32)+'",ready:ready');}
 put(path.join(plugin,'page.js'),trialPage);
 put(path.join(plugin,'cordis.patch.yml'),'- insert:\n    - id: dsha-runtime-check\n      name: dsha-runtime-check\n');
 put(path.join(profile,'package.json'),{name:profileName,private:true,dependencies:{'dsha-runtime-check':'link:'+plugin.replaceAll('\\','/')},dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app','dsha-runtime-check'],patchReload:'startup'}}});
 put(path.join(profile,'cordis.patch.yml'),'[]\n');put(path.join(profile,'pnpm-workspace.yaml'),'packages:\n  - .\nnodeLinker: hoisted\nautoInstallPeers: false\n');put(path.join(home,'settings.yaml'),'{}\n');
 fs.mkdirSync(path.join(profile,'node_modules'));
 for(const [from,to]of [[plugin,path.join(profile,'node_modules/dsha-runtime-check')],[path.join(runtime,'node_modules/@deepseek-ai'),path.join(profile,'node_modules/@deepseek-ai')],[path.join(runtime,'node_modules'),path.join(plugin,'node_modules')]])fs.symlinkSync(from,to,process.platform==='win32'?'junction':'dir');
 const env={...process.env};for(const key of Object.keys(env))if(key.includes('API_KEY')||key.startsWith('DSH_')||key.startsWith('DEEPSEEK_')||['NODE_OPTIONS','NODE_COMPILE_CACHE','DSHA_PRELOAD_PREVIOUS'].includes(key))delete env[key];
 Object.assign(env,{DSH_HOME:home,HOME:user,...process.platform==='win32'?{USERPROFILE:user}:{},DSHA_RUNTIME_TRIAL_NONCE:nonce,BROWSER:'true',DSH_CONFIRM:'1',NARB_DISABLE_NATIVE_CACHE:'1'});
 const child=spawn(process.execPath,[path.join(runtime,'node_modules/@deepseek-ai/dsh/lib/bin.js'),'--profile',profileName,'--no-open','--host','127.0.0.1','--port','0','--trusted-host','dsha-remote.localhost'],{cwd:owned,env,stdio:['ignore','pipe','pipe'],windowsHide:true});
 fs.writeFileSync(path.join(owned,'owned-child-pid.json'),JSON.stringify({pid:child.pid,profile:profileName}));
 let log='',auth,browser,page,closing=false;
 for(const stream of [child.stdout,child.stderr])stream.on('data',data=>{log+=data;auth ||= log.match(/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=[^\s]+)/)?.[1];});
 const clean=value=>String(value).replaceAll(nonce,'<nonce>').replace(/([?&]token=)[^\s]+/g,'$1***').replace(/dsh-auth-[^\s=]+=v1\.[^\s;]+/g,'<test-cookie>');
 const report={name:spec.name,fixture:spec,events:[],errors:[],requests:[],responses:[],sourceResponses:0,loaderHeld:0};
 try{
  for(let i=0;i<600&&!auth&&child.exitCode===null;i++)await wait(100);assert(auth,'actual CLI URL missing');
  const exchange=await fetch(auth,{redirect:'manual'});assert.equal(exchange.status,303);
  const cookie=exchange.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');assert(cookie);
  const base=new URL('/',auth).href,endpoint=base+'dsha-runtime-trial/'+nonce;
  let status;for(let i=0;i<100;i++){const r=await fetch(endpoint,{headers:{Cookie:cookie}});if(r.ok){status=await r.json();break;}await wait(100);}
  assert(status,'real trial endpoint missing');
  for(const key of ['dataRead','dataWrite','storageReopened','storageFreshReopened','sessionReopened'])assert.equal(status[key],true,key);
  browser=await playwright.chromium.launch({headless:true,args:['--host-resolver-rules=MAP dsha-remote.localhost 127.0.0.1','--no-proxy-server']});
  const context=await browser.newContext({viewport:{width:1200,height:800}});
  const pageBase=spec.remote?base.replace('127.0.0.1','dsha-remote.localhost'):base;
  await context.addCookies(cookie.split('; ').map(pair=>({name:pair.slice(0,pair.indexOf('=')),value:pair.slice(pair.indexOf('=')+1),url:pageBase,httpOnly:true,sameSite:'Strict'})));
  await context.addInitScript(({offline,expectedHome,expectedNonce})=>{
   if(offline)Object.defineProperty(navigator,'onLine',{configurable:true,get:()=>false});
   const trace=window.__loopbackTrial={sockets:[],posts:[],telemetry:[]};
   window.DshaTrialDiagnostic={postMessage(text){const value=JSON.parse(text),row={nonceMatches:value.nonce===expectedNonce};for(const [key,item]of Object.entries(value))if(key!=='nonce'&&(typeof item==='boolean'||key==='postHttpStatus'&&Number.isInteger(item)))row[key]=item;trace.telemetry.push(row);}};
   const Original=window.WebSocket;
   window.WebSocket=function(url,protocols){const socket=protocols===undefined?new Original(url):new Original(url,protocols);const address=new URL(url,location.href),row={mux:address.pathname==='/api/remote.mux',open:false,eventsOpen:0,readyFrames:0};trace.sockets.push(row);socket.addEventListener('open',()=>row.open=true);socket.addEventListener('message',event=>{try{const frame=JSON.parse(event.data);if(frame.type==='item'&&frame.value?.type==='ready'){row.readyFrames++;row.homeMatched=frame.value.host?.home===expectedHome;}}catch{}});const send=socket.send;socket.send=function(data){try{const frame=JSON.parse(data);if(frame.type==='open'&&frame.endpoint==='$events')row.eventsOpen++;}catch{}return send.apply(this,arguments);};return socket;};
   window.WebSocket.prototype=Original.prototype;Object.setPrototypeOf(window.WebSocket,Original);
   const realFetch=window.fetch;window.fetch=function(url,options){if(String(url).includes('/dsha-runtime-trial/')){const value=JSON.parse(options?.body||'{}');trace.posts.push({ready:value.ready,transport:value.transport,failure:value.failure,nonceMatches:value.nonce===expectedNonce});}return realFetch.apply(this,arguments);};
  },{offline:!!spec.offline,expectedHome:user,expectedNonce:nonce});
  page=await context.newPage();
  // HTTP response-only exact module patch: no shared runtime writes. A final
  // generated fixture may already carry it; the baseline case then reverses it.
  await page.route('**/*',async route=>{
   try{
   // Keep navigation on its real socket: fulfilling the main document changes
   // Chromium's address-space provenance and can falsely block local WebSocket.
   if(route.request().resourceType()!=='script'){await route.continue();return;}
   // Playwright's APIRequest resolver is separate from Chromium's test-only
   // host-resolver rule. Keep this fake remote authority on local TCP too.
   const request=route.request();
   const response=await route.fetch(spec.remote?{url:request.url().replace('dsha-remote.localhost','127.0.0.1'),headers:{...request.headers(),host:new URL(request.url()).host}}:{});const headers=response.headers();let body;
   if(/javascript/.test(headers['content-type']||'')){
    body=await response.text();
    if(body.includes('function watchBrowserNetwork(')){
     report.sourceResponses++;
     for(const patch of (spec.baseline?[...recipe.patches].reverse():recipe.patches)){
      const from=spec.baseline?patch.after:patch.before,to=spec.baseline?patch.before:patch.after;
      if(body.includes(from)){assert.equal(body.split(from).length,2);body=body.replace(from,to);}else assert.equal(body.split(to).length,2,'exact response already in requested state');
     }
    }
    if(spec.loaderPending&&body.includes('else loader.await().then(start, () => {});')){assert.equal(body.split('else loader.await().then(start, () => {});').length,2);body=body.replace('else loader.await().then(start, () => {});','else new Promise(() => {}).then(start, () => {});');report.loaderHeld++;}
   }
   await route.fulfill({response,...body===undefined?{}:{body}});
   }catch(error){if(!closing)report.errors.push('FIXTURE_ROUTE_'+error.name);await route.abort().catch(()=>{});}
  });
  page.on('pageerror',error=>report.errors.push(clean(error)));
  page.on('console',message=>{if(['warning','error'].includes(message.type()))report.events.push({type:message.type(),text:clean(message.text())});});
  page.on('request',request=>{if(request.url().includes('/dsha-runtime-trial/'))report.requests.push({method:request.method()});});
  page.on('response',response=>{if(response.url().includes('/dsha-runtime-trial/'))report.responses.push({status:response.status()});});
  await page.goto(spec.remote?pageBase+new URL(auth).search:pageBase,{waitUntil:'domcontentloaded'});
  await page.waitForFunction(()=>document.getElementById('root')?.children.length&&!document.querySelector('[data-dsh-boot]'),{timeout:20000});
  const deadline=Date.now()+(spec.accepted?20000:4000);
  do{await wait(100);const response=await fetch(endpoint,{headers:{Cookie:cookie}});status=await response.json();if(status.renderer)break;}while(Date.now()<deadline);
  if(spec.accepted)await page.waitForFunction(()=>window.__loopbackTrial.telemetry.some(row=>row.postAck&&row.postHttpStatus===200),{timeout:5000});
  report.renderer=status.renderer;report.failure=status.failure;
  report.browser=await page.evaluate(()=>({online:navigator.onLine,trace:window.__loopbackTrial,rootReady:!!(document.getElementById('root')?.children.length&&!document.querySelector('[data-dsh-boot]'))}));
  assert.equal(report.sourceResponses,1,'real connection module was served exactly once');
  assert.equal(report.renderer,spec.accepted);assert.equal(report.failure,'');assert.equal(report.browser.online,!spec.offline,'production patch never changes navigator');
  assert(report.browser.trace.sockets.some(row=>row.open),'real physical socket opens');
  if(spec.accepted){assert.equal(report.browser.trace.posts.length,1);assert(report.browser.trace.posts[0].transport);assert(report.browser.trace.posts[0].nonceMatches);assert(report.responses.some(row=>row.status===200));}
  else if(spec.wrongNonce){assert(report.browser.trace.posts.length>0);assert(report.browser.trace.posts.every(row=>!row.nonceMatches));assert(report.responses.some(row=>row.status===400));}
  else{assert.equal(report.browser.trace.posts.length,0);assert.equal(report.browser.trace.sockets.reduce((sum,row)=>sum+row.eventsOpen,0),0);}
  if(spec.loaderPending)assert.equal(report.loaderHeld,1);
  assert.equal(report.errors.length,0,JSON.stringify(report.errors));
  report.result='PASS';
 }catch(error){report.result='FAIL';report.error=clean(error);}
 finally{
  closing=true;if(page)await page.unrouteAll({behavior:'ignoreErrors'});if(browser)await browser.close();child.kill();await Promise.race([new Promise(resolve=>child.once('exit',resolve)),wait(3000)]);
  fs.writeFileSync(path.join(owned,'server.log'),clean(log));fs.writeFileSync(path.join(owned,'report.json'),JSON.stringify(report,null,2)+'\n');
  summary.cases.push({...report,report:path.join(owned,'report.json')});
  console.log(JSON.stringify({name:spec.name,result:report.result,renderer:report.renderer,online:report.browser?.online,posts:report.browser?.trace.posts.length,error:report.error,report:path.join(owned,'report.json')}));
 }
 if(report.result!=='PASS')break;
}
fs.writeFileSync(path.join(parent,'summary.json'),JSON.stringify(summary,null,2)+'\n');
if(summary.cases.some(row=>row.result!=='PASS'))process.exitCode=1;

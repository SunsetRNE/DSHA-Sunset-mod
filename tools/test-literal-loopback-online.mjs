import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';

// This browser module is self-contained. Bind its exact official bytes and npm
// lock identity instead of walking an unrelated full host runtime.
const root=path.resolve(import.meta.dirname,'..');
const pointer=JSON.parse(fs.readFileSync(path.join(root,'app/build/test-runtimes/current.json'),'utf8'));
const runtime=process.env.DSHA_TEST_RUNTIME||pointer.raw;
const recipe=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/literal-loopback-online-patch.json'),'utf8'));
const file=path.join(runtime,'node_modules',recipe.module);
const original=fs.readFileSync(file,'utf8');
assert.equal(createHash('sha256').update(original).digest('hex'),recipe.sourceSha256);
const packageName=recipe.module.split('/lib/')[0];
const lock=JSON.parse(fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json'),'utf8')).packages['node_modules/'+packageName];
assert.equal(lock.version,recipe.dshVersion);assert.equal(lock.integrity,recipe.packageIntegrity);
let candidate=original;
for(const patch of recipe.patches){assert.equal(candidate.split(patch.before).length,2);candidate=candidate.replace(patch.before,patch.after);}
for(const patch of recipe.patches)assert.equal(candidate.split(patch.after).length,2);
assert.throws(()=>{let changed=original.replace('function watchBrowserNetwork(controller)','function changedWatch(controller)');for(const patch of recipe.patches){assert.equal(changed.split(patch.before).length,2);changed=changed.replace(patch.before,patch.after);}});

function connection(source,base,transport,online=false){
 let definition,handle;const listeners=new Map();
 const browser={navigator:{onLine:online},__ModuleLoader__:{load(value){definition=value;}},addEventListener(type,fn){listeners.set(type,fn);},removeEventListener(type,fn){if(listeners.get(type)===fn)listeners.delete(type);}};
 const context={window:browser,document:{baseURI:base},URL,AbortController,AbortSignal,setTimeout,clearTimeout,console,fetch};
 vm.runInNewContext(source,context);
 const exported=definition.factory(()=>{throw Error('unexpected external dependency');});
 exported.installConnection({provide(name,value){assert.equal(name,'connection');handle=value;}},{location:{hostname:'127.0.0.1'},...(transport===undefined?{}:{transport})});
 let starts=0,aborts=0,connected=0;
 handle.registerGenerationSource(signal=>{starts++;return new Promise(resolve=>signal.addEventListener('abort',()=>{aborts++;resolve();},{once:true}));});
 const loop=handle.start({onConnected(){connected++;}});
 return {browser,get starts(){return starts;},get aborts(){return aborts;},get connected(){return connected;},signal(type){listeners.get(type)?.();},async close(){loop.stop();await new Promise(resolve=>setImmediate(resolve));assert.equal(listeners.size,0);}};
}
const rows=[];
async function check(name,base,transport,expected,online=false){
 const test=connection(candidate,base,transport,online);
 try{await new Promise(resolve=>setImmediate(resolve));assert.equal(test.starts,expected?1:0,name);assert.equal(test.connected,0,name+': attempting a real source does not imply ready');assert.equal(test.browser.navigator.onLine,online,'navigator unchanged');rows.push({name,attempted:test.starts===1,online});}
 finally{await test.close();}
}
for(const base of ['http://127.0.0.1:3080/','https://127.255.2.3/path/','http://[::1]:3080/','HTTP://127.0.0.1:80/'])await check('numeric loopback '+base,base,undefined,true);
for(const base of ['http://localhost:3080/','http://192.168.1.2:3080/','https://remote.example/','http://127.0.0.1.remote.example/','ftp://127.0.0.1/','file:///127.0.0.1/','ws://127.0.0.1/','javascript:127.0.0.1','http://user:password@127.0.0.1/','http://127.0.0.1:99999/','http://127.1/','http://2130706433/','http://0177.0.0.1/','http://127.0.0.1./','http://[::ffff:127.0.0.1]/','/relative','not a URL',''])await check('noneligible '+base,base,undefined,false);
await check('actual remote target overrides local page','http://127.0.0.1/',{streamBaseUrl:'https://remote.example/'},false);
await check('actual numeric target overrides remote page','https://remote.example/',{streamBaseUrl:'http://127.0.0.1:3000/'},true);
for(const transport of [{createWebSocket(){}},{createWebSocket:null},{openStream(){}},{rpc:{open(){}}}])await check('custom transport remains conservative','http://127.0.0.1/',transport,false);
await check('unknown transport getter remains conservative','http://127.0.0.1/',{get createWebSocket(){throw Error('fixture unreadable transport');}},false);
await check('unknown target getter remains conservative','http://127.0.0.1/',{get streamBaseUrl(){throw Error('fixture unreadable target');}},false);
await check('online remote unchanged','https://remote.example/',undefined,true,true);
await check('online local unchanged','http://127.0.0.1/',undefined,true,true);
const baseline=connection(original,'http://127.0.0.1/',undefined,false);
try{await new Promise(resolve=>setImmediate(resolve));assert.equal(baseline.starts,0);rows.push({name:'original confirms offline loopback defect',attempted:false});}finally{await baseline.close();}
const live=connection(candidate,'http://127.0.0.1/',undefined,true);
try{await new Promise(resolve=>setImmediate(resolve));live.browser.navigator.onLine=false;live.signal('offline');await new Promise(resolve=>setImmediate(resolve));assert.equal(live.starts,1);assert.equal(live.aborts,0,'WAN offline does not abort the active literal-loopback source');rows.push({name:'online to offline local keeps actual source',attempted:true});}finally{await live.close();}
const transport={streamBaseUrl:'http://127.0.0.1/'};
const change=connection(candidate,'http://127.0.0.1/',transport,false);
try{await new Promise(resolve=>setImmediate(resolve));transport.streamBaseUrl='https://remote.example/';change.signal('offline');await new Promise(resolve=>setImmediate(resolve));assert.equal(change.aborts,1,'re-read actual target on each network update');rows.push({name:'changed remote target respects next offline signal',aborted:true});}finally{await change.close();}
console.log(JSON.stringify({status:'PASS',cases:rows.length,rows},null,2));

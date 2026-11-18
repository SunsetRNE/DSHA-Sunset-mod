import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';

const ROOT=path.resolve(import.meta.dirname,'..');
const patched=process.argv.includes('--managed');
const RUNTIME=path.resolve(process.env.DSHA_TEST_RUNTIME??path.join(ROOT,'app/build/rc5-candidate'));
const MODULES=path.join(RUNTIME,'node_modules/@deepseek-ai');
const OUT=path.join(ROOT,'app/build/rc5-upgrade/regressions');
fs.mkdirSync(OUT,{recursive:true});
const LIMIT=5_000_000_000;
const records=[];
const sha=x=>createHash('sha256').update(x).digest('hex');
function load(name, additions, globals={}) {
  const file=path.join(MODULES,name,'lib/client.js');
  let original=fs.readFileSync(file,'utf8');
  const recipes={ 'dsh-client-file-upload':'file-upload-binary-client-patch.json', 'dsh-client-ui-tool':'tool-history-display-patch.json', 'dsh-client-ui-chat':'tool-history-projection-identity-patch.json' };
  const recipe=JSON.parse(fs.readFileSync(path.join(ROOT,'app/src/main/assets',recipes[name])));
  assert.equal(sha(original),recipe.sourceSha256);
  if(patched)for(const p of recipe.patches){assert.equal(original.split(p.before).length-1,1);original=original.replace(p.before,p.after);}
  assert.equal(original.split('return module.exports').length,2);
  let exported;
  const jsx=(type,props,key)=>({type,props,key});
  const primitives=new Proxy({}, {get:(_,key)=>key==='__esModule'?true:({name:key})});
  const react={memo:x=>x,forwardRef:x=>x,useMemo:f=>f(),useCallback:f=>f,useState:x=>[typeof x==='function'?x():x,()=>{}],useRef:x=>({current:x}),createContext:x=>({Provider:()=>{},_default:x}),Fragment:'Fragment',createElement:jsx};
  const context=vm.createContext({console,Error,TypeError,Blob,ReadableStream,Uint8Array,URL,URLSearchParams,AbortController,DOMException,Response,TextEncoder,TextDecoder,Map,Set,WeakMap,WeakSet,JSON,Symbol,performance,structuredClone,btoa:x=>Buffer.from(x,'binary').toString('base64'),window:{__ModuleLoader__:{load({factory}){exported=factory(Object.assign(spec=>{
    if(spec==='@deepseek-ai/cordis')return{Service:class{constructor(ctx){this.ctx=ctx;}}};
    if(spec==='react')return react;
    if(spec==='react/jsx-runtime')return {jsx,jsxs:jsx,Fragment:'Fragment'};
    if(spec==='react-dom')return{createPortal:jsx,flushSync:f=>f()};
    if(spec==='@deepseek-ai/dsh-client-ui-primitives')return primitives;
    if(spec==='@deepseek-ai/dsh-client-store')return{createSnapshotStore:v=>({getSnapshot:()=>v,subscribe:()=>()=>{}})};
    if(spec==='@tanstack/react-virtual')return{useVirtualizer:()=>{}};
    throw Error('unexpected require '+spec);
  },{asset:name=>'synthetic-asset:'+name}));}}},...globals});
  vm.runInContext(original.replace('return module.exports',`Object.assign(exports,{${additions.join(',')}});return module.exports`),context,{filename:file,timeout:10000});
  return{exported,context,source:{package:JSON.parse(fs.readFileSync(path.join(MODULES,name,'package.json'),'utf8')).name,version:JSON.parse(fs.readFileSync(path.join(MODULES,name,'package.json'),'utf8')).version,file,sha256:sha(original)}};
}
const fileValue={receiptId:'synthetic-receipt',file:{attachmentId:'sha256:synthetic',name:'sample.bin',bytes:3}};
const posts=[],rpc=[];
const u=load('dsh-client-file-upload',['FileUploadRuntime','workerTransport'],{__DSH_FILE_UPLOAD__:{fetch:async(url,init)=>{
  let bytes=0;
  if(init.body instanceof ReadableStream){const reader=init.body.getReader();for(;;){const r=await reader.read();if(r.done)break;bytes+=r.value.byteLength;}}
  posts.push({url,bodyKind:init.body?.constructor?.name,declaredSize:init.body?.size,streamBytes:bytes});
  if(new URL(url,'http://synthetic.invalid').searchParams.has('mediaType'))return new Response(JSON.stringify({ok:true,value:{receiptId:'image-receipt',image:{attachmentId:'sha256:image',bytes:3,width:1,height:1,mediaType:'image/png',name:'image.png',originalDimensions:{width:2,height:2}}}}));
  return new Response(JSON.stringify({ok:true,value:fileValue}));
}}});
const upload=new u.exported.FileUploadRuntime({remote:{fileUploads:{async upload(sessionId,request,signal){rpc.push({sessionId,...request,aborted:signal?.aborted===true});return{ok:true,value:fileValue};}}}});
await upload.upload('synthetic-session',new Uint8Array([1,2,3]),'sample.bin');
assert.equal(rpc.length,patched?0:1);assert.equal(posts.length,patched?1:0);if(!patched)assert.equal(rpc[0].data,'AQID');
records.push({case:'Uint8Array still base64 RPC',outcome:'REQUIRES_ADAPT',rpcCalls:rpc.length,rawPosts:posts.length});
class SizeOnlyBlob extends Blob{get size(){return LIMIT+1;}}
const over=await upload.upload('synthetic-session',new SizeOnlyBlob(),'declared-large.bin');
assert.equal(over.ok,!patched);if(patched)assert.equal(over.error.code,'session/attachment-invalid');else assert.equal(posts.at(-1).declaredSize,LIMIT+1);
records.push({case:'Blob > decimal 5GB is not rejected by client',outcome:'REQUIRES_ADAPT',syntheticOnly:true,actualBodyAllocated:0});
let offered=0,cancelled=false;
const reusable=new Uint8Array(64*1024);const requested=LIMIT+10*reusable.length;
const stream=new ReadableStream({pull(c){if(offered>=requested){c.close();return;}const n=Math.min(reusable.length,requested-offered);offered+=n;c.enqueue(reusable.subarray(0,n));},cancel(){cancelled=true;}});
if(patched){await assert.rejects(upload.upload('synthetic-session',stream,'counted.bin'),/5 GB/);assert.equal(cancelled,true);}else{const streamed=await upload.upload('synthetic-session',stream,'counted.bin');assert.equal(streamed.ok,true);assert.equal(posts.at(-1).streamBytes,requested);assert.equal(cancelled,false);}
records.push({case:'Unknown-length stream accepts 5GB+1 without bound',outcome:'REQUIRES_ADAPT',syntheticOnly:true,cumulativeBytesObserved:requested,maxAllocatedChunk:reusable.length,sourceCancelled:cancelled,diskWrites:0});
const cancelledController=new AbortController();cancelledController.abort();
if(patched)await assert.rejects(upload.upload('synthetic-session',new Uint8Array([1]),'aborted.bin',cancelledController.signal),e=>e.name==='AbortError');else{await upload.upload('synthetic-session',new Uint8Array([1]),'aborted.bin',cancelledController.signal);assert.equal(rpc.at(-1).aborted,true);}
records.push({case:'Aborted Uint8Array still serialized and passed to RPC',outcome:'REQUIRES_ADAPT',rpcCalls:rpc.length});
const image=await upload.uploadImage('synthetic-session',new Blob([new Uint8Array([1,2,3])]),'image/png','image.png');
assert.deepEqual(JSON.parse(JSON.stringify(image.value.image.originalDimensions)),{width:2,height:2});
records.push({case:'Upstream image route and expanded metadata survive',outcome:'PRESERVE_API',keys:Object.keys(image.value.image)});
let workerCount=0,terminated=0,abortAdded=0,abortRemoved=0;
class ThrowingWorker{constructor(){workerCount++;}postMessage(){throw new DOMException('synthetic clone refusal','DataCloneError');}terminate(){terminated++;}}
const wu=load('dsh-client-file-upload',['FileUploadRuntime'],{Worker:ThrowingWorker,document:{baseURI:'https://synthetic.invalid/'}});
const fakeSignal={aborted:false,throwIfAborted(){},addEventListener(){abortAdded++;},removeEventListener(){abortRemoved++;}};
await assert.rejects(new wu.exported.FileUploadRuntime({}).upload('synthetic-session',new Blob(['x']),'x',fakeSignal),e=>e.name==='DataCloneError');
assert.equal(workerCount,1);assert.equal(terminated,patched?1:0);assert.equal(abortAdded,1);assert.equal(abortRemoved,patched?1:0);
records.push({case:'Worker postMessage throw leaks worker and abort listener',outcome:'REQUIRES_ADAPT',workerCount,terminated,abortAdded,abortRemoved});

const ui=load('dsh-client-ui-tool',['callName','ToolCall','GenericToolCard','readCardModel','toolRowModel']);
const chat=load('dsh-client-ui-chat',['toolDefinition']);
const emptyState={root:undefined,children:new Map(),parents:new Map()};
function callEvent(id,name){return{type:'tool/call',seq:1,time:0,data:{callId:id,name,arguments:'{"file_path":"/synthetic/a.txt","offset":1}',turn:1,step:1}};}
const invalidCases=[];
for(const [id,name] of [['','read'],['   ','read'],['ok',''],['ok','   '],[undefined,'read'],[17,'read']]){
  const event=callEvent(id,name);const match={event,location:{kind:'outside'},id:String(id),role:'start'};
  const matched=chat.exported.toolDefinition.match(event);const state=chat.exported.toolDefinition.start({state:emptyState},match);
  const call={phase:'start',block:state.root};const selected=ui.exported.callName(call);
  let dispatched=false,inspected=false,fallbackCalls=0;
  const display=ui.exported.ToolCall({renderSlot:()=>{dispatched=true;return null;},callId:state.root.callId,toolName:selected,call,
    renderFallback:()=>{fallbackCalls++;return null;},openFile:()=>{throw Error('invalid open');},inspectCall:()=>{inspected=true;},useDisclosure:()=>{}});
  if(patched){assert.equal(dispatched,false);assert.equal(inspected,false);assert.equal(fallbackCalls,0);assert.equal(display.type,'pre');}
  invalidCases.push({inputId:id===undefined?'(undefined)':id,inputName:name,matchedId:matched.id,projectedCallId:state.root.callId,selectedSlot:selected});
}
assert.equal(invalidCases[0].selectedSlot,patched?'DSHA_INVALID_TOOL_CALL (read only)':'read');assert.equal(invalidCases[1].selectedSlot,patched?'DSHA_INVALID_TOOL_CALL (read only)':'read');
assert.equal(invalidCases[4].projectedCallId,'undefined');assert.equal(invalidCases[5].projectedCallId,'17');
records.push({case:'Retained malformed identities reach named tool dispatch',outcome:'REQUIRES_ADAPT',cases:invalidCases});
const event=callEvent('','read');const match={event,location:{kind:'outside'},id:'',role:'start'};
const start=chat.exported.toolDefinition.start({state:emptyState},match);
const resultEvent={type:'tool/result',seq:2,time:1,data:{message:{source:{callId:''},content:[{type:'text',text:'<path>/synthetic/a.txt</path>\n<type>file</type>\n<content>\n1: synthetic\n</content>'}]},meta:{path:'/synthetic/a.txt',offset:1,lines:[{number:1,text:'synthetic'}],totalLines:1}}};
const result=chat.exported.toolDefinition.update({state:start},{event:resultEvent,location:{kind:'outside'},id:'',role:'update'}).root;
assert.equal(result.callId,'');assert.equal(ui.exported.callName({phase:'result',block:result}),patched?'DSHA_INVALID_TOOL_CALL (read only)':'read');
let namedDispatch;
ui.exported.ToolCall({renderSlot:(slot,owner,options)=>{namedDispatch={slot,entryKey:options.entryKey,callId:owner.callId};return null;},callId:result.callId,toolName:'read',call:{phase:'result',block:result},renderFallback:()=>null,openFile:()=>{},useDisclosure:()=>{},cwd:'/synthetic'});
if(patched)assert.equal(namedDispatch,undefined);else{assert.equal(namedDispatch.entryKey,'read');assert.equal(namedDispatch.callId,'');}
const fallback=ui.exported.GenericToolCard({toolName:'DSHA_INVALID_TOOL_CALL (read only)',block:result,cwd:'/synthetic',home:'/synthetic',openFile:()=>{},getFileTriggerProps:()=>({}),useDisclosure:()=>{},t:k=>k});
if(patched){assert.equal(fallback.type,'pre');assert.equal(fallback.props['data-dsha-invalid-tool-history'],true);assert.equal(fallback.props.read,undefined);}else assert(fallback.props.read!==null);
records.push({case:'Changing only toolName sentinel does not disable rc5 semantic read card',outcome:'MINIMAL_GUARD_NEEDS_ADAPT',namedDispatch,sentinelFallbackHasReadCard:fallback.props.read!=null,sentinelFallbackVariant:fallback.props.variant,claim:'No remote execution tested; demonstrates typed rendering survives identity sentinel.'});

let inspectOwner,inspected;
ui.exported.ToolCall({renderSlot:(_slot,_owner,options)=>options.fallback,callId:'',toolName:'DSHA_INVALID_TOOL_CALL (read only)',call:{phase:'result',block:result},renderFallback:owner=>{inspectOwner=owner;return null;},openFile:()=>{},inspectCall:id=>{inspected=id;},useDisclosure:()=>{},cwd:'/synthetic'});
if(patched){assert.equal(inspectOwner,undefined);assert.equal(inspected,undefined);}else{assert.equal(typeof inspectOwner.inspect,'function');inspectOwner.inspect();assert.equal(inspected,'');}
records.push({case:'Old sentinel also retains inspect action bound to empty call id',outcome:'MINIMAL_GUARD_NEEDS_ADAPT',inspectAvailable:!!inspectOwner,inspectArgument:inspected,syntheticOnly:true});

const validEvent=callEvent('valid-call','read');
const validState=chat.exported.toolDefinition.start({state:emptyState},{event:validEvent,location:{kind:'outside'},id:'valid-call',role:'start'});
const validCall={phase:'start',block:validState.root};let validOwner,validSlot;
ui.exported.ToolCall({callId:'valid-call',toolName:ui.exported.callName(validCall),call:validCall,cwd:'/synthetic',home:'/synthetic',
 renderSlot:(slot,owner,options)=>{validOwner=owner;validSlot=options.entryKey;return null;},renderFallback:()=>null,openFile(){},inspectCall(){},useDisclosure(){}});
assert.equal(validSlot,'read');assert.equal(validOwner.block,validState.root);assert.equal(typeof validOwner.inspect,'function');
records.push({case:'Valid identity retains original block, named slot and inspector',outcome:'PRESERVE_API'});
for(const row of records)if(patched&&row.outcome!=='PRESERVE_API')row.outcome='PASS_FIXED_BEHAVIOR';
const report={status:patched?'PASS_MANAGED_RC5_REGRESSION':'PASS_RAW_RC5_NEGATIVE_CONTROL',frozenLockSha256:sha(fs.readFileSync(path.join(ROOT,'tools/dsh-runtime/package-lock.json'))),scope:'Pure VM evaluation of exact rc5 browser bundles; only synthetic data, React/transport carriers; no profile/service/network/listen or real user files; reports confined to app/build',sources:[u.source,ui.source,chat.source],records};
fs.writeFileSync(path.join(OUT,patched?'managed-browser-regression.json':'raw-browser-control.json'),JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));

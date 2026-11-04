// Locked rc5 local/sandbox/workspace modules with real isolated directories.
// Import wrappers count native work and inject deterministic I/O failure/cancellation;
// production functions, the real filesystem, Cordis services and Chokidar remain in use.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
const root=path.resolve(import.meta.dirname,'..');
const raw=path.resolve(process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync(path.join(root,'app/build/test-runtimes/current.json'),'utf8')).raw);
const sha=value=>createHash('sha256').update(value).digest('hex');
const selectedJs=process.env.DSHA_DIRECTORY_PREFIX_JS_FIXTURE==='1';
const proof=JSON.parse(fs.readFileSync(path.join(raw,selectedJs?'directory-prefix-js-proof.json':'dsha-test-runtime.json'),'utf8'));
const lockBytes=fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json')),lock=JSON.parse(lockBytes);
assert.equal(proof.kind,selectedJs?'selected-pure-js':'raw');assert.equal(proof.dshVersion,'0.2.1-rc.5');assert.equal(proof.lockSha256,sha(lockBytes));
if(selectedJs)for(const [name,entry] of Object.entries(proof.contentProof.entries))if(entry.type==='FILE')assert.equal(sha(fs.readFileSync(path.join(raw,name))),entry.sha256,'selected JS source bytes: '+name);
const names=['local-directory-prefix-patch.json','workspace-directory-prefix-patch.json','local-directory-watch-patch.json'];
const recipes=names.map(name=>JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets',name),'utf8')));
const base=fs.mkdtempSync(path.join(process.env.DSHA_DIRECTORY_PREFIX_WORK||path.join(root,'app/build'),'directory-prefix-'));
fs.symlinkSync(path.join(raw,'node_modules'),path.join(base,'node_modules'),process.platform==='win32'?'junction':'dir');
const records=[];
const nativeIoError=process.binding('uv').UV_EIO;
assert.ok(Number.isInteger(nativeIoError)&&nativeIoError<0);
const originals=new Map();
for(const [index,recipe] of recipes.entries()) {
  const source=fs.readFileSync(path.join(raw,'node_modules',recipe.module),'utf8');
  assert.equal(sha(source),recipe.sourceSha256);assert.equal(proof.contentProof.entries['node_modules/'+recipe.module].sha256,recipe.sourceSha256);
  assert.equal(recipe.packageIntegrity,lock.packages['node_modules/'+recipe.module.split('/').slice(0,2).join('/')].integrity);
  originals.set(recipe.module,source);
  records.push({test:names[index],status:'PASS',sourceSha256:sha(source)});
}
const apply=(source,recipe)=>{for(const patch of recipe.patches){assert.equal(source.split(patch.before).length,2);source=source.replace(patch.before,()=>patch.after);}return source;};
const localModule=recipes[0].module,workspaceModule=recipes[1].module;
const localFixed=apply(apply(originals.get(localModule),recipes[0]),recipes[2]);
const feedRecipe=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets/workspace-change-feed-bounded-patch.json'),'utf8'));
const workspaceFixed=apply(apply(originals.get(workspaceModule),feedRecipe),recipes[1]);
const chainCheck=JSON.parse(execFileSync(process.env.DSHA_PYTHON||'python',['-B','-c',[
  'import sys,json,importlib.util,hashlib;from pathlib import Path',
  'sys.path.insert(0,"tools");s=importlib.util.spec_from_file_location("builder","tools/build-dsh-runtime.py")',
  'b=importlib.util.module_from_spec(s);s.loader.exec_module(b)',
  'chains,_=b.load_chains(Path("app/src/main/assets"),"0.2.1-rc.5")',
  'recipes=[json.loads(Path("app/src/main/assets",name).read_text(encoding="utf8")) for name in json.loads(sys.argv[2])]',
  'registration={}',
  'for recipe,name in zip(recipes,json.loads(sys.argv[2])):',
  ' chain=chains.setdefault(recipe["module"],[]);registered=any(item[0]==name for item in chain);registration[name]=registered',
  ' if not registered: chain.append((name,[(p["before"],p["after"]) for p in recipe["patches"]],[]))',
  'results={}',
  'for module in {r["module"] for r in recipes}:',
  ' source=b.patched_content(Path(module),Path(sys.argv[1],"node_modules",module).read_bytes())',
  ' fixed=b.apply_stable_patches(source,chains[module]);assert b.apply_stable_patches(fixed,chains[module])==fixed',
  ' results[module]=dict(source=fixed.decode(),sha256=hashlib.sha256(fixed).hexdigest(),chain=[name for name,_,_ in chains[module]])',
  'print(json.dumps(dict(registration=registration,modules=results)))'
].join('\n'),raw,JSON.stringify(names)],{cwd:root,encoding:'utf8',windowsHide:true,maxBuffer:8*1024*1024}));
const completeLocal=chainCheck.modules[localModule].source;
assert.equal(completeLocal.replace('/* DSHA_ATOMIC_PUBLISH_V1 */\nimport { publishExclusive as link } from "dsha-runtime-fs";\nimport { chmod, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from "node:fs/promises";',
  'import { chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from "node:fs/promises";'),localFixed);
assert.equal(chainCheck.modules[workspaceModule].sha256,sha(workspaceFixed));
records.push({test:'actual-builder-chain',status:'PASS',registration:chainCheck.registration,
  modules:Object.fromEntries(Object.entries(chainCheck.modules).map(([name,{source,...metadata}])=>[name,metadata]))});

globalThis.__directoryAudit={};
const reset=()=>Object.assign(globalThis.__directoryAudit,{stats:0,readdirs:0,opens:0,reads:0,closes:0,maxHeap:0,nativeWatches:0,nativeClosed:0,abortNativeStart:false,chokidar:[],handles:[],failRead:0,abortRead:0,controller:null,synthetic:0});
reset();
fs.writeFileSync(path.join(base,'promises.mjs'),`import * as real from 'node:fs/promises';
export const {chmod,link,lstat,mkdir,open,readFile,rename,rm}=real;
export const stat=(...args)=>{globalThis.__directoryAudit.stats++;return real.stat(...args)};
export const readdir=(...args)=>{globalThis.__directoryAudit.readdirs++;return real.readdir(...args)};
export async function opendir(...args){const audit=globalThis.__directoryAudit;audit.opens++;
 const dir=audit.synthetic?{n:0,async read(){return this.n<audit.synthetic?{name:'entry-'+String(audit.synthetic-1-this.n++).padStart(8,'0')}:null},async close(){}}:await real.opendir(...args);
 return {async read(){audit.reads++;if(audit.failRead===audit.reads)throw Error('injected directory read failure');
   const value=await dir.read();if(audit.abortRead===audit.reads)audit.controller.abort();return value;},async close(){audit.closes++;return dir.close()}};}
`);
fs.writeFileSync(path.join(base,'native.mjs'),`import * as real from 'node:fs';
export const {createReadStream,realpath}=real;
export function watch(...args){const audit=globalThis.__directoryAudit;audit.nativeWatches++;const value=real.watch(...args);audit.handles.push(value);value.once('close',()=>audit.nativeClosed++);if(audit.abortNativeStart)audit.controller.abort();return value;}
`);
fs.writeFileSync(path.join(base,'chokidar.mjs'),`import {watch as original} from 'chokidar';export function watch(...args){const value=original(...args);globalThis.__directoryAudit.chokidar.push(value);return value;}`);
const instrument=source=>source.replaceAll('from "node:fs/promises"','from "./promises.mjs"').replace('from "node:fs"','from "./native.mjs"').replace('from "chokidar"','from "./chokidar.mjs"').replace('from "dsha-runtime-fs"','from "./runtime-fs.mjs"');
fs.copyFileSync(path.join(root,'app/src/main/assets/runtime-fs/index.js'),path.join(base,'runtime-fs.mjs'));
fs.writeFileSync(path.join(base,'original.mjs'),instrument(originals.get(localModule)));
// Observe heap length without changing the production operation or capacity.
fs.writeFileSync(path.join(base,'fixed.mjs'),instrument(completeLocal).replace('const candidate = { name: entry.name, sequence: sequence++ };','globalThis.__directoryAudit.maxHeap = Math.max(globalThis.__directoryAudit.maxHeap, heap.length);\n\t\t\tconst candidate = { name: entry.name, sequence: sequence++ };')+'\nexport { dshaDirectoryPrefixNames };\n');
fs.writeFileSync(path.join(base,'workspace.mjs'),workspaceFixed+'\nexport { WorkspaceChangeFeed };\n');
const sandboxFile=path.join(raw,'node_modules/@deepseek-ai/dsh-fs-sandbox/lib/index.js'),sandbox=fs.readFileSync(sandboxFile,'utf8');
assert.equal(sha(sandbox),proof.contentProof.entries['node_modules/@deepseek-ai/dsh-fs-sandbox/lib/index.js'].sha256);
fs.writeFileSync(path.join(base,'sandbox.mjs'),sandbox.replace('from "@deepseek-ai/dsh-fs-local"','from "./fixed.mjs"'));
const require=createRequire(pathToFileURL(path.join(base,'package.json')));
const [{Context},{LocalFileSystem:Original},{LocalFileSystem:Fixed,dshaDirectoryPrefixNames},{SandboxedFileSystem},{WorkspaceFiles,WorkspaceChangeFeed}]=await Promise.all([
  import(pathToFileURL(require.resolve('@deepseek-ai/cordis'))),import(pathToFileURL(path.join(base,'original.mjs'))),
  import(pathToFileURL(path.join(base,'fixed.mjs'))),import(pathToFileURL(path.join(base,'sandbox.mjs'))),import(pathToFileURL(path.join(base,'workspace.mjs')))]);
const config={cwd:base,diffBasisMaxBytes:10*1024*1024};
const original=new Original(new Context(),config);
const ctx=new Context();const fixed=new Fixed(ctx,config);
const directory=path.join(base,'many');fs.mkdirSync(directory);
const fileNames=Array.from({length:4200},(_,i)=>'item-'+String(4199-i).padStart(5,'0')+'.txt');
fileNames.push('é.txt','e\u0301.txt','雪.txt','a10.txt','a2.txt','zero.txt');
for(const name of fileNames)fs.writeFileSync(path.join(directory,name),'x');
fs.mkdirSync(path.join(directory,'folder'));
const target=await fixed.resolve(directory),signal=new AbortController().signal;
reset();const full=await original.listDir(target,signal);const originalWork={stats:__directoryAudit.stats,readdirs:__directoryAudit.readdirs};
assert.equal(full.length,fileNames.length+1);
reset();const prefix=await ctx.fs.dshaListDirPrefix(target,37,signal);
assert.deepEqual(prefix,full.slice(0,37));assert.equal(__directoryAudit.readdirs,0);assert.equal(__directoryAudit.opens,1);assert.equal(__directoryAudit.closes,1);
assert.equal(__directoryAudit.stats,38,'only parent and selected children are statted');
const prefixWork={stats:__directoryAudit.stats,readdirs:__directoryAudit.readdirs,opens:__directoryAudit.opens,reads:__directoryAudit.reads,closes:__directoryAudit.closes,maxHeap:__directoryAudit.maxHeap};
assert.ok(prefixWork.maxHeap<=37);
records.push({test:'real-directory-prefix-vs-full',status:'PASS',entries:full.length,originalWork,prefixWork,stableLocaleTies:true});
assert.deepEqual(await fixed.dshaListDirPrefix(target,3,signal),full.slice(0,3),'prefix boundary inside a locale-equal name group matches the full listing');
reset();assert.deepEqual(await fixed.listDir(target,signal),full);assert.equal(__directoryAudit.readdirs,1);assert.equal(__directoryAudit.opens,0);
records.push({test:'two-argument-full-api-unchanged',status:'PASS',entries:full.length});
for(const value of [undefined,0,-1,1.5,NaN,Infinity,Number.MAX_SAFE_INTEGER+1]){
  reset();await assert.rejects(fixed.dshaListDirPrefix(target,value,signal),/positive safe integer/);assert.equal(__directoryAudit.opens,0);assert.equal(__directoryAudit.stats,0);
}
for(const kind of ['cancel','failure']){
  reset();const controller=new AbortController();__directoryAudit.controller=controller;__directoryAudit[kind==='cancel'?'abortRead':'failRead']=11;
  await assert.rejects(fixed.dshaListDirPrefix(target,37,controller.signal),error=>kind==='cancel'?error.code==='FS_ABORTED':error.code==='FS_IO_ERROR'&&error.message.includes('injected directory read failure'));
  assert.equal(__directoryAudit.closes,1);records.push({test:kind+'-closes-directory-handle',status:'PASS',reads:__directoryAudit.reads});
}
reset();const aborted=new AbortController();aborted.abort();await assert.rejects(fixed.dshaListDirPrefix(target,37,aborted.signal),error=>error.code==='FS_ABORTED');assert.equal(__directoryAudit.opens,0);
reset();__directoryAudit.synthetic=100000;
const namesOnly=await dshaDirectoryPrefixNames({targetKey:'synthetic'},signal,2001);
assert.equal(namesOnly.length,2001);assert.equal(namesOnly[0].name,'entry-00000000');assert.equal(namesOnly.at(-1).name,'entry-00002000');assert.equal(__directoryAudit.maxHeap,2001);assert.equal(__directoryAudit.closes,1);
records.push({test:'100000-names-prefix-bound',status:'PASS',scanned:100000,retained:__directoryAudit.maxHeap});
reset();
const sandboxCtx=new Context();sandboxCtx.provide('sandboxPolicy',{defaultMode:'read-only'});const sandboxFs=new SandboxedFileSystem(sandboxCtx,config);
assert.deepEqual(await sandboxCtx.fs.dshaListDirPrefix(target,37,signal),prefix);assert.equal(sandboxFs.sandboxMode,'read-only');assert.equal(__directoryAudit.opens,1);
class Filtered extends Fixed {async listDir(target,signal){return (await super.listDir(target,signal)).filter(entry=>entry.name.startsWith('item-'));}}
reset();const filtered=new Filtered(new Context(),config);
assert.deepEqual(await filtered.dshaListDirPrefix(target,37,signal),full.filter(entry=>entry.name.startsWith('item-')).slice(0,37));assert.equal(__directoryAudit.opens,0);assert.equal(__directoryAudit.readdirs,1);
records.push({test:'sandbox-inherits-prefix-custom-list-policy-preserved',status:'PASS'});
const api={ctx:{fs:sandboxCtx.fs},config:{maxEntries:2000}};
for(const name of ['list','inspect','confine'])api[name]=WorkspaceFiles.prototype[name];
reset();const listed=await api.list({workspaceRoot:directory},directory,signal);
assert.equal(listed.entries.length,2000);assert.equal(listed.truncated,true);assert.deepEqual(listed.entries,full.slice(0,2000).map(({name,type,size})=>({name,type,...size===undefined?{}:{size}})));
assert.equal(__directoryAudit.readdirs,0);assert.equal(__directoryAudit.maxHeap,2001);
await assert.rejects(api.list({workspaceRoot:directory},base,signal),error=>error.code==='workspace-file/outside-workspace');
// A foreign provider without the optional capability receives its unchanged full API.
const foreign=Object.create(sandboxFs);foreign.dshaListDirPrefix=undefined;let legacyCalls=0;
foreign.listDir=async(...args)=>{legacyCalls++;assert.equal(args.length,2);return full;};api.ctx.fs=foreign;
assert.deepEqual(await api.list({workspaceRoot:directory},directory,signal),listed);assert.equal(legacyCalls,1);
records.push({test:'actual-workspace-api-prefix-truncation-confinement-and-fallback',status:'PASS'});

const watchDir=path.join(base,'watched');fs.mkdirSync(watchDir);for(let i=0;i<256;i++)fs.writeFileSync(path.join(watchDir,'file-'+i),'one');
const watchTarget=await fixed.resolve(watchDir);
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(check){const end=Date.now()+5000;while(!check()){if(Date.now()>end)throw Error('watch event timeout');await delay(10);}}
reset();const originalClose=await original.watch(watchTarget,()=>{},signal);const inventory=__directoryAudit.chokidar[0].getWatched();
const originalMembers=Object.values(inventory).reduce((sum,rows)=>sum+rows.length,0);assert.ok(originalMembers>=256);await originalClose();
records.push({test:'original-directory-watch-inventory',status:'PASS',expectedNegative:true,members:originalMembers});
reset();const events=[];const close=await fixed.watch(watchTarget,error=>events.push(error?.message||'change'),signal);
assert.equal(__directoryAudit.nativeWatches,1);assert.equal(__directoryAudit.chokidar.length,0);
for(const mutate of [()=>fs.writeFileSync(path.join(watchDir,'new.txt'),'new'),()=>fs.appendFileSync(path.join(watchDir,'new.txt'),'content'),
  ()=>fs.renameSync(path.join(watchDir,'new.txt'),path.join(watchDir,'renamed.txt')),()=>fs.unlinkSync(path.join(watchDir,'renamed.txt'))]){
  const before=events.length;mutate();await until(()=>events.length>before);
}
const beforeUnnamed=events.length;__directoryAudit.handles[0].emit('change','rename',null);assert.equal(events.length,beforeUnnamed+1,'unnamed native events still invalidate the directory');
await close();await close();const afterClose=events.length;fs.writeFileSync(path.join(watchDir,'after-close'),'x');await delay(50);assert.equal(events.length,afterClose);
records.push({test:'native-single-directory-watch-create-write-rename-delete-and-dispose',status:'PASS',events:afterClose,nativeWatches:1,chokidarInventories:0});
for(const mode of ['error','close']){
  reset();const errors=[];const stop=await fixed.watch(watchTarget,error=>{if(error)errors.push(error.message);},signal);
  if(mode==='error')__directoryAudit.handles[0].emit('error',Error('injected native watch failure'));else __directoryAudit.handles[0].close();
  await until(()=>errors.length>0);await stop();await stop();assert.equal(errors.length,1);
  await until(()=>__directoryAudit.nativeClosed===1);
  records.push({test:'native-watch-'+mode+'-reported-and-idempotently-closed',status:'PASS',errors});
}
reset();await assert.rejects(fixed.watch(watchTarget,()=>{},aborted.signal));assert.equal(__directoryAudit.nativeWatches,0);
reset();const initAbort=new AbortController();__directoryAudit.controller=initAbort;__directoryAudit.abortNativeStart=true;
await assert.rejects(fixed.watch(watchTarget,()=>{},initAbort.signal));assert.equal(__directoryAudit.nativeClosed,1);
records.push({test:'native-watch-initialization-abort-closes-handle',status:'PASS'});
// The actual feed still starts with ready, relists on native directory changes,
// forwards fatal native watch errors and waits for the async close on cancellation.
reset();const feed=new WorkspaceChangeFeed({fs:ctx.fs,on(){},effect(){},logger:{error(error){throw error;}}});
const feedLife=new AbortController(),stream=feed.follow(watchDir,watchDir,feedLife.signal);
assert.equal((await stream.next()).value.kind,'ready');
const invalidation=stream.next();fs.writeFileSync(path.join(watchDir,'feed.txt'),'feed');
const feedEvent=await Promise.race([invalidation,delay(5000).then(()=>{throw Error('directory feed timeout');})]);
assert.equal(feedEvent.value.kind,'change');assert.equal(feedEvent.value.change.absolutePath,watchDir);
const next=stream.next();feedLife.abort();assert.equal((await next).done,true);assert.equal(feed.followers.size,0);assert.equal(__directoryAudit.nativeClosed,1);
reset();const failedLife=new AbortController(),failedFeed=feed.follow(watchDir,watchDir,failedLife.signal);await failedFeed.next();
const failedRead=failedFeed.next();const fatalHandle=__directoryAudit.handles[0];
assert.equal(typeof fatalHandle._handle?.onchange,'function');fatalHandle._handle.onchange(nativeIoError,'rename','feed.txt');
await assert.rejects(Promise.race([failedRead,delay(1000).then(()=>{throw Error('fatal native error left feed teardown pending');})]),error=>error.code==='EIO');
assert.equal(fatalHandle._handle,null);assert.equal(feed.followers.size,0);assert.equal(__directoryAudit.nativeClosed,0,'actual fatal Node error does not emit close');
records.push({test:'actual-change-feed-directory-native-ready-change-error-cancel',status:'PASS'});
// Canonical file observation uses its native parent, including atomic replacement
// and deletion/recreation, without scanning or retaining the parent inventory.
reset();const fileTarget=await fixed.resolve(path.join(watchDir,'file-0')),fileEvents=[];
const closeFile=await fixed.watch(fileTarget,error=>fileEvents.push(error?.message||'change'),signal);
assert.equal(__directoryAudit.chokidar.length,0);assert.equal(__directoryAudit.nativeWatches,1);assert.equal(__directoryAudit.readdirs,0);assert.equal(__directoryAudit.opens,0);
fs.appendFileSync(fileTarget.targetKey,'updated');await until(()=>fileEvents.length>0);
const beforeAtomic=fileEvents.length;fs.writeFileSync(path.join(watchDir,'atomic.tmp'),'atomic replacement');fs.renameSync(path.join(watchDir,'atomic.tmp'),fileTarget.targetKey);await until(()=>fileEvents.length>beforeAtomic);
const beforeDelete=fileEvents.length;fs.unlinkSync(fileTarget.targetKey);await until(()=>fileEvents.length>beforeDelete);
const beforeRecreate=fileEvents.length;fs.writeFileSync(fileTarget.targetKey,'replacement');await until(()=>fileEvents.length>beforeRecreate);
const fileHandle=__directoryAudit.handles[0];let eventCount=fileEvents.length;
fileHandle.emit('change','change','clearly-unrelated.txt');assert.equal(fileEvents.length,eventCount);
for(const name of [null,Buffer.from('file-0'),Buffer.from([255]),...process.platform==='win32'?['FILE-0']:[]]){eventCount=fileEvents.length;fileHandle.emit('change','change',name);assert.equal(fileEvents.length,eventCount+1);}
await closeFile();await closeFile();assert.equal(__directoryAudit.nativeClosed,1);
records.push({test:'canonical-file-watch-atomic-replace-recreate-and-filename-variants',status:'PASS',chokidar:false,readdirs:0});
reset();const missing=await fixed.resolve(path.join(watchDir,'initially-absent.txt')),missingEvents=[];
const stopMissing=await fixed.watch(missing,error=>missingEvents.push(error?.message||'change'),signal);
assert.equal(__directoryAudit.chokidar.length,0);assert.equal(__directoryAudit.nativeWatches,1);assert.equal(__directoryAudit.readdirs,0);assert.equal(__directoryAudit.opens,0);
fs.writeFileSync(missing.targetKey,'created later');await until(()=>missingEvents.length>0);await stopMissing();assert.equal(__directoryAudit.nativeClosed,1);
records.push({test:'initially-absent-file-created-under-existing-parent',status:'PASS',chokidar:false,readdirs:0});
reset();const fileErrors=[];const stopError=await fixed.watch(fileTarget,error=>{if(error)fileErrors.push(error.message);},signal);
__directoryAudit.handles[0].emit('error',Error('file watch error'));await until(()=>fileErrors.length===1);await stopError();await until(()=>__directoryAudit.nativeClosed===1);
reset();const fileAbort=new AbortController();__directoryAudit.controller=fileAbort;__directoryAudit.abortNativeStart=true;
await assert.rejects(fixed.watch(fileTarget,()=>{},fileAbort.signal));assert.equal(__directoryAudit.nativeClosed,1);
records.push({test:'file-watch-error-and-initialization-cancel-close',status:'PASS'});
for(const [kind,watched] of [['directory',watchTarget],['file',fileTarget]]){
  reset();const errors=[];const dispose=await fixed.watch(watched,error=>{if(error)errors.push(error);},signal);
  const watcher=__directoryAudit.handles[0];assert.equal(typeof watcher._handle?.onchange,'function');
  watcher._handle.onchange(nativeIoError,'rename',path.basename(watched.targetKey));
  assert.equal(watcher._handle,null);assert.equal(errors.length,1);assert.equal(errors[0].code,'EIO');
  await Promise.race([dispose(),delay(1000).then(()=>{throw Error('fatal native error disposer did not settle');})]);
  await dispose();assert.equal(errors.length,1);assert.equal(__directoryAudit.nativeClosed,0);
  records.push({test:kind+'-real-native-fatal-error-without-close-event',status:'PASS',nativeHandleNull:true,closeEvents:0,errorCode:errors[0].code});
}
// A directory alias resolves before observation; no lexical shortcut bypasses containment.
const alias=path.join(base,'alias');let linkAvailable=true;
try{fs.symlinkSync(watchDir,alias,process.platform==='win32'?'junction':'dir');}catch(error){if(['EPERM','EACCES','ENOSYS'].includes(error.code))linkAvailable=false;else throw error;}
if(linkAvailable){assert.equal((await fixed.resolve(alias)).targetKey,watchTarget.targetKey);records.push({test:'directory-alias-canonical-identity',status:'PASS'});}
else records.push({test:'directory-alias-canonical-identity',status:'SKIP',reason:'Host cannot create a directory alias'});
const fileAlias=path.join(base,'file-alias');let fileLink=true;
try{fs.symlinkSync(fileTarget.targetKey,fileAlias,'file');}catch(error){if(['EPERM','EACCES','ENOSYS'].includes(error.code))fileLink=false;else throw error;}
if(fileLink){
  reset();const resolved=await fixed.resolve(fileAlias);assert.equal(resolved.targetKey,fileTarget.targetKey);const observed=[];
  const closeAlias=await fixed.watch(resolved,error=>observed.push(error?.message||'change'),signal);
  assert.equal(__directoryAudit.chokidar.length,0);assert.equal(__directoryAudit.nativeWatches,1);assert.equal(__directoryAudit.readdirs,0);
  fs.appendFileSync(fileTarget.targetKey,'alias update');await until(()=>observed.length>0);await closeAlias();
  records.push({test:'opened-symlink-file-observes-canonical-target',status:'PASS'});
}else records.push({test:'opened-symlink-file-observes-canonical-target',status:'SKIP',reason:'Host cannot create a file symlink'});
const result={schema:1,status:'PASS',platform:process.platform,fixture:base,records,
  node:process.version,selectedJsFixture:selectedJs,...selectedJs?{sourceMarkerSha256:proof.sourceMarkerSha256,selectedPackages:proof.packages}: {},
  scope:'Actual rc5 local/sandbox/workspace functions and real isolated filesystem/watchers; synthetic 100000-name bound and injected read/watch failures. Directory watch covers direct-entry invalidations; unopened child symlink targets are not promised live refresh, while opened canonical files use precise parent/name observation without a parent inventory. No Android device or arbitrary-provider memory-bound claim.'};
if(process.env.DSHA_DIRECTORY_PREFIX_OUTPUT)fs.writeFileSync(process.env.DSHA_DIRECTORY_PREFIX_OUTPUT,JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result,null,2));

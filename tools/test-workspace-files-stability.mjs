// Exact locked rc5 modules, isolated synthetic files, and the shipped browser renderer.
// The watcher transport is injected; this is not Android/production-account evidence.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync,spawnSync} from 'node:child_process';
import {pathToFileURL} from 'node:url';
import {browserFixture} from './rc1-browser-fixture.mjs';

const root=path.resolve(import.meta.dirname,'..');
const runtime=path.resolve(process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync(path.join(root,'app/build/test-runtimes/current.json'),'utf8')).raw);
const sha=value=>createHash('sha256').update(value).digest('hex');
const markerBytes=fs.readFileSync(path.join(runtime,'dsha-test-runtime.json')),proof=JSON.parse(markerBytes);
const lockBytes=fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json')),lock=JSON.parse(lockBytes);
assert.equal(proof.kind,'raw');assert.equal(proof.dshVersion,'0.2.1-rc.5');assert.equal(proof.lockSha256,sha(lockBytes));
const names=['workspace-change-feed-bounded-patch.json','files-list-rejection-patch.json','text-page-rejection-patch.json'];
const recipes=names.map(name=>JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets',name),'utf8')));
const base=fs.mkdtempSync(path.join(root,'app/build/workspace-files-stability-'));
fs.symlinkSync(path.join(runtime,'node_modules'),path.join(base,'node_modules'),process.platform==='win32'?'junction':'dir');
const records=[],sources=[];
for(const [index,recipe] of recipes.entries()) {
  const file=path.join(runtime,'node_modules',recipe.module),original=fs.readFileSync(file,'utf8');
  assert.equal(sha(original),recipe.sourceSha256);assert.equal(proof.contentProof.entries['node_modules/'+recipe.module].sha256,recipe.sourceSha256);
  assert.equal(recipe.packageIntegrity,lock.packages['node_modules/'+recipe.module.split('/').slice(0,2).join('/')].integrity);
  let fixed=original;
  for(const patch of recipe.patches){assert.equal(fixed.split(patch.before).length,2);fixed=fixed.replace(patch.before,()=>patch.after);}
  const check=spawnSync(process.execPath,['--check','--input-type=module'],{input:fixed,encoding:'utf8',windowsHide:true});assert.equal(check.status,0,check.stderr);
  const stable=execFileSync(process.env.DSHA_PYTHON||'python',['-B','-c',
    'import sys,json;from pathlib import Path;sys.path.insert(0,"tools");from runtime_stable_patches import apply;'+
    'p=json.loads(Path(sys.argv[1]).read_text(encoding="utf8"));b=Path(sys.argv[2]).read_bytes();'+
    'c=[(sys.argv[1],[(x["before"],x["after"]) for x in p["patches"]],[])];s=apply(b,c);assert apply(s,c)==s;sys.stdout.buffer.write(s)',
    path.join(root,'app/src/main/assets',names[index]),file],{cwd:root,encoding:'utf8',maxBuffer:8*1024*1024,windowsHide:true});
  assert.equal(stable,fixed);sources.push({original,fixed});
  const completeChain=JSON.parse(execFileSync(process.env.DSHA_PYTHON||'python',['-B','-c',[
    'import sys,json,importlib.util,hashlib;from pathlib import Path',
    'sys.path.insert(0,"tools")',
    'spec=importlib.util.spec_from_file_location("builder","tools/build-dsh-runtime.py")',
    'builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)',
    'recipe=json.loads(Path(sys.argv[1]).read_text(encoding="utf8"))',
    'chains,inputs=builder.load_chains(Path("app/src/main/assets"),recipe["dshVersion"])',
    'chain=chains.get(recipe["module"],[])',
    'registered=any(name==Path(sys.argv[1]).name for name,_,_ in chain)',
    'if not registered: chain.append((Path(sys.argv[1]).name,[(p["before"],p["after"]) for p in recipe["patches"]],[]))',
    'value=builder.patched_content(Path(recipe["module"]),Path(sys.argv[2]).read_bytes())',
    'value=builder.apply_stable_patches(value,chain)',
    'assert builder.apply_stable_patches(value,chain)==value',
    'print(json.dumps(dict(source=value.decode(),registered=registered,chain=[name for name,_,_ in chain])))'
  ].join('\n'),path.join(root,'app/src/main/assets',names[index]),file],{cwd:root,encoding:'utf8',maxBuffer:8*1024*1024,windowsHide:true}));
  for(const patch of recipe.patches)assert.ok(completeChain.source.includes(patch.after),'complete chain must retain this exact patch');
  sources[index].fixed=completeChain.source;
  records.push({test:names[index],status:'PASS',sourceSha256:sha(original),patchedSha256:sha(fixed),stableEngineIdempotent:true,
    builderRegistered:completeChain.registered,builderChain:completeChain.chain,builderSha256:sha(completeChain.source)});
}
const within=async operation=>Promise.race([operation,new Promise((_,reject)=>{const timer=setTimeout(()=>reject(Error('fixture operation did not settle')),3000);timer.unref();})]);
for(const mode of ['original','fixed']) {
  const moduleFile=path.join(base,mode+'.mjs');
  fs.writeFileSync(moduleFile,sources[0][mode]+'\nexport { WorkspaceChangeFeed, ChangeFollower };\n');
  const {WorkspaceChangeFeed}=await import(pathToFileURL(moduleFile));
  const directory=path.join(base,mode+'-workspace');fs.mkdirSync(directory);
  const targetPath=path.join(directory,'observed.txt');fs.writeFileSync(targetPath,'first');
  let observe,watch,unwatched=0,stats=0;
  const targetOf=value=>({targetKey:path.resolve(value),displayPath:path.resolve(value)});
  const target=targetOf(targetPath),other=targetOf(path.join(directory,'unrelated.txt'));
  const backend={
    async resolve(value,options){options.signal.throwIfAborted();return targetOf(path.resolve(options.cwd||directory,value));},
    async stat(value,signal){signal.throwIfAborted();stats++;try{const info=fs.statSync(value.targetKey,{bigint:true});return {type:info.isDirectory()?'directory':'file',version:String(info.mtimeNs)+':'+info.size};}catch(error){if(error.code==='ENOENT')return;throw error;}},
    contains(root,target){const rel=path.relative(root.targetKey,target.targetKey);return !rel.startsWith('..')&&!path.isAbsolute(rel);},
    async watch(_target,callback,signal){signal.throwIfAborted();watch=callback;return()=>{unwatched++;};},
    processPath:target=>target.targetKey,
  };
  const feed=new WorkspaceChangeFeed({fs:backend,on(event,callback){assert.equal(event,'fs/observed');observe=callback;},effect(){},logger:{error(error){throw error;}}});
  const controller=new AbortController(),iterator=feed.follow(directory,targetPath,controller.signal);
  assert.deepEqual((await within(iterator.next())).value,{kind:'ready'});
  const follower=[...feed.followers][0];
  for(let i=0;i<50000;i++)observe(other,{version:'other-'+i});
  if(mode==='original')assert.equal(follower.queue.size,50000);
  else assert.equal(follower.pending,undefined,'unrelated changes are never retained');
  for(let i=0;i<50000;i++)observe(target,{version:'target-'+i});
  if(mode==='original')assert.equal(follower.queue.size,100000);
  else {assert.equal(follower.queue,undefined);assert.equal(follower.pending[1].version,'target-49999');}
  fs.writeFileSync(targetPath,'current content after the entire invalidation burst');
  const expected=await backend.stat(target,new AbortController().signal),readsBefore=stats;
  const change=await within(iterator.next());assert.equal(change.value.change.version,expected.version);
  assert.equal(stats-readsBefore,1,'each pulled invalidation reads current metadata once');
  if(mode==='fixed') {
    assert.equal(follower.pending,undefined);
    watch();assert.notEqual(follower.pending,undefined,'native watch invalidates its own target');
    fs.unlinkSync(targetPath);
    assert.deepEqual((await within(iterator.next())).value.change,{absolutePath:targetPath,absent:true});
    const waiting=iterator.next();controller.abort();assert.equal((await within(waiting)).done,true);
    assert.equal(follower.pending,undefined);
  } else {controller.abort();assert.equal((await within(iterator.next())).done,true);}
  assert.equal(unwatched,1);assert.equal(feed.followers.size,0);
  records.push({test:mode+'-observation-burst',status:'PASS',expectedNegative:mode==='original',unrelated:50000,relevant:50000,
    retainedAtPeak:mode==='original'?100000:1,cancelReleasedWatcher:true});
  // A watch failure still rejects the consumer instead of being swallowed by coalescing.
  fs.writeFileSync(targetPath,'again');const errors=new AbortController(),failed=feed.follow(directory,targetPath,errors.signal);
  await failed.next();const failure=Error('synthetic watcher failure');const pending=failed.next();watch(failure);
  await assert.rejects(within(pending),error=>error===failure);assert.equal(feed.followers.size,0);assert.equal(unwatched,2);
  records.push({test:mode+'-watcher-error',status:'PASS'});
}

const fixture=await browserFixture(runtime);
try {
  for(const mode of ['original','fixed']) {
    const priorPageErrors=fixture.errors.length;
    await fixture.load('dsh-client-ui-sidebar-files',mode==='fixed'?[recipes[1]]:[],'module.exports.fileAudit = { filesFace, DirectoryNode };');
    await fixture.load('dsh-client-ui-sidebar-documentpreview',mode==='fixed'?[recipes[2]]:[],'module.exports.fileAudit = { textFace };');
    const result=await fixture.page.evaluate(async mode=>{
      const pause=()=>new Promise(resolve=>setTimeout(resolve,30));
      const unhandled=[];const handler=event=>{unhandled.push(String(event.reason));event.preventDefault();};
      addEventListener('unhandledrejection',handler);
      const {filesFace}=auditExports['@deepseek-ai/dsh-client-ui-sidebar-files'].fileAudit;
      const {textFace}=auditExports['@deepseek-ai/dsh-client-ui-sidebar-documentpreview'].fileAudit;
      const pending=[],events=[];
      const action=new Proxy({},{get:(_,name)=>(...args)=>events.push({name,args:args.map(value=>value instanceof Error?{name:value.name,code:value.code,message:value.message}:value)})});
      const read=()=>new Promise((resolve,reject)=>pending.push({resolve,reject}));
      const watch=async function*(_session,_path,signal){yield 'ready';await new Promise(resolve=>signal.addEventListener('abort',resolve,{once:true}));};
      const files=filesFace(read,watch)('session',action),fileLife=new AbortController();
      files.start('files','/work',fileLife.signal);await pause();pending.shift().reject(Error('directory carrier failed'));await pause();
      const listFailed=events.some(event=>event.name==='failed'&&event.args[2]?.message==='directory carrier failed');
      const preview=textFace(read,read,{})('session',action),previewLife=new AbortController();
      const file={sessionId:'session',path:'/work/file.txt'};
      preview.loadPage('preview',file,1,previewLife.signal);pending.shift().reject(Error('page carrier failed'));await pause();
      const pageFailed=events.some(event=>event.name==='failed'&&event.args[1]?.message==='page carrier failed');
      // Retry is explicit and a successful current request remains visible.
      files.refresh('files');await pause();pending.shift().resolve({ok:true,value:{entries:[],truncated:false}});await pause();
      preview.reloadPages('preview',file,previewLife.signal);pending.shift().resolve({ok:true,value:{version:'v1',text:'current',lines:1,eof:true}});await pause();
      const successfulRetry=events.some(event=>event.name==='loaded')&&events.some(event=>event.name==='page');
      if(mode==='fixed') {
        // Two same-path loads: old rejection cannot overwrite a newer successful result.
        const before=events.filter(event=>event.name==='failed').length;
        files.load('files','/work',fileLife.signal);const old=pending.shift();
        files.load('files','/work',fileLife.signal);pending.shift().resolve({ok:true,value:{entries:[{name:'new',type:'file'}],truncated:false}});
        old.reject(Error('obsolete directory'));await pause();
        preview.loadPage('preview',file,2,previewLife.signal);const prior=pending.shift();
        preview.reloadPages('preview',file,previewLife.signal);pending.shift().resolve({ok:true,value:{version:'v2',text:'latest',lines:1,eof:true}});
        prior.reject(Error('obsolete page'));await pause();
        if(events.filter(event=>event.name==='failed').length!==before)throw Error('obsolete read replaced current state');
        files.load('files','/work',fileLife.signal);const abandonedList=pending.shift();
        preview.loadPage('preview',file,2,previewLife.signal);const abandonedPage=pending.shift();
        fileLife.abort();previewLife.abort();abandonedList.reject(Error('closed directory'));abandonedPage.reject(Error('closed page'));await pause();
        if(events.filter(event=>event.name==='failed').length!==before)throw Error('closed tab received failure');
      } else {fileLife.abort();previewLife.abort();await pause();}
      removeEventListener('unhandledrejection',handler);
      return {mode,listFailed,pageFailed,successfulRetry,unhandled,pending:pending.length};
    },mode);
    assert.equal(result.listFailed,mode==='fixed');assert.equal(result.pageFailed,mode==='fixed');assert.equal(result.successfulRetry,true);assert.equal(result.pending,0);
    if(mode==='original'){assert.equal(result.unhandled.length,2);assert.match(result.unhandled.join('\n'),/directory carrier failed/);assert.match(result.unhandled.join('\n'),/page carrier failed/);}
    else assert.deepEqual(result.unhandled,[]);
    const pageErrors=fixture.errors.slice(priorPageErrors);
    if(mode==='fixed')assert.deepEqual(pageErrors,[]);
    else for(const error of pageErrors)assert.match(error,/^Error: (directory|page) carrier failed$/);
    records.push({test:mode+'-browser-read-rejections',status:'PASS',expectedNegative:mode==='original',...result,pageErrors});
  }
} finally {await fixture.close();}
assert.equal(sha(fs.readFileSync(path.join(runtime,'dsha-test-runtime.json'))),sha(markerBytes));
for(const recipe of recipes)assert.equal(sha(fs.readFileSync(path.join(runtime,'node_modules',recipe.module))),recipe.sourceSha256);
const result={schema:1,status:'PASS',browser:process.env.DSHA_BROWSER||'chromium',dshVersion:'0.2.1-rc.5',fixture:base,records,
  scope:'Exact locked rc5 source and actual browser frontend; synthetic read rejections/watch carrier with isolated real file stats; no Android, account or user files; module identity proof only, coordinator owns full runtime proof.'};
if(process.env.DSHA_WORKSPACE_FILES_OUTPUT)fs.writeFileSync(process.env.DSHA_WORKSPACE_FILES_OUTPUT,JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result,null,2));

#!/usr/bin/env node
// Finite managed-runtime compatibility evidence. Does not alter source or live profiles.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {mkdir,mkdtemp,readFile,writeFile,readdir,lstat,readlink,realpath,stat} from 'node:fs/promises';
import {dirname,join,resolve,sep,isAbsolute} from 'node:path';
import {homedir,tmpdir} from 'node:os';
import {fileURLToPath} from 'node:url';
import {beneath,createPathGuard,processPassed} from './fixtures/data-compat/guards.mjs';

const here=dirname(fileURLToPath(import.meta.url));
const helpers=join(here,'fixtures','data-compat');
const args={};
for(let i=2;i<process.argv.length;i+=2){
 assert(process.argv[i].startsWith('--')&&process.argv[i+1]!==undefined,'all flags require a value');
 assert(!Object.hasOwn(args,process.argv[i]),'duplicate argument');args[process.argv[i]]=process.argv[i+1];
}
const allowed=new Set(['--old','--new','--old-archive','--old-sha256','--new-archive','--new-sha256','--work-dir','--report','--python']);
for(const key of Object.keys(args))assert(allowed.has(key),'unknown argument '+key);
for(const key of ['--old','--new','--old-archive','--old-sha256','--new-archive','--new-sha256','--report'])assert(args[key],
 'usage: --old ROOT --new ROOT --old-archive FILE --old-sha256 SHA --new-archive FILE --new-sha256 SHA --report JSON [--work-dir SHORT_TEMP_PARENT] [--python PYTHON]');
for(const key of ['--old-sha256','--new-sha256'])assert(/^[a-f0-9]{64}$/.test(args[key]),'exact archive sha256 required');
const safePath=await createPathGuard([join(homedir(),'.dsh'),process.env.DSH_HOME,process.env.DSH_PROFILE_DIR,'/usr/local/lib/node_modules/@deepseek-ai/dsh'],process.env.PROROOT_ROOTFS||'');
const oldRoot=await safePath(args['--old']),newRoot=await safePath(args['--new']);
assert(oldRoot!==newRoot,'distinct runtimes required');
const oldArchive=await safePath(args['--old-archive']),newArchive=await safePath(args['--new-archive']);
const reportPath=await safePath(args['--report']);
assert(!beneath(reportPath,oldRoot)&&!beneath(reportPath,newRoot),'report may not enter runtime trees');
try{await lstat(reportPath);throw Error('report already exists; choose a new output');}catch(error){if(error.code!=='ENOENT')throw error;}
const workParent=await safePath(args['--work-dir']||tmpdir());
assert(!beneath(workParent,oldRoot)&&!beneath(workParent,newRoot),'fixtures may not enter runtime trees');
await mkdir(workParent,{recursive:true});
const run=await mkdtemp(join(workParent,'dsh-compat-'));
const hash=data=>createHash('sha256').update(data).digest('hex');
const report={schema:1,status:'RUNNING',edge:{old:'0.2.2-rc.1',new:'0.2.2-rc.5'},run,node:process.version,
 oldRuntime:oldRoot,newRuntime:newRoot,archives:{old:{path:oldArchive,sha256:args['--old-sha256']},new:{path:newArchive,sha256:args['--new-sha256']}},checks:[],
 scope:['real JSON storage','V4 none/zstd and real V3 codec','rich messages/tool records and Session.fromRestore derived messages','attachments and synthetic credentials','actual schedule descriptor/storage envelopes','real profile reader and normalization'],
 excluded:['real user data','full Settings/ConfigEditor bootstrap and online persistence','ScheduleService delivery','SQLite query rebuild','arbitrary historical runtime-label inheritance','rollback authorization']};
const scripts=[fileURLToPath(import.meta.url),join(here,'test-dsh-data-cross-runtime.mjs'),...['surfaces.mjs','profile-matrix.mjs','retired-team.mjs','network-guard.mjs','guards.mjs','verify-runtime-archive.py'].map(x=>join(helpers,x))];
report.scriptSha256=Object.fromEntries(await Promise.all(scripts.map(async p=>[p,hash(await readFile(p))])));
async function save(){await mkdir(dirname(reportPath),{recursive:true});await writeFile(reportPath,JSON.stringify(report,null,2)+'\n');}
async function tree(root,relative=''){
 const result={};
 for(const name of (await readdir(join(root,relative))).sort()){
  const rel=relative?relative+'/'+name:name;const p=join(root,rel),s=await lstat(p);
  if(s.isDirectory()){result[rel]={type:'directory',mode:s.mode&0o777};Object.assign(result,await tree(root,rel));}
  else if(s.isFile())result[rel]={type:'file',mode:s.mode&0o777,bytes:s.size,sha256:hash(await readFile(p))};
  else if(s.isSymbolicLink()){assert(beneath(await realpath(p),await realpath(root)),'fixture symlink escaped');result[rel]={type:'symlink',target:await readlink(p)};}
  else throw Error('unexpected fixture type: '+p);
 }
 return result;
}
function envFor(home){
 const env={...process.env};delete env.NODE_OPTIONS;delete env.NODE_PATH;
 return {...env,DSH_HOME:home,TMPDIR:join(run,'tmp'),XDG_CONFIG_HOME:join(run,'xdg-config'),XDG_CACHE_HOME:join(run,'xdg-cache'),DSHA_DATA_COMPAT_ROOT:run,NARB_DISABLE_NATIVE_CACHE:'1',DSH_TELEMETRY_DISABLED:'1'};
}
await mkdir(join(run,'tmp'));
async function command(name,executable,argv,env,timeout=180000){
 const outcome=spawnSync(executable,argv,{cwd:run,env,encoding:'utf8',timeout,maxBuffer:8*1024*1024,windowsHide:true});
 const log=join(run,name+'.log');await writeFile(log,(outcome.stdout||'')+(outcome.stderr||''));
 const row={name,status:processPassed(outcome)?'PASS':'FAIL',exitCode:outcome.status,signal:outcome.signal,error:outcome.error?String(outcome.error):undefined,log};
 report.checks.push(row);await save();return row;
}
const nodeArgs=script=>['--max-old-space-size=384','--import',join(helpers,'network-guard.mjs'),script];
try{
 for(const [label,root,archive,digest] of [['old',oldRoot,oldArchive,args['--old-sha256']],['new',newRoot,newArchive,args['--new-sha256']]]){
  const evidence=join(run,label+'-runtime-before.json');
  const row=await command('verify-'+label+'-archive',args['--python']||process.env.DSHA_PYTHON||'python3',[join(helpers,'verify-runtime-archive.py'),'--archive',archive,'--sha256',digest,'--root',root,'--report',evidence],envFor(join(run,'orchestrator-home')));
  assert.equal(row.status,'PASS','runtime must match exact archive');
  const identity=JSON.parse(await readFile(evidence,'utf8'));assert.equal(identity.version,label==='old'?'0.2.2-rc.1':'0.2.2-rc.5');
  report.archives[label].runtimeTreeSha256=identity.runtimeTreeSha256;
 }
 for(const surface of ['baseline','sessions','domain']){
  const data=join(run,surface,'data');await mkdir(data,{recursive:true});
  for(const [phase,runtime] of [['old-write',oldRoot],['new-read',newRoot],['new-write',newRoot],['new-reopen',newRoot],['old-read-new',oldRoot]]){
   const before=await tree(data);
   const argv=surface==='baseline'?[...nodeArgs(join(here,'test-dsh-data-cross-runtime.mjs')),'--worker',runtime,data,phase]:[...nodeArgs(join(helpers,'surfaces.mjs')),runtime,data,phase,surface];
   const row=await command(surface+'-'+phase,process.execPath,argv,envFor(join(data,'home')),120000);
   const after=await tree(data);const changed=[...new Set([...Object.keys(before),...Object.keys(after)])].filter(k=>JSON.stringify(before[k])!==JSON.stringify(after[k])).sort();
   row.readOnly=['new-read','new-reopen','old-read-new'].includes(phase);row.changedPaths=changed;
   row.treeSha256=hash(JSON.stringify(after));await writeFile(join(run,surface+'-'+phase+'-tree.json'),JSON.stringify(after,null,2)+'\n');
   if(row.readOnly&&changed.length){row.status='FAIL';row.reason='READ_ONLY_PHASE_CHANGED_DURABLE_FILES';}
   await save();if(row.status!=='PASS')break;
  }
 }
 for(const [label,runtime] of [['old',oldRoot],['new',newRoot]]){
  for(const [name,script,extra] of [['profile', 'profile-matrix.mjs',[]],['retired-team','retired-team.mjs',[label==='old'?'old':'newManaged']]]){
   const data=join(run,label+'-'+name,'data');await mkdir(dirname(data),{recursive:true});
   await command(label+'-'+name,process.execPath,[...nodeArgs(join(helpers,script)),runtime,data,...extra],envFor(join(run,'profile-home')),90000);
  }
 }
 for(const [label,root,archive,digest] of [['old',oldRoot,oldArchive,args['--old-sha256']],['new',newRoot,newArchive,args['--new-sha256']]]){
  const evidence=join(run,label+'-runtime-after.json');
  const row=await command('verify-'+label+'-after',args['--python']||process.env.DSHA_PYTHON||'python3',[join(helpers,'verify-runtime-archive.py'),'--archive',archive,'--sha256',digest,'--root',root,'--report',evidence],envFor(join(run,'orchestrator-home')));
  if(row.status==='PASS')assert.equal(JSON.parse(await readFile(evidence,'utf8')).runtimeTreeSha256,report.archives[label].runtimeTreeSha256);
 }
 report.status=report.checks.length===23&&report.checks.every(x=>x.status==='PASS')?'PASS_FINITE_MANAGED_DATA_COMPAT':'PARTIAL_WITH_FAILURES';
}catch(error){report.status='FAIL';report.error=String(error);report.stack=error.stack;}
report.finishedAt=new Date().toISOString();await save();console.log(JSON.stringify({status:report.status,checks:report.checks.length,report:reportPath,run}));
if(report.status!=='PASS_FINITE_MANAGED_DATA_COMPAT')process.exitCode=1;

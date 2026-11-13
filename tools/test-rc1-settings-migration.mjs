import { testRuntime } from './test-runtime-fixture.mjs';
// 真实锁定 SettingsForms.describe/update + schema，隔离临时数据，无模型/设备访问。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
const runtime = testRuntime('raw');
const require = createRequire(join(runtime, 'package.json'));
const expectedVersion = JSON.parse(await readFile(join(runtime,'package.json'),'utf8')).version;
const { SettingsForms } = await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-settings')));
const { default: z } = await import(pathToFileURL(require.resolve('@deepseek-ai/schemastery')));
const { resolveConfig } = await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const { parse } = await import(pathToFileURL(require.resolve('yaml')));
const { migrate } = createRequire(import.meta.url)('../app/src/main/assets/rc1-settings-migration.cjs');
const clone = value => JSON.parse(JSON.stringify(value));
function forms(home) {
  const entries = new Map(); let writes = 0;
  function add(ns, value = { model:'initial', temperature:1 }) {
    const Config = z.object({ model:z.string().volatile(), temperature:z.number().min(0).volatile(), fixed:z.string().default('untouched') });
    const runtime = { Config };
    const fiber = {uid:ns,state:2,runtime,rawConfig:clone(value),effectiveConfig:resolveConfig(runtime,value)}; fiber.ctx={fiber};
    const entry = { id:ns, options:{id:ns,config:clone(value)}, fiber };
    entries.set(ns, entry); return entry;
  }
  const settings=Object.create(SettingsForms.prototype);
  settings.revisions=new Map();settings.presentations=new Map();
  settings.ownerContext={profileContext:{home,name:'web'},emit(){},logger:{info(){}},configEditor:{
    entries:()=>[...entries.values()],
    configuration:()=>[...entries.values()].map(entry=>({entry,inherited:{},override:entry.options.config})),
    async edit(entry,change){const next=change(clone(entry.options.config),{});const config=resolveConfig(entry.fiber.runtime,next);entry.options.config=next;entry.fiber.rawConfig=next;entry.fiber.effectiveConfig=config;writes++;}
  }};
  return {settings,add,entries,get writes(){return writes;},change(ns,value){const entry=entries.get(ns);entry.options.config={...entry.options.config,...value};entry.fiber.rawConfig=entry.options.config;entry.fiber.effectiveConfig=resolveConfig(entry.fiber.runtime,entry.options.config);}};
}
async function fixture(document, run, version=expectedVersion) {
  const root=await mkdtemp(join(tmpdir(),'dsha-real-settings-'));const home=join(root,'.dsh'),state=join(root,'state');await mkdir(home);await writeFile(join(home,'runtime-patches.json'),JSON.stringify({schema:1,dshVersion:version}));await writeFile(join(home,'settings.yaml'),document);
  const p=spawnSync(process.env.DSHA_PYTHON || 'python',['app/src/main/assets/rc1-migration.py','prepare','--root',root,'--state-root',state,'--startup-id','test'],{encoding:'utf8'});
  assert.equal(p.status,0,p.stderr+p.stdout);
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));const ledger=join(state,'generations',current.generation,'settings-result.json');
  try{await run(forms(home),state,ledger,root);}finally{await rm(root,{recursive:true,force:true});}
}
test('pending真实次序只重试失败节，已成功节的用户新改动保持',()=>fixture('first: {model: old1}\nmissing: {model: old2}\n',async(api,state,ledger)=>{
  api.add('first');await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,1);assert.equal(JSON.parse(await readFile(ledger)).status,'pending');
  api.change('first',{model:'user-later'});api.add('missing');await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,2);assert.equal(api.settings.describe().find(x=>x.ns==='first').value.model,'user-later');assert.equal(api.settings.describe().find(x=>x.ns==='missing').value.model,'old2');
  await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,2);assert.equal(JSON.parse(await readFile(ledger)).status,'verified');
}));
test('真实schema拒绝非法值，日志缺失不等于成功',()=>fixture('first: {temperature: -1}\n',async(api,state,ledger)=>{
  api.add('first');await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,0);assert.equal(JSON.parse(await readFile(ledger)).status,'pending');
  await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,0);
}));
test('pending后目标改变时冲突保留，不重放旧值',()=>fixture('first: {temperature: -1}\n',async(api,state,ledger)=>{
  api.add('first');await migrate(api.settings,parse,{}, {stateRoot:state});api.change('first',{model:'user-new'});await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(JSON.parse(await readFile(ledger)).sections.first.status,'conflict');assert.equal(api.settings.describe()[0].value.model,'user-new');assert.equal(api.writes,0);
}));
test('快照正文被改动时不调用任何配置写入',()=>fixture('first: {model: old}\n',async(api,state,ledger,root)=>{
  api.add('first');const current=JSON.parse(await readFile(join(state,'current.json')));await writeFile(join(state,'generations',current.generation,'snapshots','0'),'tampered');
  await assert.rejects(migrate(api.settings,parse,{}, {stateRoot:state}),/SNAPSHOT_CHANGED/);assert.equal(api.writes,0);
}));

test('敏感字段只通过实际设置服务读回，不写入迁移回执',()=>fixture('first: {model: old, options: {apiKey: incoming-secret}}\n',async(api,state,ledger)=>{
  const entry=api.add('first',{model:'initial',options:{size:1,apiKey:'retained-secret'}});
  // fixture schema has no options field, so this is rejected before any write; ledger must remain redacted.
  await migrate(api.settings,parse,{}, {stateRoot:state});
  const text=await readFile(ledger,'utf8');assert.equal(text.includes('incoming-secret'),false);assert.equal(text.includes('retained-secret'),false);
}));

test('源正文在正式写入前变化时拒绝旧快照',()=>fixture('first: {model: old}\n',async(api,state,ledger,root)=>{
  api.add('first');await writeFile(join(root,'.dsh','settings.yaml'),'first: {model: changed}\n');
  await assert.rejects(migrate(api.settings,parse,{}, {stateRoot:state}),/SOURCE_CHANGED/);assert.equal(api.writes,0);
}));


test('runtime升级保留旧committed代与用户后改值，旧记录不提升为新版host缓存',()=>fixture('first: {model: old1}\n',async(api,state,ledger,root)=>{
  api.add('first');await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,1);
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));
  const folder=join(state,'generations',current.generation);
  const run=command=>spawnSync(process.env.DSHA_PYTHON || 'python',['app/src/main/assets/rc1-migration.py',command,'--root',root,'--state-root',state,'--startup-id','test'],{encoding:'utf8'});
  let result=run('finalize');assert.equal(result.status,0,result.stderr+result.stdout);
  const before=await readFile(join(folder,'receipt.json'),'utf8');assert.equal(JSON.parse(before).dshVersion,'0.2.0-rc.2');
  const oldCache=await readFile(join(folder,'reuse.json'),'utf8');
  await writeFile(join(root,'.dsh','runtime-patches.json'),JSON.stringify({schema:1,dshVersion:expectedVersion}));
  api.change('first',{model:'user-later'});
  result=run('prepare');assert.equal(result.status,0,result.stderr+result.stdout);
  await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,1);
  assert.equal(api.settings.describe().find(x=>x.ns==='first').value.model,'user-later');
  result=run('finalize');assert.equal(result.status,0,result.stderr+result.stdout);
  assert.equal(await readFile(join(folder,'receipt.json'),'utf8'),before);
  assert.equal(await readFile(join(folder,'reuse.json'),'utf8'),oldCache);
  assert.equal(JSON.parse(oldCache).dshVersion,'0.2.0-rc.2');
  assert.equal(JSON.parse(await readFile(join(state,'current.json'),'utf8')).generation,current.generation);
},'0.2.0-rc.2'));

test('账本缺失的普通重启只返回unknown，不新建账本也不重导旧值',()=>fixture('first: {model: old1}\n',async(api,state,ledger,root)=>{
  api.add('first');
  // 普通重启：Python 历史快路径只写 history-only 交接，不重新保护。
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));
  const p=spawnSync(process.env.DSHA_PYTHON || 'python',['app/src/main/assets/rc1-migration.py','prepare','--root',root,'--state-root',state,'--startup-id',current.startupId],{encoding:'utf8'});
  assert.equal(p.status,0,p.stderr+p.stdout);
  await rm(ledger,{force:true});
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'unknown');assert.equal(returned.reason,'DSHA_MIGRATION_LEDGER_MISSING');
  assert.equal(api.writes,0);await assert.rejects(readFile(ledger),/ENOENT/);
}));
test('update已派发但回执丢失时保持unknown且不再自动update',()=>fixture('first: {model: old1}\n',async(api,state,ledger)=>{
  api.add('first');
  const original=api.settings.update.bind(api.settings);
  let dispatches=0;
  api.settings.update=async(...args)=>{dispatches++;await original(...args);throw new Error('connection lost');};
  await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(dispatches,1);const first=JSON.parse(await readFile(ledger));
  assert.equal(first.sections.first.status,'unknown');
  api.settings.update=original;
  await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(dispatches,1);assert.equal(JSON.parse(await readFile(ledger)).status,'unknown');
}));
test('崩溃残留writing不恢复为可写pending，不自动再写',()=>fixture('first: {model: old1}\n',async(api,state,ledger)=>{
  api.add('first');
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));
  const folder=join(state,'generations',current.generation);
  const doc=JSON.parse(await readFile(join(folder,'prepare.json'),'utf8'));
  const source=doc.sources.find(row=>row.kind==='settings');
  await writeFile(ledger,JSON.stringify({version:2,generation:current.generation,sourceSha256:source.sha256,
    startupId:current.startupId,status:'pending',sections:{first:{status:'writing',before:{model:'initial',temperature:1}}}}));
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'unknown');assert.equal(api.writes,0);
  const after=JSON.parse(await readFile(ledger));assert.equal(after.sections.first.status,'unknown');
  await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,0);
}));
test('启动上下文缺失时的旧账本只读核对，不新建账本',()=>fixture('first: {model: old1}\n',async(api,state,ledger)=>{
  api.add('first');
  const currentPath=join(state,'current.json');const current=JSON.parse(await readFile(currentPath,'utf8'));
  delete current.startupContext;await writeFile(currentPath,JSON.stringify(current));
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'unknown');assert.equal(returned.reason,'DSHA_MIGRATION_LEDGER_MISSING');
  assert.equal(api.writes,0);await assert.rejects(readFile(ledger),/ENOENT/);
}));
test('history-only账本缺节时只读对账，不写配置也不重写账本',()=>fixture('first: {model: old1}\nsecond: {model: old2}\n',async(api,state,ledger)=>{
  api.add('first');api.add('second',{model:'user-later'});
  const currentPath=join(state,'current.json');const current=JSON.parse(await readFile(currentPath,'utf8'));
  const folder=join(state,'generations',current.generation);
  const doc=JSON.parse(await readFile(join(folder,'prepare.json'),'utf8'));
  const source=doc.sources.find(row=>row.kind==='settings');
  delete current.startupContext;await writeFile(currentPath,JSON.stringify(current)); // history-only
  const partial={version:2,generation:current.generation,sourceSha256:source.sha256,
    expectedSections:['first','second'],startupId:current.startupId,status:'verified',
    sections:{first:{status:'verified'}}};
  await writeFile(ledger,JSON.stringify(partial));const before=await readFile(ledger,'utf8');
  const writes=api.writes;
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'unknown');assert.equal(returned.reason,'SETTINGS_SECTION_MISSING');
  assert.equal(api.writes,writes);
  assert.equal(api.settings.describe().find(x=>x.ns==='second').value.model,'user-later');
  assert.equal(await readFile(ledger,'utf8'),before);
}));
test('history-only账本pending节不重试，用户当前值保持不变',()=>fixture('first: {model: old1}\nsecond: {model: old2}\n',async(api,state,ledger)=>{
  api.add('first');api.add('second',{model:'user-later'});
  const currentPath=join(state,'current.json');const current=JSON.parse(await readFile(currentPath,'utf8'));
  const folder=join(state,'generations',current.generation);
  const doc=JSON.parse(await readFile(join(folder,'prepare.json'),'utf8'));
  const source=doc.sources.find(row=>row.kind==='settings');
  delete current.startupContext;await writeFile(currentPath,JSON.stringify(current));
  const partial={version:2,generation:current.generation,sourceSha256:source.sha256,
    expectedSections:['first','second'],startupId:current.startupId,status:'pending',
    sections:{first:{status:'verified'},second:{status:'pending',reason:'SECTION_UNAVAILABLE'}}};
  await writeFile(ledger,JSON.stringify(partial));const before=await readFile(ledger,'utf8');
  const writes=api.writes;
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'pending');assert.equal(api.writes,writes);
  assert.equal(api.settings.describe().find(x=>x.ns==='second').value.model,'user-later');
  assert.equal(await readFile(ledger,'utf8'),before);
}));
test('write-allowed新票据仍可重试明确未派发的分节',()=>fixture('first: {model: old1}\nsecond: {model: old2}\n',async(api,state,ledger)=>{
  api.add('first');api.add('second');
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));
  const folder=join(state,'generations',current.generation);
  const doc=JSON.parse(await readFile(join(folder,'prepare.json'),'utf8'));
  const source=doc.sources.find(row=>row.kind==='settings');
  // 明确未派发（schema/目标缺失）的 pending 节：新迁移票据下允许重试。
  await writeFile(ledger,JSON.stringify({version:2,generation:current.generation,
    sourceSha256:source.sha256,expectedSections:['first','second'],startupId:current.startupId,
    status:'pending',sections:{first:{status:'verified'},second:{status:'pending',reason:'SECTION_SCHEMA_REJECTED'}}}));
  const writes=api.writes;
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'verified');assert.equal(api.writes,writes+1);
  assert.equal(api.settings.describe().find(x=>x.ns==='second').value.model,'old2');
}));
test('runtime升级后的旧pending代按history-only只读，不自动补节也不抬升证明',()=>fixture('first: {model: old1}\nmissing: {model: old2}\n',async(api,state,ledger,root)=>{
  const run=command=>spawnSync(process.env.DSHA_PYTHON || 'python',['app/src/main/assets/rc1-migration.py',command,'--root',root,'--state-root',state,'--startup-id','test'],{encoding:'utf8'});
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));
  const folder=join(state,'generations',current.generation);
  api.add('first');await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,1);
  let result=run('finalize');assert.equal(result.status,0,result.stderr+result.stdout);
  assert.equal(JSON.parse(await readFile(join(folder,'receipt.json'),'utf8')).status,'pending');
  api.change('first',{model:'user-later'});api.add('missing');
  await writeFile(join(root,'.dsh','runtime-patches.json'),JSON.stringify({schema:1,dshVersion:expectedVersion}));
  result=run('prepare');assert.equal(result.status,0,result.stderr+result.stdout);
  const ledgerBefore=await readFile(ledger,'utf8');
  // 普通重启只有 history-only 票据：不得重试 pending 节、不得改写用户当前值或账本。
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.notEqual(returned.status,'verified');assert.equal(api.writes,1);
  assert.equal(api.settings.describe().find(x=>x.ns==='first').value.model,'user-later');
  assert.notEqual(api.settings.describe().find(x=>x.ns==='missing').value.model,'old2');
  assert.equal(await readFile(ledger,'utf8'),ledgerBefore);
  result=run('finalize');assert.equal(result.status,0,result.stderr+result.stdout);
  const receipt=JSON.parse(await readFile(join(folder,'receipt.json'),'utf8'));
  assert.equal(receipt.status,'pending');assert.equal(receipt.dshVersion,'0.2.0-rc.2');
  await assert.rejects(readFile(join(folder,'reuse.json')),/ENOENT/);
  assert.equal(JSON.parse(await readFile(join(state,'current.json'),'utf8')).generation,current.generation);
},'0.2.0-rc.2'));

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
  function add(ns, value = { model:'initial', temperature:1 }, Config) {
    Config = Config || z.object({ model:z.string().volatile(), temperature:z.number().min(0).volatile(), fixed:z.string().default('untouched') });
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
// 真实 SettingsForms.describe() 的 descriptor.schema 是 schemastery 引用图（uid+refs），
// 下面用真实节点覆盖秘密字段、普通 maxTokens、嵌套对象、动态容器、数组与默认值。
const richConfig = () => z.object({
  model: z.string().volatile(),
  maxTokens: z.number().volatile(),
  apiKey: z.string().role('secret').volatile(),
  nested: z.object({ region: z.string(), token: z.string().role('secret') }).volatile(),
  providers: z.dict(z.object({ url: z.string(), key: z.string().role('secret') })).volatile(),
  list: z.array(z.object({ name: z.string(), secretToken: z.string().role('secret') })).volatile(),
  fixed: z.string().default('untouched'),
});
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
test('真实schema拒绝非法值时update已派发，保持unknown且不自动重试',()=>fixture('first: {temperature: -1}\n',async(api,state,ledger)=>{
  api.add('first');const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  // update 已被调用：真实 schema 的最小值拒绝发生在写事务里，错误文字不能证明未派发。
  assert.equal(returned.status,'unknown');assert.equal(api.writes,0);
  const first=JSON.parse(await readFile(ledger));assert.equal(first.sections.first.status,'unknown');assert.equal(first.sections.first.reason,'WRITE_OUTCOME_UNKNOWN');
  await migrate(api.settings,parse,{}, {stateRoot:state});assert.equal(api.writes,0);
  const second=JSON.parse(await readFile(ledger));assert.deepEqual(second.sections.first,first.sections.first);assert.equal(second.status,'unknown');
}));
test('update调用后抛schema文字错误且已产生效果时unknown，同一票据不重试',()=>fixture('first: {model: old1}\n',async(api,state,ledger)=>{
  api.add('first');
  const original=api.settings.update.bind(api.settings);let dispatches=0;
  api.settings.update=async(...args)=>{dispatches++;await original(...args);throw new Error('Invalid schema: No configurable plugin entry (volatile)');};
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(dispatches,1);assert.equal(api.writes,1);assert.equal(returned.status,'unknown');
  const first=JSON.parse(await readFile(ledger));
  assert.equal(first.sections.first.status,'unknown');assert.equal(first.sections.first.reason,'WRITE_OUTCOME_UNKNOWN');
  assert.equal(api.settings.describe().find(x=>x.ns==='first').value.model,'old1');
  // 同一个仍有效的 write-allowed 上下文：unknown 节不自动重试，update 调用数不增。
  const again=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(dispatches,1);assert.equal(api.writes,1);assert.equal(again.status,'unknown');
}));
test('普通重启的history-only路径也不会因schema文字错误补一次update',()=>fixture('first: {model: old1}\n',async(api,state,ledger,root)=>{
  api.add('first');
  const original=api.settings.update.bind(api.settings);let dispatches=0;
  api.settings.update=async(...args)=>{dispatches++;await original(...args);throw new Error('schema invalid volatile');};
  await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(dispatches,1);const first=await readFile(ledger,'utf8');
  api.settings.update=original;
  // 普通重启：Python 历史快路径只写 history-only 交接，不重新保护。
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));
  const p=spawnSync(process.env.DSHA_PYTHON || 'python',['app/src/main/assets/rc1-migration.py','prepare','--root',root,'--state-root',state,'--startup-id',current.startupId],{encoding:'utf8'});
  assert.equal(p.status,0,p.stderr+p.stdout);
  const again=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(dispatches,1);assert.equal(again.status,'unknown');assert.equal(await readFile(ledger,'utf8'),first);
}));
test('pending后目标改变时冲突保留，不重放旧值',()=>fixture('first: {model: old1}\n',async(api,state,ledger)=>{
  api.add('first');
  // 旧账本（update 前拒绝、已记录 before 的 pending 节）在新票据下重试：目标已被用户改动。
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));
  const folder=join(state,'generations',current.generation);
  const doc=JSON.parse(await readFile(join(folder,'prepare.json'),'utf8'));
  const source=doc.sources.find(row=>row.kind==='settings');
  await writeFile(ledger,JSON.stringify({version:2,generation:current.generation,sourceSha256:source.sha256,
    startupId:current.startupId,status:'pending',sections:{first:{status:'pending',reason:'SECTION_SCHEMA_REJECTED',before:{model:'initial',temperature:1}}}}));
  api.change('first',{model:'user-new'});
  await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(JSON.parse(await readFile(ledger)).sections.first.status,'conflict');assert.equal(api.settings.describe()[0].value.model,'user-new');assert.equal(api.writes,0);
}));
test('快照正文被改动时不调用任何配置写入',()=>fixture('first: {model: old}\n',async(api,state,ledger,root)=>{
  api.add('first');const current=JSON.parse(await readFile(join(state,'current.json')));await writeFile(join(state,'generations',current.generation,'snapshots','0'),'tampered');
  await assert.rejects(migrate(api.settings,parse,{}, {stateRoot:state}),/SNAPSHOT_CHANGED/);assert.equal(api.writes,0);
}));

test('分节不是映射时在调用update前拒绝，保留可重试pending',async()=>{
  for(const document of ['first: null\n','first: plain-scalar\n'])await fixture(document,async(api,state,ledger)=>{
    api.add('first');
    const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
    assert.equal(api.writes,0);assert.equal(returned.status,'pending');
    const section=JSON.parse(await readFile(ledger)).sections.first;
    assert.equal(section.status,'pending');assert.equal(section.reason,'SECTION_VALUE_FORMAT');
  });
});
test('敏感字段只通过实际设置服务读回，不写入迁移回执',()=>fixture('first: {model: old, options: {apiKey: incoming-secret}}\n',async(api,state,ledger)=>{
  const entry=api.add('first',{model:'initial',options:{size:1,apiKey:'retained-secret'}});
  // fixture schema has no options field: 顶层未声明字段在调用 update 之前被拒，保留可重试 pending。
  await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(api.writes,0);
  const text=await readFile(ledger,'utf8');assert.equal(text.includes('incoming-secret'),false);assert.equal(text.includes('retained-secret'),false);
  const section=JSON.parse(text).sections.first;
  assert.equal(section.status,'pending');assert.equal(section.reason,'SECTION_SCHEMA_REJECTED');
}));

// 真实锁定 SettingsForms 返回 descriptor.schema={uid,refs}；秘密只由节点 meta.role='secret'
// 决定，名称正则只对 schema 未声明的键额外少复制，不能删掉声明的普通字段。
test('真实uid/refs schema投影：秘密正文不落账，声明的maxTokens保留',()=>fixture(
  'first: {model: old, maxTokens: 4096, apiKey: incoming-key, nested: {region: us, token: nested-token}, providers: {alpha: {url: "https://example", key: dict-key}}, list: [{name: one, secretToken: list-token}]}\n',
  async(api,state,ledger)=>{
    api.add('first',{model:'initial',maxTokens:1,apiKey:'retained-key',
      nested:{region:'eu',token:'retained-nested'},
      providers:{beta:{url:'https://old',key:'retained-dict'}},
      list:[{name:'zero',secretToken:'retained-list'}]}, richConfig());
    const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
    assert.equal(returned.status,'verified');assert.equal(api.writes,1);
    const text=await readFile(ledger,'utf8');
    for(const secret of ['incoming-key','nested-token','dict-key','list-token','retained-key','retained-nested','retained-dict','retained-list'])
      assert.equal(text.includes(secret),false,'secret leaked: '+secret);
    const section=JSON.parse(text).sections.first;
    assert.equal(section.status,'verified');
    assert.equal(Object.hasOwn(section.source,'apiKey'),false);
    assert.deepEqual(section.source,{model:'old',maxTokens:4096,nested:{region:'us'},providers:{alpha:{url:'https://example'}},list:[{name:'one'}]});
    assert.equal(section.before.maxTokens,1);assert.equal(section.verified.maxTokens,4096);
    // 读回核验确实包含秘密（在内存比较），而不是靠丢字段蒙混通过。
    const live=api.settings.describe().find(row=>row.ns==='first').value;
    assert.equal(live.apiKey,'incoming-key');assert.equal(live.providers.alpha.key,'dict-key');assert.equal(live.list[0].secretToken,'list-token');
  }));
test('无法解释的schema保守冻结：悬空uid/未建模类型不写入配置',async()=>{
  await fixture('first: {model: old}\n',async(api,state,ledger)=>{
    api.add('first');
    const original=api.settings.describe.bind(api.settings);
    api.settings.describe=()=>original().map(row=>({...row,schema:{uid:1,refs:{1:{type:'object',dict:{model:9}}}}}));
    const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
    assert.equal(returned.status,'unknown');assert.equal(api.writes,0);
    const section=JSON.parse(await readFile(ledger)).sections.first;
    assert.equal(section.status,'unknown');assert.equal(section.reason,'SECTION_SCHEMA_UNKNOWN');
    assert.equal((await readFile(ledger,'utf8')).includes('"old"'),false);
  });
  await fixture('first: {model: old}\n',async(api,state,ledger)=>{
    api.add('first');
    const original=api.settings.describe.bind(api.settings);
    api.settings.describe=()=>original().map(row=>({...row,schema:{uid:1,refs:{1:{type:'object',dict:{model:2}},2:{type:'mystery'}}}}));
    const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
    assert.equal(returned.status,'unknown');assert.equal(api.writes,0);
    const section=JSON.parse(await readFile(ledger)).sections.first;
    assert.equal(section.status,'unknown');assert.equal(section.reason,'SECTION_SCHEMA_UNKNOWN');
  });
});
test('含秘密分支的联合类型未定义转移时保守冻结',()=>fixture('first: {choice: {plain: selected}}\n',async(api,state,ledger)=>{
  const Config=z.object({choice:z.union([z.object({secretValue:z.string().role('secret')}),z.object({plain:z.string()})]).volatile()});
  api.add('first',{choice:{plain:'initial'}},Config);
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'unknown');assert.equal(api.writes,0);
  const section=JSON.parse(await readFile(ledger)).sections.first;
  assert.equal(section.status,'unknown');assert.equal(section.reason,'SECTION_SCHEMA_UNKNOWN');
  assert.equal(api.settings.describe().find(row=>row.ns==='first').value.choice.plain,'initial');
}));
test('any数组项里schema未声明的凭据键按名称兜底不落账',()=>fixture('first: {items: [{apiKey: ANY-SECRET, plain: p}]}\n',async(api,state,ledger)=>{
  const Config=z.object({items:z.array(z.any()).volatile()});
  api.add('first',{items:[]},Config);
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'verified');assert.equal(api.writes,1);
  const text=await readFile(ledger,'utf8');assert.equal(text.includes('ANY-SECRET'),false);
  const section=JSON.parse(text).sections.first;
  assert.deepEqual(section.source,{items:[{plain:'p'}]});
  assert.deepEqual(section.verified,{items:[{plain:'p'}]});
  // update 仍写入未声明键（strict=false 保留），只是不复制进账本。
  assert.equal(api.settings.describe().find(row=>row.ns==='first').value.items[0].apiKey,'ANY-SECRET');
}));
test('无秘密分支的联合对象对未声明凭据键兜底，声明字段保留',()=>fixture('first: {choice: {plain: p, apiKey: UNION-SECRET}}\n',async(api,state,ledger)=>{
  const Config=z.object({choice:z.union([z.object({plain:z.string()}),z.object({other:z.string()})]).volatile()});
  api.add('first',{choice:{other:'x'}},Config);
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'verified');assert.equal(api.writes,1);
  const text=await readFile(ledger,'utf8');assert.equal(text.includes('UNION-SECRET'),false);
  assert.deepEqual(JSON.parse(text).sections.first.source,{choice:{plain:'p'}});
  assert.equal(api.settings.describe().find(row=>row.ns==='first').value.choice.apiKey,'UNION-SECRET');
}));
test('联合分支声明的凭据名字段不被名称兜底删除',()=>fixture('first: {choice: {apiKey: DECLARED-KEY, plain: p}}\n',async(api,state,ledger)=>{
  const Config=z.object({choice:z.union([z.object({apiKey:z.string(),plain:z.string()}),z.object({other:z.string()})]).volatile()});
  api.add('first',{choice:{other:'x'}},Config);
  const returned=await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(returned.status,'verified');
  const section=JSON.parse(await readFile(ledger,'utf8')).sections.first;
  // schema 声明为非秘密的字段由 schema 决定，名称正则不能删它。
  assert.deepEqual(section.source,{choice:{apiKey:'DECLARED-KEY',plain:'p'}});
}));
test('别名分节按目标descriptor的schema投影秘密',()=>fixture('legacy-first: {model: old, apiKey: alias-secret}\n',async(api,state,ledger)=>{
  api.add('first',{model:'initial',apiKey:'retained-alias'},richConfig());
  const returned=await migrate(api.settings,parse,{'legacy-first':'first'}, {stateRoot:state});
  assert.equal(returned.status,'verified');assert.equal(api.writes,1);
  const text=await readFile(ledger,'utf8');
  assert.equal(text.includes('alias-secret'),false);assert.equal(text.includes('retained-alias'),false);
  const section=JSON.parse(text).sections['legacy-first'];
  assert.equal(section.status,'verified');assert.deepEqual(section.source,{model:'old'});
  assert.equal(api.settings.describe().find(row=>row.ns==='first').value.apiKey,'alias-secret');
}));
test('旧账本留下before时秘密分节不盲目重试',()=>fixture('first: {model: old, apiKey: incoming-key}\n',async(api,state,ledger)=>{
  api.add('first',{model:'initial',apiKey:'retained-key'},richConfig());
  const current=JSON.parse(await readFile(join(state,'current.json'),'utf8'));
  const folder=join(state,'generations',current.generation);
  const doc=JSON.parse(await readFile(join(folder,'prepare.json'),'utf8'));
  const source=doc.sources.find(row=>row.kind==='settings');
  await writeFile(ledger,JSON.stringify({version:2,generation:current.generation,sourceSha256:source.sha256,
    startupId:current.startupId,status:'pending',sections:{first:{status:'pending',reason:'SECTION_SCHEMA_REJECTED',before:{model:'initial'}}}}));
  await migrate(api.settings,parse,{}, {stateRoot:state});
  assert.equal(api.writes,0);
  const section=JSON.parse(await readFile(ledger)).sections.first;
  assert.equal(section.status,'unknown');assert.equal(section.reason,'SECTION_SECRET_RETRY_REVIEW');
  assert.equal(api.settings.describe().find(row=>row.ns==='first').value.apiKey,'retained-key');
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

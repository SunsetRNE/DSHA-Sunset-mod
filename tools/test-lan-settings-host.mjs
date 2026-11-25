import { testRuntime } from './test-runtime-fixture.mjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { readFile, writeFile, mkdtemp, rm, symlink } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';

// Actual locked client controller/mirror + actual Host SettingsForms and schema.
// The controlled configEditor is the disk publication boundary; no Android/LAN network is claimed.
const runtime = testRuntime('raw');
const require = createRequire(join(runtime, 'package.json'));
const imported = async name => import(pathToFileURL(require.resolve(name)));
const cordis = await imported('@deepseek-ai/cordis');
// Upstream declares store's Zustand/Immer imports only as devDependencies because
// production browsers receive them in the frontend bundle. Load the exact store body
// with independently npm-ci-locked real dependencies; never alter the official raw tree.
const dependencyRoot = resolve(process.env.DSHA_LAN_STORE_DEPS || 'app/build/runtime190-store-deps');
const expectedLock = await readFile(new URL('./fixtures/lan-store-deps/package-lock.json',import.meta.url),'utf8');
assert.deepEqual(JSON.parse(await readFile(join(dependencyRoot,'package-lock.json'),'utf8')),JSON.parse(expectedLock));
const dependencyRequire = createRequire(join(dependencyRoot,'package.json'));
const storeBody = await readFile(require.resolve('@deepseek-ai/dsh-client-store'),'utf8');
let rebound = 0;
const linkedStore = storeBody.replace(/from "(zustand[^"\n]*|immer)"/g, (_all,name) => {
  rebound++; return 'from '+JSON.stringify(pathToFileURL(dependencyRequire.resolve(name)).href);
});
assert.equal(rebound,4);
const store = await import('data:text/javascript;base64,'+Buffer.from(linkedStore).toString('base64'));
const { default: z } = await imported('@deepseek-ai/schemastery');
const dsh = JSON.parse(await readFile(join(runtime, 'node_modules/@deepseek-ai/dsh/package.json'), 'utf8'));
const suffix = dsh.version === '0.2.2-rc.5' ? '-0.2.2-rc.5' : '';
const contract = JSON.parse(await readFile(new URL(`../app/src/main/assets/lan-settings-upstream-contract${suffix}.json`, import.meta.url)));
const source = await readFile(join(runtime,'node_modules',contract.module),'utf8');
assert.equal(createHash('sha256').update(source).digest('hex'),contract.sourceSha256);
const managedModules = process.env.DSHA_LAN_MANAGED_MODULES;
let SettingsForms;
if (managedModules) {
  const settingsModule = '@deepseek-ai/dsh-settings/lib/index.js';
  assert.deepEqual(await readFile(join(managedModules, contract.module)), Buffer.from(source));
  const settings = await readFile(join(managedModules, settingsModule));
  // The archive patches legacy import only. Use its real class body with the
  // locked host dependencies. The controlled disk boundary below exercises
  // describe/mutate without invoking the installed-profile migration hook.
  assert.equal(settings.toString().split('DSHA_RC1_SETTINGS_TRANSACTION_V2').length, 2);
  const directory = await mkdtemp(join(tmpdir(), 'dsha-lan-archive-settings-'));
  await symlink(join(runtime, 'node_modules'), join(directory, 'node_modules'), process.platform === 'win32' ? 'junction' : 'dir');
  const module = join(directory, 'settings.mjs');
  await writeFile(module, settings, { flag: 'wx' });
  ({ SettingsForms } = await import(pathToFileURL(module).href));
  console.log('Archive SettingsForms SHA-256 ' + createHash('sha256').update(settings).digest('hex'));
} else {
  ({ SettingsForms } = await imported('@deepseek-ai/dsh-settings'));
}
for (const anchor of contract.requiredAnchors) assert.equal(source.split(anchor).length-1,1);
for (const anchor of contract.forbiddenAnchors) assert.equal(source.includes(anchor),false);
let client;
const marker = 'return module.exports';
assert.equal(source.split(marker).length-1,1);
new Function('window', source.replace(marker, 'exports.__test = { ConfigFormController, SettingsDescribeMirror };\n'+marker))({
  __ModuleLoader__: { load(definition) { client = definition.factory(name => {
    if (name === '@deepseek-ai/cordis') return cordis;
    if (name === '@deepseek-ai/dsh-client-store') return store;
    throw new Error('Unexpected client dependency: '+name);
  }); }}
});
const { ConfigFormController, SettingsDescribeMirror } = client.__test;
const clone = value => JSON.parse(JSON.stringify(value));
async function host(file, beforeWrite = () => {}) {
  const value = JSON.parse(await readFile(file,'utf8'));
  const Config = z.object({ model:z.string().volatile(), temperature:z.number().min(0).volatile() });
  const runtime = { Config };
  const config = cordis.resolveConfig(runtime,value);
  const entry = { id:'model',options:{id:'model',config:clone(value)},fiber:{uid:'model',state:2,runtime,ctx:{},config,effectiveConfig:config} };
  const settings=Object.create(SettingsForms.prototype); settings.revisions=new Map(); settings.presentations=new Map();
  let writes=0;
  let tail = Promise.resolve();
  settings.ownerContext={ profileContext:{home:dirname(file),name:'web'},emit(){},logger:{info(){}},configEditor:{
    entries:()=>[entry],configuration:()=>[{entry,inherited:{},override:entry.options.config}],
    edit(item, change) {
      const operation = tail.then(async () => {
        const next = change(clone(item.options.config), {});
        const config = cordis.resolveConfig(runtime, next);
        await beforeWrite();
        await writeFile(file, JSON.stringify(next));
        item.options.config = next;
        item.fiber.config = config;
        item.fiber.effectiveConfig = config;
        writes++;
      });
      tail = operation.catch(() => {});
      return operation;
    },
  }};
  return { settings,get writes(){return writes;} };
}
function form(api, loopback) {
  let loopbackReads=0; const calls=[], rejections=[];
  const mutationSent = Promise.withResolvers();
  const remote={ $host:{ get isLoopback(){loopbackReads++; return loopback;} },settings:{
    async describe(){calls.push('describe'); return {ok:true,value:{writable:api.settings.writable,namespaces:api.settings.describe()}};},
    async mutate(ns,ops,revision){calls.push({ns,ops:clone(ops),revision}); mutationSent.resolve(); try {await api.settings.mutate(ns,ops,revision);return {ok:true,value:api.settings.describe().find(row=>row.ns===ns)};}catch(error){rejections.push(error);return {ok:false,error:{message:error.message}};}}
  }};
  const context={remote}; const mirror=new SettingsDescribeMirror(context);
  const controller=new ConfigFormController(context,{namespace:'model',decode:value=>value},mirror,{});
  return {mirror,controller,calls,rejections,mutationSent:mutationSent.promise,get loopbackReads(){return loopbackReads;}};
}
for (const loopback of [true,false]) test(`Host persistence survives client and Host recreation, isLoopback=${loopback}`,async()=>{
  const temporary=await mkdtemp(join(tmpdir(),'dsha-lan-host-'));const file=join(temporary,'settings.json');
  try {
    await writeFile(file,JSON.stringify({model:'initial',temperature:1}));
    const api=await host(file),first=form(api,loopback);await first.mirror.ensure();
    assert.equal(first.controller.getSnapshot().status,'ready');
    assert.equal(await first.controller.set('model','persisted-on-host'),true);
    assert.equal(await first.controller.set('temperature',0.7),true);
    assert.equal(api.writes,2);assert.equal(first.loopbackReads,0);
    assert.deepEqual(first.calls.filter(x=>typeof x==='object').map(x=>x.revision),[0,1]);
    await first.controller.dispose();
    const reopened=await host(file),second=form(reopened,loopback);await second.mirror.ensure();
    assert.deepEqual(second.controller.getSnapshot().value,{model:'persisted-on-host',temperature:0.7});
    assert.equal(second.loopbackReads,0);assert.equal(reopened.writes,0);await second.controller.dispose();
  } finally {await rm(temporary,{recursive:true,force:true});}
});
test('LAN schema-invalid write is rejected by actual Host and client re-reads retained value',async()=>{
  const temporary=await mkdtemp(join(tmpdir(),'dsha-lan-invalid-'));const file=join(temporary,'settings.json');
  try {await writeFile(file,JSON.stringify({model:'initial',temperature:1}));const api=await host(file),view=form(api,false);await view.mirror.ensure();
    assert.equal(await view.controller.set('temperature',-1),false);assert.equal(api.writes,0);assert.equal(view.controller.getSnapshot().value.temperature,1);assert.equal(view.loopbackReads,0);await view.controller.dispose();
  } finally {await rm(temporary,{recursive:true,force:true});}
});

test('LAN same-client writes use consecutive Host revisions', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'dsha-lan-queue-'));
  const file = join(temporary, 'settings.json');
  try {
    await writeFile(file, JSON.stringify({ model: 'initial', temperature: 1 }));
    const api = await host(file);
    const client = form(api, false);
    try {
      await client.mirror.ensure();
      const model = client.controller.set('model', 'queued-first');
      const temperature = client.controller.set('temperature', 0.4);
      assert.deepEqual(await Promise.all([model, temperature]), [true, true]);
      assert.deepEqual(client.calls.filter(call => typeof call === 'object').map(call => call.revision), [0, 1]);
      assert.equal(client.controller.getSnapshot().revision, 2);
      assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), { model: 'queued-first', temperature: 0.4 });
      assert.equal(api.writes, 2);
      assert.equal(client.loopbackReads, 0);
    } finally { await client.controller.dispose(); }
  } finally {
    assert.equal(dirname(resolve(temporary)), resolve(tmpdir()));
    assert.ok(basename(temporary).startsWith('dsha-lan-queue-'));
    await rm(temporary, { recursive: true, force: true });
  }
});

test('Two LAN clients proposing conflicting writes at the same revision get one write and one refresh', async () => {
  const temporary = await mkdtemp(join(tmpdir(), 'dsha-lan-conflict-'));
  const file = join(temporary, 'settings.json');
  const entered = Promise.withResolvers();
  const release = Promise.withResolvers();
  try {
    await writeFile(file, JSON.stringify({ model: 'initial', temperature: 1 }));
    const api = await host(file, async () => { entered.resolve(); await release.promise; });
    const first = form(api, false), stale = form(api, false);
    try {
      await Promise.all([first.mirror.ensure(), stale.mirror.ensure()]);
      const authoritative = first.controller.set('model', 'authoritative');
      await entered.promise;
      const conflict = stale.controller.set('model', 'stale-overwrite');
      await stale.mutationSent;
      assert.equal(stale.calls.find(call => typeof call === 'object').revision, 0);
      assert.equal(api.writes, 0);
      release.resolve();
      assert.deepEqual(await Promise.all([authoritative, conflict]), [true, false]);
      assert.equal(api.writes, 1);
      assert.equal(stale.rejections.length, 1);
      const [error] = stale.rejections;
      assert.equal(error.code, 'SETTINGS_CONFLICT');
      assert.deepEqual([error.expected, error.actual], [0, 1]);
      assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), { model: 'authoritative', temperature: 1 });
      assert.deepEqual(stale.controller.getSnapshot().value, { model: 'authoritative', temperature: 1 });
      assert.equal(stale.controller.getSnapshot().revision, 1);
      assert.equal(stale.calls.filter(call => call === 'describe').length, 2);
      assert.equal(stale.loopbackReads, 0);
    } finally {
      release.resolve();
      await Promise.all([first.controller.dispose(), stale.controller.dispose()]);
    }
  } finally {
    assert.equal(dirname(resolve(temporary)), resolve(tmpdir()));
    assert.ok(basename(temporary).startsWith('dsha-lan-conflict-'));
    await rm(temporary, { recursive: true, force: true });
  }
});

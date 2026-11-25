import { testRuntime } from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {setTimeout as sleep} from 'node:timers/promises';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {createRequire} from 'node:module';
const runtime=testRuntime('raw');
const require=createRequire(pathToFileURL(resolve(runtime,'package.json')));
let source = readFileSync('app/src/main/assets/profile-settings-review.js', 'utf8');
for (const name of ['yaml', '@deepseek-ai/schemastery', '@deepseek-ai/dsh-settings'])
  source = source.replace(`from '${name}'`, `from '${pathToFileURL(require.resolve(name)).href}'`);
const {apply, review, parseIncoming}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const secret={type:'string',meta:{role:'secret'}};
function service(){
 let value={model:'current',options:{size:1,apiKey:'retained-secret'}},user=structuredClone(value),revision=0;
 const schema={type:'object',dict:{model:{type:'string'},options:{type:'object',dict:{size:{type:'number'},apiKey:secret}}},'~standard':{validate(v){if(typeof v.model!=='string'||typeof v.options?.size!=='number'||v.options.size<0) return {issues:[{}]};return {value:v};}}};
 return {calls:[],describe(options){const row={ns:'llm',schema,revision,value:structuredClone(value),user:structuredClone(user),base:{model:'default',options:{size:0}}};if(options?.redactSecrets){delete row.value.options.apiKey;delete row.user.options.apiKey;}return [row];},
  async update(ns,patch,expected){assert.equal(ns,'llm');assert.equal(expected,revision);if(patch.model!==undefined&&typeof patch.model!=='string')throw Error('schema refused');this.calls.push(patch);for(const [k,v] of Object.entries(patch))value[k]=v&&typeof v==='object'?{...value[k],...v}:v;user=structuredClone(value);revision++;},
  async mutate(ns,ops,expected){assert.equal(expected,revision);for(const op of ops){if(op.op==='unset'){if(op.path[0]==='model')value.model='default';else if(op.path[0]==='options')value.options.size=0;}else value[op.path[0]]=op.value;}user=structuredClone(value);revision++;}
 };
}
let live=service();let result=await review(live,{mode:'preview'},'- id: llm\n  config:\n    model: restored\n    options:\n      size: 4\n      apiKey: incoming-secret\n');
assert.equal(result.items.length,2);assert(!JSON.stringify(result).includes('incoming-secret'));assert(!JSON.stringify(result).includes('retained-secret'));
assert.equal(live.describe()[0].value.options.apiKey,'retained-secret');
assert.equal(live.describe()[0].value.model,'current');assert.equal(live.describe()[0].value.options.size,1);
live=service();result=await review(live,{mode:'apply',selected:['llm/model']},'- id: llm\n  config:\n    model: restored\n    options:\n      size: 4\n');
assert.equal(live.calls.length,1);assert.equal(live.describe()[0].value.options.size,1);
await review(live,{mode:'verify',expected:result.items},'[]');
await assert.rejects(()=>review(service(),{mode:'verify',expected:result.items},'[]'),/READBACK/);
live=service();result=await review(live,{mode:'reset'},'[]');assert.equal(live.describe()[0].value.model,'default');assert.equal(live.describe()[0].value.options.size,0);assert.equal(live.describe()[0].value.options.apiKey,'retained-secret');
assert(!JSON.stringify(result).includes('retained-secret'));
result=await review(service(),{mode:'preview'},'- insert:\n    - id: arbitrary-code\n      name: evil\n- id: llm\n  config:\n    model: 42\n');
assert.equal(result.items.length,0);assert(result.warnings.includes('SCHEMA_REJECTED:llm/model'));assert(result.warnings.includes('CODE_OR_COMPOSITION_REQUIRES_REVIEW'));
await assert.rejects(()=>review(service(),{mode:'apply',selected:['llm/model']},'- id: llm\n  config:\n    model: 42\n'),/VALIDATION_FAILED/);
assert.throws(()=>parseIncoming('- id: llm\n  config: !!js process.exit()\n'),/YAML/);
assert.throws(()=>parseIncoming('- &a {id: llm, config: {model: ok}}\n- *a\n'),/ALIAS/);
result=await review(service(),{mode:'preview'},'- id: llm\n  config:\n    model: "${process.exit()}"\n');assert.equal(result.items.length,0);

const sparse = `- id: llm
  name: llm-package
  config:
    model: old
    options: {size: 8}
  configFields: !dsha-config-fields-v1
    - {path: [model], inherit: true}
    - {path: [options, size], value: 4}
    - {path: [options, apiKey], value: incoming-secret}
`;
const entryNames = new Map([['llm', 'llm-package']]);
live = service();
result = await review(live, { mode: 'preview' }, sparse, entryNames);
assert.deepEqual(result.items.map(({ id, actual }) => ({ id, actual })), [{ id: 'llm/options', actual: { size: 4 } }]);
assert(result.warnings.includes('SECRET_RETAINED:llm/options/apiKey'));
assert(!JSON.stringify(result).includes('secret"'));
await review(live, { mode: 'apply', selected: ['llm/options'] }, sparse, entryNames);
assert.deepEqual(live.describe()[0].value, { model: 'current', options: { size: 4, apiKey: 'retained-secret' } });
result = await review(service(), { mode: 'preview' }, sparse, new Map([['llm', 'other-package']]));
assert.deepEqual(result.items, []);
assert.deepEqual(result.warnings, ['TARGET_REQUIRES_REVIEW:llm']);
result = await review(service(), { mode: 'preview' }, [
  '- id: llm', '  config: { model: old, options: { size: 8 } }',
  '- id: llm', '  config: { model: new }',
  '- id: llm', '  configFields: [{ path: [options, size], value: 4 }]',
].join('\n'));
assert.deepEqual(result.items.map(({ actual }) => actual), ['new', { size: 4 }]);
result = await review(service(), { mode: 'preview' }, '- id: llm\n  config: { options: null }');
assert.deepEqual(result.items, []);
assert.deepEqual(result.warnings, ['SECRET_RETAINED:llm/options']);
for (const badPath of ['[]', '[__proto__]', '[options, constructor]']) {
  result = await review(service(), { mode: 'preview' },
    `- id: llm\n  configFields: [{ path: ${badPath}, value: 4 }]`);
  assert.deepEqual(result.items, []);
  assert.deepEqual(result.warnings, ['CODE_OR_COMPOSITION_REQUIRES_REVIEW']);
}
assert.throws(() => parseIncoming('- id: llm\n  config: { model: !dsha-config-fields-v1 [a] }'), /YAML_TAG/);

async function applyTrial(extraChange) {
  const directory = mkdtempSync(resolve(tmpdir(), 'dsha-settings-scope-'));
  const nonce = 'c'.repeat(32);
  const path = name => resolve(directory, name);
  const original = '- id: llm\n  name: llm-package\n  config:\n    options: { size: 1 }\n    custom: !!js 3 + 4 # keep\n';
  const inherited = '\n- id: llm\n  name: llm-package\n  configFields: !dsha-config-fields-v1\n    - { path: [model], value: restored }\n';
  const settings = service();
  const update = settings.update.bind(settings);
  settings.documentPath = path('current.yml');
  settings.update = async (...args) => {
    await update(...args);
    writeFileSync(settings.documentPath, extraChange ? original.replace('3 + 4', '3 + 5') + inherited : original + inherited);
  };
  writeFileSync(settings.documentPath, original);
  writeFileSync(path('request.json'), JSON.stringify({ mode: 'apply', nonce, selected: ['llm/model'] }));
  writeFileSync(path('incoming.yml'), '- id: llm\n  config: { model: restored }\n');
  const priorDir = process.env.DSHA_PROFILE_SETTINGS_DIRECTORY, priorNonce = process.env.DSHA_PROFILE_SETTINGS_NONCE;
  try {
    process.env.DSHA_PROFILE_SETTINGS_DIRECTORY = directory;
    process.env.DSHA_PROFILE_SETTINGS_NONCE = nonce;
    apply({ settings, configEditor: { entries: () => [{ options: { id: 'llm', name: 'llm-package' } }] },
      root: { loader: { await: () => Promise.resolve() } } });
    for (let attempt = 0; attempt < 60; attempt++) {
      try {
        return JSON.parse(readFileSync(path('result.json'), 'utf8'));
      } catch (error) {
        if (error.code !== 'ENOENT') throw error;
      }
      await sleep(20);
    }
    assert.fail('settings trial did not publish a result');
  } finally {
    if (priorDir === undefined) delete process.env.DSHA_PROFILE_SETTINGS_DIRECTORY;
    else process.env.DSHA_PROFILE_SETTINGS_DIRECTORY = priorDir;
    if (priorNonce === undefined) delete process.env.DSHA_PROFILE_SETTINGS_NONCE;
    else process.env.DSHA_PROFILE_SETTINGS_NONCE = priorNonce;
    rmSync(directory, { recursive: true });
  }
}
assert.equal((await applyTrial(false)).status, 'VERIFIED');
assert.equal((await applyTrial(true)).error, 'SETTINGS_SELECTION_SCOPE_CHANGED');
// 锁定 rc1 的真实 SettingsForms / schemastery 验证，不仅测试接口替身。
const {SettingsForms}=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-settings')).href);
const {resolveConfig}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')).href);
const {default:Schema}=await import(pathToFileURL(require.resolve('@deepseek-ai/schemastery')).href);
const Config=Schema.object({model:Schema.string().default('default'),limit:Schema.number().min(1).max(100).default(5)});
Config.meta.volatile=true;
let raw={model:'current',limit:5};const runtimeOwner={Config};
const fiber={uid:1,state:2,runtime:runtimeOwner,rawConfig:raw,effectiveConfig:resolveConfig(runtimeOwner,raw)};fiber.ctx={fiber};
const entry={id:'llm',options:{id:'llm',name:'test-settings',config:raw},fiber};
const actual=Object.create(SettingsForms.prototype);actual.revisions=new Map();actual.presentations=new Map();
actual.ownerContext={emit(){},configEditor:{entries:()=>[entry],configuration:()=>[{entry,inherited:{},override:raw}],async edit(_entry,change){const next=change(raw,{});const validated=resolveConfig(runtimeOwner,next);raw=next;entry.options.config=raw;entry.fiber.rawConfig=raw;entry.fiber.effectiveConfig=validated;}}};
result=await review(actual,{mode:'preview'},'- id: llm\n  config:\n    model: valid\n    limit: 101\n');
assert.equal(result.items.length,1);assert(result.warnings.includes('SCHEMA_REJECTED:llm/limit'));assert.equal(actual.describe()[0].value.limit,5);
result=await review(actual,{mode:'apply',selected:['llm/limit']},'- id: llm\n  config:\n    limit: 42\n');assert.equal(actual.describe()[0].value.limit,42);
await review(actual,{mode:'verify',expected:result.items},'[]');
console.log('PASS profile settings: field selection, schema rejection, executable quarantine, secret retention, reset and readback');

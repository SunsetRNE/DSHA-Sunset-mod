import os from 'node:os';
import { testRuntime } from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

// The rc2.1 overlay must remove the old review denial from both Host and UI.
const managed = testRuntime('managed');
const raw = testRuntime('raw');
const hostPath = 'node_modules/@deepseek-ai/dsh-plugin-manager/lib/index.js';
const uiPath = 'node_modules/@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js';
const policy = JSON.parse(fs.readFileSync('app/src/main/assets/plugin-manager-policy-patch.json', 'utf8'));
const upstreamHost = fs.readFileSync(path.join(raw, hostPath), 'utf8').replaceAll('\r\n', '\n');
const currentHost = fs.readFileSync(path.join(managed, hostPath), 'utf8').replaceAll('\r\n', '\n');
let expectedHost = upstreamHost;
assert.equal(policy.patches.length, 3);
const active = JSON.parse(fs.readFileSync('app/src/main/assets/runtime-patches.json','utf8')).active;
for (const row of active) {
  const recipe = JSON.parse(fs.readFileSync(path.join('app/src/main/assets',row.asset),'utf8'));
  if ((row.module ?? recipe.module) !== policy.module) continue;
  for (const patch of recipe.patches) {
    assert.equal(expectedHost.split(patch.before).length - 1, 1,row.asset);
    const after = patch.prependAsset === undefined ? patch.after : fs.readFileSync(path.join('app/src/main/assets',patch.prependAsset),'utf8')+'\n'+patch.after;
    expectedHost = expectedHost.replace(patch.before, after);
  }
}
assert.ok(currentHost === expectedHost, 'Host overlay differs from the complete pinned source chain');
assert.doesNotMatch(currentHost, /["']--ignore-scripts(?:["']|=true)/);
assert.doesNotMatch(currentHost, /--ignore-pnpmfile/);
assert.match(currentHost, /--config\.dangerously-allow-all-builds=true/);
assert.match(currentHost, /--config\.ignore-scripts=false/);
assert.match(currentHost, /--config\.ignore-dep-scripts=false/);
assert.match(currentHost, /DSHA_PLUGIN_SINGLE_INSTALL_V1/);
assert.match(currentHost, /DSHA_PLUGIN_HTTPS_GITHUB_V1/);
assert.doesNotMatch(currentHost, /DSHA_NATIVE_REVIEW_REQUIRED|DSHA_NATIVE_PLUGIN_POLICY_V1/);
const uiPatch = JSON.parse(fs.readFileSync('app/src/main/assets/plugin-manager-auto-enable-patch.json', 'utf8')).patches[0];
const upstreamUi = fs.readFileSync(path.join(raw, uiPath), 'utf8').replaceAll('\r\n', '\n');
const currentUi = fs.readFileSync(path.join(managed, uiPath), 'utf8').replaceAll('\r\n', '\n');
assert.equal(upstreamUi.split(uiPatch.before).length - 1, 1);
assert.ok(currentUi === upstreamUi.replace(uiPatch.before, uiPatch.after),
  'Web plugin installation must enable the installed bundle by default');

const { PluginManager } = await import(pathToFileURL(path.join(managed, hostPath)));
const manager = Object.create(PluginManager.prototype);
const directory = fs.mkdtempSync(path.join(os.tmpdir(),'native-plugin-policy-'));
try {
const sdk = path.join(directory, 'sdk');
const profile = path.join(directory, 'profile');
fs.mkdirSync(sdk, { recursive: true });
fs.mkdirSync(profile, { recursive: true });
fs.writeFileSync(path.join(sdk, 'package.json'), JSON.stringify({ name: '@deepseek-ai/dsh' }));
fs.writeFileSync(path.join(profile, 'package.json'), '{}');
manager.profile = { installAnchor: path.join(sdk, 'package.json'), dir: profile };
let changes = 0;
manager.change = async () => { changes++; return { changed: true, application: 'applied' }; };
const original = process.env.DSHA_NATIVE_PLUGIN_MANAGER;
process.env.DSHA_NATIVE_PLUGIN_MANAGER = '1';
try {
  for (const result of [
    await manager.installBundle('community-plugin@1.0.0', { enabled: true }),
    await manager.setBundleEnabled('community-plugin', true),
    await manager.removeBundle('community-plugin')
  ]) {
    assert.equal(result.application, 'applied');
  }
  assert.equal(changes, 3, 'Host operations must reach the upstream implementation');
} finally {
  if (original === undefined) delete process.env.DSHA_NATIVE_PLUGIN_MANAGER;
  else process.env.DSHA_NATIVE_PLUGIN_MANAGER = original;
}
console.log('PASS: Host plugin install, enable and remove are available; no review gate remains; unlocked lifecycle install is preserved.');

// A package hook can write before pnpm reports a registry error. Exercise the
// real Host operation and a real child process to prove fallback does not run it
// twice, and that this failure never becomes a successful install/enable result.
const failedProfile = path.join(directory, 'failed-profile');
fs.mkdirSync(failedProfile);
fs.writeFileSync(path.join(failedProfile, 'package.json'), JSON.stringify({name:'fixture-profile',private:true}));
fs.writeFileSync(path.join(failedProfile, 'cordis.patch.yml'), '[]\n');
const child = path.join(directory, 'package-child.cjs');
const counter = path.join(directory, 'hook-count');
const argumentsFile = path.join(directory, 'package-arguments.json');
const gitEnvironment = path.join(directory, 'package-git-environment.json');
fs.writeFileSync(child, `const fs=require('node:fs');
const args=process.argv.slice(2);
if(args.includes('config')) { console.log('https://registry.npmjs.org/'); process.exit(0); }
if(args.includes('view')) { console.log(JSON.stringify({name:'fixture-plugin',version:'1.0.0'})); process.exit(0); }
if(args.includes('add')) {
  fs.appendFileSync(process.env.DSHA_FIXTURE_COUNTER,'1');
  fs.writeFileSync(process.env.DSHA_FIXTURE_ARGUMENTS,JSON.stringify(args));
  fs.writeFileSync(process.env.DSHA_FIXTURE_GIT_ENV,JSON.stringify(Object.fromEntries(Object.entries(process.env).filter(([key])=>key.startsWith('GIT_CONFIG_')))));
  console.error('ERR_PNPM_FETCH_503 fixture hook wrote before the network failure'); process.exit(1);
}
process.exit(2);
`);
const failureManager = Object.create(PluginManager.prototype);
Object.assign(failureManager, {
  profile: {name:'web',dir:failedProfile,home:directory,patchPath:path.join(failedProfile,'cordis.patch.yml'),overlays:[],startedBundles:[],installAnchor:path.join(sdk,'package.json'),
    packageManager:{command:process.execPath,args:[child],env:{DSHA_FIXTURE_COUNTER:counter,DSHA_FIXTURE_ARGUMENTS:argumentsFile,DSHA_FIXTURE_GIT_ENV:gitEnvironment,GIT_CONFIG_COUNT:'0'}}},
  pnpmCommand:process.execPath,outputBytes:16384,lockWaitMs:1000,inspectTimeoutMs:1000,githubConnectionTimeoutMs:1000,idleTimeoutMs:2000,
  configuredRegistries:{registry:'https://registry.npmjs.org/',fallbackRegistries:['https://registry.npmmirror.com/']},
  ownerContext:{get:()=>undefined,emit:()=>{},logger:{warn:()=>{}}},
  managementBundles:new Set(),installedFrom:new Map(),packageOperations:new Set(),installs:new Map(),abort:new AbortController()
});
const priorUnlocked = process.env.DSHA_PLUGIN_UNLOCKED;
process.env.DSHA_PLUGIN_UNLOCKED = '1';
try {
  const failed = await failureManager.installBundle('fixture-plugin@1.0.0',{enabled:true,requestId:'fixture-side-effect'});
  assert.equal(failed.application,'failed');
  assert.equal(failed.packageResult.exitCode,1);
  assert.equal(failed.packageResult.kind,'network');
  assert.equal(fs.readFileSync(counter,'utf8'),'1','a failed script-bearing installation was replayed');
  assert.deepEqual(failed.registries,['https://registry.npmjs.org/']);
  const actualArguments = JSON.parse(fs.readFileSync(argumentsFile,'utf8'));
  for (const flag of ['--config.dangerously-allow-all-builds=true','--config.ignore-scripts=false','--config.ignore-dep-scripts=false','--config.fetch-retries=1','--config.fetch-timeout=60000','--reporter=append-only']) assert(actualArguments.includes(flag),flag);
  assert.equal(actualArguments.filter(arg=>arg==='fixture-plugin@1.0.0').length,1);
  assert.equal(JSON.parse(fs.readFileSync(path.join(failedProfile,'package.json'),'utf8')).dependencies,undefined);
  assert.equal(fs.existsSync(path.join(failedProfile,'pnpm-lock.yaml')),false);
  console.log('PASS: real Host child receives unlocked scripts and bounded download options; script-side-effect failure runs once and remains failed.');
  assert.equal(JSON.parse(fs.readFileSync(gitEnvironment,'utf8')).GIT_CONFIG_COUNT,'0','registry install must retain its transport policy');
  await failureManager.runPnpm(['add','https://github.com/fixture-owner/fixture-repo']);
  const githubEnv = JSON.parse(fs.readFileSync(gitEnvironment,'utf8'));
  assert.equal(githubEnv.GIT_CONFIG_COUNT,'3');
  assert.equal(githubEnv.GIT_CONFIG_KEY_0,'url.https://github.com/.insteadOf');
  assert.deepEqual([githubEnv.GIT_CONFIG_VALUE_0,githubEnv.GIT_CONFIG_VALUE_1,githubEnv.GIT_CONFIG_VALUE_2],['git+ssh://git@github.com/','ssh://git@github.com/','git@github.com:']);
  Object.assign(failureManager.profile.packageManager.env,{GIT_CONFIG_COUNT:'1',GIT_CONFIG_KEY_0:'fixture.explicit',GIT_CONFIG_VALUE_0:'keep'});
  await failureManager.runPnpm(['add','https://github.com/fixture-owner/fixture-repo']);
  const explicitEnv = JSON.parse(fs.readFileSync(gitEnvironment,'utf8'));
  assert.equal(explicitEnv.GIT_CONFIG_COUNT,'1');
  assert.equal(explicitEnv.GIT_CONFIG_KEY_0,'fixture.explicit');
  assert.equal(explicitEnv.GIT_CONFIG_VALUE_0,'keep');
  console.log('PASS: explicit GitHub HTTPS uses invocation-only transport rules; npm sources and explicit Git settings keep their own policy.');
} finally {
  if (priorUnlocked === undefined) delete process.env.DSHA_PLUGIN_UNLOCKED;
  else process.env.DSHA_PLUGIN_UNLOCKED=priorUnlocked;
}

} finally { fs.rmSync(directory,{recursive:true,force:true}); }

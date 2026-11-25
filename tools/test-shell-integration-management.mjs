import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {testRuntime} from './test-runtime-fixture.mjs';

const raw = testRuntime('raw');
const source = path.resolve(import.meta.dirname, '..');
const assets = path.join(source, 'app/src/main/assets');
const owned = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-shell-management-'));
const python = process.env.DSHA_TEST_PYTHON ?? process.env.DSHA_PYTHON ?? 'python';
const inventoryModule = '@deepseek-ai/dsh-host-plugin-inventory/lib/index.js';
const managerModule = '@deepseek-ai/dsh-plugin-manager/lib/index.js';
const names = ['dsh-app-integration', 'dsh-computer-use-android'];
const hostProvider = '@deepseek-ai/dsh-computer-use';
const serviceId = 'dsha-computer-use-service';
const ownedModules = path.join(owned, 'node_modules');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const link = (from, to) => fs.symlinkSync(from, to, process.platform === 'win32' ? 'junction' : 'dir');
const elapsed = performance.now();

try {
  link(path.join(raw, 'node_modules'), ownedModules);
  // Use the same exact patch composer as the archive. Removing only these recipes
  // from the control holds all unrelated managed patches constant.
  const patchRun = spawnSync(python, ['-B', '-c', `
import hashlib, json, sys
from pathlib import Path
sys.path.insert(0, 'tools')
from runtime_stable_patches import apply, load_chains
raw, out = map(Path, sys.argv[1:3])
chains, _ = load_chains('app/src/main/assets', '0.2.2-rc.5')
hashes = {}
for role, target in zip(('inventory', 'manager'), sys.argv[3:]):
    canonical = (raw / 'node_modules' / target).read_bytes()
    hashes[role] = {'canonical': hashlib.sha256(canonical).hexdigest()}
    for side in ('before', 'after'):
        chain = [row for row in chains[target] if side == 'after' or '-shell-integrations-patch.json' not in row[0]]
        patched = apply(canonical, chain)
        assert apply(patched, chain) == patched, target
        assert apply(apply(canonical, [row for row in chain if '-shell-integrations-patch.json' not in row[0]]), chain) == patched, target
        hashes[role][side] = hashlib.sha256(patched).hexdigest()
        (out / f'{role}-{side}.mjs').write_bytes(patched)
print(json.dumps(hashes))
`, raw, owned, inventoryModule, managerModule], {cwd: source, encoding: 'utf8', windowsHide: true});
  assert.equal(patchRun.status, 0, patchRun.stderr || String(patchRun.error));
  const moduleHashes = JSON.parse(patchRun.stdout);

  const profileDir = path.join(owned, 'home/profiles/web');
  const profileModules = path.join(profileDir, 'node_modules');
  fs.mkdirSync(profileModules, {recursive:true});
  const ordinaryBundle = 'shell-fixture-ordinary';
  const rows = [
    {id:'dsh-app-integration', name:names[0]},
    {id:'dsha-computer-use-android', name:names[1]},
    {id:serviceId, name:hostProvider},
    {id:'other-provider', name:hostProvider},
    {id:'ordinary-component', name:'fixture-ordinary-component'}
  ];
  for (const name of names) {
    const folder = name === names[0] ? 'app-integration' : 'builtin-plugins/' + name;
    link(path.join(assets, folder), path.join(profileModules, name));
  }
  const ordinaryDir = path.join(profileModules, ordinaryBundle);
  fs.mkdirSync(ordinaryDir);
  fs.writeFileSync(path.join(ordinaryDir, 'package.json'), JSON.stringify({
    name:ordinaryBundle, version:'1.0.0', dsh:{bundle:{patch:'cordis.patch.yml'}}
  }));
  // Preserve the upstream provider under an unrelated id. Scope follows the shell's
  // actual guest registration, and cannot hide unrelated providers by package alone.
  fs.writeFileSync(path.join(ordinaryDir, 'cordis.patch.yml'), JSON.stringify([{insert:[rows[2], rows[3], rows[4]]}]));
  const manifestPath = path.join(profileDir, 'package.json');
  const patchPath = path.join(profileDir, 'cordis.patch.yml');
  const profileBundles = [...names, ordinaryBundle];
  const manifest = JSON.stringify({
    name:'shell-fixture-profile', private:true, dependencies:Object.fromEntries(profileBundles.map(name => [name, '1.0.0'])),
    dsh:{profile:{bundles:profileBundles, patchReload:'startup'}}
  });
  fs.writeFileSync(manifestPath, manifest);
  fs.writeFileSync(patchPath, '[]\n');

  const {PROFILE_COMPATIBILITY_FILENAME} = await import(pathToFileURL(path.join(ownedModules, '@deepseek-ai/dsh-app-boot/lib/index.js')));
  const compatPath = path.join(profileDir, PROFILE_COMPATIBILITY_FILENAME);
  const exemptions = Object.fromEntries(profileBundles.map(name => [name + '@1.0.0', ['0.2.2-rc.5']]));
  fs.writeFileSync(compatPath, JSON.stringify(exemptions));
  const live = rows.map(row => ({
    id:'include:' + row.id, options:row, disabled:false, fiber:{state:2},
    parent:{tree:{ctx:{fiber:{entry:{id:'include'}}}}}
  }));
  const compositionInventory = () => [{rows:live.map(entry => ({entryId:entry.id, moduleName:entry.options.name, fiberState:2}))}];
  const ctx = {
    loader:{entries:() => live},
    get: service => service === 'agentPresets' ? {compositionInventory} : undefined,
    emit:() => {}, parallel:async () => {}, logger:{warn:() => {}}
  };
  const profile = {
    name:'web', dir:profileDir, home:path.join(owned, 'home'), patchPath, overlays:[], startedBundles:profileBundles,
    installAnchor:path.join(raw, 'node_modules/@deepseek-ai/dsh/package.json')
  };

  async function load(side) {
    const invFile = path.join(owned, 'inventory-' + side + '.mjs');
    const managerFile = path.join(owned, 'manager-' + side + '.mjs');
    const managerText = fs.readFileSync(managerFile, 'utf8');
    const importPath = 'from "@deepseek-ai/dsh-host-plugin-inventory"';
    assert.equal(managerText.split(importPath).length, 2);
    fs.writeFileSync(managerFile, managerText.replace(importPath, 'from ' + JSON.stringify(pathToFileURL(invFile).href)));
    const [{PluginManager}, {PluginInventoryGateway}] = await Promise.all([
      import(pathToFileURL(managerFile)), import(pathToFileURL(invFile))
    ]);
    const manager = Object.create(PluginManager.prototype);
    Object.defineProperty(manager, 'ctx', {value:ctx});
    Object.assign(manager, {
      ownerContext:ctx, profile, abort:new AbortController(), managementBundles:new Set(), installedFrom:new Map(),
      packageOperations:new Set(), installs:new Map(), lockWaitMs:1000
    });
    return {manager, inventory:PluginInventoryGateway.prototype.list.call({ctx})};
  }

  const before = await load('before');
  const after = await load('after');
  const [beforeInventory, afterInventory, beforeBundles, afterBundles, beforeRows, afterRows] = await Promise.all([
    before.inventory, after.inventory, before.manager.listBundles(), after.manager.listBundles(),
    before.manager.listPlugins(), after.manager.listPlugins()
  ]);
  assert.equal(beforeBundles.filter(bundle => names.includes(bundle.name)).length, 2);
  assert.deepEqual(afterBundles.map(bundle => bundle.name), beforeBundles.filter(bundle => !names.includes(bundle.name)).map(bundle => bundle.name));
  const publicIds = ['include:other-provider', 'include:ordinary-component'];
  assert.equal(beforeRows.length, 5);
  assert.equal(beforeInventory.entries.length, 5);
  assert.deepEqual(afterRows.map(entry => entry.entryId), publicIds);
  assert.deepEqual(afterInventory.entries.map(entry => entry.entryId), publicIds);
  assert.deepEqual(afterInventory.agentPresets[0].rows.map(entry => entry.entryId), publicIds);
  assert.deepEqual(afterBundles.find(bundle => bundle.name === ordinaryBundle).rows.map(row => row.rowId), ['other-provider', 'ordinary-component']);
  assert.equal(beforeBundles.find(bundle => bundle.name === ordinaryBundle).rows.length, 3);
  assert.deepEqual(before.manager.listVersionExemptions().exemptions, exemptions);
  assert.deepEqual(after.manager.listVersionExemptions().exemptions, {[ordinaryBundle + '@1.0.0']: ['0.2.2-rc.5']});

  const previousSwitches = [];
  for (const name of names) {
    const oldSwitch = await before.manager.setBundleEnabled(name, false);
    assert.equal(oldSwitch.application, 'restart-required', JSON.stringify(oldSwitch));
    const marker = path.join(profileModules, name + '.disabled');
    const markerText = fs.readFileSync(marker, 'utf8');
    assert.equal(markerText, 'DSHA_WEB_BUILTIN_DISABLED_V1\n');
    assert.ok(!JSON.parse(fs.readFileSync(manifestPath, 'utf8')).dsh.profile.bundles.includes(name));
    previousSwitches.push({name, disable:oldSwitch.application, marker:markerText.trim()});
    // Restore private control data before exercising candidate mutations.
    fs.writeFileSync(manifestPath, manifest);
    fs.unlinkSync(marker);
  }
  const attempts = [];
  for (const name of names) {
    assert.deepEqual(await after.manager.listBundles(name), []);
    assert.equal((await before.manager.listedBundle(name)).name, name);
    await assert.rejects(after.manager.readBundleDetail(name, 'zh-CN'), {code:'plugin-manager/unknown-bundle'});
    const inspected = await after.manager.inspect(name);
    assert.equal(inspected.problem, 'shipped');
    const install = await after.manager.installBundle(name);
    const remove = await after.manager.removeBundle(name);
    const on = await after.manager.setBundleEnabled(name, true);
    const off = await after.manager.setBundleEnabled(name, false);
    const compatibility = await after.manager.setVersionExemption(name + '@1.0.0', '0.2.2-rc.5', false, false);
    const versionList = await after.manager.listVersions(name);
    const update = await after.manager.updateBundle(name, '2.0.0');
    const actual = [install.error.code, remove.error.code, on.error.code, off.error.code, compatibility.error.code, versionList.status, update.result.application];
    assert.deepEqual(actual, ['not-removable', 'not-removable', 'management-required', 'management-required', 'management-required', 'refused', 'failed']);
    attempts.push({name, inspection:inspected.problem, install:actual[0], remove:actual[1], enable:actual[2], disable:actual[3], compatibility:actual[4], versions:actual[5], update:actual[6]});
  }
  for (const entry of live.slice(0, 3)) {
    const disabled = await after.manager.setPluginEnabled(entry.id, false);
    assert.equal(disabled.error.code, 'unknown-plugin');
    attempts.push({entryId:entry.id, disable:disabled.error.code});
  }
  assert.equal(fs.readFileSync(manifestPath, 'utf8'), manifest);
  assert.equal(fs.readFileSync(patchPath, 'utf8'), '[]\n');
  assert.deepEqual(JSON.parse(fs.readFileSync(compatPath, 'utf8')), exemptions);
  for (const name of names) assert.equal(fs.existsSync(path.join(profileModules, name + '.disabled')), false);

  const snapshot = (bundles, rows, inventory) => ({
    packages:bundles.length,
    shellPackages:bundles.filter(bundle => names.includes(bundle.name)).map(bundle => bundle.name),
    pluginManagerRows:rows.map(entry => entry.entryId),
    hostInventoryRows:inventory.entries.map(entry => entry.entryId),
    presetRows:inventory.agentPresets[0].rows.map(entry => entry.entryId),
    ordinaryBundleRows:bundles.find(bundle => bundle.name === ordinaryBundle).rows.map(row => row.rowId)
  });
  const result = {
    status:'PASS', node:process.version, platform:process.platform, osRelease:os.release(),
    shellPolicySha256:hash(fs.readFileSync(path.join(assets, 'shell-integration-policy.js'))),
    moduleHashes, before:snapshot(beforeBundles, beforeRows, beforeInventory),
    after:snapshot(afterBundles, afterRows, afterInventory), previousSwitches, attempts,
    profileFilesUnmodified:true, elapsedMs:Math.round(performance.now() - elapsed)
  };
  console.log(JSON.stringify(result, null, 2));
} finally {
  fs.rmSync(owned, {recursive:true, force:true});
}

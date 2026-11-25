// Exact rc5 module source, native filesystem, and fresh synchronous construction.
// No application profile, model request, package install, or production fixture edit.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { registerHooks, syncBuiltinESMExports } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const moduleFile = path.join(runtime, 'node_modules/@deepseek-ai/dsh-app-boot/lib/index.js');
const recipe = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/startup-resolution-patch.json'), 'utf8'));
const original = fs.readFileSync(moduleFile, 'utf8');
let patched = original;
for (const patch of recipe.patches) {
  assert.equal(patched.split(patch.before).length, 2, 'exact upstream anchor');
  patched = patched.replace(patch.before, patch.after);
  assert.equal(patched.split(patch.after).length, 2, 'single candidate anchor');
  assert.equal(patched.includes(patch.before), false, 'patch has no second eligible application');
}
assert.throws(() => {
  const changed = original.replace('function compileResolution(resolution)', 'function compileResolution(changed)');
  for (const patch of recipe.patches) assert.equal(changed.split(patch.before).length, 2);
}, 'changed upstream must fail exact application');

// Load the actual module twice at its real resolution anchor. The test-only
// export exposes this pure construction without changing its surrounding code.
const hook = registerHooks({
  load(url, context, next) {
    const parsed = new URL(url);
    if (parsed.protocol === 'file:' && fileURLToPath(parsed) === moduleFile && parsed.searchParams.has('dsha-resolution-test')) {
      return { format: 'module', shortCircuit: true,
        source: (parsed.searchParams.get('dsha-resolution-test') === 'after' ? patched : original)
          + '\nexport { compileResolution as dshaTestCompileResolution };\n' };
    }
    return next(url, context);
  },
});
let before, after, beforeProfile, afterProfile;
try {
  const originalModule = await import(pathToFileURL(moduleFile).href + '?dsha-resolution-test=before');
  const candidateModule = await import(pathToFileURL(moduleFile).href + '?dsha-resolution-test=after');
  before = originalModule.dshaTestCompileResolution;
  after = candidateModule.dshaTestCompileResolution;
  beforeProfile = originalModule.loadProfileDirectory;
  afterProfile = candidateModule.loadProfileDirectory;
} finally { hook.deregister(); }

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-startup-resolution-'));
const directory = relative => {
  const file = path.join(temporary, relative);
  fs.mkdirSync(file, { recursive: true });
  return file;
};
const normalize = value => value instanceof Map ? [...value].map(([key, item]) => [key, normalize(item)])
  : value instanceof Set ? [...value].map(normalize)
    : Array.isArray(value) ? value.map(normalize)
      : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([key, item]) => [key, normalize(item)])) : value;
const realpath = fs.realpathSync;
const tracked = callback => {
  const calls = [];
  fs.realpathSync = Object.assign(function (file, ...args) {
    calls.push(String(file));
    return Reflect.apply(realpath, this, [file, ...args]);
  }, realpath);
  syncBuiltinESMExports();
  try { return { result: callback(), calls }; }
  finally { fs.realpathSync = realpath; syncBuiltinESMExports(); }
};
const outcomes = [];
function compare(name, input) {
  const old = tracked(() => before(input)), current = tracked(() => after(input));
  assert.deepEqual(normalize(current.result), normalize(old.result), name);
  const installations = input.entries.filter(entry => entry.scope === 'installation').length;
  assert.equal(old.calls.length - current.calls.length, installations, name + ': one duplicate realpath removed per installation entry');
  assert.equal(current.result.entries.size, new Set(input.entries.map(entry => entry.name)).size);
  outcomes.push({ name, entries: input.entries.length, beforeRealpath: old.calls.length, afterRealpath: current.calls.length });
  return current.result;
}

function countVersionReads(loadProfile, profile, install, version, failFirst = false) {
  const read = fs.readFileSync;
  const versionPath = path.join(path.dirname(moduleFile), '..', 'package.json');
  let reads = 0;
  fs.readFileSync = function(file, ...args) {
    if (file !== versionPath) return read.call(this, file, ...args);
    reads++;
    if (failFirst && reads === 1) throw Error('fixture: runtime version temporarily unavailable');
    return JSON.stringify({version});
  };
  syncBuiltinESMExports();
  try {
    return {profile: loadProfile('dsh', profile, install), reads};
  } finally {
    fs.readFileSync = read;
    syncBuiltinESMExports();
  }
}

try {
  const input = { entries: [], profilesDir: directory('profiles'), profileDir: directory('profiles/web'), linkedRoots: [], localPackageNames: [] };
  compare('empty resolution', input);
  for (let index = 0; index < 300; index++) input.entries.push({ name: 'package-' + index,
    packageDir: directory('runtime/node_modules/package-' + index), scope: 'installation', version: '1.0.0', declarer: 'kept-' + index });
  compare('300 real installation directories', input);
  input.entries.push({ name: 'profile-local', packageDir: directory('profiles/web/node_modules/profile-local'), scope: 'profile' });
  input.localPackageNames = ['profile-local'];
  compare('profile-local routes preserve original scope', input);

  const targetOne = directory('external/one'), targetTwo = directory('external/two');
  const alias = path.join(temporary, 'runtime/node_modules/alias');
  fs.symlinkSync(targetOne, alias, process.platform === 'win32' ? 'junction' : 'dir');
  input.entries.push({ name: 'alias', packageDir: alias, scope: 'installation' });
  input.linkedRoots = [{ name: 'linked-one', realPath: targetOne }];
  const first = compare('current linked alias and linked-root checks', input);
  assert.equal(first.entries.get('alias').realDir, fs.realpathSync(targetOne));
  fs.unlinkSync(alias);
  fs.symlinkSync(targetTwo, alias, process.platform === 'win32' ? 'junction' : 'dir');
  const next = compare('retargeted alias is reread on next construction', input);
  assert.equal(next.entries.get('alias').realDir, fs.realpathSync(targetTwo));
  assert.notEqual(first.entries.get('alias').realDir, next.entries.get('alias').realDir);
  fs.unlinkSync(alias);
  const missing = compare('missing alias retains existing absolute fallback', input);
  assert.equal(missing.entries.get('alias').realDir, path.resolve(alias));
  input.entries.push({ name: 'alias', packageDir: targetOne, scope: 'installation' });
  compare('duplicate names retain original prefix set and last map entry', input);
  const relative = path.relative(process.cwd(), targetTwo);
  input.entries.push({ name: 'relative-path', packageDir: relative, scope: 'installation' });
  compare('relative configured spelling remains identical', input);

  const profile = directory('version-profile'), install = path.join(directory('installation'), 'package.json');
  const bundleNames = ['dsha-version-good', 'dsha-version-incompatible', 'dsha-version-exempt',
    'dsha-version-platform', 'dsha-version-good-last'];
  for (const name of bundleNames) {
    const dir = directory('version-profile/node_modules/' + name);
    const bundle = {patch: 'cordis.yml'};
    if (name === 'dsha-version-platform') bundle.platforms = [process.platform === 'win32' ? 'linux' : 'win32'];
    fs.writeFileSync(path.join(dir, 'cordis.yml'), '[]\n');
    fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({
      name, version: '1.0.0', dsh: {bundle},
      engines: {dsh: name === 'dsha-version-incompatible' || name === 'dsha-version-exempt' ? '>=1.0.0' : '=0.2.2-rc.5'},
    }));
  }
  fs.writeFileSync(path.join(profile, 'package.json'), JSON.stringify({
    name: 'dsha-version-fixture', version: '1.0.0', dsh: {profile: {bundles: bundleNames}},
  }));
  fs.writeFileSync(path.join(profile, 'compatibility.json'), '{"dsha-version-exempt@1.0.0":["0.2.2-rc.5"]}\n');
  for (const [name, version, failFirst, counts] of [
    ['known version, unsupported platform and exact exemption', '0.2.2-rc.5', false, [4, 1]],
    ['temporary read failure is caught per bundle', '0.2.2-rc.5', true, [4, 2]],
    ['successor load rereads its new version', '0.2.2-rc.6', false, [4, 1]],
    ['invalid version never becomes successful cached input', 'invalid', false, [4, 4]],
  ]) {
    const old = countVersionReads(beforeProfile, profile, install, version, failFirst);
    const current = countVersionReads(afterProfile, profile, install, version, failFirst);
    assert.deepEqual(current.profile, old.profile, name);
    assert.deepEqual([old.reads, current.reads], counts, name);
    if (name.startsWith('known')) assert.deepEqual(current.profile.layers.map(layer => layer.packageName),
      ['dsha-version-good', 'dsha-version-exempt', 'dsha-version-good-last']);
    outcomes.push({name, beforeVersionReads: old.reads, afterVersionReads: current.reads,
      admitted: current.profile.layers.length, skipped: current.profile.skippedBundles.length});
  }
  console.log(JSON.stringify({ status: 'passed', cases: outcomes, platform: process.platform, node: process.version }, null, 2));
} finally {
  // Delete only this freshly created test tree; never follow its junctions.
  assert.equal(fs.realpathSync(temporary).toLowerCase(), path.resolve(temporary).toLowerCase());
  assert.equal(path.dirname(temporary).toLowerCase(), path.resolve(os.tmpdir()).toLowerCase());
  assert(path.basename(temporary).startsWith('dsha-startup-resolution-'));
  fs.rmSync(temporary, { recursive: true, force: true });
}

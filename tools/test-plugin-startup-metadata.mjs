import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const require = createRequire(import.meta.url);
const root = path.resolve(import.meta.dirname, '..');
const assets = path.join(root, 'app/src/main/assets');
const source = fs.readFileSync(path.join(assets, 'startup-observer.cjs'), 'utf8');
const helper = require('../app/src/main/assets/plugin-semver.cjs');
const requiredRecipes = ['plugin-inventory-entries-patch.json', 'plugin-manager-entries-patch.json',
  'plugin-inventory-management-patch.json', 'plugin-inventory-management-host-codec-patch.json',
  'plugin-inventory-management-remote-codec-patch.json', 'plugin-inventory-management-api-codec-patch.json',
  'plugin-manager-management-probe-patch.json'];

// 执行真实 observer 和固定 checker；只隔离 YAML/Loader 入口，不能称真实 Cordis Loader 验收。
function observe({ manifest = {}, exemptions, registry, checkerFailure = false, runtimeMissing = false, instance = 'fixture-instance' } = {}) {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'a6-startup-metadata-'));
  const home = path.join(folder, 'home'), installation = path.join(folder, 'installation');
  const profile = path.join(home, 'profiles/web'), moduleDir = path.join(profile, 'node_modules/fixture-plugin');
  const put = (file, value) => { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, typeof value === 'string' ? value : JSON.stringify(value)); };
  put(path.join(profile, 'package.json'), { dsh: { profile: { bundles: ['fixture-plugin'] } } });
  put(path.join(moduleDir, 'package.json'), { name: 'fixture-plugin', version: '1.0.0', dsh: { bundle: { patch: 'cordis.patch.yml' } }, ...manifest });
  put(path.join(moduleDir, 'cordis.patch.yml'), [{ insert: [{ id: 'entry', name: './private.js' }] }]);
  put(path.join(installation, 'package.json'), { name: '@deepseek-ai/dsh', version: '0.2.2-rc.1' });
  if (!runtimeMissing) {
    const boot = path.join(installation, 'node_modules/@deepseek-ai/dsh-app-boot');
    put(path.join(boot, 'package.json'), { name: '@deepseek-ai/dsh-app-boot', version: '0.2.2-rc.1', main: 'lib/index.js' });
    put(path.join(boot, 'lib/index.js'), 'throw Error("must not execute app-boot");');
  }
  if (exemptions !== undefined) put(path.join(profile, 'compatibility.json'), exemptions);
  if (registry !== null) put(path.join(folder, 'runtime-patches.json'), registry || {
    schema: 1, dshVersion: '0.2.2-rc.1', active: requiredRecipes.map(asset => ({ asset })),
  });
  let checks = 0, pluginImports = 0;
  const yaml = { JSON_SCHEMA: { extend() { return {}; } }, Type: function () {}, load: text => JSON.parse(text) };
  const create = anchor => {
    const local = name => { if (name === 'js-yaml') return yaml; throw Error('optional native module unavailable'); };
    local.resolve = name => name === '@deepseek-ai/cordis-plugin-loader' ? 'fixture:loader' : path.join(installation, 'node_modules/@deepseek-ai/dsh-app-boot/lib/index.js');
    local.resolve.paths = () => [path.join(profile, 'node_modules')];
    return local;
  };
  class Entry { init() { pluginImports++; return Promise.resolve(); } }
  const context = { Buffer, console, process: { env: { DSH_HOME: home, DSHA_OBSERVER_INSTALL: installation,
      DSHA_STARTUP_PROFILE: 'web', DSHA_WEB_GENERATION: '7', DSH_TASK_INSTANCE: instance },
    platform: process.platform, stdout: { write() {} }, on() {} }, __dirname: folder,
    require(name) {
      if (name === 'node:module') return { createRequire: create };
      if (name === 'fixture:loader') return { Entry };
      if (name === path.join(folder, 'plugin-semver.cjs')) return { dshaCompatibilityReports(request) {
        checks++; if (checkerFailure) throw Error('/root/private-runtime-error');
        return helper.dshaCompatibilityReports(request);
      } };
      if (name.startsWith('node:')) return require(name);
      throw Error('unexpected module ' + name);
    } };
  vm.runInNewContext(source, context, { filename: 'startup-observer.cjs', timeout: 5000 });
  const evidence = context[Symbol.for('dsha.installed-plugin-evidence')];
  assert.equal(pluginImports, 0);
  return { evidence, checks, folder };
}

test('固定helper新增非枚举导出，不改变semver常规导出与vendor模板', () => {
  const descriptor = Object.getOwnPropertyDescriptor(helper, 'dshaCompatibilityReports');
  assert.equal(typeof descriptor.value, 'function'); assert.equal(descriptor.enumerable, false);
  assert.equal(descriptor.writable, false); assert.equal(descriptor.configurable, false);
  assert.equal(helper.satisfies('1.2.3', '^1.0.0'), true);
  const vendor = fs.readFileSync(path.join(root, 'tools/vendor-plugin-semver.cjs'), 'utf8');
  assert(vendor.includes("Object.defineProperty(module.exports, 'dshaCompatibilityReports', { value: compatibility });"));
});

test('真实observer初始化仅一次批量预检，内容/metadata不冒充Loader执行', () => {
  const { evidence, checks, folder } = observe({ manifest: { engines: { dsh: '>=0.2.2-rc.1' } } });
  assert.equal(checks, 1);
  const row = evidence.bundles[0];
  assert.equal(row.eligibility.state, 'compatible'); assert.equal(row.eligibility.evidence, 'PREFLIGHT');
  assert.equal(row.eligibility.name, 'fixture-plugin'); assert.equal(row.eligibility.version, '1.0.0');
  assert.equal(row.eligibility.generation, '7'); assert.equal(row.eligibility.instance, 'fixture-instance');
  assert.equal(row.eligibility.runtimePlatform, process.platform);
  assert.equal(row.contentFingerprint, ''); assert.equal(row.contentAlgorithm, '');
  assert.equal(evidence.patchChain.state, 'present-unverified');
  assert(Object.isFrozen(row.eligibility));
  const json = JSON.stringify(evidence); assert(!json.includes(folder)); assert(!json.includes('private-runtime'));
});

for (const [state, options] of [
  ['unconstrained', {}],
  ['incompatible', { manifest: { engines: { dsh: '>=99.0.0' } } }],
  ['exempted', { manifest: { engines: { dsh: '>=99.0.0' } }, exemptions: { 'fixture-plugin@1.0.0': ['0.2.2-rc.1'] } }],
  ['unknown', { manifest: { engines: { dsh: 42 } } }],
  ['unknown', { checkerFailure: true }],
  ['unknown', { runtimeMissing: true }],
  ['unknown', { instance: '' }],
  ['unknown', { manifest: { name: 'different-package' } }],
  ['unsupported-platform', { manifest: { dsh: { bundle: { patch: 'cordis.patch.yml', platforms: [process.platform === 'win32' ? 'linux' : 'win32'] } } } }],
  ['unknown', { exemptions: ' '.repeat(1048577) }],
  ['unknown', { exemptions: '{bad-json' }],
]) test(`启动预检按固定checker结果保守投影：${state}/${Object.keys(options).join(',')}/${typeof options.exemptions === 'string' ? options.exemptions.length : ''}`, () => {
  const { evidence } = observe(options);
  assert.equal(evidence.bundles[0].eligibility.state, state);
  assert.equal(evidence.bundles[0].entries.length, 1, '静态预检不能清理真实入口元数据');
});

for (const [expectedState, reasonCode, registry] of [
  ['unavailable', 'PATCH_REGISTRY_UNAVAILABLE', null],
  ['unavailable', 'PATCH_REGISTRY_INVALID', { schema: 2, active: [] }],
  ['mismatch', 'PATCH_VERSION_MISMATCH', { schema: 1, dshVersion: '9.0.0', active: [] }],
  ['mismatch', 'PATCH_RECIPE_MISSING', { schema: 1, dshVersion: '0.2.2-rc.1', active: [] }],
]) test(`补丁声明只输出来源未验证或固定缺口：${reasonCode}`, () => {
  const { evidence } = observe({ registry });
  assert.equal(evidence.patchChain.state, expectedState); assert.equal(evidence.patchChain.reasonCode, reasonCode);
});

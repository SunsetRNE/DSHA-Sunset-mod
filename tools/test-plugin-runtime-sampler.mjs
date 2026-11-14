import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { syncBuiltinESMExports } from 'node:module';
import { installedPluginSampler, pluginSessionIdentity, pluginStartupEvidence, pluginActivityProjection, runtimePlugins } from '../app/src/main/assets/app-integration/runtime-plugins.js';
import { apply as integration } from '../app/src/main/assets/app-integration/index.js';

// 本专项只检查采样器纯逻辑；真实固定 Loader 的覆盖仍在 test-plugin-runtime-evidence.mjs。
const hash = value => createHash('sha256').update(value).digest('hex');
const identity = { generation: 'generation-1', instance: 'instance-1', profile: 'web' };
const at = 1794652800000;
function fixture() {
  const loader = { root: {} }, include = { options: { id: 'include', name: 'cordis:include' }, parent: loader.root, subtree: {} };
  const rows = [include], attempts = new WeakMap();
  const evidence = { ...identity, bundles: [], initState: entry => attempts.get(entry) || '' };
  let reads = 0;
  loader.entries = () => { reads++; return rows.values(); };
  const ctx = { get: name => name === 'loader' ? loader : undefined };
  function bundle(name = 'fixture-plugin') {
    const item = { name, version: '1.0.0', profile: 'web', entries: [], ambiguous: false };
    evidence.bundles.push(item); return item;
  }
  function entry(bundle, id = 'entry', name = './private-module.js', group = false, parent = include) {
    const node = { options: { id, name, ...(group ? { group: true, config: [] } : {}) }, fiber: { state: 2, uid: 1 },
      parent: { tree: include.subtree, ctx: { fiber: { entry: parent } } } };
    rows.push(node);
    bundle.entries.push({ id, moduleHash: hash(name), parentId: parent === include ? '' : parent.options.id, group });
    if (parent !== include) parent.options.config.push(node.options);
    return node;
  }
  const sample = (context = identity) => installedPluginSampler(ctx, context, evidence);
  return { loader, include, rows, evidence, attempts, ctx, bundle, entry, sample, reads: () => reads };
}

for (const [source, input, ticket, startup] of [
  ['HOST_START_TICKET', identity, { startupId: 'host-start-1', ...identity }, 'host-start-1'],
  ['OBSERVER_SESSION', identity, null, 'unknown'],
  ['UNATTRIBUTED', {}, null, 'unknown'],
  ['UNATTRIBUTED', { generation: 'generation-1' }, null, 'unknown'],
]) test(`身份只报告已有来源：${source}/${startup}/${input.instance || 'missing'}`, () => {
  const result = pluginSessionIdentity(input, at, ticket);
  assert.equal(result.identitySource, source); assert.equal(result.startupId, startup); assert.equal(result.observedAt, at);
  assert.equal(result.generation, input.generation || ''); assert.equal(result.instance, input.instance || '');
});

test('宿主票据必须完整同会话，未知或路径值不能冒充 startupId', () => {
  for (const ticket of [{ startupId: 'host-start-1' }, { startupId: 'unknown', ...identity },
    { startupId: '/root/private', ...identity }, { startupId: 'host-start-1', ...identity, instance: 'other' },
    { startupId: 'host-start-1', ...identity, generation: 'other' }])
    assert.deepEqual(pluginSessionIdentity(identity, at, ticket), pluginSessionIdentity(identity, at));
  assert.equal(pluginSessionIdentity({ generation: '/private/gen', instance: 'i' }, at).identitySource, 'UNATTRIBUTED');
  assert.equal(pluginSessionIdentity(identity, Infinity).observedAt, 0);
});

test('fiber 0–5 和未知值输出独立 phase，旧 bundle 词表保持兼容', () => {
  const f = fixture(), bundle = f.bundle(), entry = f.entry(bundle), sample = f.sample();
  for (const [fiber, phase, state, diagnostic] of [
    [0, 'pending', 'pending', 'ENTRY_PENDING'], [1, 'loading', 'loading', 'ENTRY_LOADING'],
    [2, 'active', 'loaded', 'ENTRY_ACTIVE'], [3, 'failed', 'failed', 'ENTRY_FAILED'],
    [4, null, 'unknown', 'ENTRY_DISPOSED'], [5, 'unloading', 'unloading', 'ENTRY_UNLOADING'],
    [77, null, 'unknown', 'ENTRY_PHASE_UNKNOWN'],
  ]) {
    entry.fiber.state = fiber;
    const row = sample(at)[0];
    assert.equal(row.state, state); assert.equal(row.entries[0].phase, phase); assert.equal(row.entries[0].diagnostic, diagnostic);
    assert.equal(row.contentFingerprint, ''); assert.equal(row.contentAlgorithm, ''); assert.equal(row.contentReason, 'CONTENT_UNVERIFIED');
    assert.deepEqual(row.identity, pluginSessionIdentity(identity, at));
  }
  entry.fiber.state = 5; entry._initTask = {};
  assert.equal(sample(at)[0].entries[0].phase, 'unloading', '卸载中的 fiber 不被残留 init task 说成 loading');
});

test('缺席、处置完成、未绑定 fiber 和初始化失败不伪造 active', () => {
  const f = fixture(), bundle = f.bundle(), entry = f.entry(bundle), sample = f.sample();
  entry.fiber.uid = null;
  assert.equal(sample(at)[0].entries[0].diagnostic, 'ENTRY_FIBER_UNVERIFIED');
  delete entry.fiber;
  assert.equal(sample(at)[0].entries[0].diagnostic, 'ENTRY_NO_FIBER');
  f.attempts.set(entry, 'failed');
  assert.equal(sample(at)[0].entries[0].phase, 'failed');
  assert.equal(sample(at)[0].entries[0].diagnostic, 'ENTRY_INIT_FAILED');
  entry.options.disabled = true;
  assert.equal(sample(at)[0].state, 'unloaded'); assert.equal(sample(at)[0].entries[0].phase, null);
  delete entry.options.disabled; entry.fiber = { state: 4 }; f.attempts.set(entry, 'settled');
  assert.equal(sample(at)[0].state, 'unloaded'); assert.equal(sample(at)[0].entries[0].phase, null);
  f.rows.pop();
  assert.equal(sample(at)[0].state, 'unknown'); assert.equal(sample(at)[0].entries[0].diagnostic, 'ENTRY_ABSENT');
});

test('唯一 id 同时约束声明和真实 Loader，重复 bundle 不认领成功', () => {
  const f = fixture(), bundle = f.bundle(), entry = f.entry(bundle);
  f.rows.push({ ...entry });
  assert.equal(f.sample()(at)[0].entries[0].diagnostic, 'ENTRY_DUPLICATE');
  f.rows.pop();
  const other = f.bundle('fixture-other'); f.entry(other);
  assert(f.sample()(at).every(row => row.entries[0].diagnostic === 'ENTRY_DUPLICATE'));
  other.entries[0].id = 'other'; f.rows.at(-1).options.id = 'other';
  f.bundle('fixture-plugin');
  assert.equal(f.sample()(at)[0].state, 'unknown');
});

test('模块摘要、父组实际对象和 group 结构逐项匹配', () => {
  const f = fixture(), bundle = f.bundle(), group = f.entry(bundle, 'group', 'cordis:group', true);
  const child = f.entry(bundle, 'child', './private-child.js', false, group), sample = f.sample();
  assert.deepEqual(sample(at)[0].entries.map(row => row.phase), ['active', 'active']);
  assert.deepEqual(sample(at)[0].entries.map(row => [row.entryId, row.parentId, row.group]), [['group', '', true], ['child', 'group', false]]);
  child.options.name = './different.js';
  assert.equal(sample(at)[0].entries[1].diagnostic, 'ENTRY_MODULE_MISMATCH');
  child.options.name = './private-child.js';
  child.parent.ctx.fiber.entry = { ...group };
  assert.equal(sample(at)[0].entries[1].diagnostic, 'ENTRY_PARENT_MISMATCH');
  child.parent.ctx.fiber.entry = group;
  child.parent.tree = {};
  assert.equal(sample(at)[0].entries[1].diagnostic, 'ENTRY_ABSENT');
  child.parent.tree = f.include.subtree;
  child.options.group = true;
  assert.equal(sample(at)[0].entries[1].diagnostic, 'ENTRY_GROUP_MISMATCH');
  delete child.options.group;
  group.options.config.push({ id: 'unobserved', name: './secret.js' });
  assert.equal(sample(at)[0].entries[0].diagnostic, 'ENTRY_GROUP_CHANGED');
  assert.equal(sample(at)[0].entries[1].diagnostic, 'ENTRY_PARENT_MISMATCH');
});

test('来源 generation/instance/profile 不匹配时不读取 Loader', () => {
  const f = fixture(); f.entry(f.bundle());
  for (const key of ['generation', 'instance', 'profile']) {
    const result = f.sample({ ...identity, [key]: 'different' })(at);
    assert.equal(result[0].entries[0].phase, null);
    assert.equal(result[0].entries[0].diagnostic, 'SESSION_IDENTITY_UNVERIFIED');
  }
  assert.equal(f.reads(), 0);
  f.loader.entries = () => { throw new Error('private-loader-error /root/secret'); };
  assert.equal(f.sample()(at)[0].entries[0].diagnostic, 'LOADER_UNAVAILABLE');
});

test('采样不读磁盘、不访问 disabled getter、不运行表达式或插件', () => {
  const f = fixture(), bundle = f.bundle(), group = f.entry(bundle, 'group', 'cordis:group', true);
  const entry = f.entry(bundle, 'optional', './private-code.js', false, group);
  delete entry.fiber;
  let reads = 0;
  Object.defineProperty(entry, 'disabled', { get() { reads++; throw new Error('entry.disabled getter'); } });
  Object.defineProperty(entry.options, 'disabled', { get() { reads++; throw new Error('options.disabled getter'); } });
  const active = f.entry(bundle, 'active', './private-active.js');
  active.apply = () => { throw new Error('不能执行插件'); };
  const sample = f.sample(), originals = new Map();
  try {
    for (const key of ['readFileSync', 'readdirSync', 'statSync', 'lstatSync', 'openSync', 'realpathSync']) {
      originals.set(key, fs[key]); fs[key] = () => { throw new Error('心跳不能扫描磁盘'); };
    }
    for (let i = 0; i < 5; i++) {
      const row = sample(at)[0];
      assert.equal(row.state, 'loaded'); assert.equal(row.entries[1].diagnostic, 'ENTRY_CONDITION_UNOBSERVED');
      assert.equal(row.entries[1].phase, null); assert.equal(row.entries[2].phase, 'active');
    }
  } finally { for (const [key, original] of originals) fs[key] = original; }
  assert.equal(reads, 0);
});

test('metadata/Loader 数量超过预算就保留未知，不因截断认领唯一性', () => {
  const f = fixture(), bundle = f.bundle(); f.entry(bundle);
  for (let i = 1; i < 2049; i++) bundle.entries.push({ ...bundle.entries[0], id: 'entry-' + i });
  let row = f.sample()(at)[0];
  assert.equal(row.entries.length, 2048); assert.equal(row.state, 'unknown'); assert.equal(row.truncated, true);
  assert.equal(row.entries[0].diagnostic, 'METADATA_LIMIT'); assert.equal(f.reads(), 0);
  bundle.entries.length = 1;
  f.loader.entries = function* () { for (let i = 0; i < 8193; i++) yield f.rows[0]; };
  row = f.sample()(at)[0]; assert.equal(row.entries[0].diagnostic, 'LOADER_LIMIT');
  for (let i = 0; i < 501; i++) f.bundle('bundle-' + i);
  const rows = f.sample()(at); assert.equal(rows.length, 500); assert(rows.every(row => row.truncated && row.state === 'unknown'));
});

test('sidecar 只保留白名单字段，不借 moduleHash 或外来 fingerprint 声称部署内容', () => {
  const f = fixture(), bundle = f.bundle(); f.entry(bundle);
  const rows = f.sample()(at), row = rows[0];
  row.path = '/root/private'; row.error = 'private-error'; row.contentFingerprint = hash('private-source');
  row.contentAlgorithm = 'dependency-graph-sha256-v1'; row.contentReason = 'VERIFIED';
  row.entries[0].source = 'private-source'; row.entries[0].diagnostic = 'private-error /root/private';
  const frame = pluginStartupEvidence(rows, pluginSessionIdentity(identity, at), 'web');
  assert.equal(frame.schema, 1); assert.equal(frame.observedAt, at); assert.equal(frame.identitySource, 'OBSERVER_SESSION');
  assert.equal(frame.installedPlugins[0].contentFingerprint, ''); assert.equal(frame.installedPlugins[0].contentAlgorithm, '');
  assert.equal(frame.installedPlugins[0].entries[0].diagnostic, 'ENTRY_READ_FAILED');
  const json = JSON.stringify(frame);
  assert(!json.includes('private-') && !json.includes('/root/') && !json.includes('source'));
  assert.equal(frame.installedPlugins[0].entries[0].moduleHash, hash('./private-module.js'));
  assert.deepEqual(Object.keys(frame.installedPlugins[0].entries[0]), ['entryId', 'moduleHash', 'parentId', 'group', 'phase', 'diagnostic', 'managementReadOnly', 'managementReadOnlyReason']);
});

test('sidecar 不用当前身份覆盖旧帧的 phase 证据', () => {
  const f = fixture(); f.entry(f.bundle());
  const rows = f.sample()(at);
  for (const current of [pluginSessionIdentity({ ...identity, instance: 'new-instance' }, at),
    pluginSessionIdentity(identity, at + 1),
    pluginSessionIdentity(identity, at, { ...identity, startupId: 'host-start' })]) {
    const frame = pluginStartupEvidence(rows, current, 'web');
    assert.equal(frame.installedPlugins[0].state, 'unknown');
    assert.equal(frame.installedPlugins[0].entries[0].phase, null);
    assert.equal(frame.installedPlugins[0].entries[0].diagnostic, 'SESSION_IDENTITY_UNVERIFIED');
  }
  const wrongProfile = pluginStartupEvidence(rows, pluginSessionIdentity(identity, at), 'other');
  assert.equal(wrongProfile.installedPlugins[0].entries[0].phase, null);
  const missing = pluginSessionIdentity({}, at);
  rows[0].identity = missing;
  assert.equal(pluginStartupEvidence(rows, missing, 'web').installedPlugins[0].entries[0].phase, null);
});

test('sidecar 字节预算与全局 entry 预算有界，并显式标记截断', () => {
  const f = fixture(), bundle = f.bundle(); f.entry(bundle);
  const rows = f.sample()(at), template = rows[0];
  const expanded = Array.from({ length: 501 }, (_, i) => ({ ...template, name: 'bundle-' + i,
    entries: Array.from({ length: 20 }, (_, j) => ({ ...template.entries[0], entryId: 'e' + 'x'.repeat(254) + j % 10,
      parentId: 'p' + 'x'.repeat(254) + j % 10 })) }));
  const frame = pluginStartupEvidence(expanded, pluginSessionIdentity(identity, at), 'web');
  assert.equal(frame.truncated, true); assert(Buffer.byteLength(JSON.stringify(frame)) <= 128 * 1024);
  assert(frame.installedPlugins.length <= 500);
  assert(frame.installedPlugins.reduce((total, row) => total + row.entries.length, 0) <= 2048);
  const smaller = Array.from({ length: 150 }, (_, i) => ({ ...template, name: 'small-' + i,
    entries: Array.from({ length: 20 }, () => ({ ...template.entries[0] })) }));
  const entryFrame = pluginStartupEvidence(smaller, pluginSessionIdentity(identity, at), 'web');
  assert.equal(entryFrame.truncated, true);
  assert(entryFrame.installedPlugins.reduce((total, row) => total + row.entries.length, 0) <= 2048);
  assert(Buffer.byteLength(JSON.stringify(entryFrame)) <= 128 * 1024);
});

test('256 个动态插件加 entry 快照仍遵守旧 activity 256KiB，超限动态列表保留未知', () => {
  const f = fixture(); f.entry(f.bundle());
  const template = f.sample()(at)[0];
  const rows = Array.from({ length: 500 }, (_, i) => ({ ...template, name: 'bundle-' + i }));
  const agents = [{ id: 's'.repeat(128) }];
  const runner = { listPlugins: () => Array.from({ length: 256 }, () => ({
    pluginId: 'i'.repeat(256), name: 'n'.repeat(214), activeRun: {},
    latestRun: { status: 's'.repeat(40) }, currentPackageId: 'v'.repeat(128),
  })) };
  const dynamic = runtimePlugins(agents, runner);
  assert.equal(dynamic.length, 256);
  const projected = pluginActivityProjection(rows, pluginSessionIdentity(identity, at), 'web', true, dynamic);
  assert.equal(projected.activity.plugins, null, '过大摘要不能冒充确认空动态列表');
  assert.equal(projected.activity.pluginEvidenceTruncated, true); assert.equal(projected.evidence.truncated, true);
  assert.equal(projected.activity.at, at); assert.equal(projected.evidence.observedAt, at);
  assert.strictEqual(projected.activity.installedPlugins, projected.evidence.installedPlugins);
  assert(Buffer.byteLength(JSON.stringify(projected.activity)) <= 256 * 1024);
  assert(Buffer.byteLength(JSON.stringify(projected.evidence)) <= 128 * 1024);
  assert(Buffer.byteLength(JSON.stringify(projected.activity.installedPlugins)) <= 128 * 1024);
  const short = pluginActivityProjection([template], pluginSessionIdentity(identity, at), 'web', false, dynamic);
  assert.strictEqual(short.activity.plugins, dynamic, '预算内仍保留已观察到的256个动态摘要');
  assert.equal(short.activity.pluginEvidenceTruncated, false);
  const unicode = runtimePlugins(agents, { listPlugins: () => runner.listPlugins().map(row => ({ ...row, name: '界'.repeat(214) })) });
  const wide = pluginActivityProjection(rows, pluginSessionIdentity(identity, at), 'web', false, unicode);
  assert.equal(wide.activity.plugins, null); assert(Buffer.byteLength(JSON.stringify(wide.activity)) <= 256 * 1024);
  for (const value of [null, undefined, {}])
    assert.equal(pluginActivityProjection([template], pluginSessionIdentity(identity, at), 'web', true, value).activity.plugins, null);
  assert.deepEqual(pluginActivityProjection([template], pluginSessionIdentity(identity, at), 'web', true, []).activity.plugins, []);
});

test('management 服务缺席/撤回/失败不清空已观察的安装条目', () => {
  const f = fixture(), bundle = f.bundle(); f.entry(bundle);
  let manager;
  f.ctx.get = name => name === 'loader' ? f.loader : name === 'pluginManager' ? manager : undefined;
  const sample = f.sample();
  const project = () => pluginStartupEvidence(sample(at), pluginSessionIdentity(identity, at), 'web');
  assert.equal(project().management.state, 'unavailable'); assert.equal(project().installedPlugins.length, 1);
  manager = { managementBundles: new Set([bundle.name]) };
  assert.equal(project().management.state, 'available'); assert.equal(project().installedPlugins[0].managementReadOnly, true);
  manager = undefined;
  assert.equal(project().management.state, 'unavailable'); assert.equal(project().installedPlugins.length, 1);
  f.ctx.get = name => { if (name === 'pluginManager') throw Error('/root/private-error'); return f.loader; };
  assert.equal(project().management.state, 'unknown'); assert.equal(project().installedPlugins.length, 1);
  assert(!JSON.stringify(project()).includes('private-error'));
});

test('management 只读缓存Set与owner数据，不调用管理方法或getter', () => {
  const f = fixture(), bundle = f.bundle(), entry = f.entry(bundle); entry.id = 'include:entry';
  const fail = () => { throw Error('management must not execute'); };
  const manager = { managementBundles: new Set([bundle.name]), ownerEntryId: entry.id,
    listPlugins: fail, listBundles: fail, protectsManager: fail, listVersionExemptions: fail };
  manager.managementBundles.has = fail;
  f.ctx.get = name => name === 'loader' ? f.loader : manager;
  let row = f.sample()(at)[0];
  assert.equal(row.managementReadOnly, true); assert.equal(row.entries[0].managementReadOnly, true);
  manager.managementBundles.clear(); manager.ownerEntryId = 'different:entry';
  row = f.sample()(at)[0]; assert.equal(row.managementReadOnly, null); assert.equal(row.entries[0].managementReadOnly, null);
  Object.defineProperty(manager, 'managementBundles', { get: fail });
  Object.defineProperty(manager, 'ownerEntryId', { get: fail });
  Object.defineProperty(bundle, 'eligibility', { get: fail });
  row = f.sample()(at)[0]; assert.equal(row.managementReadOnlyReason, 'PROTECTION_UNVERIFIED');
  assert.equal(row.entries[0].managementReadOnlyReason, 'PROTECTION_UNVERIFIED');
});

test('eligibility 只接受同轮精确身份预检，失败未知且不能改Loader phase', () => {
  const f = fixture(), bundle = f.bundle(); f.entry(bundle);
  const base = { ...identity, name: bundle.name, version: bundle.version, observedAt: at - 100,
    runtimeVersion: '0.2.2-rc.1', runtimePlatform: process.platform, state: 'incompatible',
    reasonCode: 'PLUGIN_INCOMPATIBLE', evidence: 'PREFLIGHT', versionExempted: false };
  for (const state of ['compatible', 'unconstrained', 'incompatible', 'exempted', 'unsupported-platform']) {
    bundle.eligibility = { ...base, state };
    const row = f.sample()(at)[0]; assert.equal(row.eligibility.state, state); assert.equal(row.state, 'loaded');
    assert.equal(row.eligibility.observedAt, at - 100); assert.equal(row.contentFingerprint, '');
  }
  for (const bad of [{ version: '2.0.0' }, { name: 'other' }, { instance: 'other' }, { generation: 'other' },
    { profile: 'other' }, { observedAt: at + 1 }, { runtimePlatform: 'other' }, { state: 'check-failed' }]) {
    bundle.eligibility = { ...base, ...bad };
    const row = f.sample()(at)[0]; assert.equal(row.eligibility.state, 'unknown'); assert.equal(row.entries[0].phase, 'active');
  }
  bundle.eligibility = { ...base, state: 'unknown', evidence: 'UNAVAILABLE', reasonCode: 'PREFLIGHT_CHECK_FAILED' };
  assert.equal(f.sample()(at)[0].eligibility.reasonCode, 'PREFLIGHT_CHECK_FAILED');
});

test('patchChain不接受verified声明，coverage保留具体预算/不可归属理由', () => {
  const f = fixture(); f.entry(f.bundle());
  const project = () => pluginStartupEvidence(f.sample()(at), pluginSessionIdentity(identity, at), 'web');
  f.evidence.patchChain = { state: 'present-unverified', reasonCode: 'PATCH_PROVENANCE_UNVERIFIED' };
  assert.equal(project().patchChain.state, 'present-unverified');
  f.evidence.patchChain = { state: 'verified', reasonCode: 'MARKER_PRESENT', source: '/root/private' };
  assert.equal(project().patchChain.state, 'unavailable');
  f.rows.push({ ...f.rows[1] });
  assert(project().coverage.reasons.includes('ENTRY_UNADDRESSABLE'));
  f.loader.entries = function* () { for (let i = 0; i < 8193; i++) yield f.rows[0]; };
  assert(project().coverage.reasons.includes('LOADER_LIMIT'));
  assert(project().coverage.reasons.length <= 8);
});

test('integration 用同一次 Loader 采样写 activity 与 sidecar，销毁时停止采样', async () => {
  const f = fixture(); f.entry(f.bundle());
  const key = Symbol.for('dsha.installed-plugin-evidence'), oldEvidence = globalThis[key];
  const environment = ['DSHA_WEB_GENERATION', 'DSH_TASK_INSTANCE', 'DSHA_STARTUP_PROFILE'];
  const oldEnvironment = environment.map(name => process.env[name]);
  const originals = { writeFileSync: fs.writeFileSync, renameSync: fs.renameSync, unlinkSync: fs.unlinkSync,
    setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval };
  const files = new Map(), renamed = new Map(), cleanups = [];
  let tick, stopped = 0;
  const sampling = { ...f.ctx, agents: { list: () => [] }, sessions: { list: () => [] }, sessionProjections: {},
    inject() {}, effect(effect) { cleanups.push(effect()); } };
  const ctx = { inject(dependencies, callback) { if (dependencies.includes('agents')) callback(sampling); } };
  try {
    globalThis[key] = f.evidence;
    process.env.DSHA_WEB_GENERATION = identity.generation; process.env.DSH_TASK_INSTANCE = identity.instance;
    process.env.DSHA_STARTUP_PROFILE = 'web';
    fs.writeFileSync = (file, value, options) => { assert.equal(options.mode, 0o600); files.set(file, value); };
    fs.renameSync = (source, target) => { renamed.set(target, files.get(source)); files.delete(source); };
    fs.unlinkSync = file => { files.delete(file); };
    syncBuiltinESMExports();
    globalThis.setInterval = (callback, milliseconds) => { assert.equal(milliseconds, 2000); tick = callback; return { unref() {} }; };
    globalThis.clearInterval = () => { stopped++; };
    integration(ctx); await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.reads(), 1, 'activity 与 sidecar 不能各跑一遍 Loader');
    const activity = JSON.parse(renamed.get('/root/.dsha-web-activity.json'));
    const sidecar = JSON.parse(renamed.get('/root/.dsha-plugin-startup-evidence.json'));
    assert.deepEqual(activity.installedPlugins, sidecar.installedPlugins); assert.equal(activity.at, sidecar.observedAt);
    assert.equal(activity.pluginEvidenceTruncated, sidecar.truncated);
    assert(Buffer.byteLength(renamed.get('/root/.dsha-web-activity.json')) <= 256 * 1024);
    assert(Buffer.byteLength(renamed.get('/root/.dsha-plugin-startup-evidence.json')) <= 128 * 1024);
    assert.equal(sidecar.startupId, 'unknown'); assert.equal(sidecar.identitySource, 'OBSERVER_SESSION');
    assert.equal(sidecar.installedPlugins[0].entries[0].phase, 'active');
    for (const cleanup of cleanups) cleanup();
    await tick(); assert.equal(f.reads(), 1); assert.equal(stopped, 1);
  } finally {
    for (const name of ['writeFileSync', 'renameSync', 'unlinkSync']) fs[name] = originals[name];
    syncBuiltinESMExports(); globalThis.setInterval = originals.setInterval; globalThis.clearInterval = originals.clearInterval;
    if (oldEvidence === undefined) delete globalThis[key]; else globalThis[key] = oldEvidence;
    environment.forEach((name, index) => { if (oldEnvironment[index] === undefined) delete process.env[name]; else process.env[name] = oldEnvironment[index]; });
  }
});

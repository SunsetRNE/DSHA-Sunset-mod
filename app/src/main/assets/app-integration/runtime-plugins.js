import { createHash } from 'node:crypto';

const evidenceKey = Symbol.for('dsha.installed-plugin-evidence');
// 这是模块名称的摘要，不是插件源码或部署内容的摘要。
const moduleHash = name => createHash('sha256').update(name).digest('hex');
const MAX_BUNDLES = 500, MAX_ENTRIES = 2048, MAX_LOADER_ENTRIES = 8192;
const MAX_FRAME_BYTES = 128 * 1024, MAX_ACTIVITY_BYTES = 256 * 1024;
const safeId = value => typeof value === 'string' && /^[A-Za-z0-9_$@][A-Za-z0-9_.$:@-]{0,255}$/.test(value) ? value : '';
const safeSessionId = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$/.test(value) ? value : '';
const safeProfile = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value) ? value : '';
const safeVersion = value => typeof value === 'string' && value.length <= 128
  && /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/.test(value) ? value : '';
const safeName = value => typeof value === 'string' && value.length <= 214
  && /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/.test(value) ? value : '';
const safeHash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value) ? value : '';
const phases = new Set(['pending', 'loading', 'unloading', 'active', 'failed']);
const legacyStates = new Set(['unknown', 'unloaded', 'pending', 'loading', 'unloading', 'loaded', 'failed']);
const diagnostics = new Set(['SESSION_IDENTITY_UNVERIFIED', 'METADATA_LIMIT', 'BUNDLE_IDENTITY_INVALID',
  'BUNDLE_AMBIGUOUS', 'LOADER_UNAVAILABLE', 'LOADER_LIMIT', 'ENTRY_IDENTITY_INVALID', 'ENTRY_ABSENT',
  'ENTRY_DUPLICATE', 'ENTRY_MODULE_MISMATCH', 'ENTRY_PARENT_MISMATCH', 'ENTRY_GROUP_MISMATCH',
  'ENTRY_GROUP_CHANGED', 'ENTRY_LOADING', 'ENTRY_PENDING', 'ENTRY_ACTIVE', 'ENTRY_UNLOADING',
  'ENTRY_DISABLED', 'ENTRY_CONDITION_UNOBSERVED', 'ENTRY_NO_FIBER', 'ENTRY_DISPOSED', 'ENTRY_INIT_FAILED',
  'ENTRY_FAILED', 'ENTRY_PHASE_UNKNOWN', 'ENTRY_FIBER_UNVERIFIED', 'ENTRY_READ_FAILED']);

const ownData = (object, key) => {
  if (!object || typeof object !== 'object') return undefined;
  const field = Object.getOwnPropertyDescriptor(object, key);
  return field && Object.hasOwn(field, 'value') ? field.value : undefined;
};
const coverageCodes = new Set(['METADATA_UNAVAILABLE', 'METADATA_LIMIT', 'LOADER_UNAVAILABLE', 'LOADER_LIMIT',
  'ENTRY_UNADDRESSABLE', 'FRAME_BYTE_LIMIT', 'DYNAMIC_BYTE_LIMIT', 'SESSION_IDENTITY_UNVERIFIED']);
const eligibilityCodes = { compatible: 'PREFLIGHT_COMPATIBLE', unconstrained: 'PREFLIGHT_UNCONSTRAINED',
  exempted: 'PREFLIGHT_EXEMPTED', incompatible: 'PLUGIN_INCOMPATIBLE', 'unsupported-platform': 'PLUGIN_UNSUPPORTED_PLATFORM' };
function managementSnapshot(ctx) {
  let manager;
  try {
    manager = ctx.get('pluginManager');
    if (manager === undefined || manager === null) return { management: {
      state: 'unavailable', reasonCode: 'MANAGEMENT_SERVICE_ABSENT', evidence: 'SERVICE_PRESENCE' } };
    if (typeof manager !== 'object') throw new Error();
    return { management: { state: 'available', reasonCode: 'MANAGEMENT_AVAILABLE', evidence: 'SERVICE_PRESENCE' },
      protectedBundles: ownData(manager, 'managementBundles'), ownerEntryId: ownData(manager, 'ownerEntryId') };
  } catch { return { management: { state: 'unknown', reasonCode: 'MANAGEMENT_OBSERVATION_FAILED', evidence: 'SERVICE_PRESENCE' } }; }
}
function managementReadOnly(snapshot, name, actualEntryId) {
  let protectedItem = false;
  if (snapshot.management.state === 'available') {
    try { protectedItem = name !== undefined && Set.prototype.has.call(snapshot.protectedBundles, name); } catch {}
    if (actualEntryId !== undefined && typeof snapshot.ownerEntryId === 'string' && actualEntryId === snapshot.ownerEntryId)
      protectedItem = true;
  }
  return { managementReadOnly: protectedItem ? true : null,
    managementReadOnlyReason: protectedItem ? 'MANAGEMENT_REQUIRED'
      : snapshot.management.state === 'unavailable' ? 'MANAGEMENT_UNAVAILABLE' : 'PROTECTION_UNVERIFIED' };
}
function projectReadOnly(row, sameFrame) {
  const reason = ownData(row, 'managementReadOnlyReason');
  const protectedItem = sameFrame && ownData(row, 'managementReadOnly') === true && reason === 'MANAGEMENT_REQUIRED';
  return { managementReadOnly: protectedItem ? true : null, managementReadOnlyReason: protectedItem
    ? 'MANAGEMENT_REQUIRED' : sameFrame && reason === 'MANAGEMENT_UNAVAILABLE' ? reason : 'PROTECTION_UNVERIFIED' };
}
function projectEligibility(value, bundle, identity, profile) {
  const read = key => ownData(value, key);
  const result = { state: 'unknown', reasonCode: 'PREFLIGHT_UNAVAILABLE', evidence: 'UNAVAILABLE',
    name: safeName(bundle.name), version: safeVersion(bundle.version), profile: safeProfile(profile),
    generation: identity.generation, instance: identity.instance, observedAt: 0,
    runtimeVersion: '', runtimePlatform: '', versionExempted: false };
  if (!value) return result;
  const at = read('observedAt');
  if (!identity.generation || !identity.instance || read('generation') !== identity.generation || read('instance') !== identity.instance
      || read('name') !== bundle.name || read('version') !== bundle.version || read('profile') !== profile
      || !Number.isSafeInteger(at) || at <= 0 || at > identity.observedAt)
    return { ...result, reasonCode: 'PREFLIGHT_IDENTITY_MISMATCH' };
  result.observedAt = at;
  if (read('evidence') !== 'PREFLIGHT' || !Object.hasOwn(eligibilityCodes, read('state'))
      || !safeVersion(read('runtimeVersion')) || read('runtimePlatform') !== process.platform) {
    const reasonCode = read('reasonCode');
    return { ...result, reasonCode: ['PREFLIGHT_UNAVAILABLE', 'PREFLIGHT_CHECK_FAILED', 'PREFLIGHT_IDENTITY_MISMATCH'].includes(reasonCode)
      ? reasonCode : 'PREFLIGHT_CHECK_FAILED' };
  }
  return { ...result, state: read('state'), reasonCode: eligibilityCodes[read('state')], evidence: 'PREFLIGHT',
    runtimeVersion: read('runtimeVersion'), runtimePlatform: read('runtimePlatform'), versionExempted: read('versionExempted') === true };
}
function projectPatchChain(value) {
  const state = ownData(value, 'state'), reasonCode = ownData(value, 'reasonCode');
  const reasons = { 'present-unverified': ['PATCH_PROVENANCE_UNVERIFIED'],
    unavailable: ['PATCH_REGISTRY_UNAVAILABLE', 'PATCH_REGISTRY_INVALID', 'PATCH_RUNTIME_UNAVAILABLE'],
    mismatch: ['PATCH_VERSION_MISMATCH', 'PATCH_RECIPE_MISSING'] };
  return Object.hasOwn(reasons, state) && reasons[state].includes(reasonCode)
    ? { state, reasonCode, evidence: 'STARTUP_METADATA' }
    : { state: 'unavailable', reasonCode: 'PATCH_REGISTRY_UNAVAILABLE', evidence: 'STARTUP_METADATA' };
}
function limitCoverage(coverage, reason) {
  coverage.limited = true;
  if (coverageCodes.has(reason) && !coverage.reasons.includes(reason) && coverage.reasons.length < 8) coverage.reasons.push(reason);
}

/** 宿主票据只能由调用方显式提供；不猜测环境变量，也不把会话 ID 变造成 startupId。 */
export function pluginSessionIdentity({ generation, instance } = {}, observedAt = Date.now(), hostStartTicket = null) {
  generation = safeSessionId(generation); instance = safeSessionId(instance);
  const complete = Boolean(generation && instance);
  const startupId = safeSessionId(hostStartTicket?.startupId);
  const host = complete && startupId && startupId !== 'unknown'
    && hostStartTicket.generation === generation && hostStartTicket.instance === instance;
  return { identitySource: host ? 'HOST_START_TICKET' : complete ? 'OBSERVER_SESSION' : 'UNATTRIBUTED',
    startupId: host ? startupId : 'unknown', generation, instance,
    observedAt: Number.isSafeInteger(observedAt) && observedAt > 0 ? observedAt : 0 };
}

// 不调用 options.disabled 的访问器，更不能读取会重新求值 !!js 的 entry.disabled。
function disabledOption(entry) {
  const field = Object.getOwnPropertyDescriptor(entry.options || {}, 'disabled');
  return !field ? null : Object.hasOwn(field, 'value') ? field.value : { opaque: true };
}
function literalDisabled(entry) {
  if (entry.options?.group) return false;
  for (let depth = 0; entry && depth < 32; depth++, entry = entry.parent?.ctx?.fiber?.entry) {
    const value = disabledOption(entry);
    if (value != null && typeof value === 'object') return undefined;
    if (value) return true;
  }
  return entry ? undefined : false;
}

/** 条件禁用的可选子项没有 init，保留未知 entry，但不改变旧 bundle 聚合规则。 */
function disabledWithoutInit(entry, evidence) {
  if (disabledOption(entry) == null) return false;
  if (entry.fiber && entry.fiber.state !== 4) return false;
  return !evidence.initState?.(entry);
}

function entryState(entry, evidence) {
  const attempt = evidence.initState?.(entry), fiber = entry.fiber;
  if (fiber?.state === 5) return { phase: 'unloading', diagnostic: 'ENTRY_UNLOADING', state: 'unloading' };
  if (entry._initTask || attempt === 'loading') return { phase: 'loading', diagnostic: 'ENTRY_LOADING', state: 'loading' };
  if ((!fiber || fiber.state === 4) && literalDisabled(entry) === true)
    return { phase: null, diagnostic: 'ENTRY_DISABLED', state: 'unloaded' };
  if (attempt === 'failed') return { phase: 'failed', diagnostic: 'ENTRY_INIT_FAILED', state: 'failed' };
  if (!fiber) return { phase: null, diagnostic: 'ENTRY_NO_FIBER', state: 'unknown' };
  // 与锁定 Cordis/app-boot 的 fiber 值一致；处置完成或缺少证据时 phase 为 null。
  switch (fiber.state) {
    case 0: return { phase: 'pending', diagnostic: 'ENTRY_PENDING', state: 'pending' };
    case 1: return { phase: 'loading', diagnostic: 'ENTRY_LOADING', state: 'loading' };
    case 2: return fiber.uid === null
      ? { phase: null, diagnostic: 'ENTRY_FIBER_UNVERIFIED', state: 'unknown' }
      : { phase: 'active', diagnostic: 'ENTRY_ACTIVE', state: 'loaded' };
    case 3: return { phase: 'failed', diagnostic: 'ENTRY_FAILED', state: 'failed' };
    case 4: return { phase: null, diagnostic: 'ENTRY_DISPOSED', state: attempt === 'settled' ? 'unloaded' : 'unknown' };
    default: return { phase: null, diagnostic: 'ENTRY_PHASE_UNKNOWN', state: 'unknown' };
  }
}

/**
 * 启动目录只在 observer 初始化时读取；采样只枚举当前 Loader，不读取磁盘或执行入口。
 * 元数据和真实 entry 必须同代、同 profile、唯一 id+module+父组；缺席不推断 unloaded。
 * 这些 phase 是 Loader 观察事实；没有部署内容证明时不得宣称当前安装内容已经运行。
 */
export function installedPluginSampler(ctx, { generation, instance, profile, hostStartTicket }, evidence = globalThis[evidenceKey]) {
  const source = Array.isArray(evidence?.bundles) ? evidence.bundles : [];
  const bundles = [], names = new Map(), ids = new Map(), declared = new Map();
  let metadataLimited = evidence?.truncated === true || source.length > MAX_BUNDLES, count = 0;
  for (const raw of source.slice(0, MAX_BUNDLES)) {
    const entries = Array.isArray(ownData(raw, 'entries')) ? ownData(raw, 'entries') : [];
    const selected = entries.slice(0, Math.max(0, MAX_ENTRIES - count));
    if (selected.length !== entries.length) metadataLimited = true;
    count += selected.length;
    const bundle = { name: ownData(raw, 'name'), version: ownData(raw, 'version'), profile: ownData(raw, 'profile'),
      ambiguous: ownData(raw, 'ambiguous'), eligibility: ownData(raw, 'eligibility'), entries: selected };
    bundles.push(bundle);
    names.set(bundle.name, (names.get(bundle.name) || 0) + 1);
    for (const entry of selected) {
      ids.set(entry.id, (ids.get(entry.id) || 0) + 1); declared.set(entry.id, entry);
    }
  }
  const known = [...new Map(bundles.map(bundle => [bundle.name, bundle])).values()];
  const identityMatches = safeSessionId(generation) && safeSessionId(instance) && safeProfile(profile)
    && evidence?.generation === generation && evidence?.instance === instance && evidence?.profile === profile;
  return (observedAt = Date.now()) => {
    const identity = pluginSessionIdentity({ generation, instance }, observedAt, hostStartTicket);
    const management = managementSnapshot(ctx);
    const coverage = { limited: false, reasons: [] };
    if (!Array.isArray(evidence?.bundles)) limitCoverage(coverage, 'METADATA_UNAVAILABLE');
    if (metadataLimited) limitCoverage(coverage, 'METADATA_LIMIT');
    if (!identityMatches) limitCoverage(coverage, 'SESSION_IDENTITY_UNVERIFIED');
    let loader, entries, tree, include, loaderDiagnostic = 'LOADER_UNAVAILABLE';
    try {
      if (identityMatches && !metadataLimited) {
        loader = ctx.get('loader');
        if (typeof loader?.entries === 'function') {
          entries = [];
          for (const entry of loader.entries()) {
            if (entries.length >= MAX_LOADER_ENTRIES) { loaderDiagnostic = 'LOADER_LIMIT'; throw new Error(); }
            entries.push(entry);
          }
          const roots = entries.filter(entry => entry.options?.id === 'include'
            && entry.options?.name === 'cordis:include' && entry.parent === loader.root);
          if (roots.length === 1) { include = roots[0]; tree = include.subtree; }
        }
      }
    } catch { tree = undefined; }
    if (!tree && identityMatches && !metadataLimited) limitCoverage(coverage, loaderDiagnostic);
    const byId = new Map();
    if (tree) for (const entry of entries) {
      if (entry.parent?.tree !== tree) continue;
      const list = byId.get(entry.options?.id) || [];
      list.push(entry); byId.set(entry.options?.id, list);
    }
    const matched = new Map();
    function match(expected, depth = 0) {
      if (matched.has(expected)) return matched.get(expected);
      const fail = diagnostic => ({ diagnostic });
      if (depth > 32 || !safeId(expected.id) || !safeHash(expected.moduleHash)
          || (expected.parentId !== '' && !safeId(expected.parentId)) || typeof expected.group !== 'boolean')
        return fail('ENTRY_IDENTITY_INVALID');
      const matches = byId.get(expected.id);
      if (ids.get(expected.id) !== 1 || matches?.length > 1) return fail('ENTRY_DUPLICATE');
      if (!matches?.length) return fail('ENTRY_ABSENT');
      const entry = matches[0], parent = entry.parent?.ctx?.fiber?.entry;
      if (typeof entry.options?.name !== 'string' || entry.options.name.length > 4096
          || moduleHash(entry.options.name) !== expected.moduleHash)
        return fail('ENTRY_MODULE_MISMATCH');
      if (expected.parentId) {
        const declaration = declared.get(expected.parentId);
        if (!declaration || !declaration.group || declaration === expected
            || match(declaration, depth + 1).entry !== parent) return fail('ENTRY_PARENT_MISMATCH');
      } else if (parent !== include) return fail('ENTRY_PARENT_MISMATCH');
      const group = entry.options.group === true || ['cordis:group', '@deepseek-ai/cordis-plugin-group'].includes(entry.options.name);
      if (group !== expected.group) return fail('ENTRY_GROUP_MISMATCH');
      if (group && (!Array.isArray(entry.options.config) || entry.options.config.length > MAX_ENTRIES
          || entry.options.config.some(child => {
        const declaration = declared.get(child?.id);
        return !declaration || ids.get(child.id) !== 1 || declaration.parentId !== expected.id
          || typeof child.name !== 'string' || child.name.length > 4096 || moduleHash(child.name) !== declaration.moduleHash;
      }))) return fail('ENTRY_GROUP_CHANGED');
      const result = { entry }; matched.set(expected, result); return result;
    }
    const result = known.map(bundle => {
      const bundleDiagnostic = metadataLimited ? 'METADATA_LIMIT' : !identityMatches ? 'SESSION_IDENTITY_UNVERIFIED'
        : !tree ? loaderDiagnostic : bundle.profile !== profile || !safeName(bundle.name) || !safeVersion(bundle.version)
          ? 'BUNDLE_IDENTITY_INVALID' : names.get(bundle.name) !== 1 || bundle.ambiguous ? 'BUNDLE_AMBIGUOUS' : '';
      if (bundleDiagnostic === 'BUNDLE_AMBIGUOUS' || bundleDiagnostic === 'BUNDLE_IDENTITY_INVALID')
        limitCoverage(coverage, 'ENTRY_UNADDRESSABLE');
      const states = [];
      const outputEntries = bundle.entries.map(expected => {
        const row = { entryId: safeId(expected.id), moduleHash: safeHash(expected.moduleHash),
          parentId: safeId(expected.parentId), group: expected.group === true,
          phase: null, diagnostic: bundleDiagnostic || 'ENTRY_READ_FAILED', ...managementReadOnly(management) };
        if (bundleDiagnostic) { states.push('unknown'); return row; }
        try {
          const actual = match(expected);
          if (!actual.entry) {
            row.diagnostic = actual.diagnostic; states.push('unknown'); limitCoverage(coverage, 'ENTRY_UNADDRESSABLE'); return row;
          }
          Object.assign(row, managementReadOnly(management, undefined, ownData(actual.entry, 'id')));
          if (expected.parentId && disabledWithoutInit(actual.entry, evidence)) {
            row.diagnostic = 'ENTRY_CONDITION_UNOBSERVED'; return row;
          }
          const status = entryState(actual.entry, evidence);
          row.phase = status.phase; row.diagnostic = status.diagnostic; states.push(status.state);
        } catch { states.push('unknown'); }
        return row;
      });
      const state = bundleDiagnostic || !bundle.entries.length ? 'unknown'
        : states.length ? ['unknown', 'failed', 'unloaded', 'pending', 'unloading', 'loading', 'loaded'].find(value => states.includes(value))
          : 'unloaded';
      return { name: safeName(bundle.name), version: safeVersion(bundle.version), profile: safeProfile(bundle.profile), state,
        entries: outputEntries, identity, contentFingerprint: '', contentAlgorithm: '', contentReason: 'CONTENT_UNVERIFIED',
        truncated: metadataLimited, ...managementReadOnly(management, bundle.name),
        eligibility: projectEligibility(ownData(bundle, 'eligibility'), bundle, identity, profile) };
    });
    Object.defineProperty(result, 'metadata', { value: { identity, profile, management: management.management,
      patchChain: projectPatchChain(ownData(evidence, 'patchChain')), coverage } });
    return result;
  };
}

/** 同一次采样的有界投影；活动文件与 sidecar 共用它，绝不再次枚举 Loader。 */
export function pluginStartupEvidence(installedPlugins, identity, profile) {
  const session = pluginSessionIdentity(identity, identity?.observedAt,
    identity?.identitySource === 'HOST_START_TICKET' ? identity : null);
  const metadata = ownData(installedPlugins, 'metadata');
  const metadataIdentity = ownData(metadata, 'identity');
  const metadataMatches = ownData(metadata, 'profile') === profile && metadataIdentity
    && ['identitySource', 'startupId', 'generation', 'instance', 'observedAt'].every(key => ownData(metadataIdentity, key) === session[key]);
  const observedManagement = metadataMatches ? ownData(metadata, 'management') : null;
  const managementState = ownData(observedManagement, 'state');
  const management = managementState === 'available' ? { state: 'available', reasonCode: 'MANAGEMENT_AVAILABLE', evidence: 'SERVICE_PRESENCE' }
    : managementState === 'unavailable' ? { state: 'unavailable', reasonCode: 'MANAGEMENT_SERVICE_ABSENT', evidence: 'SERVICE_PRESENCE' }
      : { state: 'unknown', reasonCode: 'MANAGEMENT_OBSERVATION_FAILED', evidence: 'SERVICE_PRESENCE' };
  const observedCoverage = metadataMatches ? ownData(metadata, 'coverage') : null;
  const reasons = ownData(observedCoverage, 'reasons');
  const coverage = { limited: ownData(observedCoverage, 'limited') !== false,
    reasons: Array.isArray(reasons) ? [...new Set(reasons.filter(code => coverageCodes.has(code)))].slice(0, 8) : ['METADATA_UNAVAILABLE'] };
  const frame = { schema: 1, profile: safeProfile(profile), ...session, management,
    patchChain: projectPatchChain(metadataMatches ? ownData(metadata, 'patchChain') : null), coverage,
    installedPlugins: [], truncated: false };
  const source = Array.isArray(installedPlugins) ? installedPlugins : [];
  let remaining = MAX_ENTRIES, size = Buffer.byteLength(JSON.stringify(frame));
  if (source.length > MAX_BUNDLES) { frame.truncated = true; limitCoverage(coverage, 'METADATA_LIMIT'); }
  for (const bundle of source.slice(0, MAX_BUNDLES)) {
    const name = safeName(bundle.name);
    if (!name) { frame.truncated = true; limitCoverage(coverage, 'ENTRY_UNADDRESSABLE'); continue; }
    const sourceEntries = Array.isArray(bundle.entries) ? bundle.entries : [];
    const sameFrame = session.identitySource !== 'UNATTRIBUTED' && session.observedAt > 0
      && bundle.profile === frame.profile && bundle.identity
      && ['identitySource', 'startupId', 'generation', 'instance', 'observedAt']
        .every(key => bundle.identity[key] === session[key]);
    const entries = sourceEntries.slice(0, remaining).map(row => ({
      entryId: safeId(row.entryId), moduleHash: safeHash(row.moduleHash), parentId: safeId(row.parentId), group: row.group === true,
      phase: sameFrame && phases.has(row.phase) ? row.phase : null,
      diagnostic: !sameFrame ? 'SESSION_IDENTITY_UNVERIFIED'
        : diagnostics.has(row.diagnostic) ? row.diagnostic : 'ENTRY_READ_FAILED',
      ...projectReadOnly(row, sameFrame),
    }));
    remaining -= entries.length;
    const truncated = bundle.truncated === true || entries.length !== sourceEntries.length;
    const row = { name, version: safeVersion(bundle.version), profile: safeProfile(bundle.profile),
      state: sameFrame && !truncated && legacyStates.has(bundle.state) ? bundle.state : 'unknown', entries, identity: session,
      contentFingerprint: '', contentAlgorithm: '', contentReason: 'CONTENT_UNVERIFIED', truncated,
      ...projectReadOnly(bundle, sameFrame),
      eligibility: projectEligibility(ownData(bundle, 'eligibility'), bundle, session, profile) };
    if (truncated) { frame.truncated = true; limitCoverage(coverage, 'METADATA_LIMIT'); }
    const bytes = Buffer.byteLength(JSON.stringify(row)) + 1;
    if (size + bytes + 512 > MAX_FRAME_BYTES) { frame.truncated = true; limitCoverage(coverage, 'FRAME_BYTE_LIMIT'); break; }
    frame.installedPlugins.push(row); size += bytes;
  }
  return frame;
}

/** 保持已有 activity 读取预算；动态摘要过大时是未知，不伪造确认空列表。 */
export function pluginActivityProjection(installedPlugins, identity, profile, idle, plugins) {
  const evidence = pluginStartupEvidence(installedPlugins, identity, profile);
  const activity = { generation: evidence.generation, instance: evidence.instance, at: evidence.observedAt,
    idle: idle === true, installedPlugins: evidence.installedPlugins,
    management: evidence.management, patchChain: evidence.patchChain, coverage: evidence.coverage,
    pluginEvidenceTruncated: evidence.truncated, plugins: Array.isArray(plugins) ? plugins : null };
  const activitySize = () => Buffer.byteLength(JSON.stringify(activity));
  if (activitySize() > MAX_ACTIVITY_BYTES) {
    activity.plugins = null; limitCoverage(evidence.coverage, 'DYNAMIC_BYTE_LIMIT');
  }
  while (activitySize() > MAX_ACTIVITY_BYTES && activity.installedPlugins.length) {
    activity.installedPlugins.pop();
    evidence.truncated = activity.pluginEvidenceTruncated = true;
    limitCoverage(evidence.coverage, 'FRAME_BYTE_LIMIT');
  }
  return { activity, evidence };
}

/** 只取上游公开的无源码摘要；会话临时插件不能冒充 npm 持久安装。 */
export function runtimePlugins(agents, runner) {
  if (!runner || typeof runner.listPlugins !== 'function') return null;
  const items = [];
  try {
    for (const agent of agents) {
      for (const row of runner.listPlugins(agent)) {
        if (items.length >= 256) return items;
        if (typeof row.pluginId !== 'string' || typeof row.name !== 'string') continue;
        items.push({ id: row.pluginId.slice(0, 256), name: row.name.slice(0, 214),
          sessionId: String(agent.id).slice(0, 128), active: Boolean(row.activeRun),
          state: typeof row.latestRun?.status === 'string' ? row.latestRun.status.slice(0, 40) : '',
          version: String(row.currentPackageId || row.nextPackageId || '').slice(0, 128) });
      }
    }
  } catch { return null; }
  return items;
}

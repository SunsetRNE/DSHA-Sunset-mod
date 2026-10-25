import { createHash } from 'node:crypto';

const evidenceKey = Symbol.for('dsha.installed-plugin-evidence');
const moduleHash = name => createHash('sha256').update(name).digest('hex');

/** 仅检查字面禁用值；entry.disabled 会再次执行 !!js，采样不能读取该 getter。 */
function literalDisabled(entry) {
  if (entry.options?.group) return false;
  for (let depth = 0; entry && depth < 32; depth++, entry = entry.parent?.ctx?.fiber?.entry) {
    const value = entry.options?.disabled;
    if (value != null && typeof value === 'object') return undefined;
    if (value) return true;
  }
  return entry ? undefined : false;
}

function entryState(entry, evidence) {
  const attempt = evidence.initState?.(entry);
  if (entry._initTask || attempt === 'loading') return 'loading';
  const fiber = entry.fiber;
  if ((!fiber || fiber.state === 4) && literalDisabled(entry) === true) return 'unloaded';
  if (attempt === 'failed') return 'failed';
  if (!fiber) return 'unknown';
  // 与锁定 Cordis/app-boot 的状态值一致；5 是正在卸载，尚未完成处置。
  switch (fiber.state) {
    case 0: return 'pending';
    case 1: case 5: return 'loading';
    case 2: return fiber.uid === null ? 'unknown' : 'loaded';
    case 3: return 'failed';
    case 4: return attempt === 'settled' || literalDisabled(entry) === true ? 'unloaded' : 'unknown';
    default: return 'unknown';
  }
}

/**
 * 启动目录只在 observer 初始化时读取；此采样器每次只枚举当前 Loader。
 * 元数据和真实 entry 必须同代、同 profile、唯一 id+module+父组；缺席绝不推断 unloaded。
 */
export function installedPluginSampler(ctx, { generation, instance, profile }, evidence = globalThis[evidenceKey]) {
  const bundles = Array.isArray(evidence?.bundles) ? evidence.bundles.slice(0, 500) : [];
  const names = new Map(), ids = new Map(), declared = new Map();
  for (const bundle of bundles) {
    names.set(bundle.name, (names.get(bundle.name) || 0) + 1);
    for (const entry of bundle.entries || []) {
      ids.set(entry.id, (ids.get(entry.id) || 0) + 1); declared.set(entry.id, entry);
    }
  }
  const known = [...new Map(bundles.map(bundle => [bundle.name, bundle])).values()];
  const identityMatches = generation && instance && evidence?.generation === generation
    && evidence?.instance === instance && evidence?.profile === profile;
  return () => {
    let loader, entries, tree, include;
    try {
      if (identityMatches) {
        loader = ctx.get('loader');
        if (typeof loader?.entries === 'function') {
          entries = [];
          for (const entry of loader.entries()) {
            if (entries.length >= 8192) throw new Error('Loader entry limit');
            entries.push(entry);
          }
          const roots = entries.filter(entry => entry.options?.id === 'include'
            && entry.options?.name === 'cordis:include' && entry.parent === loader.root);
          if (roots.length === 1) { include = roots[0]; tree = include.subtree; }
        }
      }
    } catch { tree = undefined; }
    const byId = new Map();
    if (tree) for (const entry of entries) {
      if (entry.parent?.tree !== tree) continue;
      const list = byId.get(entry.options?.id) || [];
      list.push(entry); byId.set(entry.options?.id, list);
    }
    return known.map(bundle => {
      let state = 'unknown';
      try {
        if (tree && bundle.profile === profile && names.get(bundle.name) === 1
            && !bundle.ambiguous && bundle.entries.length) {
          const states = bundle.entries.map(expected => {
            const matches = byId.get(expected.id);
            if (ids.get(expected.id) !== 1 || matches?.length !== 1) return 'unknown';
            const entry = matches[0], parent = entry.parent.ctx.fiber.entry;
            if (typeof entry.options.name !== 'string' || moduleHash(entry.options.name) !== expected.moduleHash
                || (expected.parentId ? parent?.options?.id !== expected.parentId : parent !== include)) return 'unknown';
            const group = entry.options.group === true || ['cordis:group', '@deepseek-ai/cordis-plugin-group'].includes(entry.options.name);
            if (group !== expected.group) return 'unknown';
            if (group && (!Array.isArray(entry.options.config) || entry.options.config.some(child => {
              const declaration = declared.get(child?.id);
              return !declaration || ids.get(child.id) !== 1 || declaration.parentId !== expected.id
                || typeof child.name !== 'string' || moduleHash(child.name) !== declaration.moduleHash;
            }))) return 'unknown';
            return entryState(entry, evidence);
          });
          state = ['unknown', 'failed', 'unloaded', 'pending', 'loading', 'loaded'].find(value => states.includes(value));
        }
      } catch { /* 单个不兼容 entry 不影响同批其他 bundle。 */ }
      return { name: bundle.name, version: bundle.version, profile: bundle.profile, state };
    });
  };
}

/** 只取上游公开的无源码摘要；会话临时插件不能冒充 npm 持久安装。 */
export function runtimePlugins(agents, runner) {
  if (!runner || typeof runner.listPlugins !== 'function') return [];
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
  } catch { return []; }
  return items;
}

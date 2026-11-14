'use strict';
// 仅由锁定 settings 模块调用；回执在宿主私有绑定中，归档内同名 JSON 不具权威。
const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { isDeepStrictEqual } = require('node:util');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
async function read(file) { return JSON.parse(await fs.readFile(file, 'utf8')); }
async function atomic(file, value) {
  const temp = file + '.' + crypto.randomUUID() + '.tmp';
  await fs.writeFile(temp, JSON.stringify(value, null, 2) + '\n', { mode: 0o600, flag: 'wx' });
  await fs.rename(temp, file);
}
function contains(actual, wanted) {
  if (wanted && typeof wanted === 'object' && !Array.isArray(wanted))
    return actual && typeof actual === 'object' && Object.keys(wanted).every(k => Object.hasOwn(actual, k) && contains(actual[k], wanted[k]));
  return isDeepStrictEqual(actual, wanted);
}
const clone = value => JSON.parse(JSON.stringify(value));
const CREDENTIAL_NAME = /(?:api[_-]?key|token|password|secret|credential)/i;
// 真实 SettingsForms.describe() 的 descriptor.schema 是 schemastery 引用图：
// { uid, refs }，refs[uid] 是节点快照，dict/inner/list/sKey 用 uid（数字或字符串）
// 引用子节点；秘密字段只由节点 meta.role === 'secret' 决定。旧的普通节点形状
// （子节点直接是对象）也一并支持；半截引用图/悬空引用不猜，交给调用方保守处理。
function schemaGraph(schema) {
  if (!schema || typeof schema !== 'object' || Array.isArray(schema)) return null;
  const { uid, refs } = schema;
  if (refs && typeof refs === 'object' && !Array.isArray(refs)
      && (typeof uid === 'string' || typeof uid === 'number')) {
    const root = Object.hasOwn(refs, uid) ? refs[uid] : null;
    return root && typeof root === 'object' && !Array.isArray(root) ? { refs, root } : null;
  }
  if (uid === undefined && refs === undefined) return { refs: null, root: schema };
  return null;
}
function schemaNode(graph, reference) {
  if (reference && typeof reference === 'object' && !Array.isArray(reference)) return reference;
  if ((typeof reference === 'string' || typeof reference === 'number') && graph.refs
      && Object.hasOwn(graph.refs, reference)) {
    const node = graph.refs[reference];
    return node && typeof node === 'object' && !Array.isArray(node) ? node : null;
  }
  return null;
}
// schema 未声明的键才退回名称兜底；它只额外少复制，不能替代 schema 决定秘密字段。
// 只深入普通对象/数组，类实例（Date/RegExp 等）原样保留，避免把实例压成空对象。
function isPlainRecord(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const proto = Object.getPrototypeOf(value);
  return proto === Object.prototype || proto === null;
}
function scrubCredentialNames(value) {
  if (Array.isArray(value)) return value.map(scrubCredentialNames);
  if (!isPlainRecord(value)) return value;
  const out = {};
  for (const [key, item] of Object.entries(value)) {
    if (CREDENTIAL_NAME.test(key)) continue;
    out[key] = scrubCredentialNames(item);
  }
  return out;
}
// union/intersect 在对象层的声明键 → 各分支对应的子 schema。分支歧义时不能只靠
// 名称正则，也不能删掉任何分支声明字段；无法确定键集时返回 null，由调用方保守失败。
function unionDeclaredChildren(node, graph, depth) {
  const keys = new Map();
  const collect = (current, level) => {
    if (!current || typeof current !== 'object' || level > 64) return false;
    if (current.type === 'object') {
      if (current.dict === undefined) return true;
      if (!current.dict || typeof current.dict !== 'object' || Array.isArray(current.dict)) return false;
      for (const [key, reference] of Object.entries(current.dict)) {
        const child = schemaNode(graph, reference);
        if (!child) return false;
        if (!keys.has(key)) keys.set(key, []);
        keys.get(key).push(child);
      }
      return true;
    }
    if (current.type === 'union' || current.type === 'intersect') {
      if (!Array.isArray(current.list) || !current.list.length) return false;
      for (const reference of current.list) {
        const child = schemaNode(graph, reference);
        if (!child || !collect(child, level + 1)) return false;
      }
      return true;
    }
    if (current.type === 'transform' || current.type === 'lazy') {
      const inner = schemaNode(graph, current.inner);
      return inner ? collect(inner, level + 1) : false;
    }
    return true; // 其他类型在对象层不声明命名键
  };
  return collect(node, depth) ? keys : null;
}
// 与上游 walk 一致：逐节点确认秘密位置；无法判定的结构按“可能含秘密”处理。
function schemaHoldsSecret(node, graph, depth) {
  if (!node || typeof node !== 'object' || Array.isArray(node) || depth > 64) return true;
  if (node.meta && node.meta.role === 'secret') return true;
  const descend = references => {
    for (const reference of references) {
      const child = schemaNode(graph, reference);
      if (!child || schemaHoldsSecret(child, graph, depth + 1)) return true;
    }
    return false;
  };
  switch (node.type) {
    case 'object': {
      if (!node.dict || typeof node.dict !== 'object' || Array.isArray(node.dict)) return true;
      return descend(Object.values(node.dict));
    }
    case 'dict': case 'array': case 'transform': case 'lazy':
      return node.inner === undefined ? true : descend([node.inner]);
    case 'tuple':
      if (!Array.isArray(node.list)) return true;
      return descend(node.list);
    case 'union': case 'intersect':
      if (!Array.isArray(node.list) || !node.list.length) return true;
      return descend(node.list);
    case 'string': case 'number': case 'boolean': case 'bitset':
    case 'any': case 'never': case 'const': case 'function': case 'is':
      return false;
    default:
      return true; // 未建模类型：按可能含秘密处理
  }
}
// 返回 { ok, value }。ok=false 表示 schema 无法解释（缺失、悬空引用、未建模类型、
// 形状不符），此时无法证明值里没有秘密，调用方必须保守：不落账、不派发写入。
// dropSecrets=true 用于落账；false 只在内存读回核验里保留秘密值（绝不持久化）。
function projectValue(value, schema, options = {}) {
  const graph = schemaGraph(schema);
  if (!graph) return { ok: false };
  return projectNode(value, graph, graph.root, options.dropSecrets !== false, 0);
}
function projectNode(value, graph, node, dropSecrets, depth) {
  if (!node || typeof node !== 'object' || Array.isArray(node) || depth > 64) return { ok: false };
  if (node.meta && node.meta.role === 'secret')
    return { ok: true, value: dropSecrets ? undefined : value };
  if (value === undefined || value === null) return { ok: true, value };
  switch (node.type) {
    case 'object': {
      if (typeof value !== 'object' || Array.isArray(value)) return { ok: false };
      const dict = node.dict === undefined ? {} : node.dict;
      if (!dict || typeof dict !== 'object' || Array.isArray(dict)) return { ok: false };
      const out = {};
      for (const [key, item] of Object.entries(value)) {
        if (!Object.hasOwn(dict, key)) {
          // schema 未声明的结构：名称兜底只额外少复制，其余原样保留（update 会自行校验）。
          if (CREDENTIAL_NAME.test(key)) continue;
          out[key] = dropSecrets ? scrubCredentialNames(item) : item;
          continue;
        }
        const child = schemaNode(graph, dict[key]);
        if (!child) return { ok: false };
        const projected = projectNode(item, graph, child, dropSecrets, depth + 1);
        if (!projected.ok) return projected;
        if (projected.value !== undefined) out[key] = projected.value;
      }
      return { ok: true, value: out };
    }
    case 'dict':
    case 'array': {
      if (typeof value !== 'object' || Array.isArray(value) !== (node.type === 'array')) return { ok: false };
      const inner = schemaNode(graph, node.inner);
      if (!inner) return { ok: false };
      if (node.type === 'dict') {
        const out = {};
        for (const [key, item] of Object.entries(value)) {
          const projected = projectNode(item, graph, inner, dropSecrets, depth + 1);
          if (!projected.ok) return projected;
          if (projected.value !== undefined) out[key] = projected.value;
        }
        return { ok: true, value: out };
      }
      const out = [];
      for (const item of value) {
        const projected = projectNode(item, graph, inner, dropSecrets, depth + 1);
        if (!projected.ok) return projected;
        if (projected.value !== undefined) out.push(projected.value);
      }
      return { ok: true, value: out };
    }
    case 'tuple': {
      if (!Array.isArray(value) || !Array.isArray(node.list)) return { ok: false };
      const out = [];
      for (let index = 0; index < value.length; index += 1) {
        if (index >= node.list.length) return { ok: false }; // 越过 schema 的项无法判定
        const child = schemaNode(graph, node.list[index]);
        if (!child) return { ok: false };
        const projected = projectNode(value[index], graph, child, dropSecrets, depth + 1);
        if (!projected.ok) return projected;
        if (projected.value !== undefined) out.push(projected.value);
      }
      return { ok: true, value: out };
    }
    case 'transform':
    case 'lazy': {
      const inner = schemaNode(graph, node.inner);
      if (!inner) return { ok: false };
      return projectNode(value, graph, inner, dropSecrets, depth + 1);
    }
    case 'union':
    case 'intersect': {
      // 分支歧义下无法确定实际命中的秘密转移：只要任一分支可能含秘密就保守失败。
      if (!Array.isArray(node.list) || !node.list.length) return { ok: false };
      for (const reference of node.list) {
        const child = schemaNode(graph, reference);
        if (!child || schemaHoldsSecret(child, graph, depth + 1)) return { ok: false };
      }
      if (!dropSecrets || value === undefined || value === null) return { ok: true, value };
      if (Array.isArray(value)) return { ok: false }; // 数组项的分支声明无法逐项确定
      if (!isPlainRecord(value)) return { ok: true, value }; // 标量/类实例没有命名键
      // 无秘密分支的对象值：仍要对“所有分支都未声明”的键做名称兜底。声明键按各声明
      // 分支投影；多个分支投影结果不一致时不能猜测实际命中的分支，保守失败。
      const declared = unionDeclaredChildren(node, graph, depth + 1);
      if (!declared) return { ok: false };
      const out = {};
      for (const [key, item] of Object.entries(value)) {
        if (!declared.has(key)) {
          if (CREDENTIAL_NAME.test(key)) continue;
          out[key] = scrubCredentialNames(item);
          continue;
        }
        let merged;
        let mergedSet = false;
        for (const child of declared.get(key)) {
          const projected = projectNode(item, graph, child, true, depth + 2);
          if (!projected.ok) return { ok: false };
          if (!mergedSet) { merged = projected; mergedSet = true; }
          else if (!isDeepStrictEqual(merged.value, projected.value)) return { ok: false };
        }
        if (merged.value !== undefined) out[key] = merged.value;
      }
      return { ok: true, value: out };
    }
    case 'string': case 'number': case 'boolean': case 'bitset':
    case 'any': case 'never': case 'const': case 'function': case 'is':
      // 这些节点不声明命名键：对象/数组值仍要做名称兜底（any 数组项、const 对象等），
      // 标量原样返回；schema 声明的字段不受影响。
      return { ok: true, value: dropSecrets ? scrubCredentialNames(value) : value };
    default:
      return { ok: false }; // 未建模类型：不猜
  }
}
function holdsSecrets(schema) {
  const graph = schemaGraph(schema);
  return graph ? schemaHoldsSecret(graph.root, graph, 0) : true;
}
// update 一旦被调用，无法再从错误文本证明它没有派发；只有调用之前完成的 schema/
// 命名空间校验被拒绝，才可记 not-sent/pending。这里只复刻 settings.update 自身在
// 进入写事务之前就会拒绝的判据：分节必须是映射，且每个顶层字段都被 form schema
// 声明（未声明的键会被 update 以 not volatile 拒绝，不会写入）。类型/min 等约束
// 仍交给 update；那些一旦派发就按 unknown 处理。
function preflightRejection(values, schema) {
  if (!values || typeof values !== 'object' || Array.isArray(values)) return 'SECTION_VALUE_FORMAT';
  const graph = schemaGraph(schema);
  const root = graph && graph.root;
  if (!root || root.type !== 'object') return null; // 无法在派发前判定，交给 update
  const dict = root.dict;
  if (!dict || typeof dict !== 'object' || Array.isArray(dict)) return null;
  for (const key of Object.keys(values))
    if (!Object.hasOwn(dict, key)) return 'SECTION_SCHEMA_REJECTED';
  return null;
}

// 同次 prepare 的导入模式必须是 startupId/generation/输入指纹绑定的票据；
// 缺失、过期或摘要不符时只能只读核对旧账本，不能新建账本或重新写入。
function importContext(current, prepared, sourceSha) {
  const context = current.startupContext;
  const valid = context && typeof context === 'object'
    && context.version === 3
    && context.generation === current.generation
    && context.startupId === current.startupId
    && typeof context.inputFingerprint === 'string'
    && (context.settingsSha256 === sourceSha || context.settingsSha256 === null)
    && typeof context.proofDigest === 'string';
  if (!valid) return { mode: 'history-only', reason: 'STARTUP_CONTEXT_MISSING' };
  const expected = hash(JSON.stringify({
    generation: current.generation,
    inputDigest: prepared.inputDigest,
    settingsSha256: sourceSha,
  }));
  if (context.proofDigest !== expected)
    return { mode: 'history-only', reason: 'STARTUP_CONTEXT_MISMATCH' };
  return { mode: context.mode === 'write-allowed' ? 'write-allowed' : 'history-only', reason: null };
}

function sectionState(sections) {
  const states = Object.values(sections).map(row => row?.status);
  if (states.some(state => state === 'unknown' || state === 'writing')) return 'unknown';
  return states.every(state => state === 'verified') ? 'verified' : 'pending';
}

async function migrate(settings, parse, aliases, options = {}) {
  const home = settings.ownerContext.profileContext.home;
  const state = options.stateRoot || '/run/dsha-rc1-state';
  let current;
  try { current = await read(path.join(state, 'current.json')); }
  catch (error) {
    // 全新的隔离试运行仅有空文档，不作任何写入。带旧值时必须先建立保护事务。
    let empty;
    try { empty = parse(await fs.readFile(path.join(home, 'settings.yaml'), 'utf8')); }
    catch (missing) { if (missing.code === 'ENOENT') return; throw missing; }
    if (empty && Object.keys(empty).length === 0) return;
    throw new Error('DSHA_MIGRATION_PROTECTION_REQUIRED');
  }
  if (current.version !== 2 || current.dshHome !== home || !/^[a-f0-9-]{36}$/.test(current.generation)) {
    if (process.env.DSHA_RUNTIME_TRIAL_NONCE) return; // 隔离试运行不得操作真实用户迁移记录。
    throw new Error('DSHA_MIGRATION_GENERATION_MISMATCH');
  }
  const folder = path.join(state, 'generations', current.generation);
  const prepared = await read(path.join(folder, 'prepare.json'));
  if (prepared.status !== 'prepared' || !prepared.protectionComplete) throw new Error('DSHA_MIGRATION_PROTECTION_REQUIRED');
  const sources = prepared.sources.filter(row => row.kind === 'settings');
  const source = sources.find(row => row.path === 'settings.yaml') || sources.find(row => row.path === 'settings.yaml.imported');
  if (!source) return;
  // 对已保护的独立正文验证；不按归档或外部路径加载可执行内容。
  if (!/^snapshots\/[0-9]+$/.test(source.snapshot)) throw new Error('DSHA_MIGRATION_SNAPSHOT_PATH');
  const bytes = await fs.readFile(path.join(folder, source.snapshot));
  if (hash(bytes) !== source.sha256) throw new Error('DSHA_MIGRATION_SNAPSHOT_CHANGED');
  const context = importContext(current, prepared, source.sha256);
  const writeAllowed = context.mode === 'write-allowed';
  const activePath = path.join(home, 'settings.yaml');
  const importedPath = activePath + '.imported';
  let sourcePath;
  for (const candidate of [activePath, importedPath]) {
    try { if (hash(await fs.readFile(candidate)) === source.sha256) { sourcePath = candidate; break; } }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  if (!sourcePath) throw new Error('DSHA_MIGRATION_SOURCE_CHANGED');
  const sections = parse(bytes.toString('utf8')) ?? {};
  if (!sections || typeof sections !== 'object' || Array.isArray(sections)) throw new Error('DSHA_MIGRATION_SETTINGS_FORMAT');
  const ledgerFile = path.join(folder, 'settings-result.json');
  let result;
  try { result = await read(ledgerFile); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (result && (result.generation !== current.generation || result.sourceSha256 !== source.sha256)) {
    // 历史只读模式不得把旧账本重写成新代次，也不得据此写入配置。
    if (!writeAllowed) return { status: 'unknown', reason: 'DSHA_MIGRATION_RESULT_IDENTITY', generation: current.generation };
    throw new Error('DSHA_MIGRATION_RESULT_IDENTITY');
  }
  if (!result) {
    if (!writeAllowed) {
      // 权威账本缺失：旧 Java 缓存命中或证据票据过期时只能返回 unknown，不能新建账本后重导。
      return { status: 'unknown', reason: 'DSHA_MIGRATION_LEDGER_MISSING', generation: current.generation };
    }
    result = { version: 2, generation: current.generation, sourceSha256: source.sha256, targetVersion: '0.1.7-rc.2', sections: {} };
  }
  if (!result.sections || typeof result.sections !== 'object' || Array.isArray(result.sections)) {
    if (!writeAllowed) return { status: 'unknown', reason: 'DSHA_MIGRATION_RESULT_FORMAT', generation: current.generation };
    throw new Error('DSHA_MIGRATION_RESULT_FORMAT');
  }
  // history-only 只读核对既有账本：不得创建/更新节、写 startupId/verifiedAt/atomic，
  // 也不得做 settings.yaml -> imported 的 rename。源分节缺失或未完成一律不写并返回
  // unknown/pending，避免把用户后来改的当前值覆盖回旧快照值。
  if (!writeAllowed) {
    const expected = Object.keys(sections);
    const missing = expected.filter(name => !Object.hasOwn(result.sections, name));
    if (missing.length)
      return { status: 'unknown', reason: 'SETTINGS_SECTION_MISSING', generation: current.generation, missing: missing.slice(0, 8) };
    const states = expected.map(name => result.sections[name]?.status);
    if (states.some(state => state === 'writing' || state === 'unknown'))
      return { status: 'unknown', reason: 'SETTINGS_WRITE_OUTCOME_UNKNOWN', generation: current.generation };
    if (states.some(state => state === 'pending' || state === 'conflict'))
      return { status: 'pending', reason: 'SETTINGS_READ_ONLY_INCOMPLETE', generation: current.generation };
    if (states.some(state => state !== 'verified'))
      return { status: 'unknown', reason: 'SETTINGS_LEDGER_SECTION', generation: current.generation };
    return { status: 'verified', generation: current.generation };
  }
  result.startupId = current.startupId;
  // 记录本代次期望分节集合；后续只读方据此判断“部分 verified + overall=verified”的不完整账本。
  result.expectedSections = Object.keys(sections).sort();
  // 旧账本可能由更早版本写入未去秘密的 source/before/verified。本代次任何重写之前都按
  // 当前 schema 重新投影这些历史值；schema 无法解释时删除字段（宁可失去诊断，也不保留
  // 旧泄漏）。因此失去 before 比较依据的 pending/conflict 节保守冻结为 unknown。
  const ledgerDescriptors = new Map(settings.describe().map(row => [row.ns, row]));
  let sanitized = false;
  for (const [name, section] of Object.entries(result.sections)) {
    if (!section || typeof section !== 'object' || Array.isArray(section)) continue;
    const descriptor = ledgerDescriptors.get(Object.hasOwn(aliases, name) ? aliases[name] : name);
    for (const field of ['source', 'before', 'verified']) {
      if (!Object.hasOwn(section, field)) continue;
      const projected = descriptor ? projectValue(section[field], descriptor.schema, { dropSecrets: true }) : { ok: false };
      if (projected.ok && projected.value !== undefined) {
        const next = clone(projected.value);
        if (isDeepStrictEqual(next, section[field])) continue;
        section[field] = next;
        sanitized = true;
        continue;
      }
      delete section[field];
      sanitized = true;
      if (section.status === 'pending' || section.status === 'conflict') {
        // 旧 before 已不可比较：旧证据不能再驱动一次写入。
        section.status = 'unknown';
        section.reason = 'SECTION_LEDGER_REVIEW_REQUIRED';
      }
    }
  }
  if (sanitized) await atomic(ledgerFile, result);
  for (const [name, values] of Object.entries(sections)) {
    const ns = Object.hasOwn(aliases, name) ? aliases[name] : name;
    const descriptor = settings.describe().find(row => row.ns === ns);
    let section = Object.hasOwn(result.sections, name) ? result.sections[name] : undefined;
    if (section?.status === 'verified') continue; // 已完成节从不在普通重启覆盖后来用户修改。
    if (section?.status === 'unknown') continue; // 已派发但终态未知：不自动再写一次。
    if (section?.status === 'writing') {
      // 崩溃留下的 writing 不能恢复成可写 pending；只读对账后保留待处理。
      section.status = 'unknown';
      section.reason = 'WRITE_OUTCOME_UNKNOWN';
      await atomic(ledgerFile, result);
      continue;
    }
    if (!section) {
      section = { ns, status: 'pending' };
      Object.defineProperty(result.sections, name, { value: section, enumerable: true, configurable: true, writable: true });
    }
    if (!descriptor) { section.reason = 'SECTION_UNAVAILABLE'; await atomic(ledgerFile, result); continue; }
    // 派发前的校验拒绝：分节不是映射、或顶层字段未被 form schema 声明时，settings.update
    // 自身在进入写事务之前就会拒绝，值没有被写入配置，保留可重试 pending。
    const rejection = preflightRejection(values, descriptor.schema);
    if (rejection) { section.status = 'pending'; section.reason = rejection; await atomic(ledgerFile, result); continue; }
    // 落账只保留去秘密投影；读回核验在内存用完整投影。schema 无法解释时不能证明
    // 值里没有秘密：该节冻结为 unknown，不调用 update、不把值写进账本。
    const ledgerValues = projectValue(values, descriptor.schema, { dropSecrets: true });
    const fullValues = projectValue(values, descriptor.schema, { dropSecrets: false });
    const ledgerCurrent = projectValue(descriptor.value, descriptor.schema, { dropSecrets: true });
    const fullCurrent = projectValue(descriptor.value, descriptor.schema, { dropSecrets: false });
    if (!ledgerValues.ok || !fullValues.ok || !ledgerCurrent.ok || !fullCurrent.ok) {
      section.status = 'unknown';
      section.reason = 'SECTION_SCHEMA_UNKNOWN';
      await atomic(ledgerFile, result);
      continue;
    }
    section.source = clone(ledgerValues.value);
    if (Object.hasOwn(section, 'before') && holdsSecrets(descriptor.schema)) {
      // 旧账本可能留下“已记录 before 的 pending/conflict 节”；秘密位置没有可落账的
      // 变化证据，无法证明用户没改过目标，保守冻结而不是覆盖。
      section.status = 'unknown';
      section.reason = 'SECTION_SECRET_RETRY_REVIEW';
      await atomic(ledgerFile, result);
      continue;
    }
    if (Object.hasOwn(section, 'before') && !isDeepStrictEqual(section.before, ledgerCurrent.value)) {
      // 目标已不是记录时的值：只有当前值确实已包含期望值才算已完成，否则交用户核对。
      if (contains(fullCurrent.value, fullValues.value)) { section.status = 'verified'; section.verified = clone(ledgerCurrent.value); }
      else { section.status = 'conflict'; section.reason = 'TARGET_CHANGED_REVIEW_REQUIRED'; }
      await atomic(ledgerFile, result); continue;
    }
    section.before = clone(ledgerCurrent.value); section.status = 'writing';
    await atomic(ledgerFile, result); // 先记预期值，掉电后不盲目重放。
    try {
      await settings.update(ns, values, descriptor.revision);
      const after = settings.describe().find(row => row.ns === ns);
      const ledgerAfter = after ? projectValue(after.value, after.schema, { dropSecrets: true }) : { ok: false };
      const fullAfter = after ? projectValue(after.value, after.schema, { dropSecrets: false }) : { ok: false };
      if (!after || !ledgerAfter.ok || !fullAfter.ok || !contains(fullAfter.value, fullValues.value))
        throw new Error('SETTINGS_READBACK_MISMATCH');
      section.status = 'verified'; section.verified = clone(ledgerAfter.value); delete section.reason;
    } catch {
      // update 已进入调用：错误文本（schema / invalid / No configurable plugin entry…）
      // 不能证明未派发，读回不符也可能是“已写入但终态未知”。一律 unknown/noReplay；
      // 明确未派发的拒绝只在 preflightRejection 里判定，不再按错误文字恢复可重试态。
      // 不绑定 error，也避免把可能含配置正文的错误消息写进账本。
      section.status = 'unknown';
      section.reason = 'WRITE_OUTCOME_UNKNOWN';
    }
    await atomic(ledgerFile, result);
  }
  result.status = sectionState(result.sections);
  if (result.status === 'unknown') result.reason = 'SETTINGS_WRITE_OUTCOME_UNKNOWN';
  else delete result.reason;
  result.verifiedAt = Date.now();
  await atomic(ledgerFile, result);
  // 不再把整份 settings 放回给上游。合法 rename 仅在正文未变、目标未冲突时完成。
  const active = activePath, imported = importedPath;
  try {
    if (hash(await fs.readFile(active)) === source.sha256) {
      try { if (hash(await fs.readFile(imported)) !== source.sha256) return result; }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      await fs.rename(active, imported);
    }
  } catch (error) { if (error.code !== 'ENOENT') throw error; }
  settings.ownerContext.logger.info('DSHA rc1 settings migration: %s', result.status);
  return result;
}
module.exports = { migrate, contains };

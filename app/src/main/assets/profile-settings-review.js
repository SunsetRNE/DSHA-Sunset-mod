import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { isMap, isSeq, parseDocument, visit } from 'yaml';
import Schema from '@deepseek-ai/schemastery';
import { redactSecrets } from '@deepseek-ai/dsh-settings';

export const inject = ['settings', 'configEditor', 'profileContext'];
const fieldTag = '!dsha-config-fields-v1';
const fieldTags = [{ tag: fieldTag, collection: 'seq', resolve: sequence => sequence }];
const record = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const forbidden = k => ['__proto__', 'prototype', 'constructor', '__jsExpr'].includes(k);
function literal(value, schema, path, warnings) {
  if (schema?.meta?.role === 'secret') { warnings.push(`SECRET_RETAINED:${path}`); return undefined; }
  if (hasSecret(schema) && !(record(value) && ['object', 'dict'].includes(schema.type))) {
    // A replacement of a secret-bearing array, union or transform could erase stored credentials.
    warnings.push(`SECRET_RETAINED:${path}`);
    return undefined;
  }
  if (record(value)) {
    if (Object.keys(value).some(forbidden)) throw Error('EXECUTABLE_CONFIGURATION');
    const output = {};
    for (const [key, child] of Object.entries(value)) {
      const field = schema?.dict?.[key] ?? (schema?.type === 'dict' ? schema.inner : undefined);
      if (!field) { warnings.push(`FIELD_REQUIRES_REVIEW:${path}/${key}`); continue; }
      const next = literal(child, field, `${path}/${key}`, warnings);
      if (next !== undefined) output[key] = next;
    }
    return Object.keys(output).length || Object.keys(value).length === 0 ? output : undefined;
  }
  if (Array.isArray(value)) return value.map((v, i) => literal(v, schema?.inner, `${path}/${i}`, warnings));
  if (typeof value === 'string' && /\$\{|\{\{/.test(value)) throw Error('EXPRESSION_REQUIRES_REVIEW');
  if (value !== null && typeof value !== 'string' && typeof value !== 'boolean' && !Number.isFinite(value))
    throw Error('CONFIGURATION_TYPE');
  return value;
}
export function parseIncoming(text) {
  const doc = parseDocument(text, { customTags: fieldTags, uniqueKeys: true, maxAliasCount: 0 });
  if (doc.errors.length || doc.warnings.length) throw Error('YAML_REQUIRES_REVIEW');
  if (!isSeq(doc.contents)) throw Error('PROFILE_PATCH_FORMAT');
  const sequences = new Set(doc.contents.items.filter(isMap)
    .map(row => row.get('configFields', true)).filter(isSeq));
  visit(doc, { Alias() { throw Error('YAML_ALIAS_REQUIRES_REVIEW'); }, Node(_key, node) {
    if (node.tag === fieldTag && sequences.has(node)) return;
    if (node.tag && !['tag:yaml.org,2002:str','tag:yaml.org,2002:map','tag:yaml.org,2002:seq','tag:yaml.org,2002:int','tag:yaml.org,2002:float','tag:yaml.org,2002:bool','tag:yaml.org,2002:null'].includes(node.tag)) throw Error('YAML_TAG_REQUIRES_REVIEW');
  }});
  const rows = doc.toJS({ maxAliasCount: 0 });
  if (!Array.isArray(rows) || rows.length > 2048) throw Error('PROFILE_PATCH_FORMAT');
  return rows;
}

function incomingField(op) {
  return record(op) && Object.keys(op).length === 2 &&
    Array.isArray(op.path) && op.path.length > 0 &&
    op.path.every(key => typeof key === 'string' && key.length > 0 && !forbidden(key)) &&
    (Object.hasOwn(op, 'value') || (Object.hasOwn(op, 'inherit') && op.inherit === true));
}

function applyIncomingField(fields, op) {
  const parents = [];
  let current = fields;
  for (const key of op.path.slice(0, -1)) {
    if (!Object.hasOwn(current, key)) {
      if (op.inherit) return;
      current[key] = {};
    }
    if (!record(current[key])) throw Error('PROFILE_FIELD_PATH');
    parents.push([current, key]);
    current = current[key];
  }
  const key = op.path.at(-1);
  if (!op.inherit) {
    current[key] = structuredClone(op.value);
    return;
  }
  if (!Object.hasOwn(current, key)) return;
  delete current[key];
  for (const [parent, name] of parents.reverse()) {
    if (Object.keys(parent[name]).length) break;
    delete parent[name];
  }
}

/** Project only the source profile's own values. Legacy config still replaces the entire entry config. */
function incomingSettings(text, descriptors, names, warnings) {
  const settings = new Map();
  for (const row of parseIncoming(text)) {
    const fields = row?.configFields;
    if (!record(row) || typeof row.id !== 'string' ||
        (Object.hasOwn(row, 'name') && typeof row.name !== 'string') ||
        (!Object.hasOwn(row, 'config') && !Object.hasOwn(row, 'configFields')) ||
        (Object.hasOwn(row, 'config') && !record(row.config)) ||
        (Object.hasOwn(row, 'configFields') && (!Array.isArray(fields) || !fields.every(incomingField))) ||
        Object.keys(row).some(key => !['id', 'name', 'config', 'configFields'].includes(key))) {
      warnings.push('CODE_OR_COMPOSITION_REQUIRES_REVIEW');
      continue;
    }
    if (!descriptors.has(row.id)) {
      warnings.push(`NAMESPACE_UNAVAILABLE:${row.id}`);
      continue;
    }
    if (names && row.name !== undefined && row.name !== names.get(row.id)) {
      warnings.push(`TARGET_REQUIRES_REVIEW:${row.id}`);
      continue;
    }
    const values = structuredClone(row.config ?? settings.get(row.id) ?? {});
    try {
      for (const op of fields ?? []) applyIncomingField(values, op);
      settings.set(row.id, values);
    } catch {
      warnings.push('CODE_OR_COMPOSITION_REQUIRES_REVIEW');
    }
  }
  return settings;
}

function mergeUpdate(current, changes) {
  if (!record(current) || !record(changes)) return changes;
  return Object.fromEntries([
    ...Object.entries(current),
    ...Object.entries(changes).map(([key, value]) => [key, mergeUpdate(current[key], value)]),
  ]);
}

function display(value) { const text = JSON.stringify(value ?? null); return text.length > 400 ? text.slice(0, 400) + '…' : text; }
function validate(descriptor, value) {
  const result = descriptor.schema?.['~standard']?.validate(value);
  if (result?.then) throw Error('ASYNC_SCHEMA_REQUIRES_REVIEW');
  if (result?.issues) throw Error('SCHEMA_REJECTED');
  return result?.value ?? value;
}
function retainSecrets(base, current, schema) {
  if (schema?.meta?.role === 'secret') return current;
  if (Array.isArray(current)) return hasSecret(schema?.inner) ? current : base;
  if (!record(current)) return base;
  const next = record(base) ? structuredClone(base) : {};
  for (const [key, value] of Object.entries(current)) {
    const field = schema?.dict?.[key] ?? (schema?.type === 'dict' ? schema.inner : undefined);
    const retained = retainSecrets(next[key], value, field);
    if (retained !== undefined && (field?.meta?.role === 'secret' || record(retained) && Object.keys(retained).length)) next[key] = retained;
  }
  return Object.keys(next).length || base !== undefined ? next : undefined;
}
function hasSecret(schema, seen = new Set()) {
  if (!schema || seen.has(schema)) return false;
  seen.add(schema);
  return schema.meta?.role === 'secret' || hasSecret(schema.inner, seen) ||
    Object.values(schema.dict ?? {}).some(field => hasSecret(field, seen)) ||
    (schema.list ?? []).some(field => hasSecret(field, seen));
}
function resetOps(schema, value, path = []) {
  if (schema?.meta?.role === 'secret') return [];
  if (schema?.type === 'object' && record(value)) {
    const ops = [];
    for (const [key, child] of Object.entries(schema.dict ?? {})) if (Object.hasOwn(value, key)) ops.push(...resetOps(child, value[key], [...path, key]));
    return ops;
  }
  if (schema?.type === 'dict' || schema?.type === 'array') return hasSecret(schema) ? [] : [{ op: 'unset', path }];
  return path.length ? [{ op: 'unset', path }] : [];
}

function entryNames(ctx) {
  return new Map(ctx.configEditor.entries().map(({ options }) => [options.id, options.name]));
}

function readSelectedScope(ctx) {
  return {
    document: readFileSync(ctx.settings.documentPath, 'utf8'),
    forms: new Map(ctx.settings.describe().map(row => [row.ns, row])),
    names: entryNames(ctx),
  };
}

/** Keep the YAML AST outside selected fields, including trusted expressions, aliases and comments. */
function outsideSelectedKeys(text, selected, names) {
  const doc = parseDocument(text, { customTags: fieldTags });
  if (doc.errors.length || !isSeq(doc.contents)) throw Error('SETTINGS_SELECTION_SCOPE_CHANGED');
  doc.contents.flow = false;
  for (let index = doc.contents.items.length - 1; index >= 0; index--) {
    const row = doc.contents.items[index];
    if (!isMap(row) || row.has('insert')) continue;
    const id = row.get('id'), keys = selected.get(id);
    if (!keys || (row.has('name') && row.get('name') !== names.get(id))) continue;
    let changed = false;
    const config = row.get('config', true);
    if (isMap(config)) {
      for (const key of keys) changed = config.delete(key) || changed;
      if (changed && config.items.length === 0) row.delete('config');
    }
    const fields = row.get('configFields', true);
    if (isSeq(fields)) {
      // Known tagged and historical plain sequences carry the same field data.
      if (fields.tag === fieldTag) fields.tag = undefined;
      const remaining = fields.items.filter(op => {
        const path = isMap(op) && op.get('path', true);
        return !isSeq(path) || !keys.has(path.get(0));
      });
      if (remaining.length !== fields.items.length) {
        changed = true;
        fields.items = remaining;
        if (remaining.length === 0) row.delete('configFields');
      }
    }
    if (changed && row.items.length === Number(row.has('id')) + Number(row.has('name'))) doc.deleteIn([index]);
  }
  return doc.toString();
}

function secretValues(schema, value) {
  return new Map(redactSecrets(schema, value).secrets.filter(secret => secret.set).map(({ path }) =>
    [JSON.stringify(path), path.reduce((parent, key) => parent?.[key], value)]));
}

function requireSelectedScope(before, after, items) {
  const selected = new Map();
  for (const item of items) {
    if (!selected.has(item.ns)) selected.set(item.ns, new Set());
    selected.get(item.ns).add(item.field);
  }
  if (!isDeepStrictEqual(before.names, after.names) || before.forms.size !== after.forms.size ||
      outsideSelectedKeys(before.document, selected, before.names) !==
        outsideSelectedKeys(after.document, selected, after.names)) throw Error('SETTINGS_SELECTION_SCOPE_CHANGED');
  for (const [ns, prior] of before.forms) {
    const next = after.forms.get(ns), keys = selected.get(ns);
    const outside = value => keys ? Object.fromEntries(Object.entries(value ?? {}).filter(([key]) => !keys.has(key))) : value;
    if (!next || !isDeepStrictEqual(prior.base, next.base) ||
        !isDeepStrictEqual(outside(prior.value), outside(next.value)) ||
        !isDeepStrictEqual(outside(prior.user), outside(next.user))) throw Error('SETTINGS_SELECTION_SCOPE_CHANGED');
    if (!keys) continue;
    const schema = prior.schema?.refs ? new Schema(prior.schema) : prior.schema;
    for (const key of keys) for (const layer of ['value', 'user']) {
      const field = schema?.dict?.[key];
      if (hasSecret(field) && !isDeepStrictEqual(secretValues(field, prior[layer]?.[key]),
          secretValues(field, next[layer]?.[key]))) throw Error('SETTINGS_SELECTION_SCOPE_CHANGED');
    }
  }
}

/** settings 是当前真正的 Cordis 服务；所有验证/写入均限定在宿主准备的隔离 profile。 */
export async function review(settings, request, incoming, names) {
  const describe = () => settings.describe({ redactSecrets: true }).map(row => ({ ...row, schema: row.schema?.refs ? new Schema(row.schema) : row.schema }));
  const warnings = [], items = [], described = describe();
  const descriptors = new Map(described.map(row => [row.ns, row]));
  if (request.mode === 'verify') {
    for (const expected of request.expected ?? []) {
      const actual = descriptors.get(expected.ns)?.value?.[expected.field] ?? null;
      if (!isDeepStrictEqual(actual, expected.actual)) throw Error('SETTINGS_READBACK_MISMATCH');
    }
    return { version: 1, items: request.expected ?? [], warnings: [], status: 'VERIFIED' };
  }
  const desired = new Map();
  if (request.mode === 'reset') {
    for (const row of described) for (const key of Object.keys(row.user ?? {})) {
      const field = row.schema?.dict?.[key];
      if (!field || field.meta?.role === 'secret') continue;
      desired.set(`${row.ns}/${key}`, { ns: row.ns, key, reset: true, before: row.value?.[key] });
    }
  } else {
    for (const [ns, values] of incomingSettings(incoming, descriptors, names, warnings)) {
      const descriptor = descriptors.get(ns);
      for (const [key, value] of Object.entries(values)) {
        if (forbidden(key) || !Object.hasOwn(descriptor.schema?.dict ?? {}, key)) { warnings.push(`FIELD_REQUIRES_REVIEW:${ns}/${key}`); continue; }
        try {
          const safe = literal(value, descriptor.schema.dict[key], `${ns}/${key}`, warnings);
          if (safe !== undefined) desired.set(`${ns}/${key}`, { ns, key, value: safe, before: descriptor.value?.[key] });
        } catch { warnings.push(`EXECUTABLE_FIELD_REQUIRES_REVIEW:${ns}/${key}`); }
      }
    }
  }
  if (desired.size > 2048) throw Error('SETTINGS_ITEM_LIMIT');
  const selected = request.selected ? new Set(request.selected) : null;
  for (const [id, change] of desired) {
    if (selected && !selected.has(id)) continue;
    const descriptor = describe().find(row => row.ns === change.ns);
    if (!descriptor) throw Error('SETTINGS_NAMESPACE_CHANGED');
    try {
      if (change.reset) {
        const full = settings.describe().find(row => row.ns === change.ns);
        const next = retainSecrets(full.base?.[change.key], full.value?.[change.key], descriptor.schema.dict[change.key]);
        const operations = resetOps(descriptor.schema?.dict?.[change.key], descriptor.value?.[change.key], [change.key]);
        const candidate = structuredClone(descriptor.value ?? {}); if (next === undefined) delete candidate[change.key]; else candidate[change.key] = next;
        if (request.mode === 'preview') { validate(descriptor, candidate); items.push({ id, ns: change.ns, field: change.key, before: display(change.before), after: display(next), actual: next ?? null }); continue; }
        if (operations.length) await settings.mutate(change.ns, operations, descriptor.revision);
      } else {
        if (request.mode === 'preview') {
          const full = settings.describe().find(row => row.ns === change.ns);
          const candidate = mergeUpdate(structuredClone(full?.value ?? {}), { [change.key]: change.value });
          const actual = redactSecrets(descriptor.schema, validate(descriptor, candidate)).value?.[change.key];
          items.push({ id, ns: change.ns, field: change.key, before: display(change.before), after: display(actual), actual });
          continue;
        }
        await settings.update(change.ns, { [change.key]: change.value }, descriptor.revision);
      }
      const after = settings.describe({ redactSecrets: true }).find(row => row.ns === change.ns);
      if (!after) throw Error('SETTINGS_READBACK_MISSING');
      const actual = after.value?.[change.key];
      items.push({ id, ns: change.ns, field: change.key, before: display(change.before), after: display(actual), actual: actual ?? null });
    } catch (error) {
      if (selected || request.mode !== 'preview') throw Error(`SETTINGS_VALIDATION_FAILED:${change.ns}/${change.key}`);
      warnings.push(`SCHEMA_REJECTED:${change.ns}/${change.key}`);
    }
  }
  if (selected && items.length !== selected.size) throw Error('SETTINGS_SELECTION_CHANGED');
  return { version: 1, items, warnings: [...new Set(warnings)], status: warnings.length ? 'PARTIAL_REVIEW' : 'VERIFIED' };
}
export function apply(ctx) {
  const directory = process.env.DSHA_PROFILE_SETTINGS_DIRECTORY;
  const nonce = process.env.DSHA_PROFILE_SETTINGS_NONCE;
  if (!directory || !/^[a-f0-9]{32}$/.test(nonce ?? '')) throw Error('SETTINGS_TRIAL_ID');
  // 不在插件初始化期间 await loader 自己，避免 await() 相互等待。
  ctx.root.loader.await().then(async () => {
    let result;
    try {
      const request = JSON.parse(readFileSync(join(directory, 'request.json'), 'utf8'));
      if (request.nonce !== nonce) throw Error('SETTINGS_TRIAL_ID');
      const scope = request.mode === 'apply' && request.selected !== undefined ? readSelectedScope(ctx) : null;
      result = await review(ctx.settings, request, readFileSync(join(directory, 'incoming.yml'), 'utf8'),
        scope?.names ?? entryNames(ctx));
      // Java receives the trial candidate only after unselected values and protected fields are checked.
      if (scope) requireSelectedScope(scope, readSelectedScope(ctx), result.items);
    } catch (error) { result = { version: 1, status: 'FAILED', error: String(error.message).replace(/[^A-Za-z0-9_:/.-]/g, '').slice(0, 256) }; }
    result.nonce = nonce;
    writeFileSync(join(directory, 'result.tmp'), JSON.stringify(result), { mode: 0o600 });
    renameSync(join(directory, 'result.tmp'), join(directory, 'result.json'));
  }).catch(() => {});
}

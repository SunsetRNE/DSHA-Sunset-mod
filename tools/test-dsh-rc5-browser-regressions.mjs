import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { testRuntime } from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const assets = path.join(root, 'app/src/main/assets');
const raw = testRuntime('raw');
const projected = process.argv.includes('--projected');
const managed = projected ? null : testRuntime('managed');
const json = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const sha = data => createHash('sha256').update(data).digest('hex');
const lock = fs.readFileSync(path.join(root, 'tools/dsh-runtime/package-lock.json'));
const packages = JSON.parse(lock).packages;
const registry = json(path.join(assets, 'runtime-patches.json')).active;
const sources = {};

function apply(source, patches) {
  for (const { before, after } of patches) {
    assert.equal(source.split(before).length - 1, 1, 'locked patch anchor');
    source = source.replace(before, after);
  }
  return source;
}

function loadSources(asset) {
  assert.equal(registry.filter(row => row.asset === asset).length, 1);
  const recipe = json(path.join(assets, asset));
  const packageName = recipe.module.split('/lib/')[0];
  const packageJson = json(path.join(raw, 'node_modules', packageName, 'package.json'));
  const locked = packages['node_modules/' + packageName];
  const original = fs.readFileSync(path.join(raw, 'node_modules', recipe.module), 'utf8');
  assert.equal(packageJson.name, packageName);
  assert.equal(packageJson.version, recipe.packageVersion);
  assert.equal(packageJson.version, locked.version);
  assert.equal(recipe.packageIntegrity, locked.integrity);
  assert.equal(sha(original), recipe.sourceSha256);
  assert.equal(sha(original), recipe.canonicalSourceSha256);
  const expected = apply(original, recipe.patches);
  const installed = projected ? expected : fs.readFileSync(path.join(managed, 'node_modules', recipe.module), 'utf8');
  if (registry.filter(row => json(path.join(assets, row.asset)).module === recipe.module).length === 1) {
    assert.equal(installed, expected, 'managed browser module equals its locked projection');
  }
  for (const patch of recipe.patches) {
    assert.equal(installed.split(patch.after).length - 1, 1, 'managed module contains each reviewed replacement');
  }
  sources[packageName] = { version: locked.version, rawSha256: sha(original), managedSha256: sha(installed) };
  return { original, installed, predecessor: recipe.previousPatches && apply(original, recipe.previousPatches) };
}

function loadBrowserModule(source, extraExports) {
  let exported;
  const jsx = (type, props, key) => ({ type, props, key });
  const react = {
    memo: component => component,
    forwardRef: component => component,
    useMemo: factory => factory(),
    useCallback: callback => callback,
    useState: initial => [typeof initial === 'function' ? initial() : initial, () => {}],
    useRef: value => ({ current: value }),
    createContext: value => ({ Provider() {}, value }),
    Fragment: 'Fragment',
    createElement: jsx,
  };
  const primitives = new Proxy({}, {
    get: (_, key) => key === '__esModule' ? true : { name: key },
  });
  const require = Object.assign(specifier => {
    switch (specifier) {
      case '@deepseek-ai/cordis': return { Service: class { constructor(ctx) { this.ctx = ctx; } } };
      case 'react': return react;
      case 'react/jsx-runtime': return { jsx, jsxs: jsx, Fragment: 'Fragment' };
      case 'react-dom': return { createPortal: jsx, flushSync: callback => callback() };
      case '@deepseek-ai/dsh-client-ui-primitives': return primitives;
      case '@deepseek-ai/dsh-client-store':
        return { createSnapshotStore: value => ({ getSnapshot: () => value, subscribe: () => () => {} }) };
      case '@tanstack/react-virtual': return { useVirtualizer() {} };
      default: throw new Error('Unexpected require ' + specifier);
    }
  }, { asset: name => 'synthetic-asset:' + name });
  const context = vm.createContext({
    console, performance, TextEncoder, TextDecoder,
    window: { __ModuleLoader__: { load({ factory }) { exported = factory(require); } } },
  });
  const end = 'return module.exports';
  assert.equal(source.split(end).length - 1, 1);
  const instrumented = source.replace(end, 'Object.assign(exports, {' + extraExports.join(',') + '});' + end);
  vm.runInContext(instrumented, context, { timeout: 10_000 });
  return exported;
}

const chatSources = loadSources('tool-history-projection-identity-patch.json');
const toolSources = loadSources('tool-history-display-patch.json');
const toolExports = ['callName', 'ToolCallTree', 'GenericToolCard'];
const chat = loadBrowserModule(chatSources.installed, ['toolDefinition']).toolDefinition;
const ui = loadBrowserModule(toolSources.installed, toolExports);
const oldChat = loadBrowserModule(chatSources.original, ['toolDefinition']).toolDefinition;
const oldUi = loadBrowserModule(toolSources.predecessor, toolExports);
const report = [];
const args = '{"file_path":"/synthetic/sample.txt","offset":1}';
const output = '<path>/synthetic/sample.txt</path>\n<type>file</type>\n<content>\n1: retained sample\n</content>';
const meta = { path: '/synthetic/sample.txt', offset: 1, lines: [{ number: 1, text: 'retained sample' }], totalLines: 1 };
const labels = { invalid: 'DSHA_INVALID_TOOL_CALL', marker: 'data-dsha-invalid-tool-history' };
const read = { name: 'read', cwd: '/synthetic', home: '/synthetic' };
const open = { kind: 'outside' };
const closed = {
  kind: 'step',
  turn: { turn: 1, status: 'closed', end: { seq: 10, time: 10 } },
  step: { step: 1, status: 'closed', end: { seq: 9, time: 9 } },
};

function callEvent(callId, name = read.name) {
  return { type: 'tool/call', seq: 1, time: 0, data: { callId, name, arguments: args, turn: 1, step: 1 } };
}

function resultEvent(callId) {
  return {
    type: 'tool/result', seq: 2, time: 1, surfaceOp: 'append', data: {
      message: { source: { kind: 'tool', callId }, content: [{ type: 'text', text: output }] },
      meta,
    },
  };
}

function liveEvent(chunk, seq) {
  return { type: 'assistant/live-chunk', seq, time: seq, data: { chunk, turn: 1, step: 1 } };
}

function childEvent(type, subCallId, name, parentCallId = 'root') {
  return { type, seq: type.endsWith('-start') ? 2 : 3, time: 1, data: {
    rootCallId: 'root', parentCallId, subCallId, name, arguments: JSON.parse(args),
    content: [{ type: 'text', text: output }], meta,
  } };
}

function deepFreeze(value) {
  if (value && typeof value === 'object') {
    for (const child of Object.values(value)) deepFreeze(child);
    Object.freeze(value);
  }
}

function project(definition, events, location = open, reuseProjection = false) {
  const expected = structuredClone(events);
  const bytes = Buffer.from(events.map(event => JSON.stringify(event)).join('\n'));
  deepFreeze(events);
  const matches = events.map(event => {
    const match = definition.match(event);
    assert.ok(match, 'fixture event accepted by real browser projector');
    return { ...match, event, location };
  });
  const start = matches[0].role === 'start' ? matches[0] : undefined;
  const initial = start === undefined ? undefined : definition.start({}, start);
  const context = { key: 'synthetic-chat-node', id: matches[0].id, current: new Map(), start };
  if (reuseProjection) {
    const previous = definition.buildViewNode({ ...context, state: initial, matches: matches.slice(0, 1) });
    context.current.set('chat', previous);
  }
  const state = start === undefined ? undefined :
    matches.slice(1).reduce((current, match) => definition.update({ state: current }, match), initial);
  const node = definition.buildViewNode({ ...context, state, matches });
  assert.deepEqual(events, expected);
  assert.deepEqual(Buffer.from(events.map(event => JSON.stringify(event)).join('\n')), bytes);
  return node;
}

function tree(renderer, node) {
  const calls = [];
  const actions = [];
  const component = renderer.ToolCallTree({
    node, cwd: read.cwd, sessionId: 'synthetic-session',
    renderSlot: (slot, owner, options) => {
      calls.push({ slot, owner, key: options.entryKey });
      return options.fallback;
    },
    openFile: name => actions.push(['open', name]),
    inspectCall: id => actions.push(['inspect', id]),
    loadImage: name => actions.push(['image', name]),
    useDisclosure: () => ({}),
    useHostInfo: select => select({ home: read.home }),
    t: key => key,
    useContextMenu: () => ({ getTriggerProps: () => ({}) }),
    resolveFilePath: file => file,
  });
  return { row: renderBranch(component), calls, actions };
}

function renderBranch(element) {
  while (typeof element.type === 'function') element = element.type(element.props);
  return element;
}

function readOnly(row, includes = '') {
  assert.equal(row.type, 'pre');
  assert.equal(row.props[labels.marker], true);
  assert.match(row.props.children, /^\[DSHA_INVALID_TOOL_CALL\](?:\n|$)/);
  assert.ok(row.props.children.includes(includes));
  assert.deepEqual(Object.keys(row.props).sort(), ['children', labels.marker, 'style']);
}

function readonlyTree(node, label, includes) {
  const block = node.data.root;
  assert.equal(block.dshaInvalidIdentity, true, label);
  assert.equal(ui.callName({ block, phase: block.phase ?? 'result' }), labels.invalid);
  const rendering = tree(ui, node);
  readOnly(rendering.row, includes);
  assert.deepEqual(rendering.calls, [], label);
  assert.deepEqual(rendering.actions, [], label);
  const mustNotRun = () => assert.fail('Read-only fallback attempted an action or semantic render');
  const fallback = ui.GenericToolCard({
    block, toolName: read.name, cwd: read.cwd, home: read.home,
    t: mustNotRun, inspect: mustNotRun, openFile: mustNotRun, getFileTriggerProps: mustNotRun,
  });
  readOnly(fallback, includes);
  report.push(label);
}

function activeTree(node, label) {
  const rendering = tree(ui, node);
  assert.equal(rendering.row.type, 'div', label);
  assert.equal(rendering.calls.length, 1, label);
  assert.equal(rendering.calls[0].slot, 'tool.call.toolview', label);
  assert.equal(rendering.calls[0].key, read.name, label);
  assert.equal(rendering.calls[0].owner.block, node.data.root, label);
  rendering.calls[0].owner.inspect();
  assert.deepEqual(rendering.actions, [['inspect', node.data.root.callId]], label);
  report.push(label);
  return rendering;
}

for (const [label, callId, name] of [
  ['empty ID', '', 'read'], ['blank ID', '  ', 'read'],
  ['missing ID', undefined, 'read'], ['null ID', null, 'read'],
  ['numeric ID', 17, 'read'], ['empty name', 'call', ''],
  ['blank name', 'call', '  '], ['numeric name', 'call', 17],
]) {
  const node = project(chat, [callEvent(callId, name)]);
  assert.equal(node.data.root.callId, String(callId));
  readonlyTree(node, 'Original retained start: ' + label, args);
}

for (const [label, id, name] of [
  ['numeric ID', 17, 'read'], ['missing ID', undefined, 'read'],
  ['null ID', null, 'read'], ['blank ID', '  ', 'read'], ['blank name', 'call', '  '],
]) {
  const node = project(chat, [callEvent(id, name)], closed);
  assert.equal(node.data.root.error.code, 'interrupted');
  readonlyTree(node, 'Synthetic interruption retains ' + label, args);
}
const validInterrupted = project(chat, [callEvent('valid-interrupted')], closed);
assert.equal(validInterrupted.data.root.error.code, 'interrupted');
activeTree(validInterrupted, 'Valid interruption retains named slot and inspector');

for (const [label, events] of [
  ['raw start identity survives result projection', [callEvent(17), resultEvent('17')]],
  ['raw result identity is validated independently', [callEvent('17'), resultEvent(17)]],
  ['blank start identity survives result projection', [callEvent(''), resultEvent('')]],
  ['orphaned historical result lacks a valid call name', [resultEvent('orphan')]],
]) {
  const node = project(chat, events);
  readonlyTree(node, 'Settled result: ' + label, output);
}

const valid = project(chat, [callEvent('valid'), resultEvent('valid')]);
const rendering = activeTree(valid, 'Valid settled result retains named slot, inspector and original projected block');
const fallback = rendering.row.props.children[0];
const card = fallback.type(fallback.props);
assert.equal(card.props.read.label, 'sample.txt');
assert.equal(card.props.filePath, '/synthetic/sample.txt');
card.props.onOpenFile(card.props.filePath);
assert.deepEqual(rendering.actions, [['inspect', 'valid'], ['open', '/synthetic/sample.txt']]);
report.push('Valid semantic read fallback keeps the file action');

const delta = id => liveEvent({ type: 'tool-call-delta', id, name: 'read', argumentsDelta: args }, 1);
const final = id => liveEvent({ type: 'block-end', block: { type: 'tool-call', id, name: 'read', arguments: args } }, 2);
readonlyTree(project(chat, [delta(17)]), 'Live preview marks malformed ID before conversion');
readonlyTree(project(chat, [delta('17'), final(17)], open, true), 'Final live block invalidates cached preview on identity change');
readonlyTree(project(chat, [delta(17), final('17')]), 'Final live block keeps prior invalid identity provenance');
activeTree(project(chat, [delta('valid'), final('valid')]), 'Valid live preview retains its named slot');
activeTree(project(chat, [delta(17), callEvent('17')]), 'Authoritative valid start supersedes temporary preview identity');

for (const [label, dispatched] of [
  ['blank child name in start', [childEvent('tool/ptc-dispatch-start', 'child', '')]],
  ['numeric child ID in settled result', [childEvent('tool/ptc-dispatch', 17, 'read')]],
  ['numeric parent ID in settled result', [childEvent('tool/ptc-dispatch', 'child', 'read', 17)]],
  ['invalid source survives derived settlement', [
    childEvent('tool/ptc-dispatch-start', 'child', '  '), childEvent('tool/ptc-dispatch', 'child', 'read'),
  ]],
]) {
  const rootId = dispatched[0].data.parentCallId === 17 ? '17' : 'root';
  const events = dispatched.map(event => ({ ...event, data: { ...event.data, rootCallId: rootId } }));
  const node = project(chat, [callEvent(rootId), ...events]);
  assert.equal(node.data.root.subCalls.length, 1);
  activeTree(node, 'Valid parent remains active with ' + label);
  readonlyTree({ data: { root: node.data.root.subCalls[0] } }, 'Derived child: ' + label);
}
const validChild = project(chat, [callEvent('root'), childEvent('tool/ptc-dispatch', 'child', 'read')]);
activeTree({ data: { root: validChild.data.root.subCalls[0] } }, 'Valid derived child retains its named slot and inspector');

function rootSeventeen(event, seq) {
  return { ...event, seq, data: { ...event.data, rootCallId: '17' } };
}

const invalidRoot = callEvent(17);
const child = rootSeventeen(childEvent('tool/ptc-dispatch', 'child', 'read', '17'), 2);
const independent = tree(ui, project(chat, [invalidRoot, child]));
const [parentHistory, independentChildren] = independent.row.props.children;
readOnly(parentHistory, args);
assert.deepEqual(independent.calls, []);
assert.equal(independentChildren.props['data-subcalls'], true);
assert.equal(renderBranch(independentChildren.props.children[0]).type, 'div');
assert.deepEqual(independent.calls.map(({ key, owner }) => [key, owner.callId]), [['read', 'child']]);
independent.calls[0].owner.inspect();
assert.deepEqual(independent.actions, [['inspect', 'child']]);
report.push('Read-only parent preserves valid child named view and inspector');

const middle = rootSeventeen(childEvent('tool/ptc-dispatch', 'middle', ' ', '17'), 2);
const leaf = rootSeventeen(childEvent('tool/ptc-dispatch', 'leaf', 'read', 'middle'), 3);
const sibling = rootSeventeen(childEvent('tool/ptc-dispatch', 'sibling', 'read', '17'), 4);
const nested = tree(ui, project(chat, [invalidRoot, middle, leaf, sibling]));
const [rootHistory, rootChildren] = nested.row.props.children;
readOnly(rootHistory, args);
const [middleHistory, middleChildren] = renderBranch(rootChildren.props.children[0]).props.children;
readOnly(middleHistory, output);
assert.deepEqual(nested.calls, []);
assert.equal(middleChildren.props['data-subcalls'], true);
assert.equal(renderBranch(middleChildren.props.children[0]).type, 'div');
assert.equal(renderBranch(rootChildren.props.children[1]).type, 'div');
assert.deepEqual(nested.calls.map(({ key, owner }) => [key, owner.callId]), [['read', 'leaf'], ['read', 'sibling']]);
for (const { owner } of nested.calls) owner.inspect();
assert.deepEqual(nested.actions, [['inspect', 'leaf'], ['inspect', 'sibling']]);
report.push('Nested invalid ancestors and valid sibling/descendant each use their own identity');

// Use a harmless synthetic read card to distinguish the new guard from the prior name sentinel.
const oldInterrupted = project(oldChat, [callEvent(17)], closed);
assert.equal(tree(oldUi, oldInterrupted).calls[0].key, 'read');
const oldResult = project(oldChat, [callEvent(''), resultEvent('')]);
const oldRendering = tree(oldUi, oldResult);
assert.equal(oldRendering.calls[0].key, 'DSHA_INVALID_TOOL_CALL (read only)');
const oldFallback = oldRendering.row.props.children[0];
assert.equal(oldFallback.type(oldFallback.props).props.read.label, 'sample.txt');
report.push('Prior display control renders a semantic card from an invalid identity');

console.log(JSON.stringify({
  status: projected ? 'PASS_LOCKED_BROWSER_RECIPE_PREVIEW' : 'PASS_MANAGED_BROWSER_HISTORY',
  scope: 'Actual rc.5 browser JavaScript evaluated in a VM with synthetic immutable history and stub React/slot carriers',
  lockSha256: sha(lock), sources, cases: report,
}, null, 2));

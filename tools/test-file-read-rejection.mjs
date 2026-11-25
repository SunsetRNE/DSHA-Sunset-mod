import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {setImmediate} from 'node:timers/promises';
import vm from 'node:vm';
import {testRuntime} from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const lock = JSON.parse(fs.readFileSync(path.join(root, 'tools/dsh-runtime/package-lock.json')));

function loadPatchedFunction(asset, start, end, context) {
  const recipe = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets', asset)));
  const bytes = fs.readFileSync(path.join(runtime, 'node_modules', recipe.module));
  const pkg = lock.packages['node_modules/' + recipe.module.split('/lib/')[0]];
  assert.equal(recipe.dshVersion, pkg.version);
  assert.equal(recipe.packageIntegrity, pkg.integrity);
  assert.equal(createHash('sha256').update(bytes).digest('hex'), recipe.sourceSha256);
  let source = bytes.toString('utf8');
  for (const {before, after} of recipe.patches) {
    assert.equal(source.split(before).length, 2);
    source = source.replace(before, () => after);
  }
  new vm.Script(source);
  assert.equal(source.split(start).length, 2);
  const first = source.indexOf(start), last = source.indexOf(end, first);
  assert.ok(last > first);
  return vm.runInNewContext('(' + source.slice(first + start.length, last) + ')', context);
}

const records = [];
const actions = Object.fromEntries(['loading', 'loaded', 'failed', 'page'].map(name => [name, (...args) => {
  records.push([name, ...args]);
}]));
const fail = reason => Promise.reject(reason);
class RemoteError extends Error {
  constructor(code, message, details) {
    super(message);
    Object.assign(this, {code, details});
  }
}

let list = () => fail(new Error('directory transport disconnected'));
const load = loadPatchedFunction('files-list-rejection-patch.json', 'const load=', ';let batching=', {
  actions, sessionId: 'session', Error, RemoteError, list: (...args) => list(...args),
});
const directorySignal = new AbortController();
assert.equal(await load('pane', '/work', directorySignal.signal), undefined);
assert.deepEqual(records.shift(), ['loading', 'pane', '/work']);
const directoryError = records.shift();
assert.deepEqual(directoryError.slice(0, 3), ['failed', 'pane', '/work']);
assert.equal(directoryError[3].code, 'gateway/internal');
assert.equal(directoryError[3].message, 'directory transport disconnected');
list = async (...args) => {
  assert.deepEqual(args, ['session', '/work', directorySignal.signal]);
  return {ok: true, value: {entries: [], truncated: false}};
};
assert.deepEqual(await load('pane', '/work', directorySignal.signal), {entries: [], truncated: false});
assert.deepEqual(records.splice(0).map(([name]) => name), ['loading', 'loaded']);
list = () => { directorySignal.abort(); return fail('cancelled'); };
assert.equal(await load('pane', '/work', directorySignal.signal), undefined);
assert.deepEqual(records.splice(0).map(([name]) => name), ['loading']);

const reads = {generation: 7};
let read = () => fail(new Error('page transport disconnected'));
const loadPage = loadPatchedFunction('text-page-rejection-patch.json', 'const loadPage=', ';const loadAll=', {
  actions, Error, modeOf: () => reads, read: (...args) => read(...args),
});
const file = {sessionId: 'session', path: '/work/story.txt'};
const previewSignal = new AbortController();
loadPage('tab', file, 1, previewSignal.signal, 'old-version');
await setImmediate();
assert.deepEqual(records.shift(), ['loading', 'tab', 'text-pages', 'old-version']);
const pageError = records.shift();
assert.deepEqual(pageError.slice(0, 2), ['failed', 'tab']);
assert.equal(pageError[2].code, 'gateway/internal');
assert.equal(pageError[2].cause.message, 'page transport disconnected');
read = async () => ({ok: true, value: {version: 'current'}});
loadPage('tab', file, 1, previewSignal.signal, 'old-version');
await setImmediate();
assert.equal(reads.version, 'current');
assert.deepEqual(records.splice(0).map(([name]) => name), ['loading', 'page']);
for (const stale of [() => { reads.generation += 1; }, () => previewSignal.abort()]) {
  read = () => new Promise((_, reject) => { queueMicrotask(() => { stale(); reject(Error('obsolete')); }); });
  loadPage('tab', file, 1, previewSignal.signal, 'current');
  await setImmediate();
  assert.deepEqual(records.splice(0).map(([name]) => name), ['loading']);
}
console.log('PASS locked directory and text-page read failure, success, cancellation and stale-generation checks');

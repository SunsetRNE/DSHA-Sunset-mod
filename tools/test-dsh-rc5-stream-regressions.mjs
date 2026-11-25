// 执行锁定的 rc.5 SSE reader 和 watchdog，只使用进程内的流与取消信号。
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const assets = path.join(root, 'app/src/main/assets');
const raw = testRuntime('raw');
const version = '0.2.2-rc.5';
const registry = JSON.parse(fs.readFileSync(path.join(assets, 'runtime-patches.json'), 'utf8'));
assert.equal(registry.dshVersion, version);

const argv = process.argv.slice(2);
assert.ok(!argv.length || (argv.length === 2 && argv[0] === '--managed-runtime'),
  'Usage: node tools/test-dsh-rc5-stream-regressions.mjs [--managed-runtime ROOT]');
const managed = argv.length ? path.resolve(argv[1]) : undefined;
const build = path.join(root, 'app/build');
fs.mkdirSync(build, { recursive: true });
const scratch = fs.mkdtempSync(path.join(build, 'stream-rc5-'));
const modules = path.join(scratch, 'node_modules');
const windows = process.platform === 'win32';
const temporary = [];
const sha = data => createHash('sha256').update(data).digest('hex');
const sources = [];
const records = [];

async function load(name, module, rawSha256, expose = '') {
  const source = fs.readFileSync(path.join(raw, 'node_modules', module), 'utf8');
  assert.equal(sha(source), rawSha256, module);
  let projected = source;
  const applied = [];
  for (const row of registry.active) {
    const spec = JSON.parse(fs.readFileSync(path.join(assets, row.asset), 'utf8'));
    if ((row.module ?? spec.module) !== module) continue;
    assert.equal(spec.dshVersion, version, row.asset);
    if (spec.sourceSha256) assert.equal(spec.sourceSha256, rawSha256, row.asset);
    for (const edit of spec.patches) {
      assert.equal(projected.split(edit.before).length, 2, row.asset);
      const prefix = edit.prependAsset ? fs.readFileSync(path.join(assets, edit.prependAsset), 'utf8') + '\n' : '';
      projected = projected.replace(edit.before, () => prefix + edit.after);
    }
    for (const marker of row.markers ?? []) assert.ok(projected.includes(marker), row.asset);
    applied.push(row.asset);
  }
  const corrected = managed ? fs.readFileSync(path.join(managed, 'node_modules', module), 'utf8') : projected;
  assert.notEqual(corrected, source, module);
  assert.equal(sha(corrected), sha(projected), `managed bytes diverged from active recipes: ${module}`);
  sources.push({ module, rawSha256, managedSha256: sha(corrected), applied });
  return Promise.all(Object.entries({ raw: source, managed: corrected }).map(([mode, text]) => {
    const file = path.join(scratch, `${name}-${mode}.mjs`);
    temporary.push(file);
    fs.writeFileSync(file, text + expose);
    return import(pathToFileURL(file).href);
  }));
}

async function bounded(promise, name) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`fixture timed out: ${name}`)), 1500);
    })]);
  } finally {
    clearTimeout(timer);
  }
}

function observe(promise) {
  const record = { outcome: undefined };
  record.done = promise.then(
    value => (record.outcome = { state: 'resolved', value }),
    reason => (record.outcome = { state: 'rejected', reason }));
  return record;
}

try {
  fs.symlinkSync(path.join(raw, 'node_modules'), modules, windows ? 'junction' : 'dir');
  const [rawWatch, managedWatch] = await load('watchdog', '@deepseek-ai/dsh-timeout/lib/index.js',
    'f9a79fd5194bb4d6321e6d2d5b5eb72f99c683452e1defbb75fad52f76aed667');
  const [rawSse, managedSse] = await load('sse', '@deepseek-ai/dsh-llm-deepseek/lib/index.js',
    'aa4de6cf5136d17534ee7c2c0cc43d9b17b2f1e0c9dc250c2b44a7f694d26258', '\nexport { parseSse };\n');

  for (const [mode, api] of Object.entries({ raw: rawWatch, managed: managedWatch })) {
    const abort = new AbortController();
    const reason = new Error('synthetic user cancellation');
    const gate = Promise.withResolvers();
    const watch = api.idleWatchdog(abort.signal, 5000, 'FIXTURE_IDLE');
    const attempt = observe(watch.next({ next: () => gate.promise }));
    try {
      abort.abort(reason);
      if (mode === 'raw') {
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(attempt.outcome, undefined, 'raw watchdog still waits on the iterator');
      } else {
        assert.deepEqual(await bounded(attempt.done, 'watchdog'), { state: 'rejected', reason });
        assert.equal(attempt.outcome.reason, reason);
      }
      records.push({ mode, capability: 'watchdog', outcomeBeforeRelease: attempt.outcome?.state ?? 'pending' });
    } finally {
      gate.resolve({ done: true });
      await attempt.done;
      watch[Symbol.dispose]();
    }
  }

  for (const [mode, api] of Object.entries({ raw: rawSse, managed: managedSse })) {
    const abort = new AbortController();
    const reason = new Error('synthetic body cancellation');
    const reading = Promise.withResolvers();
    let bodyController, cancelled = false, bodyReason;
    const body = new ReadableStream({
      start(controller) { bodyController = controller; },
      pull() { reading.resolve(); },
      cancel(cause) { cancelled = true; bodyReason = cause; }
    }, { highWaterMark: 0 });
    const iterator = api.parseSse(body, () => {}, abort.signal);
    const attempt = observe(iterator.next());
    try {
      await bounded(reading.promise, 'body read started');
      abort.abort(reason);
      if (mode === 'raw') {
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(attempt.outcome, undefined, 'raw event stream still waits on the body');
        assert.equal(cancelled, false);
      } else {
        assert.deepEqual(await bounded(attempt.done, 'SSE cancellation'), { state: 'rejected', reason });
        assert.equal(attempt.outcome.reason, reason);
        assert.equal(cancelled, true);
        assert.equal(bodyReason, reason);
      }
      records.push({ mode, capability: 'SSE reader', outcomeBeforeRelease: attempt.outcome?.state ?? 'pending', cancelled });
    } finally {
      if (!cancelled) bodyController.close();
      await bounded(attempt.done, 'SSE cleanup');
      await iterator.return();
    }
  }
} finally {
  for (const file of temporary) fs.rmSync(file, { force: true });
  if (fs.existsSync(modules)) {
    if (windows) fs.rmdirSync(modules);
    else fs.unlinkSync(modules);
  }
  fs.rmdirSync(scratch);
}

console.log(JSON.stringify({
  status: 'PASS_REAL_RC5_STREAM_POSITIVE_NEGATIVE',
  version, platform: process.platform,
  managedSource: managed ? 'archive-extracted module bytes, checked against the active projection' : 'active recipe projection',
  sources, records,
  scope: 'Locked modules, native ReadableStream and AbortController, controlled pending reads. No model, HTTP or user data.'
}, null, 2));

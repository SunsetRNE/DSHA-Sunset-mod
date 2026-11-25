import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import vm from 'node:vm';
import { testRuntime } from './test-runtime-fixture.mjs';

const assets = new URL('../app/src/main/assets/', import.meta.url);
const recipeName = 'plugin-bundle-proof-workers-patch.json';
const readAsset = name => readFileSync(new URL(name, assets), 'utf8');
const sha = value => createHash('sha256').update(value).digest('hex');
const recipe = JSON.parse(readAsset(recipeName));
const canonical = readFileSync(join(testRuntime('raw'), 'node_modules', recipe.module), 'utf8');
assert.equal(sha(canonical), recipe.sourceSha256);
const registry = JSON.parse(readAsset('runtime-patches.json'));
assert.equal(registry.dshVersion, '0.2.2-rc.5');
let projected = canonical;
for (const row of registry.active) {
  const current = JSON.parse(readAsset(row.asset));
  if ((row.module ?? current.module) !== recipe.module) continue;
  if (current.sourceSha256) assert.equal(current.sourceSha256, sha(canonical), row.asset);
  for (const edit of current.patches) {
    assert.equal(projected.split(edit.before).length, 2, row.asset);
    const prefix = edit.prependAsset ? readAsset(edit.prependAsset) + '\n' : '';
    projected = projected.replace(edit.before, prefix + edit.after);
  }
}

// A freshly extracted production module can additionally be checked byte for byte.
const argv = process.argv.slice(2);
assert.ok(!argv.length || (argv.length === 2 && argv[0] === '--managed-module'));
const managed = argv.length ? readFileSync(argv[1], 'utf8') : projected;
assert.equal(sha(managed), sha(projected));
assert.equal(managed.split(recipe.patches[0].after).length, 2);
const fixed = recipe.patches[0].after;

// Re-introduce the falsey sentinel defect as a negative control, leaving the
// actual concurrency and classification logic intact.
const broken = fixed.replaceAll('dshaFailure !== void 0', 'dshaFailure?.error');
assert.notEqual(broken, fixed);

async function execute(body, reason, timing) {
  const abort = new AbortController();
  const bothStarted = Promise.withResolvers();
  const firstReleased = Promise.withResolvers();
  const peerReleased = Promise.withResolvers();
  const firstEnded = Promise.withResolvers();
  const started = [];
  let active = 0;
  let maxActive = 0;
  let settled = false;
  const env = {
    Map, Set, Promise, process, names: ['first', 'peer', 'unscheduled'], dependencies: [],
    selected: [], recorded: {}, catalog: new Map(), exemptions: [], OPTIONAL_BUNDLES: [],
    dshaSignedPluginNames: new Set(), getDshRuntimeVersion: () => registry.dshVersion,
    dshaReadProofSession: async () => ({ signal: abort.signal }),
    async dshaManagedInstallationBundle(name, _profile, session) {
      started.push(name);
      maxActive = Math.max(maxActive, ++active);
      if (started.length === 2) bothStarted.resolve();
      try {
        await Promise.resolve();
        if (timing === 'in-flight' && name === 'first') await firstReleased.promise;
        if (timing === 'in-flight' && name === 'peer') await peerReleased.promise;
        session.signal.throwIfAborted();
        return false;
      } finally {
        active--;
        if (name === 'first') firstEnded.resolve();
      }
    },
  };
  if (timing === 'pre-aborted') abort.abort(reason);
  const run = vm.runInNewContext('(async function(dshaOnlyName){' + body + '\nreturn bundles;})', env);
  const operation = run.call({ profile: {} }).then(
    rows => { settled = true; return { status: 'resolved', rows }; },
    error => { settled = true; return { status: 'rejected', error }; },
  );
  if (timing === 'in-flight') {
    try {
      await bothStarted.promise;
      abort.abort(reason);
      firstReleased.resolve();
      await firstEnded.promise;
      await new Promise(resolve => setImmediate(resolve));
      assert.equal(settled, false, 'list settled while a started proof remained active');
      assert.equal(active, 1);
      assert.deepEqual(started, ['first', 'peer']);
    } finally {
      firstReleased.resolve();
      peerReleased.resolve();
    }
  }
  return { ...(await operation), started, active, maxActive };
}

const records = [];
for (const [label, reason] of [['null', null], ['false', false], ['zero', 0], ['empty-string', ''],
  ['default', undefined], ['error-object', new Error('fixture cancellation')]]) {
  for (const timing of ['pre-aborted', 'in-flight']) {
    const actual = await execute(fixed, reason, timing);
    assert.equal(actual.status, 'rejected');
    assert.deepEqual(actual.started, ['first', 'peer']);
    assert.equal(actual.active, 0);
    assert.equal(actual.maxActive, 2);
    if (reason === undefined) assert.equal(actual.error.name, 'AbortError');
    else assert.equal(actual.error, reason);
    records.push({ timing, reason: label, proofsStarted: actual.started.length, drained: true });
  }
  if (reason !== undefined && !reason) {
    const prior = await execute(broken, reason, 'pre-aborted');
    assert.equal(prior.status, 'resolved');
    assert.equal(prior.rows.length, 0);
    assert.equal(prior.started.length, 3);
  }
}
console.log(JSON.stringify({
  status: 'PASS_RC5_PROOF_CANCELLATION', sourceSha256: sha(canonical), managedSha256: sha(managed),
  comparedManagedModule: argv.length === 2, records,
  scope: 'Exact managed worker loop with a controlled proof provider and AbortController.',
}, null, 2));

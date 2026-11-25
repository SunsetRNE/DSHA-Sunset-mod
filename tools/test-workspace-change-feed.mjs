import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import { registerHooks } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const asset = 'workspace-change-feed-bounded-patch.json';
const recipe = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets', asset), 'utf8'));
const file = path.join(runtime, 'node_modules', recipe.module);
const raw = fs.readFileSync(file, 'utf8');
const lock = JSON.parse(fs.readFileSync(path.join(root, 'tools/dsh-runtime/package-lock.json'), 'utf8'));
const pkg = 'node_modules/' + recipe.module.split('/').slice(0, 2).join('/');
assert.equal(lock.packages[pkg].version, recipe.dshVersion);
assert.equal(lock.packages[pkg].integrity, recipe.packageIntegrity);
assert.equal(createHash('sha256').update(raw).digest('hex'), recipe.sourceSha256);

const managed = execFileSync(process.env.DSHA_PYTHON || 'python', ['-B', '-c', [
  'import importlib.util,sys;from pathlib import Path',
  'sys.path.insert(0,"tools")',
  'spec=importlib.util.spec_from_file_location("builder","tools/build-dsh-runtime.py")',
  'builder=importlib.util.module_from_spec(spec);spec.loader.exec_module(builder)',
  'chains,_=builder.load_chains(Path("app/src/main/assets"),sys.argv[1])',
  'chain=chains[sys.argv[2]];assert any(name==sys.argv[3] for name,_,_ in chain)',
  'output=builder.apply_stable_patches(Path(sys.argv[4]).read_bytes(),chain)',
  'assert builder.apply_stable_patches(output,chain)==output',
  'sys.stdout.buffer.write(output)',
].join('\n'), recipe.dshVersion, recipe.module, asset, file], { cwd: root, encoding: 'utf8' });

const hook = registerHooks({
  load(url, context, next) {
    const target = new URL(url);
    const variant = target.searchParams.get('workspace-feed-test');
    if (variant && target.protocol === 'file:' && fileURLToPath(target) === file) {
      const source = variant === 'managed' ? managed : raw;
      return { format: 'module', shortCircuit: true,
        source: source + '\nexport { WorkspaceChangeFeed };\n' };
    }
    return next(url, context);
  },
});
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-workspace-change-feed-'));

function within(operation) {
  const timeout = new Promise((_, reject) => {
    setTimeout(() => reject(Error('workspace feed did not settle')), 3000).unref();
  });
  return Promise.race([operation, timeout]);
}

try {
  for (const variant of ['raw', 'managed']) {
    const { WorkspaceChangeFeed } = await import(pathToFileURL(file).href + '?workspace-feed-test=' + variant);
    const directory = fs.mkdtempSync(path.join(temporary, variant));
    const targetPath = path.join(directory, 'observed.txt');
    fs.writeFileSync(targetPath, 'first');
    const targetOf = value => ({ targetKey: path.resolve(value) });
    const target = targetOf(targetPath);
    const other = targetOf(path.join(directory, 'unrelated.txt'));
    let observe, watch, unwatched = 0, stats = 0;
    const backend = {
      async resolve(value, options) {
        options.signal.throwIfAborted();
        return targetOf(path.resolve(options.cwd || directory, value));
      },
      async stat(value, signal) {
        signal.throwIfAborted();
        stats++;
        const info = fs.statSync(value.targetKey, { bigint: true, throwIfNoEntry: false });
        if (info) return { type: info.isDirectory() ? 'directory' : 'file', version: `${info.mtimeNs}:${info.size}` };
      },
      contains(base, entry) {
        const relative = path.relative(base.targetKey, entry.targetKey);
        return !relative.startsWith('..') && !path.isAbsolute(relative);
      },
      async watch(_target, callback, signal) {
        signal.throwIfAborted();
        watch = callback;
        return () => { unwatched++; };
      },
      processPath: entry => entry.targetKey,
    };
    const feed = new WorkspaceChangeFeed({ fs: backend,
      on(event, callback) { assert.equal(event, 'fs/observed'); observe = callback; },
      effect() {}, logger: { error(error) { throw error; } } });
    const controller = new AbortController();
    const iterator = feed.follow(directory, targetPath, controller.signal);
    assert.deepEqual((await within(iterator.next())).value, { kind: 'ready' });
    const [follower] = feed.followers;
    for (let index = 0; index < 50000; index++) observe(other, { version: `other-${index}` });
    if (variant === 'raw') assert.equal(follower.queue.size, 50000);
    else assert.equal(follower.pending, undefined);
    for (let index = 0; index < 50000; index++) observe(target, { version: `target-${index}` });
    if (variant === 'raw') assert.equal(follower.queue.size, 100000);
    else {
      assert.equal(follower.queue, undefined);
      assert.equal(follower.pending[1].version, 'target-49999');
    }
    fs.writeFileSync(targetPath, 'current content after the entire invalidation burst');
    const expected = await backend.stat(target, controller.signal);
    const readsBefore = stats;
    assert.equal((await within(iterator.next())).value.change.version, expected.version);
    assert.equal(stats - readsBefore, 1);
    if (variant === 'managed') {
      assert.equal(follower.pending, undefined);
      watch();
      assert.ok(follower.pending);
      fs.unlinkSync(targetPath);
      assert.deepEqual((await within(iterator.next())).value.change, { absolutePath: targetPath, absent: true });
    }
    controller.abort();
    assert.equal((await within(iterator.next())).done, true);
    assert.equal(unwatched, 1);
    assert.equal(feed.followers.size, 0);

    fs.writeFileSync(targetPath, 'again');
    const failed = feed.follow(directory, targetPath, new AbortController().signal);
    await within(failed.next());
    const failure = Error('test watcher failure');
    const pending = failed.next();
    watch(failure);
    await assert.rejects(within(pending), error => error === failure);
    assert.equal(unwatched, 2);
    assert.equal(feed.followers.size, 0);
  }
  console.log('PASS workspace change feed: raw negative control and managed coalescing, current stat, cancellation, watcher errors');
} finally {
  hook.deregister();
  fs.rmSync(temporary, { recursive: true, force: true });
}

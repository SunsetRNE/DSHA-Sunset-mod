import { testRuntime } from './test-runtime-fixture.mjs';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { createHash } from 'node:crypto';

const contracts = new Map([
  ['0.2.1-rc.5', ['0.2.1-rc.5', 'lan-settings-upstream-contract.json']],
  ['0.2.2-rc.1', ['0.2.1-rc.5', 'lan-settings-upstream-contract.json']],
  ['0.2.2-rc.5', ['0.2.2-rc.5', 'lan-settings-upstream-contract-rc5.json']],
]);

// Standard invocation still requires the complete current testRuntime proof below.
// A separately locked cross-version runner may register the same real tests against
// its isolated tree; it must bind that tree's dependency lock and package bytes.
export async function registerLanSettingsHostTests(runtime) {
  const version = JSON.parse(await readFile(join(runtime, 'node_modules/@deepseek-ai/dsh/package.json'), 'utf8')).version;
  const selected = contracts.get(version);
  assert.ok(selected, 'LAN_SETTINGS_VERSION: ' + version);
  const [family, asset] = selected;
  const contract = JSON.parse(await readFile(new URL('../app/src/main/assets/' + asset, import.meta.url)));
  assert.equal(contract.schema, 1);
  assert.equal(contract.dshVersion, family);
  assert.equal(contract.module, '@deepseek-ai/dsh-client-ui-settings/lib/client.js');
  assert.equal(contract.requiredAnchors.length, 4);
  assert.equal(contract.forbiddenAnchors.length, 2);
  const source = await readFile(join(runtime, 'node_modules', contract.module), 'utf8');
  assert.equal(createHash('sha256').update(source).digest('hex'), contract.sourceSha256);
  for (const anchor of contract.requiredAnchors) {
    assert.notEqual(anchor, '');
    assert.equal(source.split(anchor).length - 1, 1);
  }
  for (const anchor of contract.forbiddenAnchors) assert.equal(source.includes(anchor), false);

  const require = createRequire(join(runtime, 'package.json'));
  const imported = async name => import(pathToFileURL(require.resolve(name)));
  const cordis = await imported('@deepseek-ai/cordis');
  // The browser bundle supplies these store dependencies. Rebind only those four
  // imports to an independently npm-ci-locked real tree, never to an imitation store.
  const dependencyRoot = resolve(process.env.DSHA_LAN_STORE_DEPS || 'app/build/runtime190-store-deps');
  const expectedLock = await readFile(new URL('./fixtures/lan-store-deps/package-lock.json', import.meta.url), 'utf8');
  assert.deepEqual(JSON.parse(await readFile(join(dependencyRoot, 'package-lock.json'), 'utf8')), JSON.parse(expectedLock));
  const dependencyRequire = createRequire(join(dependencyRoot, 'package.json'));
  const storeBody = await readFile(require.resolve('@deepseek-ai/dsh-client-store'), 'utf8');
  let rebound = 0;
  const linkedStore = storeBody.replace(/from "(zustand[^"\n]*|immer)"/g, (_all, name) => {
    rebound++;
    return 'from ' + JSON.stringify(pathToFileURL(dependencyRequire.resolve(name)).href);
  });
  assert.equal(rebound, 4);
  const store = await import('data:text/javascript;base64,' + Buffer.from(linkedStore).toString('base64'));
  const { SettingsForms } = await imported('@deepseek-ai/dsh-settings');
  const { default: z } = await imported('@deepseek-ai/schemastery');
  let client;
  // Both pinned bundle families have exactly one factory return. Inject exports
  // there; do not pretty-print/minify or replace the real controller implementation.
  const marker = 'return module.exports';
  assert.equal(source.split(marker).length - 1, 1);
  new Function('window', source.replace(marker, 'exports.__test = { ConfigFormController, SettingsDescribeMirror };' + marker))({
    __ModuleLoader__: { load(definition) { client = definition.factory(name => {
      if (name === '@deepseek-ai/cordis') return cordis;
      if (name === '@deepseek-ai/dsh-client-store') return store;
      throw new Error('Unexpected client dependency: ' + name);
    }); } },
  });
  const { ConfigFormController, SettingsDescribeMirror } = client.__test;
  assert.equal(typeof ConfigFormController, 'function');
  assert.equal(typeof SettingsDescribeMirror, 'function');
  const clone = value => JSON.parse(JSON.stringify(value));
  async function host(file) {
    const value = JSON.parse(await readFile(file, 'utf8'));
    const Config = z.object({ model: z.string().volatile(), temperature: z.number().min(0).volatile() });
    const runtime = { Config };
    const fiber = {
      uid: 'model', state: 2, runtime, ctx: {}, config: cordis.resolveConfig(runtime, value),
      // rc.5 reads effectiveConfig; the old family reads config. Both describe
      // the same controlled active fiber, updated only after disk publication.
      get effectiveConfig() { return this.config; },
    };
    const entry = { id: 'model', options: { id: 'model', config: clone(value) }, fiber };
    // Never construct SettingsForms: its Loader/legacy bootstrap can discover a
    // real profile. Exercise its real methods with only this owned disk boundary.
    const settings = Object.create(SettingsForms.prototype);
    settings.revisions = new Map();
    settings.presentations = new Map();
    let writes = 0;
    settings.ownerContext = {
      profileContext: { home: dirname(file), name: 'lan-fixture' }, emit() {}, logger: { info() {} },
      configEditor: {
        entries: () => [entry], configuration: () => [{ entry, inherited: {}, override: entry.options.config }],
        async edit(item, change) {
          const next = change(clone(item.options.config), {});
          const config = cordis.resolveConfig(runtime, next);
          await writeFile(file, JSON.stringify(next));
          item.options.config = next;
          item.fiber.config = config;
          writes++;
        },
      },
    };
    return { settings, get writes() { return writes; } };
  }
  function form(api, loopback) {
    let loopbackReads = 0;
    const calls = [];
    const remote = { $host: { get isLoopback() { loopbackReads++; return loopback; } }, settings: {
      async describe() {
        calls.push('describe');
        return { ok: true, value: { writable: api.settings.writable, namespaces: api.settings.describe() } };
      },
      async mutate(ns, ops, revision) {
        calls.push({ ns, ops: clone(ops), revision });
        try {
          await api.settings.mutate(ns, ops, revision);
          return { ok: true, value: api.settings.describe().find(row => row.ns === ns) };
        } catch (error) { return { ok: false, error: { message: error.message } }; }
      },
    } };
    const context = { remote };
    const mirror = new SettingsDescribeMirror(context);
    const controller = new ConfigFormController(context, { namespace: 'model', decode: value => value }, mirror, {});
    return { mirror, controller, calls, get loopbackReads() { return loopbackReads; } };
  }
  async function fixture(body) {
    const temporary = await mkdtemp(join(tmpdir(), 'dsha-lan-contract-'));
    const file = join(temporary, 'settings.json');
    try {
      await writeFile(file, JSON.stringify({ model: 'initial', temperature: 1 }));
      return await body(file);
    } finally {
      assert.equal(dirname(resolve(temporary)), resolve(tmpdir()));
      assert.ok(basename(temporary).startsWith('dsha-lan-contract-'));
      await rm(temporary, { recursive: true, force: true });
    }
  }
  for (const loopback of [true, false]) test(`${version}: Host persistence survives client and Host recreation, isLoopback=${loopback}`, async () => {
    await fixture(async file => {
      const api = await host(file), first = form(api, loopback);
      await first.mirror.ensure();
      assert.equal(first.controller.getSnapshot().status, 'ready');
      assert.equal(await first.controller.set('model', 'persisted-on-host'), true);
      assert.equal(await first.controller.set('temperature', 0.7), true);
      assert.equal(api.writes, 2);
      assert.equal(first.loopbackReads, 0);
      assert.deepEqual(first.calls.filter(x => typeof x === 'object').map(x => x.revision), [0, 1]);
      await first.controller.dispose();
      const reopened = await host(file), second = form(reopened, loopback);
      await second.mirror.ensure();
      assert.deepEqual(second.controller.getSnapshot().value, { model: 'persisted-on-host', temperature: 0.7 });
      assert.equal(second.loopbackReads, 0);
      assert.equal(reopened.writes, 0);
      await second.controller.dispose();
    });
  });
  test(`${version}: LAN queued writes advance the latest Host revision in order`, async () => {
    await fixture(async file => {
      const api = await host(file), view = form(api, false);
      await view.mirror.ensure();
      const first = view.controller.set('model', 'queued-first');
      const second = view.controller.set('temperature', 0.4);
      assert.deepEqual(await Promise.all([first, second]), [true, true]);
      assert.deepEqual(view.calls.filter(x => typeof x === 'object').map(x => x.revision), [0, 1]);
      assert.equal(view.controller.getSnapshot().revision, 2);
      assert.deepEqual(JSON.parse(await readFile(file, 'utf8')), { model: 'queued-first', temperature: 0.4 });
      assert.equal(api.writes, 2);
      assert.equal(view.loopbackReads, 0);
      await view.controller.dispose();
    });
  });
  test(`${version}: LAN stale revision is rejected, disk preserved and client re-reads`, async () => {
    await fixture(async file => {
      const api = await host(file), first = form(api, false), stale = form(api, false);
      await first.mirror.ensure();
      await stale.mirror.ensure();
      assert.equal(await first.controller.set('model', 'authoritative'), true);
      const before = await readFile(file, 'utf8');
      assert.equal(await stale.controller.set('temperature', 0.9), false);
      assert.equal(await readFile(file, 'utf8'), before);
      assert.equal(api.writes, 1);
      assert.deepEqual(stale.controller.getSnapshot().value, { model: 'authoritative', temperature: 1 });
      assert.equal(stale.controller.getSnapshot().revision, 1);
      assert.equal(stale.calls.filter(x => x === 'describe').length, 2);
      assert.equal(stale.loopbackReads, 0);
      await first.controller.dispose();
      await stale.controller.dispose();
    });
  });
  test(`${version}: LAN schema-invalid write is rejected by actual Host and client re-reads retained value`, async () => {
    await fixture(async file => {
      const api = await host(file), view = form(api, false);
      await view.mirror.ensure();
      const before = await readFile(file, 'utf8');
      assert.equal(await view.controller.set('temperature', -1), false);
      assert.equal(api.writes, 0);
      assert.equal(await readFile(file, 'utf8'), before);
      assert.equal(view.controller.getSnapshot().value.temperature, 1);
      assert.equal(view.loopbackReads, 0);
      await view.controller.dispose();
    });
  });
  return { version, contractFamily: family, contractAsset: asset, sourceSha256: contract.sourceSha256 };
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  await registerLanSettingsHostTests(testRuntime('raw'));
}

// Compare the retained runtime, the three signed recipes over it, or a prepared runtime.
// node tools/test-local-runtime-policy.mjs baseline|recipes|installed HOST_RUNTIME [GZIP_ARCHIVE]
import './fixtures/data-compat/network-guard.mjs';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire, registerHooks } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const [mode, runtimeArg, archive] = process.argv.slice(2);
assert.ok(['baseline', 'recipes', 'installed'].includes(mode) && runtimeArg, 'supply a mode and runtime directory');
const baseline = mode === 'baseline';
const assets = resolve(import.meta.dirname, '../app/src/main/assets');
const modules = join(resolve(runtimeArg), 'node_modules');
const require = createRequire(join(modules, '@deepseek-ai/dsh/package.json'));
const get = (name) => import(pathToFileURL(require.resolve('@deepseek-ai/' + name)).href);
const temp = mkdtempSync(join(tmpdir(), 'dsha-local-policy-'));
Object.assign(process.env, { DSH_HOME: temp, HOME: temp, USERPROFILE: temp });
const read = file => readFileSync(file, 'utf8');
const bytes = new Map();
const findings = { mode, node: process.version, modules: {} };
const hash = text => createHash('sha256').update(text).digest('hex');

function prepareSources() {
  const names = ['local-feedback-policy', 'api-provider-auth-policy', 'web-default-brand'];
  const registry = JSON.parse(read(join(assets, 'runtime-patches.json')));
  if (archive) {
    assert.notEqual(mode, 'recipes', 'supply a prepared archive for baseline or installed mode');
    const paths = names.map(name => JSON.parse(read(join(assets, name + '-patch.json'))).module);
    const extract = `
import hashlib, pathlib, sys, tarfile
archive, target, *modules = sys.argv[1:]
wanted = {"usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/" + name: name for name in modules}
with open(archive, "rb") as fp:
    print(hashlib.file_digest(fp, "sha256").hexdigest())
with tarfile.open(archive, "r|gz") as tar:
    for entry in tar:
        if entry.name not in wanted: continue
        name = wanted.pop(entry.name)
        if not entry.isfile(): raise ValueError("missing ordinary module: " + name)
        path = pathlib.Path(target, name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(tar.extractfile(entry).read())
if wanted: raise ValueError("missing archive members: " + repr(wanted))
`;
    const result = spawnSync(process.env.DSHA_PYTHON ?? (process.platform === 'win32' ? 'python' : 'python3'),
      ['-c', extract, resolve(archive), temp, ...paths], { encoding: 'utf8', windowsHide: true });
    assert.equal(result.status, 0, result.stderr);
    findings.archiveSha256 = result.stdout.trim();
  }
  for (const name of names) {
    const asset = name + '-patch.json';
    assert.equal(registry.active.filter(row => row.asset === asset).length, 1, asset);
    const recipe = JSON.parse(read(join(assets, asset)));
    const target = join(modules, recipe.module);
    let source = read(archive ? join(temp, recipe.module) : target);
    const input = hash(source);
    for (const { before, after } of recipe.patches) {
      if (mode === 'installed') {
        assert.equal(source.split(after).length, 2, 'missing installed edit: ' + asset);
      } else {
        assert.equal(source.split(before).length, 2, 'unexpected baseline: ' + asset);
        assert.ok(!source.includes(after), 'already patched baseline: ' + asset);
        if (mode === 'recipes') source = source.replace(before, () => after);
      }
    }
    bytes.set(target, source);
    findings.modules[recipe.module] = { input, tested: hash(source) };
  }
  if (mode === 'recipes' || archive) registerHooks({ load(url, context, next) {
    if (url.startsWith('file:')) {
      const value = bytes.get(fileURLToPath(url));
      if (value !== undefined) return { format: 'module', source: value, shortCircuit: true };
    }
    return next(url, context);
  } });
}

async function profiles() {
  const boot = await get('dsh-app-boot');
  // A private installation anchor lets the real bundle loader read the exact selected YAML.
  const install = join(temp, 'install');
  const anchor = join(install, 'package.json');
  mkdirSync(install);
  writeFileSync(anchor, '{}');
  for (const name of ['dsh-base', 'dsh-web-app', 'dsh-headless']) {
    const dir = join(install, 'node_modules', '@deepseek-ai', name);
    const original = join(modules, '@deepseek-ai', name);
    mkdirSync(dir, { recursive: true });
    copyFileSync(join(original, 'package.json'), join(dir, 'package.json'));
    const { patch } = JSON.parse(read(join(original, 'package.json'))).dsh.bundle;
    for (const file of [patch].flat()) {
      const yaml = join(original, file);
      mkdirSync(dirname(join(dir, file)), { recursive: true });
      writeFileSync(join(dir, file), bytes.get(yaml) ?? read(yaml));
    }
  }
  const check = (label, bundles, profile, overlays = []) => {
    const dir = join(temp, label);
    mkdirSync(dir);
    writeFileSync(join(dir, 'package.json'), JSON.stringify({ dsh: { profile: { bundles } } }));
    writeFileSync(join(dir, 'cordis.patch.yml'), profile);
    const loaded = boot.loadProfileDirectory('dsh', dir, anchor);
    assert.deepEqual(loaded.skippedBundles, []);
    const rows = boot.composeEntries([boot.readProfilePatches('dsh', {
      dir, installAnchor: anchor, home: temp, patchPath: loaded.patchPath, overlays
    }, loaded)]);
    const byId = id => rows.find(row => row.id === id);
    return {
      collector: !!byId('session-telemetry-otel') && !byId('session-telemetry-otel').disabled,
      brand: !!byId('ui-brand-official') && !byId('ui-brand-official').disabled,
      command: !!byId('command-feedback') && !byId('command-feedback').disabled,
      rating: !!byId('message-feedback') && !byId('message-feedback').disabled,
      deepseek: !!byId('llm-deepseek-account') && !byId('llm-deepseek-account').disabled
    };
  };
  const base = ['@deepseek-ai/dsh-base'];
  const web = [...base, '@deepseek-ai/dsh-web-app'];
  const recovery = read(join(assets, 'recovery-profile.patch.yml')).replaceAll('@RECOVERY_HOME@', '/recovery').replaceAll('@PLUGIN_ROOT@', '/fixture');
  const results = {
    web: check('web', web, '[]'),
    cli: check('headless', [...base, '@deepseek-ai/dsh-headless'], '[]'),
    recovery: check('recovery', web, recovery),
    oldProfile: check('old-profile', web, '- id: session-telemetry-otel\n  disabled: false\n', [{ id: 'session-telemetry-otel', disabled: false }]),
    customWithoutCollector: check('empty', [], '[]')
  };
  for (const name of ['web', 'cli', 'oldProfile']) assert.equal(results[name].collector, baseline);
  for (const name of ['web', 'recovery', 'oldProfile']) {
    assert.equal(results[name].brand, baseline);
    assert.equal(results[name].rating, true);
    assert.equal(results[name].deepseek, true);
  }
  assert.ok(results.cli.command);
  assert.equal(results.recovery.collector, false);
  assert.equal(results.customWithoutCollector.collector, false);
  findings.profiles = results;
  return results.web;
}

async function feedback(web) {
  const { Context } = await get('cordis');
  const storage = await get('dsh-session-persistence-jsonl');
  const { MessageFeedbackService } = await get('dsh-message-feedback');
  const { OpenTelemetrySessionBackend } = await get('dsh-session-telemetry-otel');
  const { Session } = await get('dsh-session');
  const { recordFeedback } = await get('dsh-command-feedback');
  const ctx = new Context();
  const sent = [];
  let reporters = 0;
  const marks = [];
  const id = 'synthetic-session';
  try {
    await ctx.plugin(storage, { root: join(temp, 'history'), compression: 'none' });
    ctx.provide('sessions', { get() {}, messageProjections: [] });
    ctx.provide('otel', { createSessionLogReporter() {
      reporters++;
      return { reportSessionLog(row) { sent.push(row.event.type); }, async shutdown() {} };
    } });
    if (web.collector) await ctx.plugin(OpenTelemetrySessionBackend, { mode: 'FEEDBACK_ONLY', exporter: { url: 'https://fixture.invalid/v1/logs' } });
    await ctx.plugin(MessageFeedbackService, { maxNoteBytes: 128 });
    const live = new Session(id);
    const message = role => ({
      id: role, role, content: [{ type: 'text', text: 'synthetic' }],
      source: role === 'user' ? { kind: 'user' } : { kind: 'model', provider: 'fixture', model: 'fixture' }
    });
    live.append('turn/start', { turn: 1 });
    live.append('user/message', message('user'), { surfaceOp: 'append' });
    live.append('step/start', { turn: 1, step: 1 });
    live.append('assistant/message', { turn: 1, step: 1, message: message('assistant'), stream: [] }, { surfaceOp: 'append' });
    live.append('step/end', { turn: 1, step: 1 });
    live.append('turn/end', { turn: 1, reason: { kind: 'completed' } });
    const writer = await ctx.sessionPersistence.create(live.header);
    await writer.append(live.snapshotEvents());
    await writer.close();
    for (const event of live.snapshotEvents()) await ctx.parallel('session/event', live, event);
    marks.push(sent.length);
    const rated = await ctx.messageFeedback.put({ sessionId: id, messageId: 'assistant', ifVersion: null, rating: 'positive' });
    assert.equal(rated.ok, true);
    assert.equal((await ctx.messageFeedback.list({ sessionId: id })).value.items[0].rating, 'positive');
    marks.push(sent.length);
    assert.ok((await ctx.messageFeedback.delete({ sessionId: id, messageId: 'assistant', ifVersion: rated.value.version })).ok);
    assert.deepEqual((await ctx.messageFeedback.list({ sessionId: id })).value.items, []);
    marks.push(sent.length);
    const reader = await ctx.sessionPersistence.open(id, 'read');
    const { events } = await reader.read();
    await reader.close();
    assert.deepEqual(events.map(event => event.type), [...live.snapshotEvents().map(row => row.type), 'feedback/message-put', 'feedback/message-delete']);
    recordFeedback(live, { text: 'synthetic local note' });
    const last = live.snapshotEvents().at(-1);
    assert.equal(last.type, 'feedback/record');
    await ctx.parallel('session/event', live, last);
    marks.push(sent.length);
    assert.deepEqual(marks, baseline ? [0,7,15,22] : [0,0,0,0], ctx.logger.buffer.flatMap(row => row.args.flatMap(arg => arg?.errors ?? arg)).join(' '));
    assert.equal(reporters, baseline ? 1 : 0);
    findings.feedback = { reporters, recordsAfterOrdinaryRateWithdrawCommand: marks, persisted: events.map(row => row.type), sent };
  } finally { await ctx.fiber.dispose(); }
}

async function providers() {
  const plugin = await get('dsh-llm-pi-ai');
  const methods = new Map();
  const env = new Map();
  const records = new Map();
  let directory, adapter;
  const ctx = {
    fiber: {}, logger: { warn() {} }, on() {},
    get(name) {
      if (name === 'launchEnvironment') return { get: key => env.has(key) ? { value: env.get(key) } : undefined };
      if (name === 'credentials') return { resolve: async () => undefined, readRecord: async key => records.get(key) };
    },
    inject(names, callback) {
      if (names.includes('authorization')) callback({ authorization: { registerFlow(flow) { methods.set(flow.key, flow.methods.map(m => m.id)); } } });
    },
    llm: {
      registerConfigurableProviders(rows) { directory = rows; return {}; },
      registerModelDiscovery() {},
      registerAdapter(_routes, value) { adapter = value; return {}; }
    }
  };
  const routes = { openai: {}, anthropic: {}, 'openai-codex': {}, deepseek: {} };
  plugin.apply(ctx, { providers: { get: () => routes } });
  const methodsFor = id => methods.get(plugin.recordKeyFor(id)) ?? [];
  const { models } = adapter.current();
  const anthropic = methodsFor('anthropic'), openai = methodsFor('openai'), codex = methodsFor('openai-codex');
  assert.ok(anthropic.includes('api-key') && openai.includes('api-key') && methodsFor('deepseek').includes('api-key'));
  assert.equal(anthropic.includes('oauth'), baseline);
  assert.equal(codex.includes('oauth'), baseline);
  assert.equal(directory.find(row => row.provider === 'anthropic').nativeAuth.oauth, baseline);
  assert.equal(directory.find(row => row.provider === 'openai').nativeAuth.oauth, baseline);
  const existingRoute = directory.find(row => row.provider === 'openai-codex');
  assert.ok(existingRoute);
  assert.equal(!!existingRoute.error, !baseline);
  for (const id of ['anthropic', 'openai', 'deepseek']) {
    const result = await models.getAuth(id, { apiKey: 'synthetic-public-api-key' });
    assert.equal(result.auth.apiKey, 'synthetic-public-api-key');
  }
  if (!baseline) {
    assert.equal(await models.getAuth('openai-codex'), undefined);
    for (const id of ['openai', 'anthropic']) {
      const key = plugin.recordKeyFor(id);
      records.set(key, { kind: 'grant', payload: { type: 'oauth', expires: 0 } });
      assert.equal(await models.getAuth(id), undefined);
      assert.equal(records.get(key).kind, 'grant');
      records.set(key, { kind: 'api-key', key: 'synthetic-stored-api-key' });
      assert.equal((await models.getAuth(id)).auth.apiKey, 'synthetic-stored-api-key');
      records.delete(key);
    }
    env.set('ANTHROPIC_API_KEY', 'synthetic-api');
    env.set('ANTHROPIC_OAUTH_TOKEN', 'synthetic-oauth-input');
    assert.equal((await models.getAuth('anthropic')).auth.apiKey, 'synthetic-api');
    env.set('ANTHROPIC_AUTH_TOKEN', 'synthetic-sk-ant-oat-token');
    await assert.rejects(models.getAuth('anthropic'), /subscription credentials are unsupported/);
    await assert.rejects(models.getAuth('anthropic', { apiKey: 'synthetic-sk-ant-oat-token' }), /subscription credentials are unsupported/);
  }
  findings.providers = { openai, anthropic, codex, deepseek: methodsFor('deepseek'), preservedCodexProfile: true, oldCodexDiagnostic: !!existingRoute.error };
}

try {
  prepareSources();
  await feedback(await profiles());
  await providers();
  console.log(JSON.stringify(findings));
} finally {
  rmSync(temp, { recursive: true, force: true });
}

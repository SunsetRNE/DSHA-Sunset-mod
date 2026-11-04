// This executes the pinned upstream defineTool, escalation validator/approver,
// and SandboxedFileSystem/atomic file provider. Only service wiring and approval
// outcomes are controlled. No model, device, network request or approval UI.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import crypto from 'node:crypto';

const [raw, originalFile, patchedFile, output] = process.argv.slice(2);
assert(raw && originalFile && patchedFile && output, 'invoke through test-fs-justification-patch.py');
const require = createRequire(path.join(raw, 'package.json'));
const load = name => import(pathToFileURL(require.resolve(name)));
const { Context } = await load('@deepseek-ai/cordis');
const { SandboxedFileSystem } = await load('@deepseek-ai/dsh-fs-sandbox');
const { LocalFileSystem } = await load('@deepseek-ai/dsh-fs-local');
const { FsError } = await load('@deepseek-ai/dsh-fs');
const results = [];
const root = path.dirname(patchedFile);
let sequence = 0;

async function moduleAt(file) {
  const text = await fs.readFile(file, 'utf8');
  // Keep package bytes read-only: rewrite only the temporary module's static
  // import specifiers to the npm-ci tree, preserving all executable statements.
  const relocated = text.replace(/(from\s+)("[^"\r\n]+")/g, (all, before, quoted) => {
    const name = JSON.parse(quoted);
    return name.startsWith('node:') ? all : before + JSON.stringify(pathToFileURL(require.resolve(name)).href);
  });
  const target = file + '.imports.mjs';
  await fs.writeFile(target, relocated);
  return import(pathToFileURL(target));
}
const original = await moduleAt(originalFile);
const patched = await moduleAt(patchedFile);
const missingReason = /requires a justification/;
const blankReason = /invalid justification: expected a non-empty sentence/;

async function test(name, run) {
  try { await run(); results.push({ name, status: 'PASS' }); }
  catch (error) { results.push({ name, status: 'FAIL', message: error.message, stack: error.stack }); }
}
async function fixture({ module = patched, mode = 'workspace-write', defaultMode = mode,
  outcome = 'allowed-once', approval = true, agent = true, confined = true, intent } = {}) {
  const directory = path.join(root, 'workspace-' + (++sequence));
  await fs.mkdir(directory);
  const state = { mode, requests: [], attempts: [], policyReads: [], observations: [], intent,
    outcome, unknownAfterMutation: false };
  const session = { id: 'session-' + sequence, header: { cwd: directory } };
  const policy = { defaultMode, resolve(request = {}) {
    state.policyReads.push(request.session);
    return { mode: state.mode, workspaceRoot: directory, sessionId: request.session?.id };
  } };
  const providerContext = new Context();
  providerContext.provide('sandboxPolicy', policy);
  const Provider = confined ? SandboxedFileSystem : LocalFileSystem;
  const provider = new Provider(providerContext, { cwd: directory, diffBasisMaxBytes: 1024 * 1024 });
  const backend = {
    sandboxMode: provider.sandboxMode,
    resolve: (...args) => provider.resolve(...args),
    processPath: (...args) => provider.processPath(...args),
  };
  for (const operation of ['writeText', 'editText']) backend[operation] = async (...args) => {
    state.attempts.push({ operation, policy: args[4] });
    const result = await provider[operation](...args);
    if (state.unknownAfterMutation) throw new Error('CONTROLLED_RESPONSE_LOST_AFTER_MUTATION');
    return result;
  };
  const tools = new Map();
  const approver = { async request(request) {
    state.requests.push(request);
    return typeof state.outcome === 'function' ? state.outcome(request) : state.outcome;
  } };
  const ctx = {
    fs: backend,
    get: name => name === 'sandboxPolicy' ? policy : name === 'approval' && approval ? approver : undefined,
    tools: { register: tool => tools.set(tool.name, tool), get: name => tools.get(name) },
    systemPrompt: { section() {}, getSectionOrder: () => 0 },
    workingDirectory: { ensure: async actualAgent => {
      assert.equal(actualAgent.session, session);
      return directory;
    } },
    waterfall: async () => state.intent,
    emit: (...args) => state.observations.push(args),
  };
  module.apply(ctx, { read: false, readImage: false, write: true, edit: true,
    readLimit: 100, readMaxLineLength: 1000, readMaxBytes: 4096, readStreamMinSize: 1024 });
  const abort = new AbortController();
  const exec = { callId: 'call-' + sequence, signal: abort.signal,
    ...(agent ? { agent: { session } } : {}) };
  async function input(tool, escalation = {}) {
    const target = path.join(directory, 'data.txt');
    if (tool === 'edit') await fs.writeFile(target, 'before');
    return tool === 'write' ? { file_path: target, content: 'after', ...escalation }
      : { file_path: target, old_string: 'before', new_string: 'after', ...escalation };
  }
  async function untouched(tool) {
    const content = await fs.readFile(path.join(directory, 'data.txt'), 'utf8').catch(error => {
      if (error.code === 'ENOENT') return undefined;
      throw error;
    });
    assert.equal(content, tool === 'write' ? undefined : 'before');
  }
  return { state, session, tools, exec, abort, directory, input, untouched, provider,
    run: (tool, args) => tools.get(tool).execute(args, exec) };
}

for (const tool of ['write', 'edit']) {
  await test(tool + ': official first-call blank-reason failure is reproduced before I/O', async () => {
    const f = await fixture({ module: original });
    await assert.rejects(f.run(tool, await f.input(tool, { sandbox_permissions: 'workspace-write', justification: '' })), blankReason);
    assert.equal(f.state.attempts.length, 0); assert.equal(f.state.requests.length, 0);
    await f.untouched(tool);
  });
  for (const [label, escalation] of [
    ['optional fields omitted', {}], ['empty optional reason only', { justification: '' }],
    ['whitespace optional reason only', { justification: ' \t\n\u3000' }],
    ['current mode with omitted reason', { sandbox_permissions: 'workspace-write' }],
    ['current mode with empty reason', { sandbox_permissions: 'workspace-write', justification: '' }],
    ['current mode with whitespace reason', { sandbox_permissions: 'workspace-write', justification: ' \t\n\u3000' }],
    ['current mode with real reason', { sandbox_permissions: 'workspace-write', justification: 'Update this requested file.' }],
  ]) await test(tool + ': ' + label + ' succeeds on first call under unchanged permission', async () => {
    const f = await fixture();
    const args = await f.input(tool, escalation);
    const before = JSON.stringify(args);
    await f.run(tool, args);
    assert.equal(await fs.readFile(args.file_path, 'utf8'), 'after');
    assert.equal(f.state.attempts.length, 1); assert.equal(f.state.requests.length, 0);
    assert.equal(f.state.attempts[0].policy.mode, 'workspace-write');
    assert.equal(f.state.policyReads[0], f.session); assert.equal(f.state.observations.length, 1);
    assert.equal(JSON.stringify(args), before, 'caller arguments were modified');
  });
  await test(tool + ': schema advertises optional escalation without unsupported keywords', async () => {
    const f = await fixture();
    const schema = f.tools.get(tool).parameters;
    assert(!schema.required.includes('justification'));
    assert(!schema.required.includes('sandbox_permissions'));
    assert.match(schema.properties.sandbox_permissions.description, /Omit sandbox_permissions and justification/);
    assert.match(schema.properties.justification.description, /non-empty sentence/);
    assert.equal(schema.properties.justification.type, 'string');
  });
  for (const reason of [undefined, '', ' \t\n\u3000']) await test(tool + ': wider mode with ' + JSON.stringify(reason) + ' reason stays rejected', async () => {
    const f = await fixture({ mode: 'read-only', defaultMode: 'danger-full-access' });
    const escalation = { sandbox_permissions: 'danger-full-access', ...(reason === undefined ? {} : { justification: reason }) };
    await assert.rejects(f.run(tool, await f.input(tool, escalation)), reason === undefined ? missingReason : blankReason);
    assert.equal(f.state.attempts.length, 0); assert.equal(f.state.requests.length, 0);
    await f.untouched(tool);
  });
  await test(tool + ': ordinary blank placeholder remains read-only and never retries a denial', async () => {
    const f = await fixture({ mode: 'read-only' });
    await assert.rejects(f.run(tool, await f.input(tool, { justification: '' })), error => error.code === 'FS_SANDBOX_DENIED');
    assert.equal(f.state.attempts.length, 1); assert.equal(f.state.requests.length, 0);
    await f.untouched(tool);
  });
  await test(tool + ': exact same full-access mode permits blank reason without a fabricated approval', async () => {
    const f = await fixture({ mode: 'danger-full-access', defaultMode: 'read-only' });
    await f.run(tool, await f.input(tool, { sandbox_permissions: 'danger-full-access', justification: '' }));
    assert.equal(f.state.requests.length, 0); assert.equal(f.state.attempts.length, 1);
    assert.equal(f.state.attempts[0].policy.mode, 'danger-full-access');
  });
  await test(tool + ': approval must settle before one wider write and its grant is not persistent', async () => {
    let release;
    const gate = new Promise(resolve => { release = resolve; });
    const f = await fixture({ mode: 'read-only', outcome: () => gate });
    const args = await f.input(tool, { sandbox_permissions: 'workspace-write', justification: 'Write the requested result in this workspace.' });
    const pending = f.run(tool, args);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(f.state.requests.length, 1); assert.equal(f.state.attempts.length, 0);
    await f.untouched(tool);
    assert.equal(f.state.requests[0].callId, f.exec.callId);
    assert.equal(f.state.requests[0].toolName, tool);
    assert.match(f.state.requests[0].reason, /Write the requested result in this workspace\./);
    release('allowed-once'); await pending;
    assert.equal(f.state.attempts.length, 1);
    assert.equal(f.state.attempts[0].policy.mode, 'workspace-write');
    assert.equal(f.state.mode, 'read-only');
    const next = { ...args }; delete next.sandbox_permissions; delete next.justification;
    await assert.rejects(f.run(tool, next), error => error.code === 'FS_SANDBOX_DENIED');
    assert.equal(f.state.requests.length, 1); assert.equal(f.state.attempts.length, 2);
  });
  for (const outcome of ['rejected', 'cancelled', 'unavailable']) await test(tool + ': ' + outcome + ' approval cannot write', async () => {
    const f = await fixture({ mode: 'read-only', outcome });
    await assert.rejects(f.run(tool, await f.input(tool, { sandbox_permissions: 'workspace-write', justification: 'Write the requested result.' })));
    assert.equal(f.state.requests.length, 1); assert.equal(f.state.attempts.length, 0);
    await f.untouched(tool);
  });
  for (const option of ['approval', 'agent']) await test(tool + ': missing ' + option + ' cannot widen permission', async () => {
    const f = await fixture({ mode: 'read-only', [option]: false });
    await assert.rejects(f.run(tool, await f.input(tool, { sandbox_permissions: 'workspace-write', justification: 'Write the requested result.' })), /requires approval/);
    assert.equal(f.state.requests.length, 0); assert.equal(f.state.attempts.length, 0);
    await f.untouched(tool);
  });
  for (const reason of [null, 7, {}, false]) await test(tool + ': non-string reason ' + JSON.stringify(reason) + ' stays schema-invalid', async () => {
    const f = await fixture();
    await assert.rejects(f.run(tool, await f.input(tool, { sandbox_permissions: 'workspace-write', justification: reason })), /invalid arguments/);
    assert.equal(f.state.attempts.length, 0); assert.equal(f.state.requests.length, 0);
  });
  await test(tool + ': nonempty reason without mode still fails pairing', async () => {
    const f = await fixture();
    await assert.rejects(f.run(tool, await f.input(tool, { justification: 'This is a reason.' })), /only valid together/);
    assert.equal(f.state.attempts.length, 0);
  });
  await test(tool + ': narrower requested mode cannot use the no-widening branch', async () => {
    const f = await fixture({ mode: 'danger-full-access' });
    await assert.rejects(f.run(tool, await f.input(tool, { sandbox_permissions: 'workspace-write', justification: 'Use a narrower mode.' })), /not strictly wider/);
    assert.equal(f.state.attempts.length, 0);
  });
  await test(tool + ': live session narrowing after registration does not inherit the old mode', async () => {
    const f = await fixture({ mode: 'danger-full-access' });
    f.state.mode = 'workspace-write';
    await assert.rejects(f.run(tool, await f.input(tool, { sandbox_permissions: 'danger-full-access', justification: '' })), blankReason);
    assert.equal(f.state.attempts.length, 0);
  });
  await test(tool + ': stale file observation remains rejected', async () => {
    const f = await fixture({ intent: { kind: 'replaceIfVersion', version: 'stale-version' } });
    const args = await f.input(tool, { sandbox_permissions: 'workspace-write', justification: '' });
    if (tool === 'write') await fs.writeFile(args.file_path, 'before');
    await assert.rejects(f.run(tool, args), error => error instanceof FsError && error.code === 'FS_STALE_VERSION');
    assert.equal(await fs.readFile(args.file_path, 'utf8'), 'before');
    assert.equal(f.state.attempts.length, 1); assert.equal(f.state.requests.length, 0);
  });
  await test(tool + ': unknown result after actual file mutation is never replayed', async () => {
    const f = await fixture(); f.state.unknownAfterMutation = true;
    const args = await f.input(tool, { sandbox_permissions: 'workspace-write', justification: '' });
    await assert.rejects(f.run(tool, args), /CONTROLLED_RESPONSE_LOST_AFTER_MUTATION/);
    assert.equal(await fs.readFile(args.file_path, 'utf8'), 'after');
    assert.equal(f.state.attempts.length, 1); assert.equal(f.state.observations.length, 0);
  });
  await test(tool + ': cancelled call is not normalized into an executable write', async () => {
    const f = await fixture(); const args = await f.input(tool, { sandbox_permissions: 'workspace-write', justification: '' });
    f.abort.abort(new Error('cancelled before dispatch'));
    await assert.rejects(f.run(tool, args), error => error.code === 'FS_ABORTED');
    assert.equal(f.state.attempts.length, 0); await f.untouched(tool);
  });
  await test(tool + ': an unconfined backend still rejects explicit escalation', async () => {
    const f = await fixture({ confined: false });
    await assert.rejects(f.run(tool, await f.input(tool, { sandbox_permissions: 'workspace-write', justification: 'Write the requested result.' })), /not available in this composition/);
    assert.equal(f.state.attempts.length, 0);
  });
}
const report = {
  scope: 'LOCKED_RC5_TOOLS_SCHEMA_APPROVAL_AND_HOST_FILESYSTEM',
  deviceTested: false, modelCalled: false, nativeConsentUiTested: false,
  fullManagedChainExecuted: false,
  boundary: 'Behavior tests execute only this repair over official rc5. Python separately checks registration, all exact chain anchors, repeat application and preservation of both destructive guard calls.',
  modules: await Promise.all(['@deepseek-ai/dsh-tool-fs', '@deepseek-ai/dsh-tools', '@deepseek-ai/dsh-sandbox', '@deepseek-ai/dsh-fs-sandbox', '@deepseek-ai/dsh-fs-local'].map(async name => {
    const file = require.resolve(name), bytes = await fs.readFile(file);
    return { name, path: file, sha256: crypto.createHash('sha256').update(bytes).digest('hex') };
  })),
  cases: results.length, passed: results.filter(row => row.status === 'PASS').length, results,
};
await fs.writeFile(output, JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report, null, 2));
if (report.passed !== report.cases) process.exitCode = 1;

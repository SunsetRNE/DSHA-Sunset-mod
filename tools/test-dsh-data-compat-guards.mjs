import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, realpath, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createPathGuard, beneath, processPassed } from './fixtures/data-compat/guards.mjs';

// Private, small fixtures are retained; no installed runtime or profile is imported.
test('timeout remains a failure even when the child handles TERM and exits zero', () => {
  const outcome = spawnSync(process.execPath, ['-e',
    "process.on('SIGTERM',()=>process.exit(0));setInterval(()=>{},1000)"],
    { timeout: 1000, encoding: 'utf8', maxBuffer: 65536 });
  assert.equal(outcome.error?.code, 'ETIMEDOUT');
  assert.equal(processPassed(outcome), false);
  assert.equal(outcome.status, 0);
  assert.equal(processPassed({ status: 0, signal: null }), true);
  assert.equal(processPassed({ status: 0, signal: null, error: Object.assign(new Error(), { code: 'ENOBUFS' }) }), false);
  assert.equal(processPassed({ status: null, signal: 'SIGSEGV' }), false);
});

test('protected path aliases and runtime output aliases retain canonical isolation', async () => {
  const parent = await mkdtemp(join(tmpdir(), 'dsha-data-guard-'));
  const live = join(parent, 'live-home');
  const runtime = join(parent, 'runtime');
  await mkdir(live); await mkdir(runtime);
  const liveAlias = join(parent, 'live-alias');
  const runtimeAlias = join(parent, 'runtime-alias');
  await symlink(live, liveAlias); await symlink(runtime, runtimeAlias);
  const safe = await createPathGuard([liveAlias]);
  await assert.rejects(safe(join(live, 'new-fixture')), /live profile/);
  await assert.rejects(safe(join(liveAlias, 'new-fixture')), /live profile/);
  const normalizedRuntime = await safe(runtimeAlias);
  assert.equal(normalizedRuntime, await realpath(runtime));
  assert.equal(beneath(await safe(join(runtimeAlias, 'new-report.json')), normalizedRuntime), true);
  assert.equal(beneath(await safe(join(runtimeAlias, 'new-fixtures')), normalizedRuntime), true);
  assert.equal(beneath(await safe(join(parent, 'separate', 'new-report.json')), normalizedRuntime), false);
  const missingAlias = join(parent, 'dangling');
  await symlink(join(parent, 'absent'), missingAlias);
  await assert.rejects(safe(missingAlias), /dangling path alias/);
});

test('archive validation refuses optimized Python before processing paths', () => {
  const result = spawnSync(process.env.DSHA_PYTHON || 'python3', ['-O',
    fileURLToPath(new URL('./fixtures/data-compat/verify-runtime-archive.py', import.meta.url)), '--help'],
    { encoding: 'utf8', timeout: 10000 });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /DATA_COMPAT_OPTIMIZATION_FORBIDDEN/);
});

test('runner preload refuses remote I/O before a connection can start', () => {
  const preload = fileURLToPath(new URL('./fixtures/data-compat/network-guard.mjs', import.meta.url));
  const result = spawnSync(process.execPath, ['--import', preload, '--input-type=module', '-e', `
    import assert from 'node:assert/strict';
    import { get } from 'node:https';
    import { connect } from 'node:net';
    const host = 'fixture.invalid';
    assert.throws(() => fetch('https://' + host), /AUDIT_NETWORK_DISABLED/);
    assert.throws(() => get('https://' + host), /AUDIT_NETWORK_DISABLED/);
    assert.throws(() => connect(443, host), /AUDIT_NETWORK_DISABLED/);
  `], { encoding: 'utf8', timeout: 10000 });
  assert(processPassed(result), result.stderr);
});

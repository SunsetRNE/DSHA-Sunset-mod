import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { join, resolve } from 'node:path';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { beneath } from './guards.mjs';

const [runtime, root, phase] = process.argv.slice(2);
assert(process.env.DSHA_DATA_COMPAT_ROOT && beneath(resolve(root), resolve(process.env.DSHA_DATA_COMPAT_ROOT)));
const anchor = join(runtime, 'package.json');
const require = createRequire(anchor);
const load = name => import(pathToFileURL(require.resolve('@deepseek-ai/' + name)).href);
const { loadProfileDirectory, composeEntries } = await load('dsh-app-boot');
const { JsonStorageBackend } = await load('dsh-storage-json');
const version = JSON.parse(await readFile(anchor, 'utf8')).version;
const initial = phase === 'old-write';
const migrated = !initial;
const updated = phase === 'new-reopen' || phase === 'old-read-new';
const retired = '@deepseek-ai/dsh-experimental-agent-team-profile';
const preserved = 'synthetic-missing-bundle';
const profile = join(root, 'profile');
const manifestFile = join(profile, 'package.json');
const patchFile = join(profile, 'cordis.patch.yml');
const teamDirectory = join(root, 'team');
const teamFile = join(teamDirectory, 'opaque-checkpoint.json');
const bytes = Buffer.from('{"label":"原始队伍记录","state":[null,false,0,""]}\n');
const override = { marker: 'preserved user configuration', unrecognized: [null, false, 0, ''] };
const patch = '# original user patch\n' + JSON.stringify([{ insert: [{ id: 'retained', name: 'synthetic-missing-plugin', disabled: true, config: override }] }]) + '\n';
const entry = { task: 'fixture task', payload: { present: true, result: null } };

if (initial) {
  await mkdir(profile, { recursive: true });
  await mkdir(teamDirectory, { recursive: true });
  assert.ok(['0.2.1-rc.5', '0.2.2-rc.1'].includes(version));
  await writeFile(manifestFile, JSON.stringify({ private: true, dependencies: { [retired]: version },
    customField: { keep: 'yes' }, dsh: { profile: { bundles: [retired, preserved], unknown: 'preserve' } } }, null, 2) + '\n', { flag: 'wx' });
  await writeFile(patchFile, patch, { flag: 'wx' });
  await writeFile(teamFile, bytes, { flag: 'wx' });
}

const before = await readFile(manifestFile);
const expected = JSON.parse(before);
const requestBundles = initial || phase === 'new-read' ? [retired, preserved] : [preserved];
assert.deepEqual(expected.dsh.profile.bundles, requestBundles);
const loaded = loadProfileDirectory('audit', profile, anchor);
const after = await readFile(manifestFile);
expected.dsh.profile.bundles = migrated ? [preserved] : [retired, preserved];
assert.deepEqual(JSON.parse(after), expected, 'only the retired bundle selection may change');
assert.equal(before.equals(after), phase !== 'new-read');
assert.deepEqual(await readFile(teamFile), bytes);
assert.equal(await readFile(patchFile, 'utf8'), patch);
assert.ok(loaded.skippedBundles.some(item => item.packageName === preserved));
if (migrated) {
  assert.ok(loaded.skippedBundles.every(item => item.packageName !== retired));
  assert.ok(loaded.layers.every(layer => layer.packageName !== retired));
}
const effective = composeEntries([loaded.patches]).find(row => row.id === 'retained');
assert.equal(effective.disabled, true);
assert.deepEqual(effective.config, override);

const backend = new JsonStorageBackend(join(root, 'storage'));
try {
  const store = await backend.kv.open({ name: 'profile_compat', version: 1, layout: 'single', hasGlobal: false, tables: ['entries'] });
  if (initial) await store.putRecord('entries', 'original', entry);
  else {
    const state = await store.loadAll();
    assert.deepEqual(state.tables.entries.original, entry);
    if (phase === 'new-write') await store.putRecord('entries', 'added', { ...entry, added: true });
    else if (updated) assert.deepEqual(state.tables.entries.added, { ...entry, added: true });
  }
  await store.close();
} finally { await backend.close(); }

console.log(JSON.stringify({ phase, version, manifestRewritten: !before.equals(after),
  patchBytesPreserved: true, teamCheckpointPreserved: true, retainedOverride: effective.id,
  retainedSelection: expected.dsh.profile.bundles, addedRecordRead: updated }));

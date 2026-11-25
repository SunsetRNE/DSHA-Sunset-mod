// Run against a locally prepared 0.2.2-rc.5 node_modules tree.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const [modulesArg, outputArg] = process.argv.slice(2)
assert(modulesArg && outputArg, 'usage: node test-config-field-dict-deletion.mjs node_modules new-output')
const modules = resolve(modulesArg)
const output = resolve(outputArg)
const sha = value => createHash('sha256').update(value).digest('hex')
const runtimeFile = name => join(modules, '@deepseek-ai', name, 'lib/index.js')
const load = name => import(pathToFileURL(runtimeFile(name)).href)
const { boot, readProfilePatches } = await load('dsh-app-boot')
const { ConfigEditor } = await load('dsh-config-editor')
const { SettingsForms } = await load('dsh-settings')
const schemaUrl = pathToFileURL(join(modules, '@deepseek-ai/schemastery/lib/index.mjs')).href
const results = []
const fixture = key => ({ key, models: ['one'] })
const first = fixture('SYNTHETIC_FIRST_REF')
const sibling = fixture('SYNTHETIC_SIBLING_REF')
const lower = fixture('SYNTHETIC_LOWER_REF')
const path = ['providers', 'target']
const set = (target, value) => ({ op: 'set', path: target, value })
const unset = target => ({ op: 'unset', path: target })
mkdirSync(output)

async function test(id, base, exercise, verify, overlays = []) {
  const started = performance.now()
  const dir = join(output, id)
  const bundle = join(dir, 'bundle')
  const home = join(dir, 'isolated-home')
  for (const folder of [bundle, home, join(dir, 'node_modules')]) mkdirSync(folder, { recursive: true })
  symlinkSync(bundle, join(dir, 'node_modules', 'synthetic-settings-bundle'), process.platform === 'win32' ? 'junction' : 'dir')
  const entry = join(bundle, 'entry.mjs')
  writeFileSync(entry, `import z from ${JSON.stringify(schemaUrl)};
export const Config = z.object({ providers: z.dict(z.object({ key: z.string(), models: z.array(z.string()) })).default({}).volatile() });
export function apply() {}
`)
  writeFileSync(join(bundle, 'package.json'), JSON.stringify({ name: 'synthetic-settings-bundle', version: '1.0.0',
    dsh: { bundle: { patch: 'cordis.patch.yml' } } }))
  writeFileSync(join(bundle, 'cordis.patch.yml'), JSON.stringify([{ insert: [{ id: 'fixture', name: pathToFileURL(entry).href, config: base }] }]))
  const rootFile = join(dir, 'cordis.yml')
  const patchPath = join(dir, 'cordis.patch.yml')
  const installAnchor = join(dir, 'installation.json')
  const overlayFile = join(home, 'cordis.patch.yml')
  writeFileSync(rootFile, '[]\n')
  writeFileSync(patchPath, '[]\n')
  writeFileSync(installAnchor, JSON.stringify({ name: '@deepseek-ai/dsh', version: '0.2.2-rc.5' }))
  writeFileSync(join(dir, 'package.json'), JSON.stringify({ name: 'test', private: true,
    dsh: { profile: { bundles: ['synthetic-settings-bundle'] } } }))
  const profile = { name: 'test', dir, patchPath, installAnchor, home, overlays, startedBundles: ['synthetic-settings-bundle'] }
  async function withSettings(fn) {
    const context = await boot('dsh', rootFile, readProfilePatches('dsh', profile), async services => {
      services.provide('profileContext', profile)
      services.provide('appReady', { onReady(callback) { queueMicrotask(callback); return () => {} } })
    })
    try {
      await context.plugin(ConfigEditor, {})
      await context.plugin(SettingsForms, {})
      await context.loader.await()
      const form = () => context.settings.describe({ redactSecrets: true }).find(row => row.ns === 'fixture')
      await fn({ form, patch: () => readFileSync(patchPath, 'utf8'), overlayFile, profile,
        mutate: (...ops) => context.settings.mutate('fixture', ops, form().revision),
        mutateWithoutRevision: (...ops) => context.settings.mutate('fixture', ops) })
    } finally {
      await context.fiber.dispose()
    }
  }
  try {
    await withSettings(exercise)
    await withSettings(verify)
    results.push({ id, passed: true, fullReopen: true, elapsedMs: performance.now() - started })
  } catch (error) {
    results.push({ id, passed: false, error: error.message, elapsedMs: performance.now() - started })
  }
}

const noProfileFields = ({ form, patch }) => {
  assert.deepEqual(form().value.providers, {})
  assert.deepEqual(form().user, {})
  assert.equal(patch(), '[]\n')
}
await test('new-delete', {}, async ({ mutate, form }) => {
  await mutate(set(path, first))
  assert.deepEqual(form().value.providers.target, first)
  await mutate(unset(path))
  await mutate(unset(path))
}, noProfileFields)

await test('edit-child-delete', {}, async ({ mutate, form }) => {
  await mutate(set(path, first))
  await mutate(set([...path, 'models'], ['one', 'two']))
  assert.deepEqual(form().value.providers.target.models, ['one', 'two'])
  await mutate(unset(path))
}, noProfileFields)

await test('retain-sibling', {}, async ({ mutate }) => {
  await mutate(set(path, first), set(['providers', 'sibling'], sibling))
  await mutate(unset(path))
}, ({ form, patch }) => {
  assert.deepEqual(form().value.providers, { sibling })
  assert.deepEqual(form().user.providers, { sibling })
  assert.match(patch(), /SYNTHETIC_SIBLING_REF/)
})

await test('inherit-profile', { providers: { target: lower, sibling } }, async ({ mutate }) => {
  await mutate(set(path, first), set([...path, 'models'], ['two']))
  await mutate(unset(path))
}, ({ form, patch }) => {
  assert.deepEqual(form().value.providers, { target: lower, sibling })
  assert.equal(Object.hasOwn(form().user, 'providers'), false)
  assert.equal(patch(), '[]\n')
})

await test('inherit-explicit-empty-dict', { providers: {} }, async ({ mutate }) => {
  await mutate(set(path, first))
  await mutate(unset(path))
}, noProfileFields)

await test('retain-user-explicit-empty-dict', {}, async ({ mutate }) => {
  await mutate(set(['providers'], {}))
  await mutate(set(path, first))
  await mutate(unset(path))
}, ({ form, patch }) => {
  assert.deepEqual(form().user, { providers: {} })
  assert.deepEqual(form().value.providers, {})
  assert.match(patch(), /value: \{\}/)
})

await test('retain-array-index-semantics', {}, async ({ mutate }) => {
  await mutate(set(path, { ...first, models: ['one', 'two', 'three'] }))
  await mutate(unset([...path, 'models', '1']))
}, ({ form }) => {
  assert.deepEqual(form().value.providers.target, { ...first, models: ['one', 'three'] })
})

for (const layer of ['home', 'command-line']) {
  await test(`reject-${layer}-override`, {}, async ({ mutate, mutateWithoutRevision, patch, overlayFile, profile }) => {
    await mutate(set(path, first))
    const before = patch()
    const overlay = [{ id: 'fixture', config: { providers: { target: lower, sibling } } }]
    if (layer === 'home') writeFileSync(overlayFile, JSON.stringify(overlay))
    else profile.overlays.push(...overlay)
    await assert.rejects(mutate(unset(path)), /changed since it was read/)
    await assert.rejects(mutateWithoutRevision(unset(path)), /overridden by a home patch or command-line overlay/)
    assert.equal(patch(), before)
  }, ({ form, patch }) => {
    assert.deepEqual(form().value.providers, { target: lower, sibling })
    assert.deepEqual(form().user.providers, { target: first })
    assert.match(patch(), /SYNTHETIC_FIRST_REF/)
  })
}

writeFileSync(join(output, 'result.json'), JSON.stringify({ node: process.version, platform: process.platform,
  moduleSha256: Object.fromEntries(['cordis-plugin-include', 'dsh-config-editor', 'dsh-settings'].map(name => [name, sha(readFileSync(runtimeFile(name)))])),
  results }, null, 2) + '\n')
for (const row of results) console.log(`${row.passed ? 'PASS' : 'FAIL'} ${row.id} ${Math.round(row.elapsedMs)}ms${row.error ? ' ' + row.error : ''}`)
if (results.some(row => !row.passed)) process.exitCode = 1

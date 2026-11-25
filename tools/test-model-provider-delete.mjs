// Test the deployed model settings function with controlled transport replies.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const [clientPath] = process.argv.slice(2)
assert(clientPath, 'usage: node test-model-provider-delete.mjs deployed-models-client.js')
const source = readFileSync(clientPath, 'utf8')
const start = source.indexOf('async function removeProviderProfile(')
const end = source.indexOf('function needsSetup(', start)
assert(start >= 0 && end > start)
assert.equal(source.lastIndexOf('async function removeProviderProfile('), start)
const removeProvider = runInNewContext('(' + source.slice(start, end).trim() + ')')
const ref = 'SYNTHETIC_DRAFT_API_KEY'
const results = []

function fixture({ settings = { kind: 'written' }, snapshot = {}, credentialRef = ref } = {}) {
  const calls = []
  const target = { settingsNs: 'synthetic', settingsPath: ['providers', 'draft'], credentialRef }
  const keys = new Set([ref, 'OTHER_SYNTHETIC_API_KEY'])
  const dependencies = {
    operations: {
      async writeSettings(ns, ops, revision) {
        assert.equal(ns, 'synthetic')
        assert.equal(JSON.stringify(ops), JSON.stringify([{ op: 'unset', path: ['providers', 'draft'] }]))
        calls.push(`settings:${revision}`)
        return settings
      },
      async removeCredential(key) {
        calls.push('credential')
        assert.equal(key, ref)
        keys.delete(key)
      },
    },
    controller: {
      store: { getSnapshot: () => ({ status: 'ready', rows: [], namespaces: new Map([['synthetic', { revision: 24 }]]), ...snapshot }) },
      async load() { calls.push('refresh') },
    },
  }
  return { ...dependencies, calls, keys, target, remove: () => removeProvider(dependencies.operations, dependencies.controller, target) }
}

async function test(id, exercise) {
  try {
    await exercise()
    results.push({ id, passed: true })
  } catch (error) { results.push({ id, passed: false, error: error.message }) }
}

for (const kind of ['refused', 'conflict']) {
  await test(`settings-${kind}-leaves-key`, async () => {
    const test = fixture({ settings: { kind, message: kind } })
    assert.equal(await test.remove(), kind)
    assert.deepEqual(test.calls, ['settings:24'])
    assert.equal(test.keys.has(ref), true)
  })
}

await test('remove-provider-without-touching-stored-keys', async () => {
  const test = fixture()
  assert.equal(await test.remove(), undefined)
  assert.deepEqual(test.calls, ['settings:24', 'refresh'])
  assert.deepEqual([...test.keys], [ref, 'OTHER_SYNTHETIC_API_KEY'])
})

await test('remove-provider-with-no-saved-key', async () => {
  const test = fixture({ credentialRef: null })
  delete test.target.credentialRef
  assert.equal(await test.remove(), undefined)
  assert.deepEqual(test.calls, ['settings:24', 'refresh'])
})

await test('keep-key-also-when-current-directory-cannot-establish-ownership', async () => {
  const test = fixture({ snapshot: { status: 'error', error: 'directory unavailable' } })
  assert.equal(await test.remove(), undefined)
  assert.deepEqual(test.calls, ['settings:24', 'refresh'])
  assert.equal(test.keys.has(ref), true)
})

console.log(JSON.stringify(results, null, 2))
if (results.some(row => !row.passed)) process.exitCode = 1

// Exercise the actual deployed model selection controller with a revision-fenced form fixture.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { setImmediate as nextTurn } from 'node:timers/promises'
import { runInNewContext } from 'node:vm'

const [clientPath] = process.argv.slice(2)
assert(clientPath, 'usage: node test-subagent-pool-field-scope.mjs deployed-subagent-client.js')
const bytes = readFileSync(clientPath)
const source = bytes.toString('utf8')
const exportAnchor = 'return module.exports}});'
assert.equal(source.split(exportAnchor).length, 2, 'Expected the signed client module factory')
const instrumented = source.replace(exportAnchor,
  'exports.TestController=SubagentModelSelectionCardController;' + exportAnchor)
let Controller
const storeModule = {
  createSnapshotStore(initial) {
    let value = initial
    return { getSnapshot: () => value, set: next => { value = next } }
  },
}
runInNewContext(instrumented, { window: { __ModuleLoader__: {
  load({ id, factory }) {
    assert.equal(id, '@deepseek-ai/dsh-client-ui-settings-subagent')
    Controller = factory(name => name === '@deepseek-ai/dsh-client-store' ? storeModule : {}).TestController
  },
} } }, { filename: clientPath })
assert.equal(typeof Controller, 'function')

const first = { provider: 'synthetic', model: 'first' }
const second = { provider: 'synthetic', model: 'second' }
const routes = [{ id: 'synthetic', name: 'Synthetic',
  models: [{ id: 'first', name: 'First' }, { id: 'second', name: 'Second' }] }]
const defaults = { enabled: true, allowedModels: [first] }
const set = (field, value) => ({ op: 'set', path: [field], value })
const plain = data => JSON.parse(JSON.stringify(data))

async function fixture({ inherited = defaults, existing = {}, settle } = {}) {
  let lower = structuredClone(inherited)
  const user = structuredClone(existing)
  let revision = 12
  let update = () => {}
  const mutations = []
  function nextLower(value) {
    lower = structuredClone(value)
    revision += 1
    update()
  }
  const scope = {
    getSnapshot: () => ({ status: 'ready', writable: true, revision, value: { ...lower, ...user } }),
    subscribe(callback) {
      update = callback
      return () => { update = () => {} }
    },
    async mutate(ops, expectedRevision) {
      const requests = plain(ops)
      mutations.push({ ops: requests, revision: expectedRevision })
      if (expectedRevision !== revision) return false
      return settle ? settle({ apply, nextLower }) : apply()

      function apply() {
        for (const operation of requests) {
          assert.equal(operation.op, 'set')
          assert.equal(operation.path.length, 1)
          const [field] = operation.path
          assert(['enabled', 'allowedModels'].includes(field))
          user[field] = operation.value
        }
        revision += 1
        update()
        return true
      }
    },
  }
  const ctx = { remote: { session: { modelCatalog: async () => ({
    ok: true, value: { groups: routes, failures: [] },
  }) } } }
  const controller = new Controller(scope, ctx)
  const face = controller.inject()
  const state = () => face.hooks.subagentModelSelectionCard.getSnapshot()
  const toggle = async route => {
    await nextTurn()
    const candidate = state().candidates.find(row => row.provider === route.provider && row.model === route.model)
    assert(candidate)
    face.toggleModel(candidate.key)
  }
  await nextTurn()
  return {
    face, state, toggle, user, mutations, save: () => controller.save(),
    effective: () => scope.getSnapshot().value,
    nextLower,
  }
}

const results = []
async function test(id, exercise) {
  try {
    await exercise()
    results.push({ id, passed: true })
  } catch (error) {
    results.push({ id, passed: false, detail: error.message })
  }
}

await test('inherited-switch-only-writes-switch-and-list-still-follows', async () => {
  const t = await fixture()
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.mutations, [{ ops: [set('enabled', false)], revision: 12 }])
  assert.deepEqual(t.user, { enabled: false })
  t.nextLower({ enabled: true, allowedModels: [second] })
  assert.deepEqual(t.effective(), { enabled: false, allowedModels: [second] })
})

await test('inherited-routes-only-writes-routes-and-switch-still-follows', async () => {
  const t = await fixture()
  await t.toggle(second)
  await t.save()
  assert.deepEqual(t.mutations, [{ ops: [set('allowedModels', [first, second])], revision: 12 }])
  assert.deepEqual(t.user, { allowedModels: [first, second] })
  t.nextLower({ enabled: false, allowedModels: [first] })
  assert.equal(t.state().enabled, false)
  assert.deepEqual(t.effective().allowedModels, [first, second])
})

await test('existing-explicit-list-equal-to-lower-layer-is-retained', async () => {
  const t = await fixture({ existing: { allowedModels: [first] } })
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.mutations, [{ ops: [set('enabled', false)], revision: 12 }])
  t.nextLower({ enabled: true, allowedModels: [second] })
  assert.deepEqual(t.user, { enabled: false, allowedModels: [first] })
  assert.deepEqual(t.effective().allowedModels, [first])
})

await test('existing-explicit-switch-equal-to-lower-layer-is-retained', async () => {
  const t = await fixture({ existing: { enabled: true } })
  await t.toggle(second)
  await t.save()
  assert.deepEqual(t.mutations, [{ ops: [set('allowedModels', [first, second])], revision: 12 }])
  t.nextLower({ enabled: false, allowedModels: [first] })
  assert.equal(t.user.enabled, true)
  assert.equal(t.state().enabled, true)
})

await test('deliberate-write-equal-to-lower-layer-is-explicit', async () => {
  const t = await fixture({ inherited: { enabled: false, allowedModels: [first] }, existing: { enabled: true } })
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.user, { enabled: false })
  assert.deepEqual(t.mutations, [{ ops: [set('enabled', false)], revision: 12 }])
})

await test('enable-and-select-a-model-is-one-revision-fenced-write', async () => {
  const t = await fixture({ inherited: { enabled: false, allowedModels: [] } })
  t.face.toggleEnabled()
  await t.toggle(first)
  await t.save()
  assert.deepEqual(t.mutations, [{ ops: [set('enabled', true), set('allowedModels', [first])], revision: 12 }])
  assert.equal(t.state().dirty, false)
})

await test('disabled-empty-list-is-explicit-enabled-empty-is-invalid', async () => {
  const t = await fixture()
  await t.toggle(first)
  assert.equal(t.state().invalid, true)
  await t.save()
  assert.equal(t.mutations.length, 0)
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.mutations, [{ ops: [set('enabled', false), set('allowedModels', [])], revision: 12 }])
  assert.deepEqual(t.user, { enabled: false, allowedModels: [] })
})

await test('net-noop-does-not-materialize-a-field', async () => {
  const t = await fixture()
  await t.toggle(second)
  await t.toggle(second)
  t.face.toggleEnabled()
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.mutations, [])
  assert.deepEqual(t.user, {})
})

await test('newer-revision-conflicts-with-staged-draft-and-discard-reloads', async () => {
  const t = await fixture()
  await t.toggle(second)
  t.nextLower({ enabled: true, allowedModels: [second] })
  await t.save()
  assert.equal(t.state().conflicted, true)
  assert.equal(t.state().dirty, true)
  assert.equal(t.mutations.length, 0)
  t.face.discard()
  assert.equal(t.state().conflicted, false)
  assert.equal(t.state().dirty, false)
  assert.deepEqual(t.effective().allowedModels, [second])
})

await test('writer-refusal-keeps-the-draft-and-leaves-profile-intact', async () => {
  const t = await fixture({ settle: () => false })
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.mutations, [{ ops: [set('enabled', false)], revision: 12 }])
  assert.deepEqual(t.user, {})
  assert.equal(t.state().failed, true)
  assert.equal(t.state().dirty, true)
  assert.equal(t.state().saving, false)
  t.face.discard()
  assert.equal(t.state().enabled, true)
  assert.equal(t.state().failed, false)
})

await test('acknowledged-switch-write-survives-a-newer-route-refresh', async () => {
  const t = await fixture({ settle({ apply, nextLower }) {
    apply()
    nextLower({ enabled: true, allowedModels: [second] })
    return true
  } })
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.user, { enabled: false })
  assert.deepEqual(t.effective(), { enabled: false, allowedModels: [second] })
  assert.equal(t.state().dirty, false)
  assert.equal(t.state().failed, false)
})

await test('acknowledged-routes-write-survives-a-newer-switch-refresh', async () => {
  const t = await fixture({ settle({ apply, nextLower }) {
    apply()
    nextLower({ enabled: false, allowedModels: [first] })
    return true
  } })
  await t.toggle(second)
  await t.save()
  assert.deepEqual(t.user, { allowedModels: [first, second] })
  assert.equal(t.state().enabled, false)
  assert.equal(t.state().dirty, false)
  assert.equal(t.state().failed, false)
})

await test('refusal-is-failure-even-if-a-refresh-matches-the-draft', async () => {
  const t = await fixture({ settle({ nextLower }) {
    nextLower({ enabled: false, allowedModels: [first] })
    return false
  } })
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.user, {})
  assert.equal(t.state().failed, true)
  assert.equal(t.state().saving, false)
})

await test('acknowledgement-without-the-written-value-is-failure', async () => {
  const t = await fixture({ settle: () => true })
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.user, {})
  assert.equal(t.state().failed, true)
  assert.equal(t.state().dirty, true)
})

await test('transport-rejection-keeps-the-draft-retriable', async () => {
  const t = await fixture({ settle: () => Promise.reject(new Error('Synthetic transport rejection')) })
  t.face.toggleEnabled()
  await t.save()
  assert.deepEqual(t.user, {})
  assert.equal(t.state().failed, true)
  assert.equal(t.state().dirty, true)
  assert.equal(t.state().saving, false)
})

console.log(JSON.stringify({ clientSha256: createHash('sha256').update(bytes).digest('hex'), results }, null, 2))
if (results.some(row => !row.passed)) process.exitCode = 1

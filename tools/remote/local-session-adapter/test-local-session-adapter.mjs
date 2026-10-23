import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { testRuntime } from '../../test-runtime-fixture.mjs'
import { sessionAdapter, phoneTaskEnvironment } from '../../../app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/session-adapter.js'
const inputs = ['tools/remote/local-session-adapter/test-local-session-adapter.mjs',
  'app/src/main/assets/builtin-plugins/dsh-status-overlay/lib/session-adapter.js']
process.stderr.write(JSON.stringify({ stage: 'assistant-local-runtime-proof-start', inputs: Object.fromEntries(inputs.map(file =>
  [file, createHash('sha256').update(readFileSync(file)).digest('hex')])) }) + '\n')
const runtime = testRuntime('raw')
process.stderr.write(JSON.stringify({ stage: 'assistant-local-runtime-proof-complete', runtime }) + '\n')
const require = createRequire(join(runtime, 'package.json'))
const load = name => import(pathToFileURL(require.resolve('@deepseek-ai/' + name)))
const { Context } = await load('cordis')
const { SessionController } = await load('dsh-api-session-controller')
const { createUserMessage, createAssistantMessage } = await load('dsh-llm')
const modules = await Promise.all(['dsh-session', 'dsh-agent', 'dsh-session-projection', 'dsh-llm',
  'dsh-system-prompt', 'dsh-tools', 'dsh-agent-loop'].map(load))
async function fixture(run) {
  const ctx = new Context()
  try {
    for (let index = 0; index < modules.length; index++)
      await ctx.plugin(modules[index].default, index === 5 ? { mode: 'native' } : index === 6 ? { agents: [], maxParallelToolCalls: 1 } : {})
    const handles = await Promise.all(['prefix-A-same1234', 'prefix-B-same1234'].map(sessionId => ctx.agents.create({ sessionId, meta: { cwd: resolve('.') } })))
    // Real rc3 inspect/prompt entrypoints, with an explicit public Agent admission port.
    // No model, network, file mutation, or complete Host assembly is claimed here.
    const controller = Object.create(SessionController.prototype); controller.ctx = ctx
    controller.commands = { prompt(request, source) {
      ctx.agents.get(request.sessionId).send(createUserMessage({ source, content: request.content }), 'next-turn', false)
      return { accepted: true }
    } }
    const call = sessionAdapter({ sessionController: controller, agents: ctx.agents }, 'host-actual-rc3')
    await run(ctx, call, handles.map(handle => handle.agent))
  } finally { await ctx.fiber.dispose() }
}
const args = sessionId => ({ hostInstanceId: 'host-actual-rc3', sessionId })
test('real locked rc3 Session inspection retains complete IDs sharing the same suffix', () => fixture(async (ctx, call, [a,b]) => {
  a.session.append('turn/start', { turn: 17 }); b.session.append('turn/start', { turn: 29 })
  a.session.append('assistant/message', { turn: 17, step: 1, message: createAssistantMessage({
    source: { provider: 'owned-test', model: 'no-network' }, content: [{ type: 'text', text: 'Committed assistant reply survives stream removal' }],
  }), stream: [] }, { surfaceOp: 'append' })
  const first = await call('sessions.status', args(a.id)), second = await call('sessions.status', args(b.id))
  assert.equal(first.sessionId, a.id); assert.equal(first.turnId, '17')
  assert.equal(second.sessionId, b.id); assert.equal(second.turnId, '29'); assert.notEqual(first.taskId, second.taskId)
  assert.match(first.text, /Committed assistant reply survives stream removal/)
  assert.deepEqual(phoneTaskEnvironment({ agent: a }), { DSH_PHONE_SESSION_ID: a.id, DSH_PHONE_TURN_ID: '17' })
  assert.deepEqual(phoneTaskEnvironment({ agent: b }), { DSH_PHONE_SESSION_ID: b.id, DSH_PHONE_TURN_ID: '29' })
  assert.deepEqual(phoneTaskEnvironment({}), {})
}))
test('host change rejects before prompt admission; actual public prompt creates an identified real Inbox occurrence', () => fixture(async (ctx, call, [agent]) => {
  await assert.rejects(call('sessions.send', { ...args(agent.id), hostInstanceId: 'old-host', text: 'no', requestId: 'r0' }), /HOST_INSTANCE_CHANGED/)
  const receipt = await call('sessions.send', { ...args(agent.id), text: 'retained text', requestId: 'request-full-id' })
  assert.equal(receipt.accepted, true); assert.equal(agent.inbox.nextTurn.length, 1)
  assert.equal(agent.inbox.nextTurn[0].source.rpcId, 'request-full-id')
  assert.equal(agent.inbox.nextTurn[0].content[0].text, 'retained text')
  const status = await call('sessions.status', args(agent.id)); assert.equal(status.running, true, 'queued input is not idle')
}))
test('pause cancels actual Agent maintenance and pending Inbox, waits for quiescence, leaves conversation attached', () => fixture(async (ctx, call, [agent]) => {
  let cancelled = false, settled = false
  agent.session.append('turn/start', { turn: 3 })
  const work = agent.runMaintenance(signal => new Promise(resolve => signal.addEventListener('abort', () => {
    cancelled = true; setTimeout(() => { settled = true; resolve() }, 20)
  }, { once: true })))
  await call('sessions.send', { ...args(agent.id), text: 'queued-do-not-replay', requestId: 'queued1' })
  const result = await call('sessions.cancel', { ...args(agent.id), turnId: '3' })
  assert.equal(result.accepted, true); assert.equal(cancelled, true); assert.equal(settled, true)
  await work; assert.equal(agent.inbox.nextTurn.length, 0); assert.equal(ctx.agents.get(agent.id), agent)
  assert.equal((await call('sessions.status', args(agent.id))).running, false)
}))
test('stale turn cancellation is rejected without cancelling a newer real turn', () => fixture(async (ctx, call, [agent]) => {
  agent.session.append('turn/start', { turn: 8 })
  await assert.rejects(call('sessions.cancel', { ...args(agent.id), turnId: '7' }), /TURN_CHANGED/)
  assert.equal(agent.session.snapshotEvents().at(-1).data.turn, 8)
}))

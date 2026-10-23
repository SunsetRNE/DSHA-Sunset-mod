import { randomUUID } from 'node:crypto'

/** The process instance is deliberately separate from Android Web generations. */
export const hostInstanceId = randomUUID()
const MAX_BODY = 65536
function id(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 256 || value.includes('\0'))
    throw new Error('SESSION_ID_INVALID')
  return value
}
function textOf(event) {
  const message = event?.type === 'assistant/message' ? event?.data?.message : event?.data
  if (!['user/message', 'assistant/message'].includes(event?.type)) return ''
  return (message?.content || []).filter(part => part.type === 'text')
    .map(part => String(part.text || '')).join('\n')
}
/** Trusted values are rebuilt for each actual shell execution, never last-active-session globals. */
export function phoneTaskEnvironment(execution) {
  const session = execution?.agent?.session
  const turn = session?.snapshotEvents().findLast(event => event.type === 'turn/start')
  return session?.id && turn ? { DSH_PHONE_SESSION_ID: session.id, DSH_PHONE_TURN_ID: String(turn.data.turn) } : {}
}
/** Uses the exact locked rc3 public business API. No private registry mutation. */
export function sessionAdapter(ctx, instance = hostInstanceId, liveText = () => '') {
  return async function call(method, args = {}, signal = AbortSignal.timeout(10000)) {
    if (args.hostInstanceId !== undefined && args.hostInstanceId !== instance)
      throw new Error('HOST_INSTANCE_CHANGED')
    const controller = ctx.sessionController
    if (!controller) throw new Error('SESSION_CONTROLLER_UNAVAILABLE')
    if (method === 'sessions.list') {
      const result = await controller.list({}, signal)
      return { ...result, hostInstanceId: instance }
    }
    if (!args.hostInstanceId) throw new Error('HOST_INSTANCE_REQUIRED')
    if (method === 'sessions.create') {
      // Native UI supplies no arbitrary path. Respect the configured Host workspace.
      return { ...await controller.create({}), hostInstanceId: instance }
    }
    const sessionId = id(args.sessionId)
    if (method === 'sessions.status') {
      const inspected = await controller.inspect(sessionId, signal)
      if (inspected.meta.id !== sessionId) throw new Error('SESSION_ID_CHANGED')
      const events = inspected.events
      const turn = events.findLast(event => event.type === 'turn/start')
      const agent = ctx.agents?.get(sessionId)
      const queued = (agent?.inbox?.nextTurn?.length || 0) + (agent?.inbox?.nextStep?.length || 0)
      const messages = events.filter(event => ['user/message', 'assistant/message'].includes(event.type)).slice(-8)
      const stream = liveText(sessionId)
      return { sessionId, hostInstanceId: instance, running: agent?.status === 'running' || queued > 0,
        turnId: turn ? String(turn.data.turn) : '', taskId: sessionId,
        text: (messages.map(textOf).filter(Boolean).join('\n\n') + (stream ? '\n\n' + stream : '')).slice(-24000) }
    }
    if (method === 'sessions.send') {
      if (typeof args.text !== 'string' || !args.text.trim() || args.text.length > 32768)
        throw new Error('PROMPT_INVALID')
      const requestId = id(args.requestId)
      // A receipt only means real inbox admission; never fabricated completion.
      return await controller.prompt({ sessionId, requestId, mode: 'queue',
        content: [{ type: 'text', text: args.text }] }, signal)
    }
    if (method === 'sessions.cancel') {
      const agent = ctx.agents?.get(sessionId)
      if (!agent) {
        // Inspect proves an existing cold conversation; it has no turn to cancel.
        await controller.inspect(sessionId, signal)
        return { accepted: true, running: false }
      }
      const lastTurn = agent.session.snapshotEvents().findLast(event => event.type === 'turn/start')
      if (args.turnId && String(lastTurn?.data?.turn) !== args.turnId) throw new Error('TURN_CHANGED')
      // Unlike ordinary Web cancel, pause must not let an old queued prompt wake later.
      // Public Agent.cancel defaults keepInbox=false and durably cancels pending input.
      agent.cancel({ kind: 'user' }, { keepInbox: false })
      await Promise.race([agent.whenIdle(), new Promise((_, reject) => {
        if (signal.aborted) { reject(signal.reason); return }
        signal.addEventListener('abort', () => reject(signal.reason), { once: true })
      })])
      return { accepted: true }
    }
    throw new Error('SESSION_METHOD_UNSUPPORTED')
  }
}
export function installSessionAdapter(ctx, liveText) {
  ctx.inject(['shellEnv'], scope => scope.effect(() => scope.shellEnv.register({
    name: 'dsha-phone-task', variables: {
      DSH_PHONE_SESSION_ID: { description: 'Exact local phone-control conversation ID for this tool execution.' },
      DSH_PHONE_TURN_ID: { description: 'Exact local phone-control turn ID for this tool execution.' },
    }, resolve: phoneTaskEnvironment,
  }), 'dsha exact per-execution phone task environment'))
  ctx.inject(['systemPrompt'], scope => scope.systemPrompt.section({ name: 'dsha:phone-task-binding', order: 155,
    text: 'For physical-phone /app/ui/* calls, use the current tool environment identity: append '
      + '--data-urlencode "taskSessionId=$DSH_PHONE_SESSION_ID" --data-urlencode "turnId=$DSH_PHONE_TURN_ID" '
      + 'to curl -G. These values identify this exact full conversation and turn; never copy another session or a shortened suffix. '
      + 'Pause/stop/expanded chat invalidates phone actions immediately. After user continuation, obtain a fresh screen observation; never replay UNKNOWN actions.',
  }))
  ctx.inject(['webServer', 'connection', 'sessionController', 'agents'], web => {
    const call = sessionAdapter(web, hostInstanceId, liveText)
    web.effect(() => web.webServer.register({ kind: 'exact', path: '/api/dsha-assistant',
      handler: async (req, res) => {
        const reply = (status, value) => { const bytes = Buffer.from(JSON.stringify(value));
          res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': bytes.length }); res.end(bytes) }
        const denied = web.connection.requestRejection(req)
        if (denied !== undefined) { reply(denied, { ok: false, code: 'AUTH_REQUIRED' }); return }
        if (req.method !== 'POST') { reply(405, { ok: false, code: 'POST_REQUIRED' }); return }
        if (req.headers.origin) {
          try { if (new URL(req.headers.origin).host !== req.headers.host) throw Error() }
          catch { reply(403, { ok: false, code: 'ORIGIN_REJECTED' }); return }
        }
        const deadline = AbortSignal.timeout(10000)
        try {
          req.setTimeout?.(10000, () => req.destroy(new Error('REQUEST_TIMEOUT')))
          let size = 0, blocks = []
          for await (const block of req) {
            size += block.length
            if (size > MAX_BODY) { reply(413, { ok: false, code: 'BODY_TOO_LARGE' }); return }
            blocks.push(block)
          }
          const body = JSON.parse(Buffer.concat(blocks).toString('utf8'))
          const value = await call(body.method, body.args, deadline)
          reply(200, { ok: true, value })
        } catch (error) { reply(409, { ok: false, code: String(error?.code || error?.message || 'SESSION_REQUEST_FAILED').slice(0, 160) }) }
      } }), 'dsha assistant exact local Host session API')
  })
}

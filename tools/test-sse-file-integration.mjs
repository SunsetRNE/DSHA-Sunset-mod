/**
 * Locked rc5 PiAiAdapter -> pi-ai -> OpenAI SDK -> loopback HTTP -> file tool.
 * No external model, credentials, Android, Host boot or automatic tool retry.
 * The filesystem test port wires the actual sandbox provider and defineTool;
 * native consent/dispatch policy is outside this transport/serialization test.
 * Run with DSHA_TEST_RUNTIME=<proven raw runtime>, optionally
 * DSHA_SSE_CANDIDATE=1 to apply the separately reviewed SDK SSE recipes.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire, registerHooks } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { createServer, request } from 'node:http';
import { once } from 'node:events';
import { gzipSync } from 'node:zlib';
import { setImmediate as nextTurn } from 'node:timers/promises';
import { testRuntime } from './test-runtime-fixture.mjs';
import { installResponseCompression } from './mobile-server/compress.js';

const root = path.resolve(import.meta.dirname, '..');
const assets = path.join(root, 'app/src/main/assets');
const output = path.resolve(process.env.DSHA_SSE_INTEGRATION_OUTPUT || path.join(root, 'app/build/sse-file-integration'));
fs.mkdirSync(output, { recursive: true });
const candidate = process.env.DSHA_SSE_CANDIDATE === '1';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const startedAt = new Date().toISOString();
process.stderr.write('SSE integration: verify locked raw runtime\n');
const raw = testRuntime('raw');
const require = createRequire(path.join(raw, 'package.json'));
const sources = new Map(), evidence = [], recipes = [];
const observed = new Map();
function applyRecipe(name) {
  const bytes = fs.readFileSync(path.join(assets, name));
  const recipe = JSON.parse(bytes);
  assert.equal(recipe.dshVersion, '0.2.1-rc.5');
  const file = path.join(raw, 'node_modules', recipe.module), url = pathToFileURL(file).href;
  let source = sources.get(url) ?? fs.readFileSync(file, 'utf8');
  for (const patch of recipe.patches) {
    assert.equal(source.split(patch.before).length - 1, 1, name + ' exact source anchor');
    const after = patch.prependAsset ? fs.readFileSync(path.join(assets, patch.prependAsset), 'utf8') + '\n' + patch.after : patch.after;
    source = source.replace(patch.before, after);
  }
  sources.set(url, source);
  recipes.push({ name, sha256: sha(bytes) });
}
// These existing production recipes affect the chain exercised here. The raw
// dependency tree stays byte-for-byte read only; hooks substitute source in RAM.
for (const name of ['tool-stream-identity-patch.json', 'tool-block-identity-patch.json',
  'conversation-stream-cancel-patch.json', 'fs-justification-patch.json']) applyRecipe(name);
if (candidate) for (const name of ['openai-sse-esm-patch.json', 'openai-sse-cjs-patch.json']) applyRecipe(name);
const piUrl = pathToFileURL(require.resolve('@deepseek-ai/dsh-llm-pi-ai')).href;
sources.set(piUrl, sources.get(piUrl) + '\nexport { resolveProfiles };\n');
const hook = registerHooks({
  load(url, context, next) {
    if (url.startsWith('file:')) {
      const file = fileURLToPath(url);
      if (file.startsWith(raw + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile())
        observed.set(file, sha(fs.readFileSync(file)));
    }
    if (sources.has(url)) return { format: url.endsWith('.mjs') || !url.includes('/openai/') ? 'module' : 'commonjs', source: sources.get(url), shortCircuit: true };
    return next(url, context);
  }
});
const load = name => import(pathToFileURL(require.resolve(name)).href);
const { PiAiAdapter, resolveProfiles } = await load('@deepseek-ai/dsh-llm-pi-ai');
const { BlockAssembler } = await load('@deepseek-ai/dsh-llm');
const { Context } = await load('@deepseek-ai/cordis');
const { SandboxedFileSystem } = await load('@deepseek-ai/dsh-fs-sandbox');
const filesystem = await load('@deepseek-ai/dsh-tool-fs');
const { ToolRuntime } = await load('@deepseek-ai/dsh-tools');
const sdkVersion = JSON.parse(fs.readFileSync(path.join(raw, 'node_modules/openai/package.json'))).version;

const fixtureText = '中文🧪 café العربية\nquotes: " \\ \t \r\n'
  + 'data: [DONE]\nevent: error\n\n{"not":"an SSE frame inside a file"}\n';
const largeText = fixtureText.repeat(9000);
const chatChunk = (delta, finish_reason = null) => ({ id: 'owned-response', object: 'chat.completion.chunk',
  created: 1, model: 'owned-local', choices: [{ index: 0, delta, finish_reason }] });
const wire = value => 'data: ' + JSON.stringify(value) + '\r\n\r\n';
function toolWire(args, { heartbeat = false } = {}) {
  const json = JSON.stringify(args), events = [wire(chatChunk({ role: 'assistant', tool_calls: [
    { index: 0, id: 'owned-write-call', type: 'function', function: { name: 'write', arguments: '' } }]
  }))];
  // Split arguments at UTF-16 positions, including escaped text and surrogates.
  // Every individual event still has valid JSON, as required by the protocol.
  for (let offset = 0; offset < json.length; offset += 4037) {
    if (heartbeat && offset % (4037 * 31) === 0) events.push('event: ping\r\n\r\n');
    events.push(wire(chatChunk({ tool_calls: [{ index: 0, function: { arguments: json.slice(offset, offset + 4037) } }] })));
  }
  events.push(wire(chatChunk({}, 'tool_calls')), 'data: [DONE]\r\n\r\n');
  return Buffer.from(events.join(''));
}
const replyWire = Buffer.from(wire(chatChunk({ role: 'assistant', content: '已保存。🧪' })) + wire(chatChunk({}, 'stop')) + 'data: [DONE]\r\n\r\n');
const responseEvent = event => 'event: ' + event.type + '\r\ndata: ' + JSON.stringify(event) + '\r\n\r\n';
function responsesWire(args, heartbeat = false) {
  const item = { id: 'fc_owned', call_id: 'owned-write-call', type: 'function_call', name: 'write', arguments: '' };
  const events = [responseEvent({ type: 'response.created', response: { id: 'resp_owned' } }),
    responseEvent({ type: 'response.output_item.added', output_index: 0, item })];
  if (heartbeat) events.push('event: ping\r\n\r\n');
  const json = JSON.stringify(args);
  for (let offset = 0; offset < json.length; offset += 4037) events.push(responseEvent({
    type: 'response.function_call_arguments.delta', output_index: 0, item_id: item.id, delta: json.slice(offset, offset + 4037) }));
  const completed = { ...item, arguments: json, status: 'completed' };
  events.push(responseEvent({ type: 'response.output_item.done', output_index: 0, item: completed }),
    responseEvent({ type: 'response.completed', response: { id: 'resp_owned', status: 'completed', output: [completed] } }));
  return Buffer.from(events.join(''));
}
const responseTextItem = { id: 'msg_owned', type: 'message', role: 'assistant', status: 'completed',
  content: [{ type: 'output_text', text: '已保存。🧪', annotations: [] }] };
const responsesReply = Buffer.from([
  { type: 'response.created', response: { id: 'resp_owned_reply' } },
  { type: 'response.output_item.added', output_index: 0, item: { ...responseTextItem, content: [] } },
  { type: 'response.output_text.delta', output_index: 0, delta: '已保存。🧪' },
  { type: 'response.output_item.done', output_index: 0, item: responseTextItem },
  { type: 'response.completed', response: { id: 'resp_owned_reply', status: 'completed', output: [responseTextItem] } }
].map(responseEvent).join(''));
async function bind(server) {
  server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port;
}
async function close(server) {
  server.closeAllConnections?.(); await new Promise(resolve => server.close(resolve));
}
async function sendPieces(response, bytes, pattern = [1, 2, 3, 7, 97, 4093, 16381]) {
  for (let offset = 0, part = 0; offset < bytes.length; part++) {
    const end = Math.min(offset + pattern[part % pattern.length], bytes.length);
    if (!response.write(bytes.subarray(offset, end))) await once(response, 'drain');
    offset = end;
    // Preserve actual asynchronous socket boundaries without a timer per byte.
    if (part % 13 === 0) await nextTurn();
  }
  response.end();
}
async function makeCarrier({ body, reply = replyWire, gzip = false, proxy = false, status = 200, api = 'openai-completions' }) {
  const requests = [], errors = [], headers = [];
  const backend = createServer(async (req, res) => {
    try {
      assert.equal(req.url, api === 'openai-responses' ? '/v1/responses' : '/v1/chat/completions'); assert.equal(req.method, 'POST');
      const chunks = []; for await (const chunk of req) chunks.push(chunk);
      const bytes = Buffer.concat(chunks);
      requests.push({ payload: JSON.parse(bytes), sha256: sha(bytes), bytes: bytes.length });
      const payload = requests.length === 1 ? body : reply;
      res.writeHead(status, { 'Content-Type': 'text/event-stream; charset=utf-8',
        ...(gzip ? { 'Content-Encoding': 'gzip' } : {}) });
      headers.push(res.getHeaders());
      await sendPieces(res, gzip ? gzipSync(payload) : payload);
    } catch (error) { errors.push(error); res.destroy(error); }
  });
  const backendPort = await bind(backend);
  let front, port = backendPort;
  if (proxy) {
    // A byte-preserving loopback relay, not an imitation Android LAN service.
    front = createServer((req, res) => {
      const relay = request({ hostname: '127.0.0.1', port: backendPort, path: req.url,
        method: req.method, headers: req.headers }, upstream => {
        res.writeHead(upstream.statusCode, upstream.headers); upstream.pipe(res);
        upstream.on('error', error => { errors.push(error); res.destroy(error); });
      });
      relay.on('error', error => { errors.push(error); res.destroy(error); }); req.pipe(relay);
    });
    port = await bind(front);
  }
  const disposeCompression = installResponseCompression({ matches: req => [backendPort, port].includes(req.socket.localPort) });
  const profiles = resolveProfiles({ owned: { api, transport: 'sse', baseURL: `http://127.0.0.1:${port}/v1`,
    models: [{ id: 'owned-local', input: ['text'], reasoningEfforts: false }], streamIdleTimeoutMs: 15000 } });
  const adapter = new PiAiAdapter({ profiles: () => profiles, resolveApiKey: async () => 'owned-loopback-fixture-key' });
  return { adapter, requests, headers, errors, port,
    async dispose() { disposeCompression(); if (front) await close(front); await close(backend); } };
}
async function fileFixture(name) {
  const directory = await fsp.mkdtemp(path.join(output, name + '-'));
  const session = { id: 'owned-file-session', header: { cwd: directory } };
  const policy = { defaultMode: 'workspace-write', resolve: () => ({ mode: 'workspace-write', workspaceRoot: directory, sessionId: session.id }) };
  const providerContext = new Context(); providerContext.provide('sandboxPolicy', policy);
  const provider = new SandboxedFileSystem(providerContext, { cwd: directory, diffBasisMaxBytes: 4 * 1024 * 1024 });
  const state = { writes: 0, observed: 0 }, tools = new Map();
  const ctx = { fs: { sandboxMode: provider.sandboxMode,
    resolve: (...args) => provider.resolve(...args), processPath: (...args) => provider.processPath(...args),
    async writeText(...args) { state.writes++; return provider.writeText(...args); } },
    get: name => name === 'sandboxPolicy' ? policy : undefined,
    tools: { register: tool => tools.set(tool.name, tool), get: name => tools.get(name) },
    systemPrompt: { section() {}, getSectionOrder: () => 0 }, workingDirectory: { ensure: async () => directory },
    waterfall: async () => undefined, emit: () => state.observed++ };
  filesystem.apply(ctx, { read: false, readImage: false, write: true, edit: false,
    readLimit: 100, readMaxLineLength: 1000, readMaxBytes: 4096, readStreamMinSize: 1024 });
  const tool = tools.get('write'), target = path.join(directory, '文件-é.txt');
  const exec = { callId: 'owned-write-call', token: {}, signal: new AbortController().signal, agent: { session } };
  // Execute the real output snapshot/schema/render/freeze path with explicit
  // memory ownership ports; do not construct a Host or a native dispatch policy.
  const results = Object.create(ToolRuntime.prototype);
  results.canonicalResults = new WeakMap(); results.concludingExecutions = new WeakSet();
  return { state, tool, exec, target, providerContext, results };
}
const prompt = { role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text: 'Owned synthetic file-writing fixture.' }] };
async function consume(adapter, messages, tool) {
  const assembler = new BlockAssembler(), chunks = [];
  for await (const chunk of adapter.stream({ provider: 'owned', model: 'owned-local', messages,
    tools: [{ name: 'write', description: tool.description, parameters: tool.parameters }] })) {
    chunks.push(chunk); assembler.push(chunk);
    if (chunk.type === 'finish' && ['error', 'aborted'].includes(chunk.reason.kind)) {
      const error = new Error(chunk.reason.failure.message); error.code = chunk.reason.failure.code; throw error;
    }
  }
  return { chunks, blocks: assembler.blocks() };
}
function assistantMessage(response) {
  const finish = response.chunks.findLast(chunk => chunk.type === 'finish');
  assert(finish?.replayState, 'retain real adapter replay metadata as the agent does');
  return { role: 'assistant', source: { kind: 'model', provider: 'owned', model: 'owned-local', replayState: finish.replayState }, content: response.blocks };
}
async function run(name, fn) {
  const began = performance.now();
  try { const facts = await fn(); evidence.push({ name, status: 'PASS', elapsedMs: Math.round(performance.now() - began), ...facts }); }
  catch (error) { evidence.push({ name, status: 'FAIL', elapsedMs: Math.round(performance.now() - began), message: error.message, stack: error.stack }); }
  process.stdout.write(JSON.stringify(evidence.at(-1)) + '\n');
}

try {
  for (const mode of [{ name: 'direct-large', gzip: false, proxy: false },
    { name: 'gzip-large', gzip: true, proxy: false },
    { name: 'relay-gzip-large', gzip: true, proxy: true },
    { name: 'relay-event-only-heartbeat', gzip: false, proxy: true, heartbeat: true }]) {
    await run(mode.name + '-file-and-result-roundtrip', async () => {
      const f = await fileFixture(mode.name);
      const args = { file_path: f.target, content: largeText, justification: '' };
      const carrier = await makeCarrier({ ...mode, body: toolWire(args, mode) });
      try {
        if (mode.heartbeat && !candidate) {
          await assert.rejects(consume(carrier.adapter, [prompt], f.tool), /malformed server-sent event JSON/);
          assert.equal(f.state.writes, 0); assert.equal(carrier.requests.length, 1);
          assert.equal(fs.existsSync(f.target), false);
          return { expectedControlFailure: 'event-only heartbeat rejected by locked SDK', requests: 1, writes: 0 };
        }
        const first = await consume(carrier.adapter, [prompt], f.tool);
        assert(first.chunks.some(chunk => chunk.type === 'finish' && chunk.reason.kind === 'tool-calls'));
        assert.equal(first.blocks.length, 1); const call = first.blocks[0];
        assert.equal(call.type, 'tool-call'); assert.equal(call.id, 'owned-write-call'); assert.equal(call.name, 'write');
        assert.deepEqual(JSON.parse(call.arguments), args);
        // Execute once only after a complete successful provider response.
        f.exec.arguments = JSON.parse(call.arguments);
        const value = await f.tool.execute(f.exec.arguments, f.exec);
        const result = f.results.createSuccessResult(f.exec, f.tool, value);
        assert.equal(f.state.writes, 1); assert.equal(f.state.observed, 1);
        const fileBytes = fs.readFileSync(f.target);
        assert.deepEqual(fileBytes, Buffer.from(largeText));
        const history = [prompt,
          assistantMessage(first),
          { role: 'tool', toolCallId: call.id, toolName: call.name, content: result.content, isError: false }];
        assert(Array.isArray(result.content), 'real defineTool returned canonical tool content');
        const second = await consume(carrier.adapter, history, f.tool);
        assert.equal(second.blocks.filter(block => block.type === 'text').map(block => block.text).join(''), '已保存。🧪');
        assert.equal(carrier.requests.length, 2); assert.equal(f.state.writes, 1);
        const payload = carrier.requests[1].payload, toolMessage = payload.messages.find(message => message.role === 'tool');
        assert.equal(toolMessage.tool_call_id, call.id);
        assert.equal(toolMessage.content, result.content.map(block => block.text).join('\n'));
        assert.equal(JSON.parse(payload.messages.find(message => message.tool_calls)?.tool_calls[0].function.arguments).content, largeText);
        assert.deepEqual(carrier.errors, []);
        return { requests: 2, writes: 1, bytes: fileBytes.length, fileSha256: sha(fileBytes),
          requestBytes: carrier.requests.map(row => row.bytes), responseFrames: first.chunks.length,
          transport: { gzip: mode.gzip, byteRelay: mode.proxy, dshaCompressionInstalled: true } };
      } finally { await carrier.dispose(); await f.providerContext.fiber.dispose(); }
    });
  }
  for (const malformed of [
    { name: 'invalid-json-data-before-tool', bytes: Buffer.from('data: {broken}\n\n') },
    { name: 'invalid-json-data-after-complete-tool-frame', after: true, bytes: Buffer.from('data: {broken}\n\n') },
    { name: 'truncated-tool-stream', bytes: Buffer.from(wire(chatChunk({ role: 'assistant', tool_calls: [{ index: 0, id: 'owned-write-call', type: 'function', function: { name: 'write', arguments: '{"file_path":' } }] }))) }
  ]) await run(malformed.name + '-does-not-write-or-replay', async () => {
    const f = await fileFixture(malformed.name), args = { file_path: f.target, content: fixtureText };
    // A complete tool block followed by broken data still has no terminal
    // finish; the owned runner refuses to dispatch this unknown response.
    const full = toolWire(args), done = Buffer.from('data: [DONE]\r\n\r\n');
    assert.deepEqual(full.subarray(-done.length), done);
    // File contents deliberately contain literal "data: [DONE]". Remove the
    // exact terminal suffix, never a substring inside the tool arguments.
    const body = malformed.after ? Buffer.concat([full.subarray(0, -done.length), malformed.bytes]) : malformed.bytes;
    const carrier = await makeCarrier({ body, proxy: true });
    try {
      let failure;
      try { await consume(carrier.adapter, [prompt], f.tool); } catch (error) { failure = error; }
      assert(failure, 'malformed/incomplete response must fail');
      assert.equal(carrier.requests.length, 1); assert.equal(f.state.writes, 0); assert(!fs.existsSync(f.target));
      return { requests: 1, writes: 0, error: failure.message, code: failure.code };
    } finally { await carrier.dispose(); await f.providerContext.fiber.dispose(); }
  });
  await run('malformed-next-response-preserves-completed-write-without-replay', async () => {
    const f = await fileFixture('after-write-failure');
    const args = { file_path: f.target, content: fixtureText, justification: '' };
    const carrier = await makeCarrier({ body: toolWire(args), reply: Buffer.from('data: {broken}\n\n'), proxy: true });
    try {
      const first = await consume(carrier.adapter, [prompt], f.tool);
      assert(first.chunks.some(chunk => chunk.type === 'finish' && chunk.reason.kind === 'tool-calls'));
      const call = first.blocks[0]; f.exec.arguments = JSON.parse(call.arguments);
      const value = await f.tool.execute(f.exec.arguments, f.exec);
      const result = f.results.createSuccessResult(f.exec, f.tool, value);
      assert.equal(f.state.writes, 1);
      const before = fs.readFileSync(f.target);
      const history = [prompt,
        assistantMessage(first),
        { role: 'tool', toolCallId: call.id, toolName: call.name, content: result.content, isError: false }];
      await assert.rejects(consume(carrier.adapter, history, f.tool), /malformed server-sent event JSON/);
      assert.equal(carrier.requests.length, 2); assert.equal(f.state.writes, 1);
      assert.deepEqual(fs.readFileSync(f.target), before);
      return { requests: 2, writes: 1, fileStillPresent: true, fileSha256: sha(before),
        evidenceBoundary: 'completed file mutation retained independently of failure in later provider response' };
    } finally { await carrier.dispose(); await f.providerContext.fiber.dispose(); }
  });
  for (const heartbeat of [false, true]) await run('responses-api-gzip-file-roundtrip' + (heartbeat ? '-heartbeat' : ''), async () => {
    const f = await fileFixture('responses-' + heartbeat);
    const args = { file_path: f.target, content: largeText, justification: '' };
    const carrier = await makeCarrier({ api: 'openai-responses', body: responsesWire(args, heartbeat),
      reply: responsesReply, gzip: true, proxy: true });
    try {
      if (heartbeat && !candidate) {
        await assert.rejects(consume(carrier.adapter, [prompt], f.tool), /malformed server-sent event JSON/);
        assert.equal(f.state.writes, 0); assert.equal(carrier.requests.length, 1); assert(!fs.existsSync(f.target));
        return { expectedControlFailure: 'Responses API also rejects event-only heartbeat in locked SDK', requests: 1, writes: 0 };
      }
      const first = await consume(carrier.adapter, [prompt], f.tool);
      assert(first.chunks.some(chunk => chunk.type === 'finish' && chunk.reason.kind === 'tool-calls'));
      assert.equal(first.blocks.length, 1); const call = first.blocks[0];
      assert.equal(call.id, 'owned-write-call|fc_owned'); assert.equal(call.name, 'write');
      f.exec.callId = call.id; f.exec.arguments = JSON.parse(call.arguments);
      assert.deepEqual(f.exec.arguments, args);
      const result = f.results.createSuccessResult(f.exec, f.tool, await f.tool.execute(f.exec.arguments, f.exec));
      assert.deepEqual(fs.readFileSync(f.target), Buffer.from(largeText));
      const history = [prompt,
        assistantMessage(first),
        { role: 'tool', toolCallId: call.id, toolName: call.name, content: result.content, isError: false }];
      const second = await consume(carrier.adapter, history, f.tool);
      assert.equal(second.blocks.filter(block => block.type === 'text').map(block => block.text).join(''), '已保存。🧪');
      const input = carrier.requests[1].payload.input;
      const callItem = input.find(item => item.type === 'function_call');
      assert.equal(JSON.parse(callItem.arguments).content, largeText);
      const resultItem = input.find(item => item.type === 'function_call_output');
      // pi-ai normalizes IDs for custom provider routes. The result must refer
      // to exactly the paired serialized call, including that normalization.
      assert.equal(typeof callItem.call_id, 'string'); assert(callItem.call_id.length > 0);
      assert.equal(resultItem.call_id, callItem.call_id);
      assert.equal(resultItem.output, result.content.map(block => block.text).join('\n'));
      assert.equal(carrier.requests.length, 2); assert.equal(f.state.writes, 1); assert.deepEqual(carrier.errors, []);
      return { api: 'openai-responses', requests: 2, writes: 1, bytes: Buffer.byteLength(largeText),
        fileSha256: sha(fs.readFileSync(f.target)), requestBytes: carrier.requests.map(row => row.bytes),
        transport: { gzip: true, byteRelay: true, dshaCompressionInstalled: true } };
    } finally { await carrier.dispose(); await f.providerContext.fiber.dispose(); }
  });
} finally {
  hook.deregister();
  for (const [file, digest] of observed) assert.equal(sha(fs.readFileSync(file)), digest, 'locked executed dependency changed: ' + file);
  const report = { schema: 1, startedAt, finishedAt: new Date().toISOString(), mode: candidate ? 'candidate-sdk' : 'locked-sdk-control',
    dshVersion: '0.2.1-rc.5', openaiVersion: sdkVersion, rawRuntime: raw, runtimeMarkerSha256: sha(fs.readFileSync(path.join(raw, 'dsha-test-runtime.json'))),
    recipes, observedDependencies: [...observed].map(([file, sha256]) => ({ file, sha256 })),
    limitations: ['Loopback provider and byte relay only; no external model or Android LAN proxy.',
      'Real file tool/schema/sandbox and result serialization; native consent/agent dispatch policy not exercised.',
      'Explicit owned runner dispatches once after complete stream; no automatic retry or unknown-result replay.'],
    results: evidence, passed: evidence.filter(row => row.status === 'PASS').length, failed: evidence.filter(row => row.status === 'FAIL').length };
  fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2) + '\n');
  if (report.failed) process.exitCode = 1;
}

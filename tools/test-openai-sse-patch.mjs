// 真正加载锁定 SDK；候选只重定位静态导入，不替换解析器或网络流实现。
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';

const [raw, output] = process.argv.slice(2);
assert(raw && output, 'invoke through test-openai-sse-patch.py');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const work = path.join(path.dirname(output), 'sdk-module-candidates');
await fs.mkdir(work, { recursive: true });
const require = createRequire(path.join(raw, 'package.json'));
const enc = new TextEncoder();
const results = [];
const baseline = [];
const silent = { logger: { error() {}, warn() {}, info() {}, debug() {} }, logLevel: 'off' };

async function test(name, fn) {
  try { await fn(); results.push({ name, status: 'PASS' }); }
  catch (e) { results.push({ name, status: 'FAIL', message: e.message, stack: e.stack }); }
}

function response(wire, width = Infinity, { stayOpen = false, errorAtEnd, onCancel } = {}) {
  const bytes = typeof wire === 'string' ? enc.encode(wire) : wire;
  let index = 0;
  return new Response(new ReadableStream({
    pull(controller) {
      if (index < bytes.length) {
        const end = Math.min(index + width, bytes.length);
        controller.enqueue(bytes.slice(index, end));
        index = end;
      } else if (errorAtEnd) controller.error(errorAtEnd);
      else if (!stayOpen) controller.close();
    },
    cancel(reason) { onCancel?.(reason); },
  }), { headers: { 'content-type': 'text/event-stream', 'x-request-id': 'synthetic-request' } });
}

async function read(sdk, wire, width, options = {}) {
  const controller = options.controller ?? new AbortController();
  const stream = sdk.Stream.fromSSEResponse(response(wire, width, options), controller, options.client ?? silent, options.synthesize);
  const values = [];
  try { for await (const value of stream) values.push(value); }
  catch (error) { return { values, error, aborted: controller.signal.aborted }; }
  return { values, aborted: controller.signal.aborted };
}

async function loadCandidate(kind) {
  const recipe = JSON.parse(await fs.readFile(path.join(root, 'app/src/main/assets', `openai-sse-${kind}-patch.json`), 'utf8'));
  const file = path.join(raw, 'node_modules', recipe.module);
  const source = await fs.readFile(file, 'utf8');
  assert.equal(crypto.createHash('sha256').update(source).digest('hex'), recipe.sourceSha256);
  let text = source;
  for (const { before, after } of recipe.patches) {
    assert.equal(text.split(before).length - 1, 1);
    text = text.replace(before, after);
  }
  assert.equal(crypto.createHash('sha256').update(text).digest('hex'), recipe.targetSha256);
  const target = path.join(work, 'streaming.' + (kind === 'esm' ? 'mjs' : 'cjs'));
  if (kind === 'esm') {
    text = text.replace(/(from\s+)("[^"\r\n]+")/g, (all, prefix, quoted) => {
      const name = JSON.parse(quoted);
      return name.startsWith('.') ? prefix + JSON.stringify(pathToFileURL(path.resolve(path.dirname(file), name)).href) : all;
    });
  } else {
    text = text.replace(/require\(("[^"\r\n]+")\)/g, (all, quoted) => {
      const name = JSON.parse(quoted);
      return name.startsWith('.') ? 'require(' + JSON.stringify(path.resolve(path.dirname(file), name)) + ')' : all;
    });
  }
  await fs.writeFile(target, text);
  return { original: kind === 'esm' ? await import(pathToFileURL(file)) : require(file),
           candidate: kind === 'esm' ? await import(pathToFileURL(target)) : require(target) };
}

for (const kind of ['esm', 'cjs']) {
  const { original, candidate } = await loadCandidate(kind);
  for (const [name, wire] of [
    ['event-only-control', 'event: ping\n\ndata: {"ok":true}\n\ndata: [DONE]\n\n'],
    ['unterminated-complete-data', 'data: {"ok":true}'],
    ['unterminated-broken-data', 'data: {"content":"unfinished'],
  ]) {
    const value = await read(original, wire, 1);
    baseline.push({ kind, name, values: value.values, error: value.error?.message, aborted: value.aborted });
  }
  await test(`${kind}: raw event-only regression is reproduced`, async () => {
    const row = baseline.find(r => r.kind === kind && r.name === 'event-only-control');
    assert.match(row.error, /malformed server-sent event JSON/);
    assert.deepEqual(row.values, []);
  });
  for (const ending of ['\n', '\r', '\r\n']) {
    const wire = '\ufeff: 保活🚀' + ending + ending + 'event: ping' + ending + ending +
      'event: message' + ending + 'data: {"text":' + ending + 'data: "中文🚀🧪", "n":1}' + ending + ending +
      'event:' + ending + 'data: {"n":2}' + ending + ending + 'data: [DONE]' + ending + ending;
    for (const width of [1, 2, 3, 7, 64, Infinity]) {
      await test(`${kind}: UTF8 ${JSON.stringify(ending)} multiline width=${width}`, async () => {
        const result = await read(candidate, wire, width, { synthesize: true });
        assert.ifError(result.error);
        assert.deepEqual(result.values, [
          { event: 'message', data: { text: '中文🚀🧪', n: 1 } },
          { event: null, data: { n: 2 } },
        ]);
        assert.equal(result.aborted, false);
      });
    }
  }
  await test(`${kind}: every two-part byte split preserves frames`, async () => {
    const wire = enc.encode('event: ping\r\n\r\ndata: {"text":\r\ndata: "中文🚀"}\r\n\r\ndata: [DONE]\r\n\r\n');
    for (let split = 0; split <= wire.length; split++) {
      const controller = new AbortController();
      const response = new Response(new ReadableStream({ start(c) {
        c.enqueue(wire.slice(0, split)); c.enqueue(wire.slice(split)); c.close();
      }}));
      const values = [];
      for await (const value of candidate.Stream.fromSSEResponse(response, controller, silent)) values.push(value);
      assert.deepEqual(values, [{ text: '中文🚀' }], 'split=' + split);
    }
  });
  await test(`${kind}: heartbeat diagnostics are released at each blank line`, async () => {
    const wire = ': heartbeat\n\n'.repeat(20000) + 'id: 7\nretry: 5\nunknown: ignored\n\ndata: {"ok":true}\n\n';
    const frames = [];
    for await (const frame of candidate._iterSSEMessages(response(wire, 127), new AbortController())) frames.push(frame);
    assert.equal(frames.length, 1);
    assert.deepEqual(frames[0].raw, ['data: {"ok":true}']);
  });
  await test(`${kind}: one 2MiB tool argument event is byte exact`, async () => {
    const text = '中文🚀\n"\\'.repeat(160000);
    const item = { choices: [{ delta: { tool_calls: [{ id: 'file-1', type: 'function', function: {
      name: 'write_file', arguments: JSON.stringify({ path: 'proof.txt', content: text }),
    }}] } }] };
    const result = await read(candidate, 'data: ' + JSON.stringify(item) + '\r\n\r\ndata: [DONE]\r\n\r\n', 8191);
    assert.ifError(result.error);
    assert.deepEqual(result.values, [item]);
  });
  await test(`${kind}: 10000 coalesced events remain ordered and unique`, async () => {
    const items = Array.from({ length: 10000 }, (_, n) => ({ n }));
    const result = await read(candidate, items.map(x => 'data: ' + JSON.stringify(x) + '\n\n').join('') + 'data: [DONE]\n\n', Infinity);
    assert.ifError(result.error);
    assert.deepEqual(result.values, items);
  });
  for (const bad of ['', ' ', '{"x":', '<html>proxy failure</html>', '[DONE]junk', '{"x":"unescaped\ndata: newline"}']) {
    await test(`${kind}: malformed data length=${bad.length} fails without following event`, async () => {
      const result = await read(candidate, 'data: ' + bad + '\n\ndata: {"shouldNotRun":true}\n\n', 3);
      assert.match(result.error?.message ?? '', /DSHA_SSE_INVALID_JSON/);
      assert.deepEqual(result.values, []);
      assert.equal(result.aborted, true);
      assert(!result.error.message.includes('proxy failure'));
    });
  }
  await test(`${kind}: named thread malformed JSON remains terminal`, async () => {
    const result = await read(candidate, 'event: thread.message.delta\ndata: {bad}\n\n', 2);
    assert.match(result.error?.message ?? '', /DSHA_SSE_INVALID_JSON/);
    assert.deepEqual(result.values, []);
  });
  await test(`${kind}: control characters fail without exposing payload in error or logger`, async () => {
    for (const control of ['\0', '\u0001', '\u000b', '\u001b']) {
      const logged = [];
      const client = { logLevel: 'debug', logger: Object.fromEntries(['error', 'warn', 'info', 'debug']
        .map(level => [level, (...args) => logged.push([level, ...args])])) };
      const secret = 'SYNTHETIC_PRIVATE_FILE_PAYLOAD';
      const result = await read(candidate, 'event: thread.' + secret + '\ndata: {"content":"' + secret + control + '"}\n\n', 1, { client });
      assert.match(result.error?.message ?? '', /DSHA_SSE_INVALID_JSON; dataChars=\d+/);
      assert.deepEqual(result.values, []);
      assert.equal(result.aborted, true);
      assert.equal(result.error.cause, undefined);
      assert(!JSON.stringify([result.error.message, result.error.stack, logged]).includes(secret));
      assert.deepEqual(logged, [['error', 'Could not parse message into JSON:'], ['error', 'From chunk:']]);
    }
  });
  for (const tail of ['data: {"x":', 'data:', 'data: {"tool":{"arguments":"unfinished']) {
    await test(`${kind}: truncated JSON tail ${JSON.stringify(tail)} is not committed`, async () => {
      const result = await read(candidate, 'data: {"before":1}\n\n' + tail, 1);
      assert.deepEqual(result.values, [{ before: 1 }]);
      assert.match(result.error?.message ?? '', /DSHA_SSE_INVALID_JSON/);
      assert.equal(result.aborted, true);
    });
  }
  await test(`${kind}: complete JSON at EOF keeps SDK compatibility without duplicate dispatch`, async () => {
    for (const tail of ['data: {"x":1}', 'data: {"x":1}\n', 'data: {"x":1}\r\n', 'data: {"x":1}\n\n']) {
      const result = await read(candidate, 'data: {"before":1}\n\n' + tail, 1);
      assert.ifError(result.error);
      assert.deepEqual(result.values, [{ before: 1 }, { x: 1 }]);
    }
  });
  await test(`${kind}: DONE at EOF keeps SDK compatibility`, async () => {
    const result = await read(candidate, 'data: {"before":1}\n\ndata: [DONE]', 1);
    assert.ifError(result.error);
    assert.deepEqual(result.values, [{ before: 1 }]);
  });
  await test(`${kind}: EOF with only comment or control metadata is clean`, async () => {
    for (const wire of ['', ': ping', ': ping\n', 'event: ping', 'event: ping\n\n', 'id: last\nretry: 1']) {
      const result = await read(candidate, wire, 1);
      assert.ifError(result.error);
      assert.deepEqual(result.values, []);
    }
  });
  await test(`${kind}: real provider error is preserved and cancels source`, async () => {
    const result = await read(candidate, 'event: error\ndata: {"error":{"message":"synthetic overload","type":"overloaded_error"}}\n\n', 3);
    assert.match(result.error?.message ?? '', /synthetic overload/);
    assert.equal(result.error?.constructor?.name, 'APIError');
    assert.equal(result.aborted, true);
  });
  await test(`${kind}: unnamed error envelope remains terminal`, async () => {
    const result = await read(candidate, 'data: {"error":{"message":"synthetic error"}}\n\n', 1);
    assert.match(result.error?.message ?? '', /synthetic error/);
    assert.deepEqual(result.values, []);
  });
  await test(`${kind}: transport failure is preserved`, async () => {
    const error = new Error('synthetic socket reset');
    const result = await read(candidate, 'data: {"partial":', 3, { errorAtEnd: error });
    assert.equal(result.error, error);
    assert.deepEqual(result.values, []);
  });
  await test(`${kind}: abort while awaiting partial UTF8 body cancels once`, async () => {
    const controller = new AbortController();
    let cancels = 0;
    const pending = read(candidate, enc.encode('data: {"中文').slice(0, -1), 1,
      { controller, stayOpen: true, onCancel() { cancels++; } });
    const timer = setTimeout(() => controller.abort(), 10);
    const result = await pending;
    clearTimeout(timer);
    assert.ifError(result.error);
    assert.deepEqual(result.values, []);
    assert.equal(cancels, 1);
  });
  await test(`${kind}: break after one event cancels once and never yields subsequent tools`, async () => {
    const controller = new AbortController();
    let cancels = 0;
    const stream = candidate.Stream.fromSSEResponse(response('data: {"first":1}\n\ndata: {"tool":2}\n\n', 1,
      { stayOpen: true, onCancel() { cancels++; } }), controller, silent);
    const values = [];
    for await (const value of stream) { values.push(value); break; }
    assert.deepEqual(values, [{ first: 1 }]);
    assert.equal(controller.signal.aborted, true);
    assert.equal(cancels, 1);
  });
  await test(`${kind}: DONE suppresses following garbage and permits stalled cleanup`, async () => {
    const result = await read(candidate, 'data: {"ok":true}\n\ndata: [DONE]\n\ndata: {bad}\n\n', 1, { stayOpen: true });
    assert.ifError(result.error);
    assert.deepEqual(result.values, [{ ok: true }]);
  });
}

await fs.writeFile(output, JSON.stringify({
  schema: 1, runtime: raw, node: process.version,
  scope: 'Real pinned OpenAI SDK modules and synthetic byte streams; no device or provider account request.',
  counts: { total: results.length, passed: results.filter(r => r.status === 'PASS').length, failed: results.filter(r => r.status !== 'PASS').length },
  baseline, results,
}, null, 2) + '\n');
console.log(JSON.stringify({ total: results.length, failed: results.filter(r => r.status !== 'PASS') }, null, 2));
if (results.some(r => r.status !== 'PASS')) process.exitCode = 1;

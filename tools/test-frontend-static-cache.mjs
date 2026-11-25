// 冷启动回归：外壳静态资源必须可复用，索引必须永不缓存。
// Index renders are uncached. Static revalidation may reuse an upstream content
// tag only while the opened file has the same settled identity.
// 显式参数：`node tools/test-frontend-static-cache.mjs <runtime 根或模块文件>`；
// 不给参数时按 CI 夹具选择当前 raw 运行时。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { createHash } from 'node:crypto';

const root = path.resolve(import.meta.dirname, '..');
const relativeModule = 'node_modules/@deepseek-ai/dsh-host-frontend-static/lib/index.js';

async function moduleFile() {
  const explicit = process.argv[2] || process.env.DSHA_FRONTEND_STATIC_MODULE;
  if (explicit) {
    const selected = path.resolve(explicit);
    return fs.statSync(selected).isDirectory() ? path.join(selected, relativeModule) : selected;
  }
  const { testRuntime } = await import('./test-runtime-fixture.mjs');
  return path.join(testRuntime('raw'), relativeModule);
}

const recipe = JSON.parse(
    fs.readFileSync(path.join(root, 'app/src/main/assets/frontend-static-cache-patch.json'), 'utf8'));
const registry = JSON.parse(
    fs.readFileSync(path.join(root, 'app/src/main/assets/runtime-patches.json'), 'utf8'));
assert.equal(recipe.module, '@deepseek-ai/dsh-host-frontend-static/lib/index.js');
assert.equal(recipe.dshVersion, registry.dshVersion);
const registration = registry.active.find((entry) => entry.asset === 'frontend-static-cache-patch.json');
assert.deepEqual(registration?.markers, ['DSHA_STATIC_CACHE_V4']);
const file = await moduleFile();
const original = fs.readFileSync(file, 'utf8');
if (recipe.sourceSha256)
  assert.equal(createHash('sha256').update(fs.readFileSync(file)).digest('hex'), recipe.sourceSha256, '上游源码与配方绑定不一致');

// 与 ManagedPatchChain / ExactTextPatch 相同的判据：每条 before 必须唯一命中。
function replaceAllExact(source, patches) {
  for (const { before, after } of patches) {
    assert.equal(source.split(before).length, 2, '补丁锚点必须唯一命中：' + before.slice(0, 60));
    source = source.replace(before, after);
  }
  return source;
}
const patched = replaceAllExact(original, recipe.patches);
const predecessor = replaceAllExact(original, recipe.previousPatches);
assert.ok(predecessor.includes('DSHA_STATIC_CACHE_V3'));
assert.ok(!predecessor.includes(registration.markers[0]));
const restored = replaceAllExact(predecessor, [...recipe.previousPatches].reverse().map(
    ({ before, after }) => ({ before: after, after: before })));
assert.equal(restored, original);
assert.equal(replaceAllExact(restored, recipe.patches), patched);
assert.ok(patched.includes(registration.markers[0]), '补丁必须留下可跳过的标记');

// 真实模块的依赖与导出都在 vm 里替换掉：这里量的是 serveStatic 的响应行为。
const reads = [];
const opens = [];
let hashes = 0;
let nowOffset = 0;
function load(source) {
  const context = {
    createHash: (algorithm) => { hashes++; return createHash(algorithm); },
    readFile: async (target, encoding) => { reads.push(target); return fs.promises.readFile(target, encoding); },
    open: async (target, mode) => {
      opens.push(target);
      const file = await fs.promises.open(target, mode);
      return {
        stat: (options) => file.stat(options),
        readFile: async () => { reads.push(target); return file.readFile(); },
        close: () => file.close(),
      };
    },
    Date: { now: () => Date.now() + nowOffset },
    dirname: path.dirname, extname: path.extname, join: path.join,
    normalize: path.normalize, resolve: path.resolve, sep: path.sep,
    z: { object: (shape) => ({ shape }), string: () => ({ required: () => ({}) }) },
    URL, Response, Headers, ReadableStream, Object, Promise, Buffer, Set,
  };
  const stripped = source
      .replace(/^import [^\n]*\n/gm, '')
      .replace(/^export \{[^}]*\};\s*$/m, '');
  assert.ok(!/^import /m.test(stripped), '模块导入必须已被替换，避免依赖真实运行时');
  const metrics = source === patched ? ', validators: dshaStaticValidators' : '';
  return vm.runInNewContext(`${stripped}\n({ serveStatic, apply${metrics} });`, context, { filename: file });
}

const before = load(original);
const after = load(patched);
assert.equal(typeof after.serveStatic, 'function');

const dist = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-frontend-static-'));
fs.writeFileSync(path.join(dist, 'index.html'), '<html><head></head><body>index</body></html>');
fs.mkdirSync(path.join(dist, 'assets'));
const assetPath = '/assets/index-BPHePDI_.js';
const asset = path.join(dist, assetPath);
const content = 'console.log("shell");\n';
fs.writeFileSync(asset, content);
// Round to seconds so exact restoration is representable on Windows and Linux.
const originalMtime = new Date('2025-01-15T00:00:00Z');
fs.utimesSync(asset, originalMtime, originalMtime);
fs.writeFileSync(path.join(dist, 'assets/blob.bin'), 'not-a-known-extension\n');
const distIndex = path.join(dist, 'index.html');
const rootDir = path.resolve(dist);
let renders = 0;
const renderIndex = (html) => { renders++; return html; };
let refusal;
const authorize = () => refusal;
const call = (module, pathname, headers = {}, method = 'GET') =>
  module.serveStatic(new Request('http://fixture' + pathname, { headers, method }), rootDir, distIndex, authorize, renderIndex);

try {
  // 1) 上游原样：索引不带任何缓存头，候选列表不命中。
  const rawIndex = await call(before, '/');
  assert.equal(rawIndex.headers.get('cache-control'), null);
  const rawAsset = await call(before, '/assets/index-BPHePDI_.js');
  const tag = rawAsset.headers.get('etag');
  assert.match(tag, /^W\/"/);
  assert.equal((await call(before, '/assets/index-BPHePDI_.js', { 'if-none-match': `"stale", ${tag}` })).status, 200);

  // 2) 渲染索引永不缓存，也不走 304：缓存住它等于冻住内联的 DSHA 启动脚本。
  const rendersBefore = renders;
  const index = await call(after, '/');
  assert.equal(index.status, 200);
  assert.equal(index.headers.get('cache-control'), 'no-store');
  assert.equal(index.headers.get('etag'), null);
  assert.equal(renders, rendersBefore + 1, '索引仍必须每次重新渲染');
  assert.equal((await call(after, '/', { 'if-none-match': '*' })).status, 200);
  assert.equal((await call(after, '/index.html')).headers.get('cache-control'), 'no-store', '显式 index 路径同样是索引');

  // 3) 模拟文件稳定后，资产标签沿用上游语义；304 和 HEAD 都跳过读取和哈希。
  nowOffset = 2000;
  const first = await call(after, assetPath);
  assert.equal(first.status, 200);
  assert.equal(first.headers.get('cache-control'), 'no-cache');
  assert.equal(first.headers.get('etag'), tag, '标签算法沿用上游');
  assert.equal(await first.text(), content);
  const warmReads = reads.length;
  const warmHashes = hashes;
  for (const value of [tag, '*', ` * `, `"stale", ${tag}`, `W/"other" , ${tag}`, `  ${tag}  `, tag.slice(2)]) {
    const hit = await call(after, assetPath, { 'if-none-match': value });
    assert.equal(hit.status, 304, '候选列表里的命中必须复用：' + value);
    assert.equal(hit.body, null, '304 不得携带响应体');
    assert.equal(hit.headers.get('etag'), tag);
  }
  const validHead = await call(after, assetPath, { 'if-none-match': tag }, 'HEAD');
  assert.equal(validHead.status, 304);
  const head = await call(after, assetPath, { 'if-none-match': '"stale"' }, 'HEAD');
  assert.equal(head.status, 200);
  assert.equal(head.body, null);
  assert.equal(head.headers.get('etag'), tag);
  assert.equal(head.headers.get('content-type'), first.headers.get('content-type'));
  assert.equal(reads.length, warmReads, 'warm 304/HEAD do not read asset bytes');
  assert.equal(hashes, warmHashes, 'warm 304/HEAD do not hash asset bytes');
  for (const headers of [{}, { 'if-none-match': 'W/"nope"' }, { 'if-none-match': '"a", "b"' }]) {
    const miss = await call(after, assetPath, headers);
    assert.equal(miss.status, 200, '未命中必须回 200');
    assert.equal(await miss.text(), content);
  }

  // 4) A same-size in-place rewrite with identical mtime must miss on ctime alone.
  const replacement = 'console.log("fresh");\n';
  const oldIdentity = fs.statSync(asset, { bigint: true });
  fs.writeFileSync(asset, replacement);
  fs.utimesSync(asset, originalMtime, originalMtime);
  const changed = fs.statSync(asset, { bigint: true });
  assert.equal(changed.size, oldIdentity.size);
  assert.equal(changed.ino, oldIdentity.ino);
  assert.equal(changed.mtimeNs, oldIdentity.mtimeNs);
  assert.notEqual(changed.ctimeNs, oldIdentity.ctimeNs);
  const rebuilt = await call(after, assetPath, { 'if-none-match': tag });
  assert.equal(rebuilt.status, 200);
  const changedTag = rebuilt.headers.get('etag');
  assert.notEqual(changedTag, tag);
  assert.equal(await rebuilt.text(), replacement);

  // Atomic rollback to earlier bytes revalidates the opened replacement inode.
  const rollback = asset + '.rollback';
  fs.writeFileSync(rollback, content);
  fs.utimesSync(rollback, originalMtime, originalMtime);
  fs.renameSync(rollback, asset);
  const reverted = await call(after, assetPath, { 'if-none-match': changedTag });
  assert.equal(reverted.status, 200);
  assert.equal(reverted.headers.get('etag'), tag);
  assert.equal(await reverted.text(), content);
  fs.unlinkSync(asset);
  assert.equal((await call(after, assetPath, { 'if-none-match': tag })).status, 404);

  // 5) 原有安全与缺失语义不变。
  assert.equal((await call(after, '/..%2f..%2fetc/passwd')).status, 403, '路径逃逸仍然 403');
  assert.equal((await call(after, '/assets/does-not-exist.js')).status, 404);
  assert.equal((await call(after, '/assets')).status, 404, '目录目标仍然 404');
  assert.equal((await call(after, '/assets', { 'if-none-match': '*' })).status, 404, '条件目录请求不得误回 304');
  assert.equal((await call(after, '/assets/does-not-exist.js', { 'if-none-match': '*' })).status, 404);
  const unknown = await call(after, '/assets/blob.bin');
  assert.equal(unknown.headers.get('content-type'), 'application/octet-stream');
  assert.equal(unknown.headers.get('cache-control'), 'no-cache');

  // 6) 鉴权仍然先于任何字节：拒绝时不得读索引，返回鉴权给出的响应。
  refusal = new Response(null, { status: 401 });
  const readsBefore = reads.length;
  const opensBefore = opens.length;
  assert.equal((await call(after, '/')).status, 401);
  assert.equal(reads.length, readsBefore, '未授权的索引不得读文件');
  assert.equal(opens.length, opensBefore, '未授权的索引不得打开文件');

  // 7) Recently changed files have no fast path; stable entries have an LRU bound.
  const recent = '/assets/recent.txt';
  fs.writeFileSync(path.join(dist, recent), 'recent');
  nowOffset = -2000;
  const recentTag = (await call(after, recent)).headers.get('etag');
  const recentReads = reads.length;
  assert.equal((await call(after, recent, { 'if-none-match': recentTag })).status, 304);
  assert.equal(reads.length, recentReads + 1, 'recent writes revalidate the bytes');
  assert.ok(!after.validators.has(path.join(dist, recent)));
  nowOffset = 2000;
  for (let i = 0; i < 193; i++) {
    const url = `/assets/fill-${i}.txt`;
    fs.writeFileSync(path.join(dist, url), 'fill');
    assert.equal((await call(after, url, {}, 'HEAD')).status, 200);
  }
  assert.equal(after.validators.size, 192);
  assert.ok(!after.validators.has(path.join(dist, 'assets/fill-0.txt')));
  for (const value of after.validators.values()) assert.deepEqual(Object.keys(value).sort(), ['etag', 'identity']);
} finally {
  fs.rmSync(dist, { recursive: true, force: true });
}
console.log('frontend static cache: ok');

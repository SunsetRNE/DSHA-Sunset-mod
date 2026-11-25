// Verify the actual DSH file-observation guard with its native executor and filesystem tools.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const raw = testRuntime('raw'), modules = path.join(raw, 'node_modules');
const load = name => import(pathToFileURL(path.join(modules, name)));
const { Context } = await load('@deepseek-ai/cordis/lib/index.js');
const { ToolsService } = await load('@deepseek-ai/dsh-tools/lib/index.js');
const native = await load('@deepseek-ai/dsh-tool-executor-native/lib/index.js');
const { LocalFileSystem } = await load('@deepseek-ai/dsh-fs-local/lib/index.js');
const policy = await load('@deepseek-ai/dsh-fs-observation-policy/lib/index.js');
const fsTools = await load('@deepseek-ai/dsh-tool-fs/lib/index.js');
const editor = await load('@deepseek-ai/dsh-tool-str-replace-editor/lib/index.js');

fs.mkdirSync(path.join(root, 'app/build'), { recursive: true });
const directory = fs.mkdtempSync(path.join(root, 'app/build/fs-observation-policy-'));
const main = path.join(directory, 'repo/app/src/X.java');
const tree = path.join(directory, 'repo/.agents/worktrees/fix/a/app/src/X.java');
let calls = 0;

const settle = () => new Promise(resolve => setTimeout(resolve, 0));
async function harness() {
  const ctx = new Context();
  ctx.provide('systemPrompt', { tools() {}, context() {}, getContextOrder() { return 10; }, getSectionOrder() { return 10; }, section() {} });
  // 工作目录服务只需给出临时目录；真实服务的会话 cwd 管理与本插件无关。
  ctx.provide('workingDirectory', { get: () => directory, async ensure() { return directory; } });
  // 0.2.2：执行在 ToolExecutionPipeline，由原生执行器插件挂到 tools 上。
  new ToolsService(ctx);
  ctx.plugin(native);
  new LocalFileSystem(ctx, { cwd: directory, diffBasisMaxBytes: 1048576 });
  policy.apply(ctx);
  fsTools.apply(ctx, { read: true, write: true, edit: true, readImage: false, readLimit: 2000, readMaxLineLength: 2000, readMaxBytes: 1048576, readStreamMinSize: 1048576 });
  editor.apply(ctx, {});
  const session = () => ({ header: { cwd: directory }, seq: 1, eventAt() { return { type: 'turn/start', data: {} }; }, append() {} });
  const own = session();
  const call = (name, args, s = own) => ctx.tools.execute({ calls: [{ type: 'tool-call', id: 'file-observation-' + calls++, name, arguments: JSON.stringify(args) }], agent: { ctx, session: s }, signal: new AbortController().signal }).then(batch => batch.results[0].result);
  await settle();
  return { ctx, call };
}
const unobserved = `Error: cannot modify "${tree}": file has not been read — read the file, then retry`;
const operations = [
  ['edit', { file_path: tree, old_string: 'hello', new_string: 'world' }, unobserved],
  ['write', { file_path: tree, content: 'world\n' }, unobserved],
  ['str_replace_editor', { command: 'str_replace', path: tree, old_str: 'hello', new_str: 'world' },
    `Error: edit requires reading "${tree}" first`],
];

try {
  for (const [name, args, errorText] of operations) {
    for (const file of [main, tree]) {
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, 'hello\n');
    }
    const h = await harness();
    try {
      const observed = await h.call('read', { file_path: main });
      assert.notEqual(observed.isError, true, name);
      const denied = await h.call(name, args);
      assert.equal(denied.isError, true, name);
      assert.equal(denied.error.info?.code, 'FS_NOT_OBSERVED', name);
      assert.equal(denied.content?.[0]?.text, errorText, name);
      assert.equal(fs.readFileSync(tree, 'utf8'), 'hello\n', name);
      assert.equal(fs.readFileSync(main, 'utf8'), 'hello\n', name);
      console.log(`ok - ${name}: unread target rejected with FS_NOT_OBSERVED and original bytes intact`);
      const exact = await h.call('read', { file_path: tree });
      assert.notEqual(exact.isError, true, name);
      const allowed = await h.call(name, args);
      assert.notEqual(allowed.isError, true, JSON.stringify(allowed));
      assert.equal(fs.readFileSync(tree, 'utf8'), 'world\n', name);
      assert.equal(fs.readFileSync(main, 'utf8'), 'hello\n', name);
      console.log(`ok - ${name}: exact read enables the change`);
    } finally {
      await h.ctx.fiber.dispose();
    }
  }
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}

// S3a 夹具：导入固定归档中的真实 Node 锁实现；只写锁文件。
import { pathToFileURL } from 'node:url';
import { createInterface } from 'node:readline';
const [moduleFile, manifest, timeout = '2000', announceWait = 'false'] = process.argv.slice(2);
const { withFileLock } = await import(pathToFileURL(moduleFile));
const abort = new AbortController();
if (announceWait === 'true') {
  const original = abort.signal.throwIfAborted.bind(abort.signal);
  let checks = 0;
  // 标准 signal seam：真实锁实现第二轮取锁前必经此检查，不改写锁算法。
  abort.signal.throwIfAborted = () => {
    if (++checks === 2) console.log('CONTENDED');
    original();
  };
}
let release;
const wait = new Promise(resolve => { release = resolve; });
const input = createInterface({ input: process.stdin });
input.on('line', value => {
  if (value === 'CANCEL') abort.abort(new Error('CANCELLED'));
  else if (value === 'RELEASE') release();
});
input.on('close', () => { abort.abort(new Error('PARENT_EXITED')); release(); });
try {
  await withFileLock(manifest, async () => {
    console.log('HELD');
    await wait;
  }, { waitMs: Number(timeout), signal: abort.signal });
  console.log('RELEASED');
} catch (error) {
  if (abort.signal.aborted) { console.log('CANCELLED'); process.exitCode = 2; }
  else if (String(error).includes('timed out')) { console.log('TIMEOUT'); process.exitCode = 3; }
  else { console.error(error); process.exitCode = 4; }
} finally {
  input.close();
  process.stdin.destroy();
}

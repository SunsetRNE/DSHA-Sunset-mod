import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { createConnection, createServer } from 'node:net'
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { tmpdir } from 'node:os'

const root = new URL('../', import.meta.url).pathname
const proxy = join(root, 'app/src/main/assets/gh-proxy.mjs')
const wrapper = join(root, 'app/src/main/assets/gh-wrapper.sh')

const startProxy = async (stateDir, requestedPort = 0) => {
  const child = spawn(process.execPath, [proxy, '--port', String(requestedPort), '--state-dir', stateDir], {
    stdio: ['ignore', 'pipe', 'pipe']
  })
  const output = []
  child.stdout.on('data', data => output.push(data.toString()))
  child.stderr.on('data', data => output.push(data.toString()))
  const portFile = join(stateDir, 'port')
  for (let i = 0; i < 100; i++) {
    try {
      const port = Number((await readFile(portFile, 'utf8')).trim())
      if (port) return { child, port, output }
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  child.kill()
  throw new Error(output.join(''))
}

const request = (port, text) => new Promise((resolve, reject) => {
  const socket = createConnection({ host: '127.0.0.1', port })
  let body = ''
  socket.setTimeout(1000, () => { socket.destroy(); reject(new Error('timeout')) })
  socket.on('error', reject)
  socket.on('data', data => { body += data.toString('latin1') })
  socket.on('end', () => resolve(body))
  socket.on('connect', () => socket.end(text))
})

test('proxy rejects non-GitHub targets before opening an upstream socket', async () => {
  const state = await mkdtemp(join(tmpdir(), 'dsha-gh-proxy-'))
  const { child, port } = await startProxy(state)
  try {
    const response = await request(port, 'CONNECT example.com:443 HTTP/1.1\r\n\r\n')
    assert.match(response, /^HTTP\/1\.1 403 Forbidden/)
  } finally {
    child.kill()
    await rm(state, { recursive: true, force: true })
  }
})

test('proxy rejects non-TLS GitHub ports', async () => {
  const state = await mkdtemp(join(tmpdir(), 'dsha-gh-proxy-'))
  const { child, port } = await startProxy(state)
  try {
    const response = await request(port, 'CONNECT api.github.com:80 HTTP/1.1\r\n\r\n')
    assert.match(response, /^HTTP\/1\.1 403 Forbidden/)
  } finally {
    child.kill()
    await rm(state, { recursive: true, force: true })
  }
})

test('proxy falls back to an ephemeral port when the requested port is occupied', async () => {
  const blocker = createServer().listen(0, '127.0.0.1')
  await new Promise(resolve => blocker.once('listening', resolve))
  const blockedPort = blocker.address().port
  const state = await mkdtemp(join(tmpdir(), 'dsha-gh-proxy-'))
  const { child, port } = await startProxy(state, blockedPort)
  try {
    assert.notEqual(port, blockedPort)
  } finally {
    child.kill()
    await new Promise(resolve => blocker.close(resolve))
    await rm(state, { recursive: true, force: true })
  }
})

test('wrapper keeps the managed binary and proxy paths explicit', async () => {
  const source = await readFile(wrapper, 'utf8')
  assert.match(source, /DSHA_GH_HOST_ROOT/)
  assert.match(source, /gh-proxy\.mjs/)
  assert.match(source, /DSHA_GH_RAW/)
  assert.match(source, /proroot adapter/)
})

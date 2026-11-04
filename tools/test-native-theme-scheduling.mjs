import assert from 'node:assert/strict';
import {readFileSync, mkdirSync, writeFileSync} from 'node:fs';
import vm from 'node:vm';
import path from 'node:path';

// Production JS with deterministic DOM/event scheduling. No browser pixels, Android or device timing.
const source = readFileSync('app/src/main/assets/web-integration/page.js', 'utf8');
const binding = {nonce:'a'.repeat(32), generation:7, instance:'theme-fixture'};
class DetailEvent extends Event {
  constructor(type, options = {}) { super(type); this.detail = options.detail; }
}
function fixture() {
  const document = new EventTarget(), window = new EventTarget();
  const frames = new Map(), observers = [], messages = [];
  let serial = 0, probes = 0;
  const style = {input:'#123456', color:'rgb(18, 52, 86)', backgroundColor:'rgb(18, 52, 86)', colorScheme:'light'};
  document.hidden = false;
  document.documentElement = {setAttribute() {}};
  document.body = {appendChild() { probes++; }};
  document.head = {};
  document.querySelector = () => null;
  document.createElement = () => ({style:{}, remove() {}});
  window.top = window;
  window.DshaTheme = {postMessage:text => messages.push(JSON.parse(text))};
  const context = vm.createContext({window, document, JSON, CustomEvent:DetailEvent,
    getComputedStyle:() => ({...style, getPropertyValue:() => style.input}),
    MutationObserver:class {
      constructor(callback) { this.callback = callback; observers.push(this); }
      observe() { this.active = true; }
      disconnect() { this.active = false; }
    },
    requestAnimationFrame:callback => { frames.set(++serial, callback); return serial; },
    cancelAnimationFrame:id => frames.delete(id),
    clearTimeout() {}, setTimeout() { throw new Error('Theme sampling must not poll'); }
  });
  vm.runInContext(source, context);
  const api = window.__dshaNativePresentation;
  return {api, document, window, style, frames, observers, messages,
    probes:() => probes,
    flush() {
      const queued = [...frames.values()]; frames.clear();
      for (const callback of queued) callback();
      assert.equal(frames.size, 0, 'theme sampling cannot create an animation loop');
    },
    resize() { window.dispatchEvent(new Event('resize')); },
    mutation() { for (const observer of observers) if (observer.active) observer.callback([]); },
    visible(value) { document.hidden = !value; document.dispatchEvent(new Event('visibilitychange')); }
  };
}

const cases = [];
function test(name, run) { run(); cases.push(name); }

test('unbound and initially hidden documents schedule no frame', () => {
  const f = fixture();
  f.resize(); assert.equal(f.frames.size, 0);
  f.visible(false); f.api.install(binding); f.mutation();
  assert.equal(f.frames.size, 0); assert.equal(f.probes(), 0);
  f.visible(true); assert.equal(f.frames.size, 1); f.flush();
  assert.equal(f.messages.length, 1);
});

test('120 unchanged geometry events retain high-rate events without repeated DOM probes', () => {
  const f = fixture(); f.api.install(binding); f.flush();
  for (let i = 0; i < 120; i++) { f.resize(); f.flush(); }
  assert.equal(f.probes(), 1); assert.equal(f.messages.length, 1);
});

test('media layout changes still publish the changed computed theme', () => {
  const f = fixture(); f.api.install(binding); f.flush();
  f.style.input = '#ffffff'; f.style.backgroundColor = 'rgb(255, 255, 255)';
  f.resize(); f.flush();
  assert.equal(f.probes(), 2); assert.equal(f.messages.at(-1).color, 'rgb(255, 255, 255)');
});

test('style mutation forces revalidation even with the same candidate input', () => {
  const f = fixture(); f.api.install(binding); f.flush();
  f.mutation(); f.flush();
  assert.equal(f.probes(), 2); assert.equal(f.messages.length, 1);
});

test('hidden page cancels an outstanding frame and becomes current once visible', () => {
  const f = fixture(); f.api.install(binding); f.flush();
  f.resize(); assert.equal(f.frames.size, 1);
  f.visible(false); assert.equal(f.frames.size, 0);
  f.style.input = '#ffffff'; f.style.backgroundColor = 'rgb(255, 255, 255)';
  for (let i = 0; i < 120; i++) { f.resize(); f.mutation(); }
  assert.equal(f.frames.size, 0); assert.equal(f.probes(), 1);
  f.visible(true); f.flush();
  assert.equal(f.probes(), 2); assert.equal(f.messages.at(-1).color, 'rgb(255, 255, 255)');
});

test('rebinding before the first frame cancels old work and schedules the new identity', () => {
  const f = fixture(); f.api.install(binding);
  const next = {nonce:'b'.repeat(32), generation:8, instance:'replacement'};
  f.api.install(next); assert.equal(f.frames.size, 1); f.flush();
  assert.equal(f.messages.length, 1);
  assert.equal(f.messages[0].nonce, next.nonce); assert.equal(f.messages[0].generation, 8);
  assert.equal(f.observers.filter(observer => observer.active).length, 1);
});

test('current color and color scheme dependencies invalidate resize reuse', () => {
  const f = fixture(); f.style.input = 'currentcolor'; f.api.install(binding); f.flush();
  f.style.color = 'rgb(255, 255, 255)'; f.resize(); f.flush();
  assert.equal(f.probes(), 2);
  f.style.colorScheme = 'dark'; f.resize(); f.flush(); assert.equal(f.probes(), 3);
});

const output = path.resolve(process.env.DSHA_NATIVE_THEME_OUTPUT || 'app/build/native-theme-scheduling-js.json');
assert.ok(output.startsWith(path.resolve('app/build') + path.sep), 'Write evidence only within app/build');
mkdirSync(path.dirname(output), {recursive:true});
writeFileSync(output, JSON.stringify({passed:cases.length, cases,
  scope:'Production native page theme JS; deterministic DOM/event fixture, no browser pixels, device FPS, CPU/GPU or elapsed-time claim'}, null, 2));
console.log('PASS_NATIVE_THEME_SCHEDULING_JS: ' + cases.length + ' cases');

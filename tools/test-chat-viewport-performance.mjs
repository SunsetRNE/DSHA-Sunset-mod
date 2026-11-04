#!/usr/bin/env node
// Actual locked rc5 ChatViewport/ChatReading; generated DOM only, no account or device access.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {testRuntime} from './test-runtime-fixture.mjs';
import {browserFixture} from './rc1-browser-fixture.mjs';

const root = path.resolve(import.meta.dirname, '..');
const runtime = testRuntime('raw');
const readRecipe = name => JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets', name), 'utf8'));
const previous = readRecipe('chat-render-performance-patch.json');
const recipe = readRecipe('chat-viewport-performance-patch.json');
const sha = value => createHash('sha256').update(value).digest('hex');
assert.equal(sha(fs.readFileSync(path.join(runtime, 'node_modules', recipe.module))), recipe.sourceSha256);
const fixture = await browserFixture(runtime);
const results = {};
const order = process.env.DSHA_CHAT_VIEWPORT_PATCHED_FIRST === '1' ? ['patched', 'baseline'] : ['baseline', 'patched'];
try {
  for (const mode of order) {
    await fixture.load('dsh-client-ui-chat', [previous, ...(mode === 'patched' ? [recipe] : [])],
      'module.exports.auditViewport = {ChatViewport, ChatReading, ScrollFollow};');
    results[mode] = await fixture.page.evaluate(async mode => {
      const {ChatViewport, ChatReading, ScrollFollow} = auditExports['@deepseek-ai/dsh-client-ui-chat'].auditViewport;
      let assertions = 0;
      const check = (condition, label) => {assertions++; if (!condition) throw Error(mode + ': ' + label);};
      const median = values => [...values].sort((a,b) => a-b)[Math.floor(values.length/2)];
      const frame = () => new Promise(resolve => requestAnimationFrame(resolve));
      const original = (list, key, identity = 'position') => {
        let nodePart = null;
        for (const row of list.querySelectorAll('[data-chat-anchor-key]:not([hidden]):not([hidden] *)')) {
          if (row.dataset.chatAnchorKey === key || identity === 'node' && row.dataset.chatNodeKey === key) return row;
          if (nodePart === null && row.dataset.chatNodeKey === key) nodePart = row;
        }
        return nodePart;
      };
      const host = document.createElement('div');
      host.dataset.conversationScroll = '';
      host.style.cssText = 'height:720px;width:320px;overflow:auto;position:relative;overflow-anchor:none';
      const column = document.createElement('div'); host.append(column); document.body.append(host);
      const viewport = new ChatViewport(); viewport.attach(host,column);
      const keys = ['plain','a"b','a\\b','a\nb','a\rb','a\fb','a\tb','0start','-0','-', '', '\0', '\ufffd', '中文💬', 'x] :not(*) [y=', 'quote\'colon:comma,', '\ud800', '\udfff'];
      const make = (key, node = key) => {
        const row = document.createElement('div');
        row.dataset.chatAnchorKey = key; row.dataset.chatNodeKey = node; row.dataset.chatFlowKey = key;
        row.textContent = 'complete ' + key; row.style.cssText = 'height:36px';
        return row;
      };
      const same = (key, identity) => check(viewport.anchor(key,identity) === original(host,key,identity), 'live selection equality ' + JSON.stringify([key,identity]));
      for (const key of keys) {
        const firstPart = make(key + ':part', key); column.append(firstPart);
        const hidden = make(key); hidden.hidden = true; column.append(hidden);
        const hiddenParent = document.createElement('div'); hiddenParent.hidden = true; hiddenParent.append(make(key)); column.append(hiddenParent);
        const exact = make(key); column.append(exact);
        same(key,'position');same(key,'node');same(key,undefined);same(key,'other');
      }
      for (const key of [null,undefined,1,{},false]) {same(key,'position');same(key,'node');}
      // Same-task mutations must be visible: no MutationObserver delivery or frame is needed.
      for (let step=0;step<80;step++) {
        const key=keys[step%keys.length];
        const selected=viewport.anchor(key);
        if (selected) selected.hidden=!selected.hidden;
        const replacement=make(key, key); column.prepend(replacement);
        same(key,'position');same(key,'node');
        replacement.remove(); same(key,'position');same(key,'node');
      }
      const escape = CSS.escape; CSS.escape = undefined;
      try {for (const key of keys) {same(key,'position');same(key,'node');}}
      finally {CSS.escape=escape;}
      column.replaceChildren();
      const timings=[];
      for (const count of [400,2000,5000]) {
        const fragment=document.createDocumentFragment();
        for(let index=0;index<count;index++) {const row=make('node-'+index);row.dataset.chatTurn=index;fragment.append(row);}
        column.replaceChildren(fragment);
        viewport.updateTurns(Array.from({length:count},(_,turn)=>({turn,anchorKey:'node-'+turn})));
        const queryKeys=['node-0','node-'+Math.floor(count/2),'node-'+(count-1),'absent'];
        let allAnchorLists=0; let returnedAnchorRows=0;
        const queryAll=host.querySelectorAll.bind(host);
        host.querySelectorAll=selector=>{const result=queryAll(selector);if(selector==='[data-chat-anchor-key]:not([hidden]):not([hidden] *)'){allAnchorLists++;returnedAnchorRows+=result.length;}return result;};
        for(const key of queryKeys) viewport.anchor(key);
        const sampleCounts={allAnchorLists,returnedAnchorRows};
        delete host.querySelectorAll;
        const samples=[];
        for(let repeat=0;repeat<5;repeat++) {
          const start=performance.now();
          for(let i=0;i<200;i++) viewport.anchor(queryKeys[i%queryKeys.length]);
          samples.push(performance.now()-start);
        }
        // Real restore/navigation operations also preserve exact physical landings.
        const landing=viewport.scrollToTurn(Math.floor(count/2));
        check(landing?.turn===Math.floor(count/2),'navigation resolves loaded turn');
        check(Math.abs(viewport.anchor('node-'+Math.floor(count/2)).getBoundingClientRect().top-host.getBoundingClientRect().top-24)<1,'navigation offset exact');
        const position=viewport.capturePosition();
        check(position!==null,'semantic position captured');
        viewport.beginPreserving(position);
        const anchor=viewport.anchor(position.anchorKey);const before=anchor.getBoundingClientRect().top;
        column.prepend(make('older'));
        viewport.preserve();
        check(Math.abs(anchor.getBoundingClientRect().top-before)<1,'prepend anchor preserved');
        viewport.stopPreserving();
        host.scrollTop=0; viewport.restore(position);
        check(Math.abs(anchor.getBoundingClientRect().top-host.getBoundingClientRect().top-position.anchorTop)<1,'saved position restored after prepend');
        const navigation=[];
        for(let repeat=0;repeat<5;repeat++) {
          const start=performance.now();
          for(let i=0;i<40;i++) viewport.scrollToTurn(i%2 ? 5 : count-5);
          navigation.push(performance.now()-start);
        }
        timings.push({count,callsPerSample:200,samples,medianMs:median(samples),...sampleCounts,navigationCallsPerSample:40,navigationSamples:navigation,navigationMedianMs:median(navigation)});
      }
      // Full ordinary-scroll wiring remains unchanged. Preserve real 500 ms sampling and final flush.
      let saved=null, stateChanges=0, scrollEvents=0, captures=0, visibleReads=0;
      const state={initialized:true,followingTail:false,activeTurn:0};
      const reading=new ChatReading(viewport,{read:()=>saved,save:value=>{saved=value;}},state,()=>stateChanges++,new ScrollFollow(false,48));
      const oldCapture=viewport.capturePosition.bind(viewport), oldReadVisible=viewport.readVisibleTurn.bind(viewport);
      viewport.capturePosition=()=>{captures++;return oldCapture();};
      viewport.readVisibleTurn=(...args)=>{visibleReads++;return oldReadVisible(...args);};
      viewport.connect({scroll:event=>{scrollEvents++;reading.onScroll(event);},scrollEnd:outer=>reading.onScrollEnd(outer),resize(){},interact(){}});
      const scrollStarted=performance.now();
      for(let i=0;i<90;i++) {host.scrollTop=18000+i*18; await frame();}
      host.dispatchEvent(new Event('scrollend'));
      check(saved!==null,'scroll end persists semantic position');
      check(scrollEvents>10,'actual native scroll events delivered');
      check(captures<=scrollEvents,'programmatic scroll/end emits no duplicate capture');
      // Programmatic per-frame writes can emit scrollend every frame; test the timer's
      // continuous-event coalescing separately instead of treating these as a touch fling.
      const captureBefore=captures;
      for(let i=0;i<100;i++) reading.onScroll({metrics:viewport.metrics(),movedByReader:true});
      check(captures===captureBefore && reading.pending,'continuous synchronous scroll events queue one pending sample');
      reading.onScrollEnd();check(captures===captureBefore+1 && !reading.pending,'scroll end flushes the queued sample once');
      const scroll={durationMs:performance.now()-scrollStarted,scrollEvents,captures,visibleReads,stateChanges,finalSaved: saved};
      reading.dispose();viewport.detach();
      check(viewport.anchor('node-1')===null,'detached viewport holds no node');
      const replacementHost=document.createElement('div');const replacement=make('node-1');replacementHost.append(replacement);document.body.append(replacementHost);
      viewport.attach(replacementHost,replacementHost);check(viewport.anchor('node-1')===replacement,'reattach uses current list');viewport.detach();replacementHost.remove();host.remove();
      return {assertions,timings,scroll};
    },mode);
  }
  for(const item of results.baseline.timings) {assert.equal(item.allAnchorLists,4);assert.equal(item.returnedAnchorRows,item.count*4);}
  for(const item of results.patched.timings) {assert.equal(item.allAnchorLists,0);assert.equal(item.returnedAnchorRows,0);}
  assert.deepEqual(fixture.errors,[]);
  const report={status:'PASS',runtime,engine:await fixture.browser.version(),order,
    inputSha256:{source:recipe.sourceSha256,recipe:sha(JSON.stringify(recipe)),test:sha(fs.readFileSync(import.meta.filename))},
    scope:'Desktop browser, actual locked rc5 viewport/reading with generated DOM. Anchor and navigation timings only; programmatic scrolling is a correctness control, not a touch-fling or device CPU/GPU improvement claim.',...results};
  if(process.env.DSHA_CHAT_VIEWPORT_OUTPUT) {
    const output=path.resolve(process.env.DSHA_CHAT_VIEWPORT_OUTPUT);
    fs.mkdirSync(path.dirname(output),{recursive:true});
    fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
  }
  console.log(JSON.stringify(report,null,2));
} finally {await fixture.close();}

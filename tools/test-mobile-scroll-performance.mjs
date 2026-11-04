// Chromium runs the actual fixed mobile modules. Native element scrolling is
// measured separately from DOM/lazy-row mutations; this is not an Android GPU test.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {stripTypeScriptTypes} from 'node:module';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {applyMobileClientPatches, upstreamClientHash} from './apply-mobile-client-patches.mjs';
import {mobileScrollSourceFiles, applyMobileScrollSourcePatch, restoreMobileScrollSource, restoreMobileScrollBundle} from './mobile-scroll-patches.mjs';

const root = 'app/src/main/assets/builtin-plugins/dsh-web-mobile';
const upstream = fs.readFileSync('tools/mobile-plugin-upstream/lib/client.js');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
assert.equal(sha(upstream), upstreamClientHash);
const candidate = applyMobileClientPatches(upstream);
const baseline = restoreMobileScrollBundle(candidate);
const lock = JSON.parse(fs.readFileSync('tools/mobile-plugin-upstream.lock.json', 'utf8'));
const evidence = process.env.DSHA_MOBILE_SCROLL_EVIDENCE;
if (evidence) fs.mkdirSync(evidence, {recursive:true});
for (const name of mobileScrollSourceFiles) {
  const output = fs.readFileSync(`${root}/${name}`, 'utf8');
  const pristine = restoreMobileScrollSource(name, output);
  assert.equal(sha(pristine), lock.files[name].sha256, name);
  assert.equal(applyMobileScrollSourcePatch(name, pristine), output, name);
  assert.throws(() => applyMobileScrollSourcePatch(name, 'changed upstream'), /MOBILE_SCROLL_ANCHOR/);
}
const sourceRuntime = () => mobileScrollSourceFiles.map(name => stripTypeScriptTypes(fs.readFileSync(`${root}/${name}`, 'utf8'))
  .replace(/^import .* from .*\n/gm, '').replaceAll('export function', 'function')).join('\n');
const results = [];
async function run(label, bundle, fixed, useSource = false) {
  const fixture = await browserFixture();
  const {page} = fixture;
  const result = {label};
  try {
    await page.evaluate(() => {window.__ModuleLoader__={load({factory}) {factory(() => ({}));}};});
    await page.addScriptTag({content:bundle.replace('var __cache = {};', 'window.mobileRequire=__localRequire; var __cache = {};')});
    await page.evaluate(() => {
      document.getElementById('root').innerHTML='<main data-mobile-nav="frame" id="frame"><div id="history" data-chat-flow style="height:480px;overflow:auto"><div id="rows"></div></div><div data-composer-card id="card" style="position:fixed;left:16px;bottom:0;width:328px;height:60px"><button class="_7KE1Ra_trigger" id="trigger" style="position:absolute;left:220px;top:0;width:30px;height:30px">Model</button><div id="editor" data-composer-input contenteditable="true">Draft remains</div></div></main>';
      const rows=document.getElementById('rows'), fragment=document.createDocumentFragment();
      for(let i=0;i<1200;i++) {const article=document.createElement('article');article.style.height='60px';article.innerHTML='<p>History '+i+'</p><span>Tool result</span>';fragment.append(article);}
      rows.append(fragment);
      window.cleanup=[];window.current='a';window.subscribers=[];
      window.ctx={effect(run){const d=run();if(d)cleanup.push(d);},sessions:{list:{getSnapshot:()=>({current}),subscribe(cb){subscribers.push(cb);return ()=>subscribers.splice(subscribers.indexOf(cb),1);}}}};
      window.metrics={raf:0,documentQueries:0,rectReads:0,scrollEvents:0};
      window.nativeRaf=window.requestAnimationFrame.bind(window);
      window.tick=()=>new Promise(resolve=>nativeRaf(()=>nativeRaf(resolve)));
      window.requestAnimationFrame=cb=>{metrics.raf++;return nativeRaf(cb);};
      const query=Document.prototype.querySelector,queries=Document.prototype.querySelectorAll,rect=Element.prototype.getBoundingClientRect;
      Document.prototype.querySelector=function(...args){metrics.documentQueries++;return query.apply(this,args);};
      Document.prototype.querySelectorAll=function(...args){metrics.documentQueries++;return queries.apply(this,args);};
      Element.prototype.getBoundingClientRect=function(...args){metrics.rectReads++;return rect.apply(this,args);};
      document.getElementById('history').addEventListener('scroll',()=>metrics.scrollEvents++);
      window.resetMetrics=()=>{for(const key of Object.keys(metrics))metrics[key]=0;};
    });
    if(useSource) await page.addScriptTag({content:`const {installMobileEffect}=mobileRequire('./platform/breakpoints.js');const {currentSessionIdOf}=mobileRequire('./core/sessions-compat.js');const {SHADOW_MARKER}=mobileRequire('./effects/composer-keyboard-guard.js');\n${sourceRuntime()}\nwindow.installModel=installModelMenuAnchor;window.installFocus=installSessionFocusGuard;`});
    else await page.evaluate(()=>{window.installModel=mobileRequire('./effects/model-menu-anchor.js').installModelMenuAnchor;window.installFocus=mobileRequire('./effects/session-focus-guard.js').installSessionFocusGuard;});
    await page.evaluate(async()=>{installModel(ctx);installFocus(ctx);await tick();resetMetrics();});
    result.closedMenuScroll=await page.evaluate(async()=>{
      const history=document.getElementById('history');
      for(let frame=0;frame<60;frame++){history.scrollTop=100+frame*200;await tick();}
      return {...metrics,messages:1200,frames:60};
    });
    assert.ok(result.closedMenuScroll.scrollEvents>=55, 'real element scroll events must be delivered');
    assert.equal(result.closedMenuScroll.rectReads,0);
    assert.equal(result.closedMenuScroll.documentQueries,0);
    if(fixed)assert.equal(result.closedMenuScroll.raf,0);
    else assert.ok(result.closedMenuScroll.raf>=55);

    // 3.2.3 upstream already disconnects input observation outside the guard
    // window. Local routing must also stay quiet while its late-panel backstop
    // watches childList, and avoid scans inside an active focus window.
    result.historyChanges=await page.evaluate(async()=>{
      resetMetrics();
      for(let frame=0;frame<30;frame++) {document.getElementById('rows').lastElementChild.firstElementChild.textContent='Token '+frame;await tick();}
      return {...metrics,bursts:30};
    });
    assert.equal(result.historyChanges.documentQueries,0);
    result.guardedHistoryChanges=await page.evaluate(async()=>{
      const timeout=window.setTimeout;window.setTimeout=()=>0;
      current='guarded';for(const cb of subscribers)cb();resetMetrics();
      for(let frame=0;frame<30;frame++){document.getElementById('rows').lastElementChild.firstElementChild.textContent='Guarded token '+frame;await tick();}
      const captured={...metrics,bursts:30};window.setTimeout=timeout;
      document.getElementById('editor').dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));
      return captured;
    });
    if(fixed)assert.equal(result.guardedHistoryChanges.documentQueries,0);
    else assert.ok(result.guardedHistoryChanges.documentQueries>=30);

    // Isolate same-frame ordering from the 0/60/200ms safety timers: a queued
    // discovery followed by scroll must still discover and place in that frame.
    result.menuDiscovery=await page.evaluate(async()=>{
      const timeout=window.setTimeout;
      window.setTimeout=()=>0;
      document.getElementById('trigger').click();
      const menu=document.createElement('div');menu.id='model-menu';menu.className='_7KE1Ra_menu';menu.style.cssText='position:fixed;left:0px;top:400px;width:100px;height:60px';document.body.append(menu);
      document.getElementById('history').dispatchEvent(new Event('scroll'));
      await tick();window.setTimeout=timeout;
      return {left:menu.style.left};
    });
    assert.equal(result.menuDiscovery.left,fixed?'166px':'0px');
    // A normal follow while open still tracks moved anchors. Clicking refreshes
    // the baseline too so both variants can exercise the ordinary open path.
    await page.evaluate(async()=>{document.getElementById('trigger').click();await new Promise(resolve=>setTimeout(resolve,250));await tick();});
    result.menuReplacement=await page.evaluate(async()=>{
      const timeout=window.setTimeout;window.setTimeout=()=>0;
      document.getElementById('trigger').click();
      const old=document.getElementById('model-menu'),next=old.cloneNode(true);next.style.left='0px';old.replaceWith(next);
      document.getElementById('history').dispatchEvent(new Event('scroll'));await tick();window.setTimeout=timeout;
      return next.style.left;
    });
    assert.equal(result.menuReplacement,fixed?'166px':'0px');
    await page.evaluate(async()=>{document.getElementById('trigger').click();await new Promise(resolve=>setTimeout(resolve,250));await tick();});
    result.openMenuFollow=await page.evaluate(async()=>{
      document.getElementById('trigger').style.left='180px';
      document.getElementById('history').dispatchEvent(new Event('scroll'));await tick();
      return document.getElementById('model-menu').style.left;
    });
    assert.equal(result.openMenuFollow,'126px');
    result.closedAgain=await page.evaluate(async()=>{
      document.getElementById('model-menu').remove();document.getElementById('history').dispatchEvent(new Event('scroll'));await tick();resetMetrics();
      for(let i=0;i<10;i++){document.getElementById('history').scrollTop=500+i*100;await tick();}
      return {...metrics};
    });
    if(fixed)assert.equal(result.closedAgain.raf,0);

    // Preserve session-change timing, shadowing a remounted editor before a
    // passive effect, synchronous focus fallback, and intentional user input.
    result.focusLifecycle=await page.evaluate(async()=>{
      current='b';for(const cb of subscribers)cb();
      const old=document.getElementById('editor'),fresh=old.cloneNode(true);fresh.removeAttribute('data-mobile-nav-focus-shadow');old.replaceWith(fresh);
      await Promise.resolve();fresh.focus();const guarded=document.activeElement!==fresh;
      const shadowed=Object.prototype.hasOwnProperty.call(fresh,'focus');
      fresh.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));fresh.focus();
      const userFocus=document.activeElement===fresh, draft=fresh.textContent;
      fresh.blur();
      document.getElementById('frame').setAttribute('data-mobile-panel-exit','');await Promise.resolve();fresh.focus();
      const panelGuarded=document.activeElement!==fresh;
      document.getElementById('frame').removeAttribute('data-mobile-panel-exit');
      fresh.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));
      await Promise.resolve(); // drain marker removal before testing a later childList-only mount
      const wrapper=document.createElement('section');wrapper.innerHTML='<div data-mobile-nav="frame" data-mobile-panel-exit></div>';document.body.append(wrapper);await Promise.resolve();fresh.focus();
      const latePanelGuarded=document.activeElement!==fresh;wrapper.remove();
      for(const d of cleanup.splice(0))d();
      fresh.focus();const disposedFocus=document.activeElement===fresh;
      return {guarded,shadowed,userFocus,panelGuarded,latePanelGuarded,disposedFocus,draft};
    });
    assert.deepEqual(result.focusLifecycle,{guarded:true,shadowed:true,userFocus:true,panelGuarded:true,latePanelGuarded:fixed,disposedFocus:true,draft:'Draft remains'});
    result.breakpoint=await page.evaluate(async()=>{installModel(ctx);installFocus(ctx);await tick();resetMetrics();});
    await page.setViewportSize({width:1280,height:800});
    await page.evaluate(()=>tick());
    result.breakpoint=await page.evaluate(async()=>{resetMetrics();document.getElementById('history').scrollTop=50;document.getElementById('rows').firstElementChild.textContent='Desktop';await tick();return {...metrics};});
    assert.equal(result.breakpoint.raf,0);
    assert.equal(result.breakpoint.documentQueries,0);
    await page.setViewportSize({width:360,height:800});
    await page.evaluate(async()=>{await tick();current='c';for(const cb of subscribers)cb();});
    assert.equal(await page.evaluate(()=>Object.prototype.hasOwnProperty.call(document.getElementById('editor'),'focus')),true);
    assert.deepEqual(fixture.errors,[]);
    results.push(result);
    if(evidence)fs.writeFileSync(path.join(evidence,`${label}.json`),JSON.stringify(result,null,2)+'\n');
  } finally {await fixture.close();}
}
await run('before-local-scroll-patches-3.2.3',baseline,false);
await run('after-bundle',candidate,true);
await run('after-source',candidate,true,true);
const summary={pass:true,scope:'Chromium actual mobile modules; element scrolling and separate mutation bursts; no device CPU/GPU claim',baselineSha256:sha(baseline),candidateSha256:sha(candidate),results};
if(evidence)fs.writeFileSync(path.join(evidence,'summary.json'),JSON.stringify(summary,null,2)+'\n');
console.log(JSON.stringify(summary,null,2));

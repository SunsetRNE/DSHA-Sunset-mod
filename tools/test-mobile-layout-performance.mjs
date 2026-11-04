// Runs the actual fixed-upstream mobile modules in Chromium. Synthetic host DOM
// isolates observer/query cost; this is not Android frame-rate or IME evidence.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {stripTypeScriptTypes} from 'node:module';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {applyMobileClientPatches, upstreamClientHash} from './apply-mobile-client-patches.mjs';
import {applyMobileLayoutSourcePatch, mobileLayoutSourceFiles, restoreMobileLayoutSource, restoreMobileLayoutBundle} from './mobile-layout-patches.mjs';
import {restoreMobileLiveLocaleSource} from './mobile-locales.mjs';

const root = 'app/src/main/assets/builtin-plugins/dsh-web-mobile';
const upstream = fs.readFileSync('tools/mobile-plugin-upstream/lib/client.js');
assert.equal(createHash('sha256').update(upstream).digest('hex'), upstreamClientHash);
const current = applyMobileClientPatches(upstream);
// Keep the other build294 DSHA patches, locales and file transfer identical in
// the negative control; only the reviewed layout hunks are reversed.
const baseline = restoreMobileLayoutBundle(current);
const evidence = process.env.DSHA_MOBILE_LAYOUT_EVIDENCE;
if (evidence) fs.mkdirSync(evidence, {recursive:true});
const results = [];
const lock = JSON.parse(fs.readFileSync('tools/mobile-plugin-upstream.lock.json', 'utf8'));
for (const name of mobileLayoutSourceFiles) {
  const output = fs.readFileSync(`${root}/${name}`, 'utf8');
  const base = restoreMobileLayoutSource(name, output);
  const pristine = name.endsWith('drawer-quick-settings.ts') ? restoreMobileLiveLocaleSource(name, base) : base;
  assert.equal(createHash('sha256').update(pristine).digest('hex'), lock.files[name].sha256, name);
  assert.equal(applyMobileLayoutSourcePatch(name, base), output, name);
}
const sourceRuntime = name => {
  const code = stripTypeScriptTypes(fs.readFileSync(`${root}/src/client/effects/${name}.ts`, 'utf8'));
  return code.replace(/^import .* from .*\n/gm, '').replace('export function', 'function')
    .replace('function createDrawerQuickSettingsTask(', 'function createSourceDrawerQuickSettingsTask(')
    + `\nwindow.source${name === 'tablet-quick-settings' ? 'Tablet' : 'Drawer'} = ${name === 'tablet-quick-settings' ? 'installTabletQuickSettings' : 'createSourceDrawerQuickSettingsTask'};`;
};

async function run(label, source, fixed, useTypescript = false) {
  const fixture = await browserFixture();
  const {page} = fixture;
  const result = {label};
  try {
    await page.setViewportSize({width:1280,height:800});
    await page.evaluate(() => {window.__ModuleLoader__={load({factory}) {factory(() => ({}));}};});
    await page.addScriptTag({content:source.replace('var __cache = {};', 'window.mobileRequire=__localRequire; var __cache = {};')});
    await page.evaluate(() => {
      window.cleanups=[];window.ensures=0;window.menuClicks=0;window.queries=0;
      const qs=Document.prototype.querySelector,qsa=Document.prototype.querySelectorAll;
      Document.prototype.querySelector=function(...args){queries++;return qs.apply(this,args);};
      Document.prototype.querySelectorAll=function(...args){queries++;return qsa.apply(this,args);};
      document.getElementById('root').innerHTML='<main id="frame"><aside class="host_sidebarCol"><div id="sessions"></div><div class="account_triggerRow"><button aria-haspopup="menu" aria-expanded="false">Account</button></div></aside><section><div data-chat-flow id="history"></div><div class="host_composerStack" id="composer"></div></section><div data-shell-overlay></div></main>';
      const history=document.getElementById('history'),fragment=document.createDocumentFragment();
      for(let i=0;i<1200;i++) {const message=document.createElement('article');message.innerHTML='<div><p>Message '+i+'</p><button>Tool</button><pre>tool result</pre></div>';fragment.append(message);}
      history.append(fragment);
      document.getElementById('sessions').innerHTML=Array.from({length:500},(_,i)=>'<div class="host_sessionRow">Session '+i+'</div>').join('');
      window.drawermod=mobileRequire('./effects/drawer-quick-settings.js');
      window.tabletmod=mobileRequire('./effects/tablet-quick-settings.js');
      const create=drawermod.createDrawerQuickSettingsTask;
      drawermod.createDrawerQuickSettingsTask=ctx=>{const task=create(ctx),ensure=task.ensure;task.ensure=()=>{ensures++;ensure();};return task;};
      window.ctx={effect(run){const dispose=run();if(dispose)cleanups.push(dispose);},locale:{bind(){return key=>key;},subscribe(){return ()=>{};}}};
      window.tick=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    });
    if (useTypescript) {
      // Execute the published TS source independently, using its real shared
      // platform modules, so source/bundle divergence cannot hide behind strings.
      await page.addScriptTag({content:`const {installMobileEffect,TABLET_TOUCH_QUERY}=mobileRequire('./platform/breakpoints.js');const {getFrame}=mobileRequire('./platform/frame.js');const NS='test';\n${sourceRuntime('drawer-quick-settings')}\nconst createDrawerQuickSettingsTask=ctx=>{const task=window.sourceDrawer(ctx),ensure=task.ensure;task.ensure=()=>{ensures++;ensure();};return task;};\n${sourceRuntime('tablet-quick-settings')}`});
    }
    // The shell anchor can arrive in a later commit than the account row.
    // getFrame() cannot resolve until then; that dependency must wake retry.
    await page.evaluate(()=>document.querySelector('[data-shell-overlay]').remove());
    await page.evaluate(useSource=>{(useSource ? sourceTablet : tabletmod.installTabletQuickSettings)(ctx);},useTypescript);
    await page.evaluate(()=>tick());
    assert.equal(await page.locator('[data-mobile-nav="quick-settings"]').count(),0);
    await page.evaluate(async()=>{const overlay=document.createElement('div');overlay.setAttribute('data-shell-overlay','');document.getElementById('frame').append(overlay);await tick();});
    assert.equal(await page.locator('[data-mobile-nav="quick-settings"]').count(),1,'a delayed shell anchor must retry account-row installation');
    result.delayedShellAnchor=true;
    await page.evaluate(()=>tick());
    assert.equal(await page.locator('[data-mobile-nav="quick-settings"]').count(),1);
    result.storm=await page.evaluate(async()=>{
      const before={ensures,queries},start=performance.now();
      for(let i=0;i<30;i++) {
        document.getElementById('history').lastElementChild.firstElementChild.firstElementChild.textContent='Streaming '+i;
        document.getElementById('composer').textContent='TPS '+i;
        document.getElementById('sessions').firstElementChild.textContent='Renamed '+i;
        await tick();
      }
      return {ensures:ensures-before.ensures,documentQueries:queries-before.queries,milliseconds:performance.now()-start,messages:1200,sessions:500,bursts:30};
    });
    if(fixed){assert.equal(result.storm.ensures,0);assert.equal(result.storm.documentQueries,0);}
    else {assert.ok(result.storm.ensures>=30);assert.ok(result.storm.documentQueries>=90);}

    // Parent/whole-sidebar replacement must reattach the same single gear.
    await page.evaluate(async()=>{const old=document.getElementsByClassName('host_sidebarCol')[0];const next=document.createElement('aside');next.className='host_sidebarCol';next.innerHTML='<div class="account_triggerRow"><button aria-haspopup="menu" aria-expanded="false">New account</button></div>';old.replaceWith(next);await tick();});
    assert.equal(await page.locator('.account_triggerRow [data-mobile-nav="quick-settings"]').count(),1);
    result.sidebarReplacement=true;
    // Portal menu, item replacement, connection chip yielding and restoration.
    await page.evaluate(async()=>{const trigger=document.querySelector('.account_triggerRow > button');trigger.setAttribute('aria-expanded','true');const menu=document.createElement('div');menu.setAttribute('role','menu');menu.id='account-menu';menu.innerHTML='<button role="menuitem">Settings</button>';document.body.append(menu);await tick();});
    assert.equal(await page.locator('#account-menu[data-mobile-nav-quick-menu]').count(),1);
    await page.evaluate(async()=>{const chip=document.createElement('div');chip.id='connection';chip.setAttribute('role','status');document.querySelector('.account_triggerRow').append(chip);await tick();});
    assert.equal(await page.locator('#account-menu[data-mobile-nav-quick-menu]').count(),0);
    await page.evaluate(async()=>{document.getElementById('connection').remove();await tick();});
    assert.equal(await page.locator('#account-menu[data-mobile-nav-quick-menu]').count(),1);
    await page.evaluate(async()=>{const next=document.getElementById('account-menu').cloneNode(true);next.removeAttribute('data-mobile-nav-quick-menu');document.getElementById('account-menu').replaceWith(next);await tick();});
    assert.equal(await page.locator('#account-menu[data-mobile-nav-quick-menu]').count(),1);
    result.menuAndConnection=true;
    // Disposing between mutation delivery and rAF used to revive a stale task.
    result.disposeRace=await page.evaluate(async()=>{
      const row=document.querySelector('.account_triggerRow');row.append(document.createElement('span'));
      await Promise.resolve();for(const dispose of cleanups.splice(0))dispose();await tick();
      return {gear:document.querySelectorAll('[data-mobile-nav="quick-settings"]').length,marks:document.querySelectorAll('[data-mobile-nav-quick-menu]').length};
    });
    assert.equal(result.disposeRace.gear,fixed?0:1);
    if(fixed) assert.equal(result.disposeRace.marks,0);

    // Re-arm and dispose an initiated click before its following frame. It must
    // never click a host menu item after ownership ended.
    await page.evaluate(useSource=>{for(const dispose of cleanups.splice(0))dispose();(useSource ? sourceTablet : tabletmod.installTabletQuickSettings)(ctx);},useTypescript);
    await page.evaluate(()=>tick());
    result.clickDisposeRace=await page.evaluate(async()=>{
      document.querySelector('[role="menuitem"]').onclick=()=>menuClicks++;
      document.querySelector('[data-mobile-nav="quick-settings"]').click();
      for(const dispose of cleanups.splice(0))dispose();await tick();
      return {menuClicks,quick:document.documentElement.hasAttribute('data-mobile-nav-quick')};
    });
    assert.equal(result.clickDisposeRace.menuClicks,fixed?0:1);
    if(fixed)assert.equal(result.clickDisposeRace.quick,false);
    if(fixed) {
      await page.evaluate(useSource=>{(useSource ? sourceTablet : tabletmod.installTabletQuickSettings)(ctx);},useTypescript);
      await page.evaluate(()=>tick());
      await page.setViewportSize({width:800,height:800});
      await page.evaluate(()=>tick());
      assert.equal(await page.locator('[data-mobile-nav="quick-settings"]').count(),0);
      await page.evaluate(async()=>{document.querySelector('.account_triggerRow').append(document.createElement('span'));await tick();});
      assert.equal(await page.locator('[data-mobile-nav="quick-settings"]').count(),0);
      await page.setViewportSize({width:1280,height:800});
      await page.evaluate(()=>tick());
      assert.equal(await page.locator('[data-mobile-nav="quick-settings"]').count(),1);
      result.breakpointRearm=true;
      const clicked=await page.evaluate(async()=>{const before=menuClicks;document.querySelector('[data-mobile-nav="quick-settings"]').click();await tick();return menuClicks-before;});
      assert.equal(clicked,1,'an active gear still selects Settings once');
      await page.evaluate(()=>{for(const dispose of cleanups.splice(0))dispose();});
      result.normalClick=true;
    }
    assert.deepEqual(fixture.errors,[]);
    if(evidence)fs.writeFileSync(path.join(evidence,`${label}.json`),JSON.stringify(result,null,2)+'\n');
    results.push(result);
  }finally{await fixture.close();}
}

await run('before', baseline, false);
await run('after-bundle', current, true);
await run('after-source', current, true, true);
// Patch anchors must reject source drift rather than silently producing a mixed build.
for(const name of mobileLayoutSourceFiles)assert.throws(()=>applyMobileLayoutSourcePatch(name,'changed upstream'),/MOBILE_LAYOUT_ANCHOR/);
const summary={pass:true,scope:'Chromium synthetic host; actual mobile source/prebuilt modules',
  baselineSha256:createHash('sha256').update(baseline).digest('hex'),
  candidateSha256:createHash('sha256').update(current).digest('hex'),results};
if(evidence)fs.writeFileSync(path.join(evidence,'summary.json'),JSON.stringify(summary,null,2)+'\n');
console.log(JSON.stringify(summary,null,2));

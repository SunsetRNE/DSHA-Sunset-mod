import assert from 'node:assert/strict';
import fs from 'node:fs';
import {browserFixture} from './rc1-browser-fixture.mjs';
const fixture=await browserFixture();
try {
  const page=fixture.page;
  await page.evaluate(()=>{window.__ModuleLoader__={load:({factory})=>factory(()=>({}))};});
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8').replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')});
  await page.evaluate(()=>{
    window.disposers=[]; mobileRequire('./effects/phone-chrome.js').installPhoneChrome({effect:run=>disposers.push(run())});
    const css=mobileRequire('./styles/misc.css.js'); const style=document.createElement('style'); style.textContent=css.MISC_CSS; document.head.append(style);
    const host=document.getElementById('root'); host.innerHTML='<div data-slot="conversation.input.dock"><div class="dsws-capsule" style="display:flex;width:290px;max-width:90vw;overflow:hidden;gap:6px;padding:3px 7px">'+Array.from({length:11},(_,i)=>'<span class="dsws-seg" style="flex:none;padding:2px 7px">'+(i===9?'<button class="dsws-skillbtn">⊞</button>':i===10?'<button class="dsws-fold-toggle">⌄</button>':'88')+'</span>').join('')+'</div></div>';
    window.writes=0; const root=document.documentElement, original=root.style.setProperty.bind(root.style); root.style.setProperty=(key,value,...rest)=>{if(key.startsWith('--dsha-mobile'))writes++;return original(key,value,...rest);};
  });
  assert.equal(await page.evaluate(()=>matchMedia('(pointer:coarse)').matches),true);
  for (const width of [320,393,800]) {
    await page.setViewportSize({width,height:500});
    await page.waitForFunction(()=>document.documentElement.style.getPropertyValue('--dsha-mobile-visible-vh')==='500px');
    const result=await page.locator('.dsws-capsule').evaluate(el=>{el.scrollLeft=el.scrollWidth;return {overflow:getComputedStyle(el).overflowX,padding:getComputedStyle(el).paddingRight,visible:el.querySelector('.dsws-fold-toggle').getBoundingClientRect().right<=el.getBoundingClientRect().right+1};});
    assert.equal(result.overflow,'auto'); assert.equal(result.padding,'4px'); assert.equal(result.visible,true);
  }
  await page.evaluate(()=>{writes=0;for(let i=0;i<200;i++)visualViewport.dispatchEvent(new Event('scroll'));});
  await page.waitForTimeout(200); assert.equal(await page.evaluate(()=>writes),0,'same viewport must not invalidate root style');
  await page.evaluate(()=>{Object.defineProperty(visualViewport,'height',{configurable:true,value:240});visualViewport.dispatchEvent(new Event('resize'));disposers.reverse().forEach(fn=>fn?.());});
  await page.waitForTimeout(200); assert.equal(await page.evaluate(()=>document.documentElement.style.getPropertyValue('--dsha-mobile-visible-vh')),'','dispose cancels late writes');
  await page.setViewportSize({width:1200,height:600});
  assert.equal(await page.locator('.dsws-capsule').evaluate(el=>getComputedStyle(el).paddingRight),'7px','desktop remains original');
  console.log(JSON.stringify({engine:process.env.DSHA_BROWSER||'chromium',capsuleWidths:[320,393,800],rootDedupe:true,dispose:true,desktop:true}));
} finally {await fixture.close();}

// 3.2.3's new mechanisms execute together in the browser: document pinning
// must leave inner scrolling intact, and settled visible-band publication must
// restore an expanded question card after the keyboard-height fixture clears.
const updateFixture=await browserFixture();
try {
  const page=updateFixture.page;
  await page.evaluate(()=>{window.__ModuleLoader__={load:({factory})=>factory(()=>({}))};});
  await page.addScriptTag({content:fs.readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8').replace('var __cache = {};','window.mobileRequire=__localRequire; var __cache = {};')});
  const scrolling=await page.evaluate(async()=>{
    window.tick=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    const originalScrollTo=window.scrollTo.bind(window),stops=[];
    document.body.style.height='2400px';
    document.getElementById('root').innerHTML='<div id="inner-scroll" style="height:100px;overflow:auto"><div style="height:1000px">History</div></div>';
    mobileRequire('./effects/document-scroll-lock.js').installDocumentScrollLock({effect:run=>stops.push(run())});
    window.scrollTo(0,200);await tick();const intercepted=window.scrollY===0;
    originalScrollTo(0,200);await tick();const pinned=window.scrollY===0;
    document.getElementById('inner-scroll').scrollTop=300;await tick();const inner=document.getElementById('inner-scroll').scrollTop;
    for(const stop of stops.reverse())stop();window.scrollTo(0,200);await tick();const released=window.scrollY>0;
    originalScrollTo(0,0);document.body.style.height='';document.getElementById('root').replaceChildren();
    return {intercepted,pinned,inner,released};
  });
  assert.deepEqual(scrolling,{intercepted:true,pinned:true,inner:300,released:true});
  const ring=await page.evaluate(()=>{
    const host=document.getElementById('root');
    host.innerHTML='<div class="host_composerStack"><div data-composer-dock><div id="stats">Tokens 10</div><div data-composer-card>Model 50% <textarea></textarea></div><div id="ring"><svg><circle style="stroke-dasharray: 5px, 10px"></circle></svg><span>50%</span></div></div></div>';
    const anchor=mobileRequire('./platform/composer-anchor.js'),stats=document.getElementById('stats');
    const first=anchor.locateRing(stats)?.ring.id;
    const replacement=document.getElementById('ring').cloneNode(true);replacement.id='next-ring';document.getElementById('ring').replaceWith(replacement);
    const next=anchor.locateRing(stats)?.ring.id;
    const composerRejected=!anchor.isRingCandidate(document.querySelector('[data-composer-card]'),stats);
    host.replaceChildren();return {first,next,composerRejected};
  });
  assert.deepEqual(ring,{first:'ring',next:'next-ring',composerRejected:true});
  await page.addStyleTag({content:await page.evaluate(()=>mobileRequire('./styles/index.js').MOBILE_CSS)});
  await page.addStyleTag({content:'#question-host{position:relative;width:100%;height:var(--dsha-mobile-visible-vh,800px)}#question-host>.fixture_centerCol{position:relative;height:100%}.fixture_composerSeat{position:absolute;bottom:0;width:100%}.Mbwy4a_card{display:flex;flex-direction:column;max-height:min(60vh,520px);overflow:hidden}.Mbwy4a_body{min-height:0;overflow:auto}.Mbwy4a_frame{padding:0}.fixture_input{height:70px}'});
  await page.evaluate(()=>{
    document.getElementById('root').innerHTML='<main id="question-host" data-mobile-nav="frame" data-sidebar-collapsed><aside class="fixture_sidebarCol"></aside><section class="fixture_centerCol"><div class="fixture_composerSeat"><div class="Mbwy4a_frame"><div class="Mbwy4a_card" id="question-card"><header><div class="Mbwy4a_headerActions"><button>Close</button></div><div style="height:500px">A long question</div></header><div class="Mbwy4a_body" id="question-body"><textarea class="Mbwy4a_fieldInput">Draft answer</textarea><div style="height:400px">Options</div></div><footer class="Mbwy4a_footer">Submit</footer></div></div><div class="fixture_input">Composer</div></div></section><div data-shell-overlay></div></main>';
    document.documentElement.style.setProperty('--mobile-nav-header-h','60px');
    window.questionStops=[];window.bandEvents=0;
    document.addEventListener('dsh-mobile-visible-band',()=>bandEvents++);
    const ctx={effect:run=>{const stop=run();if(stop)questionStops.push(stop);},locale:{bind:()=>key=>key}};
    questionStops.push(mobileRequire('./effects/viewport-meta.js').syncStableViewportHeight());
    mobileRequire('./effects/question-fullscreen.js').installQuestionFullscreen(ctx);
    questionStops.push(mobileRequire('./effects/reconciler-adapter.js').installReconciler(ctx));
  });
  await page.waitForFunction(()=>document.documentElement.style.getPropertyValue('--dsha-mobile-visible-vh')==='800px');
  await page.locator('[data-mobile-nav="question-expand"]').click();
  const question=await page.evaluate(async()=>{
    const card=document.getElementById('question-card'),body=document.getElementById('question-body'),host=document.getElementById('question-host');
    await tick();const expanded=card.getBoundingClientRect().height;
    const initial={frame:host.getBoundingClientRect().toJSON(),seat:host.querySelector('.fixture_composerSeat').getBoundingClientRect().toJSON(),cardFrame:host.querySelector('.Mbwy4a_frame').getBoundingClientRect().toJSON(),header:document.documentElement.style.getPropertyValue('--mobile-nav-header-h'),visible:document.documentElement.style.getPropertyValue('--dsha-mobile-visible-vh'),reserve:host.querySelector('.Mbwy4a_frame').style.getPropertyValue('--dsha-mobile-question-reserve'),expanded:host.hasAttribute('data-mobile-nav-question-full')};
    const overflow={card:getComputedStyle(card).overflowY,body:getComputedStyle(body).overflowY};
    card.scrollTop=120;const cardScroll=card.scrollTop;
    Object.defineProperty(visualViewport,'height',{configurable:true,value:400});visualViewport.dispatchEvent(new Event('resize'));
    await tick();await tick();const shrunk=card.getBoundingClientRect().height;
    delete visualViewport.height;visualViewport.dispatchEvent(new Event('resize'));
    await tick();await tick();const restored=card.getBoundingClientRect().height;
    const full=host.hasAttribute('data-mobile-nav-question-full'),draft=body.querySelector('textarea').value;
    for(const stop of questionStops.reverse())stop();
    return {expanded,shrunk,restored,full,draft,overflow,cardScroll,bandEvents,initial,disposed:document.querySelector('[data-mobile-nav="question-expand"]')===null};
  });
  console.log(JSON.stringify({upstream:'3.2.3',scrolling,ring,question,scope:'Chromium synthetic host geometry; no Android or physical IME claim'}));
  assert.equal(question.full,true);
  assert.ok(question.expanded>500);
  assert.ok(question.shrunk<question.expanded-250);
  assert.ok(Math.abs(question.restored-question.expanded)<=1);
  assert.equal(question.draft,'Draft answer');
  assert.equal(question.overflow.card,'auto');
  assert.equal(question.overflow.body,'visible');
  assert.ok(question.cardScroll>0);
  assert.ok(question.bandEvents>=3);
  assert.equal(question.disposed,true);
  assert.deepEqual(updateFixture.errors,[]);
} finally {await updateFixture.close();}

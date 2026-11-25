// Real DSH Web over ephemeral loopback and a new synthetic profile.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {setTimeout as pause} from 'node:timers/promises';

const [output, modules, pwModule, chromium] = process.argv.slice(2).map(v=>path.resolve(v));
fs.mkdirSync(output);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dsha-377-settings-'));
const profile = path.join(dir,'home','profiles','web');
fs.mkdirSync(profile, {recursive:true});
const launchName='dsh-launch-target';
const launchFrom=path.resolve(import.meta.dirname,'../../../source/build377-local/app/src/main/assets/builtin-plugins',launchName);
const launchCopy=path.join(profile,'node_modules',launchName);
fs.cpSync(launchFrom,launchCopy,{recursive:true});
fs.writeFileSync(path.join(profile,'package.json'), JSON.stringify({private:true,dependencies:{[launchName]:'0.1.0'},dsh:{profile:{bundles:['@deepseek-ai/dsh-base','@deepseek-ai/dsh-web-app',launchName]}}}));
fs.writeFileSync(path.join(profile,'cordis.patch.yml'),'[]\n');
const env = Object.fromEntries(Object.entries(process.env).filter(([k])=>/^(systemroot|windir|path|temp|tmp|comspec|pathext)$/i.test(k)));
Object.assign(env,{HOME:dir,USERPROFILE:dir,DSH_HOME:path.join(dir,'home'),BROWSER:'true',DSH_CONFIRM:'1',NARB_DISABLE_NATIVE_CACHE:'1'});
const sha = b=>createHash('sha256').update(b).digest('hex');
const snapshot = ()=>sha(fs.readFileSync(path.join(profile,'cordis.patch.yml')));
const started = performance.now(), stages = [];
const record = (stage,fields={})=>{
  const row = {stage,...fields,utc:new Date().toISOString(),ms:Math.round(performance.now()-started)};
  fs.appendFileSync(path.join(output,'events.jsonl'),JSON.stringify(row)+'\n');
  stages.push(row);
};
let child, browser, page;
try {
  const before = snapshot();
  const cli = path.join(modules,'@deepseek-ai/dsh/lib/bin.js');
  child = spawn(process.execPath,['--import',pathToFileURL(path.join(import.meta.dirname,'loopback-only.mjs')).href,cli,'--profile','web','--no-open','--host','127.0.0.1','--port','0'],{env,cwd:dir,windowsHide:true,stdio:['ignore','pipe','pipe']});
  let logs='',auth;
  const onChunk=chunk=>{logs=(logs+chunk.toString()).slice(-100000); auth??=/dsh web: (http:\/\/127\.0\.0\.1:\d+\/\?token=[\w-]+)/.exec(logs)?.[1]};
  child.stdout.on('data',onChunk); child.stderr.on('data',onChunk);
  for(let i=0;i<500&&!auth&&child.exitCode===null;i++)await pause(100);
  assert.ok(auth, 'no local CLI listening, exit '+child.exitCode);
  const origin=new URL(auth).origin;
  record('loopback-ready',{node:process.version,profileSha256:before});
  browser=await createRequire(import.meta.url)(pwModule).chromium.launch({executablePath:chromium,headless:true,args:['--disable-background-networking']});
  const ctx=await browser.newContext({viewport:{width:1280,height:850},locale:'en-US',serviceWorkers:'block'});
  await ctx.route('**/*',route=>new URL(route.request().url()).origin===origin?route.continue():route.abort());
  page=await ctx.newPage();
  page.setDefaultTimeout(5500);
  const res=await page.goto(auth,{waitUntil:'domcontentloaded'});assert.equal(res.status(),200);
  await page.locator('#root [data-shell-overlay]').first().waitFor({timeout:40000});
  for(const name of ['Continue','Configure later','Close']) {
    const button=page.getByRole('dialog').getByRole('button',{name,exact:true}).last();
    await button.waitFor({timeout:700}).catch(()=>{});
    if(await button.isVisible())await button.click();
  }
  async function openSettings(){
    await page.locator('button[data-signed-out]').click();
    // AccountMenu orders Settings first in every language. Verify the actual dialog opened.
    await page.getByRole('menuitem').first().click();
    await page.locator('[data-shortcut-modal="settings"]').waitFor();
  }
  async function dismissAccountPrompt() {
    const intro=page.getByRole('dialog').filter({has:page.getByRole('button',{name:/^(Add API Key|添加 API Key)$/i})}).last();
    await intro.waitFor({timeout:3500}).catch(()=>{});
    if(await intro.isVisible()){
      await intro.getByRole('button',{name:/^(Close|关闭)$/i}).click();
      await intro.waitFor({state:'hidden'});
    }
  }
  const modal=page.locator('[data-shortcut-modal="settings"]');
  record('shell-ready',{chromium:browser.version()});
  const afterBoot = snapshot();
  record('profile-before-settings',{sha256:afterBoot});
  const labels=['General','Launch target','Models','Built-in plugins','Agent presets'];
  for(let round=1;round<=2;round++) {
    for(const label of labels) {
      await openSettings();
      const nav=modal.getByRole('navigation');
      if(round===1 && label===labels[0])record('normal-navigation',{names:await nav.getByRole('button').allTextContents()});
      const option=nav.getByRole('button',{name:new RegExp('^'+label+'$','i')});
      await option.click();
      assert.equal(await option.getAttribute('aria-current'),'true');
      const body=modal.locator(':scope > div').last();
      if(label==='Built-in plugins') await body.getByRole('searchbox').waitFor({timeout:20000});
      else if(label==='Launch target') await body.getByRole('button',{name:'Retry'}).waitFor();
      else {
        const anchor={'General':'Language','Models':'Add model provider','Agent presets':'Standard mode'}[label];
        await body.getByText(anchor,{exact:true}).first().waitFor();
      }
      const text=await body.innerText();
      assert(text.trim().length>15);
      record('section',{round,label,text:text.slice(0,400),tabs:await body.getByRole('tab').allTextContents()});
      if(label==='Launch target'){
        const retry=body.getByRole('button',{name:'Retry'});
        await retry.waitFor();
        const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/dsha-launch-target');
        await retry.click();
        record('launch-retry',{round,httpStatus:(await response).status()});
        await retry.waitFor();
      }
      if(label==='Built-in plugins'&&round===1) {
        const search=modal.getByRole('searchbox');
        await search.waitFor({timeout:20000});
        assert.equal(await search.inputValue(),'');
        const section=modal.locator('[data-plugin-scope="preset"]');
        const chooser=section.getByRole('button',{name:/switch|choose|select/i});
        const baselinePreset=await section.getAttribute('data-preset-id');
        const baselineRows=await section.locator('[data-plugin-module]').count();
        await chooser.click();
        const options=await page.getByRole('menuitem').allTextContents();
        record('preset-menu',{options});
        for(let i=0;i<options.length;i++){
          if(i>0)await chooser.click();
          await page.getByRole('menuitem').nth(i).click();
          assert.equal(await chooser.getAttribute('aria-expanded'),'false');
          record('preset-selected',{option:options[i],id:await section.getAttribute('data-preset-id'),rowCount:await section.locator('[data-plugin-module]').count()});
        }
        assert.deepEqual(new Set(stages.filter(r=>r.stage==='preset-selected').map(r=>r.id)),new Set(['standard','ptc','minimal','cordis']));
        await chooser.click();
        await page.getByRole('menuitem').first().click();
        assert.equal(await section.getAttribute('data-preset-id'),baselinePreset);
        const words=['sandbox','fixture-no-match-377',''];
        for(const value of words){
          await search.fill(value);
          assert.equal(await search.inputValue(),value);
          const global=modal.locator('[data-plugin-scope="global"]');
          const g=await global.innerText();
          if(value==='sandbox')assert.match(g,/sandbox/i);
          if(value===words[1])assert.match(await body.innerText(),/no .*match|nothing match/i);
          const visibleRows=await body.locator('[data-plugin-module]').count();
          if(value===words[1])assert.equal(visibleRows,0);
          if(value==='')assert.equal(await section.locator('[data-plugin-module]').count(),baselineRows);
          record('filter',{value,visibleRows,globalText:g.slice(0,220),noMatches:await body.getByText(/no .*match|nothing match/i).count()});
        }
      }
      await modal.getByRole('button',{name:'Close',exact:true}).click();
      await modal.waitFor({state:'hidden'});
    }
  }
  assert.equal(snapshot(),afterBoot);
  record('read-only-profile-unchanged',{sha256:afterBoot});
  async function languageSwitch(from, toPattern, fullReload) {
    await openSettings();
    await modal.getByRole('navigation').getByRole('button').first().click();
    await modal.getByRole('button',{name:from,exact:true}).click();
    const choices=await page.getByRole('menuitem').allTextContents();
    const next=choices.find(x=>toPattern.test(x));
    assert.ok(next,'locale option not found among '+JSON.stringify(choices));
    await page.getByRole('menuitem',{name:next,exact:true}).click();
    await modal.getByRole('button',{name:next,exact:true}).waitFor();
    if(fullReload){
      await page.reload({waitUntil:'domcontentloaded'});
      await dismissAccountPrompt();
    } else {
      await page.keyboard.press('Escape');
      await modal.waitFor({state:'hidden'});
    }
    await openSettings();
    await modal.getByRole('navigation').getByRole('button').first().click();
    await modal.getByRole('button',{name:next,exact:true}).waitFor();
    record('persisted-language',{from,next,choices,fullReload});
    await page.keyboard.press('Escape');
    await modal.waitFor({state:'hidden'});
    return next;
  }
  let active=await languageSwitch('English',/^中文$/,true);
  const choices=stages.filter(r=>r.stage==='persisted-language')[0].choices;
  for(const name of choices.filter(s=>s!==active && s!=='English')){
    active=await languageSwitch(active,new RegExp('^'+name+'$'),false);
  }
  await languageSwitch(active,/^English$/,true);
  record('checks-finished',{profileSha256:snapshot(),readOnlyProfileSha256:afterBoot,
    languageResult:'Originally inherited en-US. Last explicit selection is English.'});
} catch(error) {
  const screen=page?await page.locator('body').innerText({timeout:1200}).catch(()=>null):null;
  record('error',{message:String(error).replace(/https?:\/\/\S+/g,'[local URL]').slice(0,1500),screen:screen?.slice(0,5100)});
  process.exitCode=1;
} finally {
  await browser?.close();
  if(child&&child.exitCode===null){const ended=new Promise(r=>child.once('close',r));child.kill();await ended;}
  fs.rmSync(dir,{recursive:true,force:true});
  record('complete',{checksPassed:!process.exitCode,profileRemoved:!fs.existsSync(dir),childExited:child?.exitCode!==null||child?.signalCode!==null});
}

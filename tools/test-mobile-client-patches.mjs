// 源漂移必须拒绝；正式产物只能来自固定预构建client与明确DSHA差异。
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {applyMobileClientPatches,upstreamCommit,upstreamClientHash} from './apply-mobile-client-patches.mjs';
test('移动client固定预构建来源与部署产物闭环，拒绝错误版本/追加字节',()=>{
  const source=readFileSync('tools/mobile-plugin-upstream/lib/client.js');
  assert.equal(createHash('sha256').update(source).digest('hex'),upstreamClientHash);
  assert.equal(upstreamCommit,'bb0e5e151ca549fef506e829020a5d7138074711');
  assert.equal(readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8'),applyMobileClientPatches(source));
  assert.throws(()=>applyMobileClientPatches(Buffer.concat([source,Buffer.from('\n')])),/字节不匹配/);
});
test('手机设置弹层字体族行换行，字号行/其它弹层不受影响且产物逐字节一致',()=>{
  const source=readFileSync('tools/mobile-plugin-upstream/lib/client.js','utf8');
  const patched=applyMobileClientPatches(Buffer.from(source));
  const settings='[aria-modal="true"][data-shortcut-modal="settings"]';
  const row=settings+' div[class*="_row"]';
  const input='input[spellcheck="false"][autocomplete="off"][maxlength="200"]';
  assert.ok(patched.includes('DSHA_FONT_FAMILY_ROW_V1'));
  // rc.5 的 Input 原语把 <input> 包在 span[class*="_wrap"] 内（240px 也在 wrapper 上），
  // 所以「裸 input」与「wrapper 内 input」两种宿主形态都要覆盖。
  assert.ok(patched.includes(row+':has(> '+input+')'));
  assert.ok(patched.includes(row+':has(> span[class*="_wrap"] > '+input+')'));
  assert.ok(patched.includes(row+':has(> '+input+') > div[class*="_rowText"]'));
  assert.ok(patched.includes(row+':has(> span[class*="_wrap"] > '+input+') > div[class*="_rowText"]'));
  assert.ok(patched.includes(row+':has(> '+input+') > input'));
  assert.ok(patched.includes(row+':has(> span[class*="_wrap"] > '+input+') > span[class*="_wrap"]'));
  assert.ok(patched.includes('@media (max-width: 1023px) and (pointer: coarse) {'));
  // 单层 :has：仓库踩过“嵌套 :has 整条失效”的坑；字号行没有 input 也不会命中。
  const start=patched.indexOf('/* DSHA_FONT_FAMILY_ROW_V1');
  const block=patched.slice(start,patched.indexOf('/* ---------- sidebar panel enter / exit',start));
  assert.ok(block.length>500);
  assert.ok(!block.includes('data-shortcut-modal="shortcuts"'));
  for(const line of block.split('\n'))if(line.includes(':has('))
    assert.equal((line.match(/:has\(/g)||[]).length,1,'同一选择器只允许单层 :has: '+line.trim());
  assert.equal(readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js','utf8'),patched);
});
test('3.2.2 最新上游视口函数保持原字节，不复活已撤回的帧合并试验',()=>{
  const source=readFileSync('tools/mobile-plugin-upstream/lib/client.js','utf8');
  const patched=applyMobileClientPatches(Buffer.from(source));
  const viewport=text=>text.slice(text.indexOf('function syncStableViewportHeight() {'),text.indexOf('/** iOS-only: mark documentElement'));
  assert.ok(viewport(source).length>500);
  assert.equal(viewport(patched),viewport(source));
  assert.ok(patched.includes('interactive-widget=resizes-content'));
});
test('宽屏触屏档收起归档/置顶，规则落在 ≥1024 档内且与手机壳名单一致',()=>{
  const source=readFileSync('tools/mobile-plugin-upstream/lib/client.js','utf8');
  const patched=applyMobileClientPatches(Buffer.from(source));
  const open='@media (min-width: 1024px) and (pointer: coarse) {';
  const at=patched.indexOf(open);
  assert.ok(at>=0,'未找到宽屏触屏档');
  let depth=0,end=-1;
  for(let i=patched.indexOf('{',at);i<patched.length;i++){
    if(patched[i]==='{')depth++;
    else if(patched[i]==='}'){depth--;if(depth===0){end=i;break;}}
  }
  assert.ok(end>at,'宽屏触屏档大括号不配平');
  const tablet=patched.slice(at,end+1);
  assert.ok(tablet.includes('DSHA_WIDE_TOUCH_ROW_ACTIONS_V1'),'补丁标记缺失');
  const hide=/\[class\*="sessionRow"\] \[class\*="_rowActions"\] :is\(([\s\S]*?)\) \{([\s\S]*?)\}/.exec(tablet);
  assert.ok(hide,'宽屏触屏档没有隐藏归档/置顶的规则');
  assert.match(hide[2],/display:\s*none !important/);
  const labels=text=>[...text.matchAll(/aria-label="([^"]+)"/g)].map(m=>m[1]).sort();
  const phone=/\[class\*="_rowActions"\] :is\(([\s\S]*?)\) \{/.exec(source);
  assert.ok(phone,'手机壳 #154 规则缺失');
  // 两档共用宿主 zh/en 字典：名单必须逐字一致，换语言时同时静默失效。
  assert.deepEqual(labels(hide[1]),labels(phone[1]),'宽屏触屏档名单必须与手机壳一致');
  assert.equal(labels(hide[1]).length,8);
});


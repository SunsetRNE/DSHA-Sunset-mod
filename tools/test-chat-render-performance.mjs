#!/usr/bin/env node
// 锁定 rc5 的真实 React、ChatFlow、节点订阅、Markdown 与滚动控制器。
// 全部记录均为生成夹具；不连接账户，不裁剪历史，不禁用观察器。
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {testRuntime} from './test-runtime-fixture.mjs';
import {browserFixture} from './rc1-browser-fixture.mjs';

const runtime = testRuntime('raw');
const root = path.resolve(import.meta.dirname, '..');
const recipe = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/chat-render-performance-patch.json'), 'utf8'));
const viewportRecipe = JSON.parse(fs.readFileSync(path.join(root, 'app/src/main/assets/chat-viewport-performance-patch.json'), 'utf8'));
const source = fs.readFileSync(path.join(runtime, 'node_modules', recipe.module));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const inputSha256 = {officialSource: digest(source), recipe: digest(fs.readFileSync(path.join(root, 'app/src/main/assets/chat-render-performance-patch.json'))),
  viewportRecipe: digest(fs.readFileSync(path.join(root, 'app/src/main/assets/chat-viewport-performance-patch.json'))),
  test: digest(fs.readFileSync(import.meta.filename))};
assert.equal(createHash('sha256').update(source).digest('hex'), recipe.sourceSha256);
assert.equal(recipe.dshVersion, '0.2.1-rc.5');
assert.equal(recipe.module, '@deepseek-ai/dsh-client-ui-chat/lib/client.js');
assert.equal(viewportRecipe.sourceSha256, recipe.sourceSha256);
assert.equal(viewportRecipe.module, recipe.module);
assert.equal(viewportRecipe.dshVersion, recipe.dshVersion);
const instrumentation = {patches: [{
  before: '\t\t\t\tswitch (entry.kind) {\n\t\t\t\t\tcase "node": return (0, react.createElement)(ChatNodeSeat, {',
  after: '\t\t\t\tglobalThis.chatAuditRowBuilds = (globalThis.chatAuditRowBuilds || 0) + 1;\n\t\t\t\tswitch (entry.kind) {\n\t\t\t\t\tcase "node": return (0, react.createElement)(ChatNodeSeat, {'
}]};
const fixture = await browserFixture(runtime);
const errors = [];
fixture.page.on('pageerror', error => errors.push(error.message));
fixture.page.on('console', message => {
  if (message.type() === 'error' && !message.location().url.endsWith('/favicon.ico')) errors.push(message.text());
});
const results = {};
try {
  const order = process.env.DSHA_CHAT_RENDER_PATCHED_FIRST === '1' ? ['patched', 'baseline'] : ['baseline', 'patched'];
  for (const mode of order) {
    await fixture.load('dsh-client-ui-chat', [...(mode === 'patched' ? [recipe, viewportRecipe] : []), instrumentation],
      'module.exports.audit = {ChatFlow, ChatViewport, MutableChatNodeStore, MutableTurnNavigationIndex, markdownLabels};');
    results[mode] = await fixture.page.evaluate(async ({mode}) => {
      const check = (value, label) => { if (!value) throw Error(mode + ': ' + label); };
      const React = auditModules.react.default || auditModules.react;
      const ReactDOM = auditModules['react-dom'].default || auditModules['react-dom'];
      const primitives = auditModules['@deepseek-ai/dsh-client-ui-primitives'];
      const {ChatFlow, ChatViewport, MutableChatNodeStore, MutableTurnNavigationIndex, markdownLabels} = auditExports['@deepseek-ai/dsh-client-ui-chat'].audit;
      const frame = () => new Promise(resolve => requestAnimationFrame(resolve));
      const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
      const makeNode = (i, text = `Message ${i}\n\n**Complete history** ${'content '.repeat(12)}`) => ({
        key: `node-${i}`, kind: 'assistant-step', target: 'chat', visibility: 'visible', anchorSeq: i + 1,
        data: {turn: i, step: 0, status: 'settled', blocks: [{kind: 'text', text}]},
        location: {kind: 'turn', turn: {turn: i, status: 'closed', data: {}}}
      });
      const indexResults = [];
      for (const count of [1000, 10000]) {
        const nodes = new Map(Array.from({length: count}, (_, i) => [i, makeNode(i, 'A'.repeat(400))]));
        const locations = {getTurn: turn => [turn]};
        const timeline = {turnOrder: [...nodes.keys()]};
        const index = new MutableTurnNavigationIndex();
        index.rebuild(timeline, locations, nodes);
        const identity = index.items();
        const changed = new Set([count - 1]);
        let reads = 0;
        index.current = new Proxy(identity, {get(target, key, receiver) {
          if (typeof key === 'string' && /^\d+$/.test(key)) reads++;
          return Reflect.get(target, key, receiver);
        }});
        index.touch(changed, locations, nodes);
        const stableReads = reads;
        check(index.items() !== identity && index.items().length === count, 'instrumentation keeps current proxy');
        index.current = identity;
        const samples = [];
        for (let repeat = 0; repeat < 7; repeat++) {
          const started = performance.now();
          for (let update = 0; update < 500; update++) index.touch(changed, locations, nodes);
          samples.push(performance.now() - started);
        }
        check(index.items() === identity, 'unchanged summary retains immutable output');
        nodes.set(count - 1, makeNode(count - 1, 'Edited tail'));
        nodes.set(0, makeNode(0, 'Edited first'));
        index.touch(new Set([count - 1, 0, count + 20]), locations, nodes);
        const edited = index.items();
        check(edited !== identity && edited[0].response === 'Edited first' && edited.at(-1).response === 'Edited tail', 'multi-turn edits update exact positions');
        check(identity[0].response !== 'Edited first' && edited[1] === identity[1], 'old snapshot immutable and untouched item stable');
        nodes.set(-1, makeNode(-1, 'Older history'));
        index.rebuild({turnOrder: [-1, ...timeline.turnOrder]}, locations, nodes);
        nodes.set(count - 1, makeNode(count - 1, 'Tail after prepend'));
        index.touch(changed, locations, nodes);
        check(index.items()[0].turn === -1 && index.items().at(-1).response === 'Tail after prepend', 'prepend reindexes positions');
        index.rebuild({turnOrder: []}, locations, nodes);
        index.touch(changed, locations, nodes);
        check(index.items().length === 0, 'cleared index cannot restore stale rows');
        indexResults.push({count,updatesPerSample:500,samples,medianMs:median(samples),stableHistoryArrayReads:stableReads});
      }
      const count = 400;
      let store = new MutableChatNodeStore();
      const nodes = Array.from({length: count}, (_, i) => makeNode(i));
      nodes[0] = makeNode(0, '# Searchable first message\n\n```js\nconst preserved = true;\n```');
      store.replace(nodes);
      let entries = nodes.map(node => ({kind: 'node', key: node.key}));
      const chatSnapshot = {nodes: store, navigation: {items: () => []}};
      const policy = {foldCompletedTurns: false, stepGrouping: 'expanded', collapseTiming: 'immediate'};
      const counts = new Map();
      const labels = markdownLabels(key => key);
      const useNode = (key, select = value => value) => {
        const source = store.source(key);
        return React.useSyncExternalStore(source.subscribe, () => select(source.getSnapshot()));
      };
      const useBottom = key => {
        const source = store.bottomSource(key);
        return React.useSyncExternalStore(source.subscribe, source.getSnapshot);
      };
      const useProcess = key => {
        const source = store.processSource(key);
        return React.useSyncExternalStore(source.subscribe, source.getSnapshot);
      };
      const groupListeners = new Set();
      let group = {members: entries.slice(10, 13), data: {turn:10, closed:true, summary:{counts:[]}}};
      const subscribeGroup = listener => {groupListeners.add(listener);return () => groupListeners.delete(listener);};
      const useGroup = (_key, select) => React.useSyncExternalStore(subscribeGroup, () => select(group));
      function TestMessage({node, t}) {
        counts.set(node.key, (counts.get(node.key) || 0) + 1);
        const [editing, setEditing] = React.useState(false);
        return React.createElement('div', {'data-message':node.key},
          React.createElement(primitives.MarkdownText, {text:node.data.blocks?.[0]?.text || 'Tool result',streaming:node.data.status === 'running',labels}),
          React.createElement('button', {type:'button','aria-label':t('edit'),onClick:()=>setEditing(value=>!value)}, t('edit')),
          editing ? React.createElement('input', {'aria-label':'draft', defaultValue:'retained draft'}) : null,
          node.key === 'node-2' ? React.createElement('details', {'data-tool-details':true}, React.createElement('summary',null,'Tool result'),React.createElement('pre',null,'complete tool output')) : null);
      }
      let props = {
        entries, pendingInputs: [], deferCollapse:false, useSession:select=>select({running:false}),
        useChat:select=>select(chatSnapshot),useChatNode:useNode,useChatNodeBottom:useBottom,useChatNodeProcess:useProcess,
        useChatGroup:useGroup,usePresentation:select=>select(policy),useStore:select=>select({turnProcesses:[]}),
        actions:{setTurnProcessOpen(){}},useGroupAction:()=>React.useRef(null),useGroupHeaderAction(){},
        renderSlot:(_name,owner)=>React.createElement(TestMessage,{node:owner.node,t:props.t}),
        renderFactorySlot:(_name,owner)=>React.createElement('span',{'data-pending-content':true},owner.text || owner.content?.[0]?.text || ''),
        t:key=>key,cwd:'',openFile(){},inspectCall(){},forkAt(){},loadImage(){},fileMentions(){}
      };
      const host = document.createElement('div');
      host.setAttribute('data-conversation-scroll','');
      host.style.cssText = 'height:720px;width:800px;overflow:auto;position:relative;line-height:24px';
      const column = document.createElement('div');host.append(column);document.body.append(host);
      const viewport = new ChatViewport();viewport.attach(host,column);
      viewport.updateTurns(nodes.map((node,i)=>({turn:i,anchorKey:node.key})));
      let resizeCalls = 0;
      viewport.connect({resize(){resizeCalls++;if(viewport.preserving)viewport.preserve();},scroll(){},scrollEnd(){},interact(){}});
      const app = ReactDOM.createRoot(column);
      const render = () => ReactDOM.flushSync(()=>app.render(React.createElement(ChatFlow,props)));
      const started = performance.now();render();await frame();await frame();
      const mountMs = performance.now()-started;
      check(column.querySelectorAll('[data-chat-node-key]').length === count, 'all history mounted');
      const first = column.querySelector('[data-chat-node-key="node-0"]');
      const last = column.querySelector('[data-chat-node-key="node-399"]');
      ReactDOM.flushSync(()=>first.querySelector('button[aria-label="edit"]').click());
      const editor = first.querySelector('input');editor.value='in progress user edit';
      const details=column.querySelector('[data-tool-details]');details.open=true;
      host.scrollTop = column.querySelector('[data-chat-node-key="node-200"]').offsetTop;
      const anchor = viewport.capturePosition();viewport.beginPreserving(anchor);
      // 代码高亮由真实 Markdown 懒加载；先等其初次提交完成再量流式增量。
      await new Promise(resolve => setTimeout(resolve, 300));
      const anchorBefore = viewport.anchor(anchor.anchorKey).getBoundingClientRect().top;
      const firstRenderCount=counts.get('node-0');
      let historicMutations=0;const mutationDetails=[];
      const observer=new MutationObserver(changes=>{for(const change of changes){
        const node=change.target.nodeType===1?change.target:change.target.parentElement;
        const row=node?.closest('[data-chat-node-key]');
        if(row && row.dataset.chatNodeKey!=='node-399') {historicMutations++;if(mutationDetails.length<5)mutationDetails.push({key:row.dataset.chatNodeKey,type:change.type,attribute:change.attributeName});}
      }});
      observer.observe(column,{subtree:true,childList:true,characterData:true,attributes:true});
      const streaming=[];
      for(let update=0;update<40;update++){
        const next=makeNode(399,`Stream ${update}\n\n${'new output '.repeat(update+10)}`);next.data.status='running';
        const time=performance.now();ReactDOM.flushSync(()=>{store.upsert([next]);store.publish();});
        streaming.push(performance.now()-time);await frame();
      }
      await frame();observer.disconnect();
      check(counts.get('node-0')===firstRenderCount,'unaffected historical markdown does not rerender');
      check(historicMutations===0,'stream does not mutate historical DOM '+JSON.stringify(mutationDetails));
      check(first===column.querySelector('[data-chat-node-key="node-0"]') && last===column.querySelector('[data-chat-node-key="node-399"]'),'stream preserves keyed DOM');
      check(Math.abs(viewport.anchor(anchor.anchorKey).getBoundingClientRect().top-anchorBefore)<1,'reading anchor held during stream');
      check(editor.value==='in progress user edit' && details.open,'edit and tool expansion retained');
      check(last.textContent.includes('Stream 39'),'final streaming token visible');
      globalThis.chatAuditRowBuilds=0;
      const parentSamples=[];
      for(let update=0;update<20;update++){
        props={...props,pendingInputs:[]};const time=performance.now();render();parentSamples.push(performance.now()-time);
      }
      const parentRowBuilds=globalThis.chatAuditRowBuilds;
      check(editor===first.querySelector('input') && editor.value==='in progress user edit','parent updates retain editing instance');
      props={...props,t:key=>'localized:'+key};render();
      check(first.querySelector('button[aria-label="localized:edit"]')!==null,'locale dependency reaches all rows');
      const edited=makeNode(0,'# Edited earlier message');
      ReactDOM.flushSync(()=>{store.upsert([edited]);store.publish();});
      check(first.textContent.includes('Edited earlier message'),'historical edit publishes through own subscription');
      entries=[...entries.slice(0,10),{kind:'group',key:'group-a'},...entries.slice(13)];
      props={...props,entries};render();await frame();
      check(column.querySelectorAll('[data-chat-node-key]').length===count,'grouping retains all members');
      group={...group,members:[...group.members,{kind:'node',key:'node-13'}]};
      props={...props,entries:entries.filter(entry=>entry.key!=='node-13')};
      ReactDOM.flushSync(()=>{for(const listener of groupListeners)listener();});render();
      check(column.querySelector('[data-chat-group-key="group-a"] [data-chat-node-key="node-13"]')!==null,'group subscription survives row cache');
      check(column.querySelectorAll('[data-chat-node-key]').length===count,'group transfer loses no messages');
      const control={...makeNode(500,'Control'),key:'control',kind:'turn-process',location:{kind:'turn',turn:{turn:500,status:'open',data:{}}}};
      store.upsert([control]);store.publish();
      const pending={requestId:'pending-a',text:'Pending text',attachments:[],placement:'transcript',time:Date.now()};
      props={...props,entries:[...props.entries,{kind:'node',key:'control'}],pendingInputs:[pending]};render();render();
      check(column.querySelectorAll('[data-chat-node-key="control"]').length===1,'open control not duplicated');
      check(column.querySelectorAll('[data-pending-content]').length===1,'pending input not duplicated');
      props={...props,pendingInputs:[]};render();
      check(column.querySelectorAll('[data-pending-content]').length===0,'removed pending input not retained');
      viewport.stopPreserving();host.scrollTop=column.querySelector('[data-chat-node-key="node-200"]').offsetTop;
      const beforePaging=viewport.capturePosition();viewport.beginPreserving(beforePaging);
      const pagingTop=viewport.anchor(beforePaging.anchorKey).getBoundingClientRect().top;
      const older=Array.from({length:20},(_,i)=>makeNode(-20+i));store.upsert(older);store.publish();
      props={...props,entries:[...older.map(node=>({kind:'node',key:node.key})),...props.entries]};render();
      await frame();await frame();viewport.preserve();
      check(Math.abs(viewport.anchor(beforePaging.anchorKey).getBoundingClientRect().top-pagingTop)<1,'prepend preserves reading anchor');
      check(column.querySelectorAll('[data-chat-node-key]').length===421,'entire loaded history remains searchable');
      check(column.textContent.includes('Message -20') && column.textContent.includes('Stream 39'),'oldest and newest full content retained');
      // registry/session 更换存储时，即使 keyed hook 函数和条目未变也必须重新绑定。
      const previousStore=store;
      store=new MutableChatNodeStore();store.replace(previousStore.values());
      store.upsert([makeNode(0,'Replacement source')]);chatSnapshot.nodes=store;
      // 本夹具 useChat 是静态选择器；显式触发一次父提交模拟真实 selector 通知。
      props={...props,pendingInputs:[...props.pendingInputs]};render();
      check(first.textContent.includes('Replacement source'),'new node store invalidates memoized seats');
      ReactDOM.flushSync(()=>{previousStore.upsert([makeNode(0,'Stale source')]);previousStore.publish();});
      check(!first.textContent.includes('Stale source'),'old source detached after store replacement');
      ReactDOM.flushSync(()=>{store.upsert([makeNode(0,'New subscription live')]);store.publish();});
      check(first.textContent.includes('New subscription live') && first.querySelector('input')===editor,'replacement retains keyed editor and live subscription');
      const result={count,mountMs,streamingCommitMedianMs:median(streaming),streamingCommitMaxMs:Math.max(...streaming),
        parentCommitMedianMs:median(parentSamples),parentRowBuilds,historicMutations,resizeCalls,
        finalRows:column.querySelectorAll('[data-chat-node-key]').length};
      viewport.detach();app.unmount();host.remove();
      return {index:indexResults,render:result};
    }, {mode});
  }
  assert.equal(results.baseline.render.parentRowBuilds, 400 * 20);
  assert.equal(results.patched.render.parentRowBuilds, 0);
  for (const item of results.patched.index) assert.equal(item.stableHistoryArrayReads, 0);
  for (const item of results.baseline.index) assert.equal(item.stableHistoryArrayReads, item.count * 2);
  assert.deepEqual(errors, []);assert.deepEqual(fixture.errors, []);
  const budget = Number(process.env.DSHA_CHAT_RENDER_BUDGET_MS || 10000);
  assert.ok(Number.isFinite(budget) && budget > 0, 'valid render budget');
  assert.ok(results.patched.render.mountMs < budget, 'full-history mount within host safety budget');
  console.log(JSON.stringify({status:'PASS',runtime,inputSha256,order,engine:await fixture.browser.version(),
    scope:'desktop browser, synthetic complete history; sequential mount timings are observational, not causal improvements; no phone/panel/API latency claim',...results},null,2));
} finally { await fixture.close(); }

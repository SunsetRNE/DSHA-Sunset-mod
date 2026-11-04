// Execute the locked runner with real Cordis fibers, and the exact bundled UI
// inventory function. Only activation timing and cleanup failure are controlled.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {testRuntime} from './test-runtime-fixture.mjs';
import {browserFixture} from './rc1-browser-fixture.mjs';

const root=path.resolve(import.meta.dirname,'..');
const args=process.argv.slice(2), arg=name=>args.includes(name)?args[args.indexOf(name)+1]:undefined;
const raw=arg('--raw')||testRuntime('raw');
const output=path.resolve(arg('--output')||path.join(root,'app/build/temporary-plugin-remove'));
fs.mkdirSync(output,{recursive:true});
const owned=fs.mkdtempSync(path.join(output,'run-'));
const requireRaw=createRequire(path.join(raw,'package.json'));
const sha=data=>createHash('sha256').update(data).digest('hex');
const proof=JSON.parse(fs.readFileSync(path.join(raw,'dsha-test-runtime.json'),'utf8'));
assert.equal(proof.kind,'raw');
assert.equal(proof.lockSha256,sha(fs.readFileSync(path.join(root,'tools/dsh-runtime/package-lock.json'))));
const recipes={};
const sources={};
for(const kind of ['host','ui']){
  const recipe=recipes[kind]=JSON.parse(fs.readFileSync(path.join(root,`app/src/main/assets/temporary-plugin-${kind}-remove-patch.json`),'utf8'));
  const bytes=fs.readFileSync(path.join(raw,'node_modules',recipe.module));
  assert.equal(sha(bytes),recipe.sourceSha256);
  assert.equal(proof.contentProof.entries['node_modules/'+recipe.module].sha256,sha(bytes));
  let source=bytes.toString('utf8');
  sources[kind]={raw:source};
  for(const patch of recipe.patches){
    assert.equal(source.split(patch.before).length,2);
    source=source.replace(patch.before,patch.after);
  }
  assert.equal(sha(source),recipe.targetSha256);
  sources[kind].patched=source;
}
const {Context}=await import(pathToFileURL(requireRaw.resolve('@deepseek-ai/cordis')));
const classes={};
for(const variant of ['raw','patched']){
  // Resolve only imports, leaving the complete runner implementation intact.
  const text=sources.host[variant].replace(/from "(@[^"\n]+)";/g,(_,id)=>`from ${JSON.stringify(pathToFileURL(requireRaw.resolve(id)).href)};`);
  const file=path.join(owned,variant+'-runner.mjs');fs.writeFileSync(file,text);
  classes[variant]=(await import(pathToFileURL(file))).DynamicCordisRunnerService;
}
const rows=[];
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
const ticks=async()=>{await Promise.resolve();await Promise.resolve();await Promise.resolve();};
async function fixture(variant,run){
  const ctx=new Context();
  const runner=new classes[variant](ctx,{vmTimeoutMs:5000,clientInspectTimeoutMs:1000});
  const agent={id:'owned-session'},events=[];
  ctx.on('cordis/dynamic-package',event=>events.push(event));
  try {await run({ctx,runner,agent,events});} finally {await ctx.fiber.dispose();}
}
const define=(runner,agent,code={host:'return { apply(ctx) { ctx.effect(() => () => {}); } };'})=>runner.define({
  sessionId:agent.id,plugin:{kind:'new',idPrefix:'owned'},name:'Owned fixture',purpose:'Removal lifecycle test',code});

for(const variant of ['raw','patched'])await fixture(variant,async({runner,agent,events})=>{
  const definition=define(runner,agent),plugin=runner.registry.get(definition.pluginId);
  const entered=deferred(),release=deferred(),original=runner.startHost;
  let fiber;
  runner.startHost=async function(...args){
    const result=await original.apply(this,args);fiber=args[2].fiber;
    entered.resolve();await release.promise;return result;
  };
  const activation=runner.run(agent,definition.pluginId,definition.packageId,'run');
  await entered.promise;
  assert.equal(fiber.state,2,'actual Cordis host fiber must be mounted');
  let acknowledged=false;
  const deletion=runner.undefineFromPanel(agent,definition.pluginId).then(value=>{acknowledged=true;return value;});
  await ticks();
  if(variant==='raw'){
    await deletion;assert.equal(acknowledged,true);
  }else{
    assert.equal(acknowledged,false,'removal cannot acknowledge while activation still owns effects');
    assert.equal((await runner.run(agent,definition.pluginId,definition.packageId,'run')).reason,'transition-in-flight');
  }
  release.resolve();
  const result=await activation,removed=await deletion;
  assert.equal(removed.ok,true);assert.equal(runner.registry.get(definition.pluginId),undefined);
  if(variant==='raw'){
    assert.equal(result.ok,true);assert.equal(fiber.state,2);assert.equal(events.length,1);
    assert.ok(plugin.run,'negative control leaves a mounted run on a removed record');
    await runner.retract(plugin);
  }else{
    assert.equal(result.ok,false);assert.equal(fiber.state,4);assert.equal(events.length,0);
    assert.equal(plugin.run,undefined);
    assert.deepEqual(runner.listPlugins(agent),[]);
    assert.equal((await runner.run(agent,definition.pluginId,definition.packageId,'run')).reason,'plugin-missing');
  }
  rows.push({name:variant+'-remove-during-real-host-activation',status:'PASS',negativeControl:variant==='raw'});
});

await fixture('patched',async({runner,agent})=>{
  const bad=define(runner,agent,{host:'return { apply() { throw new Error("owned startup failure"); } };'});
  assert.equal((await runner.run(agent,bad.pluginId,bad.packageId,'run')).ok,false);
  assert.equal((await runner.undefineFromPanel(agent,bad.pluginId)).ok,true);
  assert.deepEqual(runner.listPlugins(agent),[]);
  rows.push({name:'failed-real-cordis-apply-remains-removable',status:'PASS'});
  const pending=define(runner,agent,{client:'return { apply() {} };'});
  assert.equal((await runner.run(agent,pending.pluginId,pending.packageId,'run')).status,'awaiting-approval');
  const request=runner.registry.pendingRequestFor(pending.pluginId);
  assert.ok(request);
  assert.equal((await runner.undefineFromPanel({id:'foreign-session'},pending.pluginId)).ok,false);
  assert.ok(runner.registry.get(pending.pluginId));
  assert.equal((await runner.undefineFromPanel(agent,pending.pluginId)).ok,true);
  assert.equal(runner.registry.peekRequest(request),undefined);
  assert.equal((await runner.runHostHalf(agent,pending.pluginId,pending.packageId,'run',request,false)).ok,false);
  assert.equal((await runner.resolveRequestRun(agent,request,{ok:true})).accepted,false);
  rows.push({name:'pending-approval-removal-cancels-exact-request-and-rejects-late-approval',status:'PASS'});
});

await fixture('patched',async({runner,agent})=>{
  const definition=define(runner,agent);await runner.run(agent,definition.pluginId,definition.packageId,'run');
  const plugin=runner.registry.get(definition.pluginId),run=plugin.run;
  const original=run.fiber.dispose.bind(run.fiber);let count=0;
  run.fiber.dispose=async()=>{if(++count===1)throw Error('owned unconfirmed cleanup');return original();};
  await assert.rejects(runner.undefineFromPanel(agent,definition.pluginId),/owned unconfirmed cleanup/);
  assert.equal(runner.registry.get(definition.pluginId),plugin);
  assert.equal(plugin.run,run,'cleanup failure must retain exact run for explicit recovery');
  assert.equal((await runner.run(agent,definition.pluginId,definition.packageId,'run')).reason,'transition-in-flight');
  assert.equal((await runner.invoke(definition.pluginId,run.pluginRunId,'owned-handler',{})).code,'plugin-not-running');
  assert.equal((await runner.undefineFromPanel(agent,definition.pluginId)).ok,true);
  assert.equal(count,2);assert.equal(runner.registry.get(definition.pluginId),undefined);
  rows.push({name:'unconfirmed-cleanup-is-not-success-keeps-run-and-requires-explicit-remove',status:'PASS'});
});

function inventory(source){
  const start=source.indexOf('\t\tfunction createCordisInventory(');
  const end=source.indexOf('\n\t\t//#endregion',start);
  assert.ok(start>0&&end>start);
  return vm.runInNewContext(source.slice(start,end)+'\ncreateCordisInventory;');
}
for(const variant of ['raw','patched']){
  const old=deferred(),fresh=deferred();let calls=0;
  const value=inventory(sources.ui[variant])({inventory:()=>++calls===1?old.promise:fresh.promise},error=>{throw error;});
  value.refresh();value.retire('owned-1');value.refresh();
  old.resolve([{pluginId:'owned-1'}]);await ticks();await ticks();
  if(variant==='raw'){
    assert.equal(calls,1);assert.equal(value.getSnapshot().rows[0].pluginId,'owned-1');
  }else{
    assert.equal(calls,2);assert.equal(value.getSnapshot().rows.length,0);
    fresh.resolve([{pluginId:'unrelated-2'}]);await ticks();await ticks();
    assert.equal(value.getSnapshot().rows[0].pluginId,'unrelated-2');
    assert.equal(value.getSnapshot().removed.has('owned-1'),true);
  }
  rows.push({name:variant+'-late-inventory-after-acknowledged-removal',status:'PASS',negativeControl:variant==='raw'});
}

// Load the complete real client bundle. React hooks are an explicit one-render
// seam (open panel), with JSX and primitive output represented as element data. Exercise the
// actual Remove click closure for pending approval and interrupted orchestration.
const jsx={jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props}),Fragment:'Fragment'};
function clientPanel(source){
  let exports,stateIndex=0;
  const hooks={useState(initial){const index=stateIndex++;let value=index===0?true:index===4?{left:0,bottom:0}:initial;return[value,update=>{value=typeof update==='function'?update(value):update;}];},
    useRef:value=>({current:value}),useLayoutEffect(){},useEffect(){}};
  const primitives=new Proxy({useDismissOnOutsidePointer(){}},{get(target,key){return target[key]||String(key);}});
  const window={__ModuleLoader__:{load(module){exports=module.factory(id=>id==='react'?hooks:id==='react/jsx-runtime'?jsx:primitives);}}};
  const before='\t\texports.apply = apply;';
  assert.equal(source.split(before).length,2);
  vm.runInNewContext(source.replace(before,'\t\texports.testPanel = CordisPanel;\n'+before),{window,console});
  return exports.testPanel;
}
function findElement(tree,predicate){
  if(!tree||typeof tree!=='object')return undefined;
  if(Array.isArray(tree)){for(const child of tree){const found=findElement(child,predicate);if(found)return found;}return undefined;}
  return predicate(tree)?tree:findElement(tree.props?.children,predicate);
}
for(const variant of ['raw','patched'])for(const phase of ['awaiting-approval','orchestrating'])await fixture('patched',async({runner,agent})=>{
  const definition=define(runner,agent,{client:'return { apply() {} };'});
  await runner.run(agent,definition.pluginId,definition.packageId,'run');
  const panel=clientPanel(sources.ui[variant]);let removal;
  const inventoryRows=await runner.inventory();
  const activity={phase,agentId:agent.id,pluginId:definition.pluginId,packageId:definition.packageId,requestId:runner.registry.pendingRequestFor(definition.pluginId),name:'Owned pending',purpose:'Remove pending'};
  const snapshotHook=snapshot=>selector=>selector(snapshot);
  const tree=panel({wide:true,useSessions:snapshotHook({byId:{[agent.id]:{id:agent.id,retainedBy:{mainView:1}}}}),
    useInventory:snapshotHook({rows:inventoryRows,read:true,removed:new Set()}),useActiveRuns:snapshotHook(new Map([[definition.pluginId,activity]])),
    useRunErrors:snapshotHook(new Map()),useLoaded:snapshotHook(new Map()),useRenderFailures:snapshotHook(new Map()),
    onRefresh(){},onRemove(sessionId,pluginId){assert.equal(sessionId,agent.id);removal=runner.undefineFromPanel(agent,pluginId);return removal;},t:key=>key});
  const button=findElement(tree,node=>node.props?.['data-cordis-remove']===definition.pluginId);
  if(variant==='raw')assert.ok(button===undefined||button.props.disabled===true,'negative control cannot remove pending/interrupted dynamic plugin');
  else{
    assert.ok(button);assert.equal(button.props.disabled,false);button.props.onClick();
    assert.equal((await removal).ok,true);assert.deepEqual(runner.listPlugins(agent),[]);
  }
  rows.push({name:variant+'-real-client-remove-action-'+phase,status:'PASS',negativeControl:variant==='raw'});
});
if(args.includes('--browser')){
  const browser=await browserFixture(raw);
  let selected;
  try{
    await browser.page.exposeFunction('temporaryInventory',async()=>selected.runner.inventory());
    await browser.page.exposeFunction('temporaryRemove',async(sessionId,pluginId)=>{
      assert.equal(sessionId,selected.agent.id);
      selected.calls++;
      return selected.runner.undefineFromPanel(selected.agent,pluginId);
    });
    for(const variant of ['raw','patched'])for(const phase of ['awaiting-approval','orchestrating'])await fixture('patched',async state=>{
      selected={...state,calls:0};
      const definition=define(state.runner,state.agent,{client:'return { apply() {} };'});
      await state.runner.run(state.agent,definition.pluginId,definition.packageId,'run');
      await browser.load('dsh-client-ui-cordis',variant==='patched'?[recipes.ui]:[],
        'module.exports.audit = {CordisPanel, createCordisInventory};');
      await browser.page.evaluate(async({definition,phase,sessionId})=>{
        const React=auditModules.react.default||auditModules.react;
        const ReactDOM=auditModules['react-dom'].default||auditModules['react-dom'];
        if(globalThis.temporaryRoot)ReactDOM.flushSync(()=>temporaryRoot.unmount());
        Object.assign(document.getElementById('root').style,{position:'fixed',left:'8px',right:'8px',bottom:'12px'});
        const {CordisPanel,createCordisInventory}=auditExports['@deepseek-ai/dsh-client-ui-cordis'].audit;
        const inventory=createCordisInventory({inventory:()=>temporaryInventory()},error=>{throw error;});
        const activity={phase,agentId:sessionId,pluginId:definition.pluginId,packageId:definition.packageId,requestId:'approval-1',name:'Owned pending',purpose:'Removal test'};
        let activeRuns=new Map([[definition.pluginId,activity]]);
        const staticHook=snapshot=>selector=>selector(snapshot),empty=new Map();
        const props={wide:true,t:key=>key,useSessions:staticHook({byId:{[sessionId]:{id:sessionId,retainedBy:{mainView:1}}}}),
          useInventory:selector=>selector(React.useSyncExternalStore(inventory.subscribe,inventory.getSnapshot)),
          useActiveRuns:selector=>selector(activeRuns),useRunErrors:staticHook(empty),useLoaded:staticHook(empty),useRenderFailures:staticHook(empty),
          onRefresh:()=>inventory.refresh(),onRemove:async(id,pluginId)=>{
            const result=await temporaryRemove(id,pluginId);
            if(result.ok){activeRuns=new Map();inventory.retire(pluginId);}
            inventory.refresh();return result;
          }};
        globalThis.temporaryRoot=ReactDOM.createRoot(document.getElementById('root'));
        ReactDOM.flushSync(()=>temporaryRoot.render(React.createElement(CordisPanel,props)));
        inventory.refresh();
      },{definition,phase,sessionId:state.agent.id});
      const badge=browser.page.locator('[data-cordis-badge]');
      await badge.waitFor();
      if(await badge.getAttribute('aria-expanded')!=='true')await badge.click();
      await browser.page.locator(`[data-cordis-row="${definition.pluginId}"]`).waitFor();
      const button=browser.page.locator(`[data-cordis-remove="${definition.pluginId}"]`);
      if(variant==='raw'){
        assert.ok(await button.count()===0||await button.isDisabled());
        assert.equal(selected.calls,0);
      }else{
        assert.equal(await button.isEnabled(),true);
        await browser.page.screenshot({path:path.join(owned,`${variant}-${phase}-before.png`)});
        await button.click();
        await browser.page.waitForFunction(id=>!document.querySelector(`[data-cordis-row="${id}"]`),definition.pluginId);
        assert.equal(selected.calls,1);assert.deepEqual(state.runner.listPlugins(state.agent),[]);
        await browser.page.screenshot({path:path.join(owned,`${variant}-${phase}-after.png`)});
      }
      rows.push({name:`${variant}-chromium-click-remove-${phase}`,status:'PASS',negativeControl:variant==='raw'});
    });
    assert.deepEqual(browser.errors,[]);
  }finally{await browser.close();}
}
fs.writeFileSync(path.join(owned,'results.json'),JSON.stringify({schema:1,runtime:raw,browser:args.includes('--browser')?(process.env.DSHA_BROWSER||'chromium'):null,sourceDigests:Object.fromEntries(Object.entries(recipes).map(([kind,r])=>[kind,r.sourceSha256])),patchedDigests:Object.fromEntries(Object.entries(recipes).map(([kind,r])=>[kind,r.targetSha256])),scope:'Actual complete rc5 runner with real Cordis fibers; controlled activation scheduling/cleanup rejection; complete client bundle and inventory with controlled transport. Optional browser uses locked DSH React/Primitives and real clicks routed to the actual runner; transport and session are fixtures, not a full authenticated DSH server. No device/model/external network request or persistent plugin mutation.',results:rows},null,2)+'\n');
for(const row of rows)console.log('PASS '+row.name);
console.log('Evidence: '+owned);

// Genuine rc5 React + Primitives + workspace FlatList, with the historical
// session-interaction patch composed with the candidate order patch.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {browserFixture} from './rc1-browser-fixture.mjs';
const args=process.argv.slice(2),option=name=>args.includes(name)?args[args.indexOf(name)+1]:undefined;
const runtime=option('--runtime')||process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json','utf8')).raw;
const load=name=>JSON.parse(fs.readFileSync('app/src/main/assets/'+name,'utf8'));
const reports=[];
for(const mode of ['before','after']){
  const fixture=await browserFixture(runtime);
  try{
    const recipes=[load('session-interaction-patch.json')];
    if(mode==='after')recipes.push(load('workspace-order-scale-patch.json'));
    await fixture.load('dsh-client-ui-workspace',recipes,'exports.audit={FlatList,reconcileManualOrder,zh,en};');
    const result=await fixture.page.evaluate(async()=>{
      const R=auditModules.react,D=auditModules['react-dom'],api=auditExports['@deepseek-ai/dsh-client-ui-workspace'].audit;
      const count=350,ids=Array.from({length:count},(_,i)=>'s'+i),byId=Object.fromEntries(ids.map((id,i)=>[id,{id,title:'History '+i,updatedAt:Date.now()-i*1000,running:false,blank:false,retainedBy:i===0?{mainView:1}:{},...(i>0?{parentId:'s0'}:{})}]));
      const list={ids,byId,phase:'ready',projectionsBySession:{}};
      const rowState={pinnedSessionIds:[],archivedSessionIds:[],archivedFilter:'default'};
      const sessionIds=api.reconcileManualOrder(ids,[],byId,rowState);
      const statusListeners=new Set();let statuses=new Map();
      const subscribe=listener=>{statusListeners.add(listener);return()=>statusListeners.delete(listener);};
      const snapshot=()=>statuses;
      const t=(key,values={})=>Object.entries(values).reduce((text,[name,value])=>text.replace('{'+name+'}',String(value)),api.en[key]||key);
      let opened=[];
      const root=D.createRoot(document.getElementById('root'));
      const start=performance.now();
      D.flushSync(()=>root.render(R.createElement(api.FlatList,{
        list,sessionIds,rowState,onLeaveArchivedOnly(){},useSessionStatus:selector=>selector(R.useSyncExternalStore(subscribe,snapshot)),
        open:id=>opened.push(id),onSessionRenameRequest(){},usePanelInfo:selector=>selector({activePanelId:null}),
        setSessionOrder(){},workspaceReady:true,animationResetKey:'fixture',onSessionRevealed(){},renderSlot:()=>null,t,
      })));
      await new Promise(resolve=>requestAnimationFrame(resolve));
      const mountMs=performance.now()-start;
      const selectors=()=>[...document.querySelectorAll('[data-dsha-session-select]')];
      const rendered=selectors().map(node=>node.getAttribute('data-dsha-session-select'));
      if(rendered.length!==count||new Set(rendered).size!==count)throw Error('Full history membership was not rendered');
      for(const id of ['s349','s0','s175']){
        const node=document.querySelector('[data-dsha-session-select="'+id+'"]');
        D.flushSync(()=>node.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true})));
        if(node.getAttribute('aria-selected')!=='true')throw Error('Keyboard selection did not commit: '+id);
      }
      D.flushSync(()=>{statuses=new Map([['s175',{running:true,completionUnread:false}]]);for(const listener of statusListeners)listener();});
      if(!document.querySelector('[data-dsha-session-select="s175"]').textContent.includes('Running'))throw Error('Live running status absent');
      const nodes=document.querySelectorAll('*').length,subscriptionCount=statusListeners.size;
      D.flushSync(()=>root.unmount());
      if(statusListeners.size!==0)throw Error('Status subscription leaked after unmount');
      return {count,mountMs,opened,renderedOrder:rendered,domNodes:nodes,subscriptionCount,afterUnmountSubscriptions:statusListeners.size,liveRunningStatus:true};
    });
    assert.deepEqual(result.opened,['s349','s0','s175']);
    assert.deepEqual(fixture.errors,[]);
    reports.push({mode,engine:await fixture.browser.version(),...result});
  }finally{await fixture.close();}
}
assert.deepEqual(reports[0].renderedOrder,reports[1].renderedOrder);
const report={scope:'Headless host browser, genuine rc5 React/Primitives/FlatList; 350 synthetic sessions, all rows reachable and live status/unmount checked. Not tablet performance.',reports};
const output=option('--output');if(output){fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');}
console.log(JSON.stringify({...report,reports:reports.map(({renderedOrder,...rest})=>({...rest,completeRenderedOrderCount:renderedOrder.length}))},null,2));

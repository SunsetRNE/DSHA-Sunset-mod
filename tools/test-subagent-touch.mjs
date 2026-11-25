// 执行锁定的真实 React 子代理组件，验证触摸打开、关闭和会话地址回调。
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {browserFixture} from './rc1-browser-fixture.mjs';
import {testRuntime} from './test-runtime-fixture.mjs';

const runtime=testRuntime('raw');
const spec=JSON.parse(fs.readFileSync('app/src/main/assets/subagent-navigation-patch.json','utf8'));
for(const width of [393,920]){
 const fixture=await browserFixture(runtime);
 const {page,errors}=fixture;
 try{
 await page.setViewportSize({width,height:800});
 await page.evaluate(()=>{
  document.getElementById('root').dataset.mobileNav='frame';
  const outside=document.createElement('button');
  outside.id='outside';
  outside.style.cssText='position:fixed;bottom:8px';
  outside.textContent='Outside';
  document.body.appendChild(outside);
 });
 await fixture.load('dsh-client-ui-subagent',[spec],'module.exports.audit={CatalogDropdown};');
 await page.evaluate(()=>{
  const state={byId:{parent:{id:'parent'}},projectionsBySession:{parent:{state:'idle',values:{subagentCatalog:[{kind:'child',id:'child-1',label:'My subagent',mode:'continuable',activity:'inactive',hasChildren:false}]},error:null}}};
  const React=auditModules.react;
  const ReactDOM=auditModules['react-dom/client'];
  const CatalogDropdown=auditExports['@deepseek-ai/dsh-client-ui-subagent'].audit.CatalogDropdown;
  window.opened=[];window.prepared=[];
  ReactDOM.createRoot(document.getElementById('root')).render(React.createElement(CatalogDropdown,{rootSessionId:'parent',currentSessionId:'parent',variant:'count',useSessions:select=>select(state),useSessionStatus:select=>select(new Map()),openChild:address=>opened.push(address),openChildAside:address=>opened.push(address),async prepareCatalog(id){prepared.push(id);},t:(key,values)=>key+(values?.count??'')}));
 });
 const trigger=page.locator('[data-dsha-lineage-trigger]');
 try { await trigger.waitFor({timeout:5000}); } catch (error) { throw new Error(`Subagent trigger did not render: ${errors.join(' | ')}`,{cause:error}); }
 await trigger.tap();await page.locator('[role="tree"]').waitFor();
 await page.waitForTimeout(250);assert.equal(await trigger.getAttribute('aria-expanded'),'true');
 await page.locator('[role="treeitem"]').tap();await page.locator('[role="tree"]').waitFor({state:'detached'});
 assert.deepEqual(await page.evaluate(()=>opened),[{parentSessionId:'parent',childSessionId:'child-1',mode:'continuable'}]);
 await trigger.tap();await page.locator('[role="tree"]').waitFor();await page.locator('#outside').tap();await page.waitForTimeout(300);assert.equal(await trigger.getAttribute('aria-expanded'),'false');
 await trigger.tap();await page.locator('[role="tree"]').waitFor();await trigger.tap();
 await page.locator('[role="tree"]').waitFor({state:'detached'});
 assert.equal(await trigger.getAttribute('aria-expanded'),'false');
 await trigger.tap();await page.locator('[role="tree"]').waitFor();
 await page.locator('#outside').tap();await page.locator('[role="tree"]').waitFor({state:'detached'});
 assert.equal(await trigger.getAttribute('aria-expanded'),'false');
 assert.deepEqual(await page.evaluate(()=>prepared),['parent','parent','parent','parent']);
 assert.deepEqual(errors,[]);
 console.log(`Subagent ${width}px: single-tap open, child address, count toggle and outside close passed.`);
 }finally{await fixture.close();}
}

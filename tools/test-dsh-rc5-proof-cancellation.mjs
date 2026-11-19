// Execute the exact reviewed worker loop against controlled async proof providers.
// The official rc5 manager bytes and complete patch chain are pinned before extraction.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
const root=path.resolve(import.meta.dirname,'..');
const raw=path.resolve(process.env.DSHA_TEST_RUNTIME??path.join(root,'app/build/rc5-candidate'));
const assets=path.join(root,'app/src/main/assets');
const recipe=JSON.parse(fs.readFileSync(path.join(assets,'plugin-bundle-proof-workers-patch.json')));
const source=fs.readFileSync(path.join(raw,'node_modules',recipe.module),'utf8');
assert.equal(createHash('sha256').update(source).digest('hex'),recipe.sourceSha256);
let projected=source;
const registry=JSON.parse(fs.readFileSync(path.join(assets,'runtime-patches.json')));
for(const row of registry.active){
 const current=JSON.parse(fs.readFileSync(path.join(assets,row.asset)));
 if((row.module??current.module)!==recipe.module)continue;
 for(const edit of current.patches){assert.equal(projected.split(edit.before).length-1,1);projected=projected.replace(edit.before,edit.after);}
}
assert(projected.includes(recipe.patches[0].after));
const fixed=recipe.patches[0].after;
// Exact counterfactual recreates the reviewed defect, not an alternate algorithm.
const broken=fixed.replace('let dshaFailed = false, dshaFailure;','let dshaFailure;')
 .replace('error => { if (!dshaFailed) { dshaFailed = true; dshaFailure = error; } }','error => { dshaFailure ??= error; }')
 .replace('if (dshaFailed) break;','if (dshaFailure) break;')
 .replace('if (dshaFailed) throw dshaFailure;','if (dshaFailure) throw dshaFailure;');
assert.notEqual(broken,fixed);
async function execute(body,reason){
 const abort=new AbortController();abort.abort(reason);let reads=0,active=0,maxActive=0;
 const env={Map,Set,Promise,process:{platform:'linux'},names:['one','two','three'],dependencies:[],selected:[],recorded:{},catalog:new Map(),exemptions:[],OPTIONAL_BUNDLES:[],dshaSignedPluginNames:new Set(),
  dshaReadProofSession:async()=>({signal:abort.signal}),getDshRuntimeVersion:()=> '0.2.2-rc.5',
  dshaManagedInstallationBundle:async(_name,_profile,session)=>{reads++;maxActive=Math.max(maxActive,++active);try{await Promise.resolve();session.signal.throwIfAborted();return false;}finally{active--;}}};
 const run=vm.runInNewContext('(async function(dshaOnlyName){'+body+'\nreturn bundles;})',env);
 try{const rows=await run.call({profile:{}});return{status:'resolved',rows,reads,active,maxActive};}
 catch(error){return{status:'rejected',error,reads,active,maxActive};}
}
const records=[];
for(const reason of [null,false,0,'',undefined,new Error('synthetic cancellation')]){
 const actual=await execute(fixed,reason);
 assert.equal(actual.status,'rejected');assert.equal(actual.reads,2);assert.equal(actual.active,0);assert.equal(actual.maxActive,2);
 if(reason===undefined)assert.equal(actual.error.name,'AbortError');else assert.equal(actual.error,reason);
 if([null,false,0,''].includes(reason)){
  const prior=await execute(broken,reason);assert.equal(prior.status,'resolved');assert.equal(prior.rows.length,0);assert.equal(prior.reads,3);
 }
 records.push({reason:String(reason),status:'PASS_REJECTED_ORIGINAL_REASON',proofsStarted:actual.reads,drained:actual.active===0});
}
console.log(JSON.stringify({status:'PASS_REAL_RC5_PROOF_LOOP_FALSY_CANCELLATION',sourceSha256:recipe.sourceSha256,records,scope:'Exact published manager module projection; actual worker loop, synthetic proof backend. No files, profile, network or native calls.'},null,2));

// Differential behavior and measurements use the exact upstream and patched
// production functions. No reference implementation of ordering is substituted.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';
const args=process.argv.slice(2),option=name=>args.includes(name)?args[args.indexOf(name)+1]:undefined;
const runtime=option('--runtime')||process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json','utf8')).raw;
const assets='app/src/main/assets/',recipeText=fs.readFileSync(assets+'workspace-order-scale-patch.json','utf8'),recipe=JSON.parse(recipeText);
const source=fs.readFileSync(path.join(runtime,'node_modules',recipe.module),'utf8');
const hash=value=>createHash('sha256').update(value).digest('hex');
assert.equal(hash(source),recipe.sourceSha256);
let patched=source;
for(const edit of recipe.patches){assert.equal(patched.split(edit.before).length,2);patched=patched.replace(edit.before,edit.after);}
new vm.Script(patched);
function functions(value){
  const start=value.indexOf('\t\tfunction orderByRecency('),end=value.indexOf('\t\tfunction pinCurrentBlank(',start);
  assert.ok(start>0&&end>start);
  const scope={};vm.runInNewContext(value.slice(start,end)+';globalThis.reconcile=reconcileManualOrder;',scope);return scope.reconcile;
}
const implementations={before:functions(source),after:functions(patched)};
const plain=value=>JSON.parse(JSON.stringify(value));
const deepFreeze=value=>{if(value&&typeof value==='object'){Object.freeze(value);for(const child of Object.values(value))deepFreeze(child);}return value;};
let seed=712919,cases=0;
const random=max=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%max;};
for(let test=0;test<1200;test++){
  const ids=Array.from({length:random(100)},(_,i)=>'s'+i),summaries={};
  for(const [i,id] of ids.entries())if(random(7)!==0)summaries[id]={id,updatedAt:random(11),...(random(3)!==0?{parentId:'s'+random(ids.length+10)}:{})};
  const saved=ids.filter(()=>random(3)===0).sort(()=>random(3)-1);
  saved.push('unknown',...saved.slice(0,4));
  const rows={pinnedSessionIds:ids.filter(()=>random(5)===0),archivedSessionIds:ids.filter(()=>random(4)===0)};
  const inputs=deepFreeze([[...ids,...ids.slice(0,3)],test%4===0?undefined:saved,summaries,test%9===0?undefined:rows]);
  assert.deepEqual(plain(implementations.after(...inputs)),plain(implementations.before(...inputs)));
  cases++;
}
// Named cycle, pinned/archived parents, and saved relative order cases.
for(const input of [
  [[],[],{},{}],
  [['a','b','c'],[],{a:{updatedAt:1,parentId:'b'},b:{updatedAt:2,parentId:'a'},c:{updatedAt:3,parentId:'a'}},{}],
  [['a','b','c'],['b','a'],{a:{updatedAt:1},b:{updatedAt:2},c:{updatedAt:3,parentId:'a'}},{pinnedSessionIds:['a'],archivedSessionIds:['b']}],
]){deepFreeze(input);assert.deepEqual(plain(implementations.after(...input)),plain(implementations.before(...input)));cases++;}

const results=[];
function fixture(count,kind){
  const ids=Array.from({length:count},(_,i)=>'s'+i);
  const summaries=Object.fromEntries(ids.map((id,i)=>[id,{id,updatedAt:kind==='chain'?-i:i,parentId:kind==='siblings'&&i>0?'s0':kind==='chain'&&i>0?'s'+(i-1):undefined}]));
  return [ids,[],summaries,{}];
}
for(const count of [100,1000,5000,10000])for(const kind of ['ordinary','siblings']){
  const inputs=deepFreeze(fixture(count,kind));
  const expected=plain(implementations.before(...inputs));
  for(const version of ['before','after']){
    const times=[];
    for(let sample=0;sample<9;sample++){
      const start=performance.now(),value=implementations[version](...inputs),elapsed=performance.now()-start;
      assert.deepEqual(plain(value),expected);
      if(sample>0)times.push(elapsed);
    }
    const sorted=[...times].sort((a,b)=>a-b);
    results.push({count,kind,version,medianMs:sorted[3],p95Ms:sorted[6],samplesMs:times});
  }
}
const deep=deepFreeze(fixture(30000,'chain'));
const deepResults={count:deep[0].length};
try{implementations.before(...deep);deepResults.before='completed';}catch(error){assert.match(String(error),/call stack/i);deepResults.before=String(error);}
const start=performance.now(),ordered=implementations.after(...deep);
deepResults.afterMs=performance.now()-start;
assert.equal(ordered.length,deep[0].length);
assert.equal(new Set(ordered).size,deep[0].length);
assert.deepEqual(plain(ordered),[...deep[0]].reverse());
deepResults.after='completed with every session exactly once in fork-before-parent order';
// Verify composition with every currently registered workspace patch, without
// changing the active registry. The candidate recipe is applied exactly once.
let combined=source;
const registry=JSON.parse(fs.readFileSync(assets+'runtime-patches.json','utf8'));
const applied=[];
for(const row of registry.active){
  const current=JSON.parse(fs.readFileSync(assets+row.asset,'utf8'));
  if((row.module||current.module)!==recipe.module||row.asset==='workspace-order-scale-patch.json')continue;
  for(const edit of current.patches){
    assert.equal(combined.split(edit.before).length,2,row.asset);
    combined=combined.replace(edit.before,(edit.prependAsset?fs.readFileSync(assets+edit.prependAsset,'utf8')+'\n':'')+edit.after);
  }
  applied.push(row.asset);
}
for(const edit of recipe.patches){assert.equal(combined.split(edit.before).length,2);combined=combined.replace(edit.before,edit.after);}
new vm.Script(combined);
const report={scope:'Windows Node host; exact production workspace order functions with synthetic frozen histories, not device timing',node:process.version,cases,sourceSha256:hash(source),patchedSha256:hash(patched),recipeSha256:hash(recipeText),composedWith:applied,deepChain:deepResults,measurements:results};
const output=option('--output');if(output){fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');}
console.log(JSON.stringify(report,null,2));

// Execute the real locked rc5 UiSession class before/after the exact asset patch.
// Synthetic immutable Controller snapshots and remote events are the environment;
// status projection, reconciliation, and subscriptions are production methods.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createHash} from 'node:crypto';
import {performance} from 'node:perf_hooks';

const args=process.argv.slice(2);
const option=name=>args.includes(name)?args[args.indexOf(name)+1]:undefined;
const runtime=option('--runtime')||process.env.DSHA_TEST_RUNTIME||JSON.parse(fs.readFileSync('app/build/test-runtimes/current.json','utf8')).raw;
const assets='app/src/main/assets/';
const recipeText=fs.readFileSync(assets+'session-status-scale-patch.json','utf8'),recipe=JSON.parse(recipeText);
const original=fs.readFileSync(path.join(runtime,'node_modules',recipe.module),'utf8');
const digest=value=>createHash('sha256').update(value).digest('hex');
assert.equal(digest(original),recipe.sourceSha256);
assert.equal(JSON.parse(fs.readFileSync(path.join(runtime,'node_modules/@deepseek-ai/dsh-client-ui-session/package.json'),'utf8')).version,recipe.dshVersion);
let patched=original;
for(const patch of recipe.patches){assert.equal(patched.split(patch.before).length,2);patched=patched.replace(patch.before,patch.after);}

function load(source){
  let exports;
  const deps={
    '@deepseek-ai/cordis':{Service:class {constructor(ctx){this.ctx=ctx;}}},
    '@deepseek-ai/dsh-client-store':{notifySubscribers(listeners){for(const listener of [...listeners])listener();}},
    '@deepseek-ai/dsh-client-ui-slots':{standardHookPropName:key=>'use:'+key},
    'react/jsx-runtime':{},
  };
  vm.runInNewContext(source,{window:{__ModuleLoader__:{load:({factory})=>{exports=factory(name=>{assert.ok(name in deps,name);return deps[name];});}}},WeakRef,FinalizationRegistry});
  return exports.UiSession;
}
const versions={before:load(original),after:load(patched)};
const row=(id,running=false,main=false)=>Object.freeze({id,running,retainedBy:Object.freeze(main?{mainView:1}:{})});
function listOf(count,phase='ready'){
  const ids=Array.from({length:count},(_,i)=>'s'+i);
  return Object.freeze({ids:Object.freeze(ids),byId:Object.freeze(Object.fromEntries(ids.map(id=>[id,row(id)]))),phase});
}
function fixture(kind,initial){
  let list=initial,broadcasts=0,projections=0;
  const listeners=new Set(),events=new Map(),retains=new Map(),cleanups=[];
  const sessions={
    list:{getSnapshot:()=>list,subscribe(fn){listeners.add(fn);return()=>listeners.delete(fn);}},
    retainInfo(id){if(!retains.has(id))retains.set(id,{getSnapshot:()=>({retainedBy:list.byId[id]?.retainedBy??{}}),subscribe:()=>()=>{}});return retains.get(id);},
    binding:()=>undefined,
  };
  const ctx={effect(fn){const off=fn();cleanups.push(off);return off;},remote:{$on(name,fn){events.set(name,fn);return()=>events.delete(name);}}};
  const service=new versions[kind](ctx,sessions);
  const publish=service.publishStatus.bind(service);
  service.publishStatus=(...args)=>{projections++;return publish(...args);};
  service.sessionStatus.subscribe(()=>broadcasts++);
  return {service,
    snapshot:()=>service.sessionStatus.getSnapshot(),
    update(next,notify=true){list=next;if(notify)for(const fn of [...listeners])fn();},
    remote(id,running){events.get('api-session/status')(id,running);},
    counters:()=>({broadcasts,projections}),reset(){broadcasts=0;projections=0;},
    close(){for(const off of cleanups)off();},
  };
}
const snapshot=f=>JSON.stringify([...f.snapshot()]);
let assertions=0;
function same(a,b){assert.equal(snapshot(a),snapshot(b));assertions++;}
const initial=listOf(100);
const a=fixture('before',initial),b=fixture('after',initial);
same(a,b);
const initialMap=b.snapshot(),unchanged=initialMap.get('s99');
for(const f of [a,b])f.remote('s1',true);
same(a,b);assert.equal(b.snapshot().get('s99'),unchanged);assert.equal(initialMap.get('s1').running,false);assertions+=2;
for(const f of [a,b])f.remote('s1',false);
same(a,b);assert.equal(b.snapshot().get('s1').completionUnread,true);assertions++;
let current=Object.freeze({...initial,byId:Object.freeze({...initial.byId,s1:row('s1',false,true)})});
for(const f of [a,b])f.update(current);
same(a,b);assert.equal(b.snapshot().get('s1').completionUnread,false);assertions++;
const pending={sessionId:'s3',key:'ask3',kind:'approval'};
for(const f of [a,b]){f.service.pendingDomains=[{valuesSnapshot:()=>[pending],precedence:()=>1}];f.service.publishPendingInteractions();}
same(a,b);assert.equal(b.snapshot().get('s3').pendingInteraction,pending);assertions++;
for(const f of [a,b]){f.service.pendingDomains=[];f.service.publishPendingInteractions();}
same(a,b);
// A remote event before the list subscription catches up must still publish new membership.
current=Object.freeze({...current,ids:Object.freeze([...current.ids,'late']),byId:Object.freeze({...current.byId,late:row('late')})});
for(const f of [a,b]){f.update(current,false);f.remote('s1',false);}
same(a,b);assert.ok(b.snapshot().has('late'));assertions++;
for(const f of [a,b])f.update(current);
same(a,b);
// Deterministic mixed remote/list updates cover completion, deletion, and main selection.
let seed=7151;
const random=max=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed%max;};
for(let i=0;i<240;i++){
  const id='s'+random(100),running=random(3)!==0;
  if(i%7===0){
    const byId={...current.byId};
    for(let k=0;k<20;k++){const target='s'+random(100);byId[target]=row(target,random(2)===0,target===id);}
    current=Object.freeze({...current,byId:Object.freeze(byId)});
    for(const f of [a,b])f.update(current);
  }else for(const f of [a,b])f.remote(id,running);
  same(a,b);
}
current=listOf(2);
for(const f of [a,b])f.update(current);
same(a,b);assert.equal(b.snapshot().size,2);assertions++;
const phaseA=fixture('before',listOf(0,'pending')),phaseB=fixture('after',listOf(0,'pending'));
for(const f of [phaseA,phaseB])f.remote('prebaseline',false);
same(phaseA,phaseB);assert.equal(phaseB.snapshot().get('prebaseline').completionUnread,true);assertions++;
for(const f of [phaseA,phaseB])f.update(listOf(0));
same(phaseA,phaseB);assert.equal(phaseB.snapshot().size,0);assertions++;
for(const f of [a,b,phaseA,phaseB])f.close();

const measurements=[];
function percentile(values,p){const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.floor((sorted.length-1)*p)];}
for(const count of [100,1000,5000]){
  const base=listOf(count);
  const changed=Object.freeze({...base,byId:Object.freeze(Object.fromEntries(base.ids.map((id,i)=>[id,row(id,i%5===0)])))});
  const allStopped=listOf(count);
  for(const mode of ['duplicate-remote','single-remote','batch-list']){
    for(const kind of ['before','after']){
      const times=[],counters=[];
      for(let run=0;run<7;run++){
        const f=fixture(kind,base);
        if(mode==='duplicate-remote')f.remote('s1',true);
        f.reset();const start=performance.now();
        if(mode==='duplicate-remote')for(let i=0;i<200;i++)f.remote('s1',true);
        if(mode==='single-remote')for(let i=0;i<80;i++)f.remote('s1',i%2===0);
        if(mode==='batch-list'){f.update(changed);f.update(allStopped);}
        const elapsed=performance.now()-start;
        if(run>0){times.push(elapsed);counters.push(f.counters());}
        if(mode==='batch-list'){
          assert.equal(f.snapshot().size,count);
          assert.equal([...f.snapshot().values()].filter(value=>value.completionUnread).length,Math.ceil(count/5));
        }
        f.close();
      }
      measurements.push({count,mode,kind,iterations:mode==='duplicate-remote'?200:mode==='single-remote'?80:2,medianMs:percentile(times,.5),p95Ms:percentile(times,.95),samplesMs:times,...counters[0]});
    }
  }
}
const report={scope:'Windows Node host, real hash-locked rc5 UiSession methods with synthetic immutable Controller snapshots; not Android/tablet timing or end-to-end UI',node:process.version,sourceSha256:digest(original),patchedSha256:digest(patched),recipeSha256:digest(recipeText),assertions,measurements};
const output=option('--output');if(output){fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');}
console.log(JSON.stringify(report,null,2));

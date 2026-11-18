// Execute the exact rc5 watchdog and SSE reader; no public network or model request.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
const root=path.resolve(import.meta.dirname,'..');
const raw=path.resolve(process.env.DSHA_TEST_RUNTIME??path.join(root,'app/build/rc5-candidate'));
const base=fs.mkdtempSync(path.join(root,'app/build/r5stream-'));
fs.symlinkSync(path.join(raw,'node_modules'),path.join(base,'node_modules'),'dir');
const sha=x=>createHash('sha256').update(x).digest('hex');
const records=[];
function prepare(name,file,extra=''){
 const recipe=JSON.parse(fs.readFileSync(path.join(root,'app/src/main/assets',name)));
 const source=fs.readFileSync(path.join(raw,'node_modules',recipe.module),'utf8');
 assert.equal(sha(source),recipe.sourceSha256);
 let fixed=source;for(const edit of recipe.patches){assert.equal(fixed.split(edit.before).length-1,1);fixed=fixed.replace(edit.before,edit.after);}
 fs.writeFileSync(path.join(base,file+'-raw.mjs'),source+extra);
 fs.writeFileSync(path.join(base,file+'-managed.mjs'),fixed+extra);
 return Promise.all(['raw','managed'].map(mode=>import(pathToFileURL(path.join(base,file+'-'+mode+'.mjs')))));
}
const [rawWatch,managedWatch]=await prepare('conversation-stream-cancel-patch.json','timeout');
const [rawSse,managedSse]=await prepare('deepseek-stream-cancel-patch.json','sse','\nexport {parseSse};\n');
for(const [name,api,fixed]of [['raw',rawWatch,false],['managed',managedWatch,true]]){
 const abort=new AbortController();const reason=new Error('synthetic user cancellation');
 const gate=Promise.withResolvers();const watch=api.idleWatchdog(abort.signal,1000,'IDLE');
 const pending=watch.next({next:()=>gate.promise});abort.abort(reason);
 const winner=await Promise.race([pending.then(()=>({settled:true}),error=>({settled:true,error})),new Promise(resolve=>setTimeout(()=>resolve({settled:false}),30))]);
 assert.equal(winner.settled,fixed);
 if(fixed)assert.equal(winner.error,reason);else{gate.resolve({done:true});await pending;}
 watch[Symbol.dispose]();records.push({case:name+' watchdog wakes caller abort',observed:winner.settled});
}
for(const [name,api,fixed]of [['raw',rawSse,false],['managed',managedSse,true]]){
 const abort=new AbortController();const reason=new Error('synthetic body cancellation');let cancelled=false,controller;
 const body=new ReadableStream({start(c){controller=c;},cancel(){cancelled=true;}});
 const iterator=api.parseSse(body,()=>{},abort.signal);const pending=iterator.next();abort.abort(reason);
 const winner=await Promise.race([pending.then(()=>({settled:true}),error=>({settled:true,error})),new Promise(resolve=>setTimeout(()=>resolve({settled:false}),30))]);
 assert.equal(winner.settled,fixed);assert.equal(cancelled,fixed);
 if(!fixed){controller.close();await pending;}else assert.equal(winner.error,reason);
 await iterator.return();records.push({case:name+' SSE body cancellation',settled:winner.settled,cancelled});
}
console.log(JSON.stringify({status:'PASS_REAL_RC5_STREAM_POSITIVE_NEGATIVE',records,scope:'Exact upstream modules with native ReadableStream and AbortController, controlled pending iterator; no model, HTTP or user data.'},null,2));

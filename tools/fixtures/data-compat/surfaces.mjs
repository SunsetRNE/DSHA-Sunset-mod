#!/usr/bin/env node
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {join, resolve} from 'node:path';
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {beneath} from './guards.mjs';

const [runtime, root, phase, surface, oldVersion] = process.argv.slice(2).map((x,i)=>i<2?resolve(x):x);
const owned=process.env.DSHA_DATA_COMPAT_ROOT;
assert(owned && beneath(root,resolve(owned)), 'fixture must be in runner-owned root');
const require=createRequire(join(runtime,'package.json'));
const mod=async name=>import(pathToFileURL(require.resolve('@deepseek-ai/'+name)).href);
const json=async p=>JSON.parse(await readFile(p,'utf8'));
const initial=phase==='old-write', writing=phase==='new-write', updated=['new-reopen','old-read-new'].includes(phase);
const baseValue={text:'兼容验证',nullable:null,flag:false,list:[1,'two'],nested:{retained:'yes'}};
const result={surface,phase,runtime,checks:[]};
const check=(name,details={})=>result.checks.push({name,status:'PASS',...details});

async function sessions(){
 const {Context}=await mod('cordis');
 const Jsonl=await mod('dsh-session-persistence-jsonl');
 const {Session}=await mod('dsh-session');
 const {LocalAttachmentStore}=await mod('dsh-attachment-local');
 const attachmentCtx=new Context();
 let refs;
 try{
  await attachmentCtx.plugin(LocalAttachmentStore,{dshHome:join(root,'home')});
  if(initial){
   const file=await attachmentCtx.attachments.saveFile({data:Buffer.from('verbatim binary\0中文'),'name':'引用附件.bin'});
   const sharp=require('sharp');
   const png=await sharp({create:{width:2,height:2,channels:4,background:{r:2,g:22,b:122,alpha:1}}}).png().toBuffer();
   const image=await attachmentCtx.attachments.saveImage({data:png,mediaType:'image/png',name:'引用图片.png'});
   refs={file,image}; await writeFile(join(root,'refs.json'),JSON.stringify(refs));
  }else refs=await json(join(root,'refs.json'));
  const chunks=[];for await(const c of attachmentCtx.attachments.readFileStream(refs.file))chunks.push(c);
  assert.equal(Buffer.concat(chunks).toString(),'verbatim binary\0中文');
  assert((await attachmentCtx.attachments.readImage(refs.image)).data.length>0);
 }finally{await attachmentCtx.fiber.dispose();}
 const e=(type,seq,data,extra={})=>({type,seq,time:1000+seq,data,...extra});
 const user={id:'rich-user',role:'user',source:{kind:'user'},content:[{type:'text',text:'保存工具记录和附件引用'},{type:'file',attachment:refs.file},{type:'image',attachment:refs.image}]};
 const assistant={id:'rich-assistant',role:'assistant',source:{kind:'model',provider:'audit',model:'no-network',replayState:{opaque:'retained'}},content:[{type:'reasoning',text:'合成推理片段'},{type:'text',text:'正在执行合成工具'},{type:'tool-call',id:'call-ok',name:'audit.read',arguments:'{"path":"synthetic.txt"}'},{type:'tool-call',id:'call-error',name:'audit.fail',arguments:'{}'}]};
 const good={id:'tool-ok',role:'tool',source:{kind:'tool',callId:'call-ok'},toolCallId:'call-ok',content:[{type:'text',text:'result 中文'}]};
 const bad={id:'tool-error',role:'tool',source:{kind:'tool',callId:'call-error'},toolCallId:'call-error',isError:true,content:[{type:'text',text:'synthetic failure'}]};
 const rows=[e('turn/start',0,{turn:1}),e('step/start',1,{turn:1,step:1}),
  e('system/message',2,{turn:1,step:1,message:{id:'system',role:'system',source:{kind:'system-prompt'},content:[{type:'text',text:'Audit fixture only'}]}},{surfaceOp:'append'}),
  e('user/message',3,user,{surfaceOp:'append'}),e('assistant/message',4,{turn:1,step:1,message:assistant,stream:[]},{surfaceOp:'append'}),
  e('tool/call',5,{turn:1,step:1,callId:'call-ok',name:'audit.read',arguments:'{"path":"synthetic.txt"}'}),
  e('tool/result',6,{turn:1,step:1,message:good,meta:{opaque:{unicode:'保留'}}},{surfaceOp:'append'}),
  e('tool/call',7,{turn:1,step:1,callId:'call-error',name:'audit.fail',arguments:'{}'}),
  e('tool/result',8,{turn:1,step:1,message:bad,error:{name:'AuditError',code:'SYNTHETIC',reason:'synthetic reason'},meta:{retained:true}},{surfaceOp:'append'}),
  e('plugin:audit/opaque',9,{unknown:'retained',nested:baseValue},{ignorable:true}),e('step/end',10,{turn:1,step:1}),e('turn/end',11,{turn:1,reason:{kind:'completed'}})];
 const tail=[e('turn/start',12,{turn:2}),e('user/message',13,{id:'new-user',role:'user',source:{kind:'user'},content:[{type:'text',text:'new append'}]},{surfaceOp:'append'}),e('turn/end',14,{turn:2,reason:{kind:'completed'}})];
 for(const compression of ['none','zstd']){
  const ctx=new Context();try{
   await ctx.plugin(Jsonl,{root:join(root,'sessions',compression),compression});
   const store=ctx.sessionPersistence;
   if(initial){const handle=await store.create({version:4,id:'rich-session',createdAt:100,isSeeded:false,agentPreset:'audit-preset'});await handle.append(rows);await handle.close();}
   const handle=await store.open('rich-session',writing?'write':'read');
   const events=(await handle.read()).events;assert.deepEqual(events,updated?[...rows,...tail]:rows);
   const restored=Session.fromRestore('rich-session',events,handle.header,handle.inheritedEventCount,'shared-frozen');
   const messages=restored.deriveMessages();
   assert.deepEqual(messages.find(m=>m.id==='rich-user'),user);
   assert.deepEqual(messages.find(m=>m.id==='rich-assistant'),assistant);
   assert.deepEqual(messages.find(m=>m.id==='tool-ok'),good);
   assert.deepEqual(messages.find(m=>m.id==='tool-error'),bad);
   assert.equal(events.find(r=>r.type==='plugin:audit/opaque').data.nested.text,baseValue.text);
   if(writing)await handle.append(tail);
   await handle.close();check('v4-rich-events-and-derived-messages',{compression,eventCount:events.length,messageCount:messages.length,attachmentRefsRead:true});
  }finally{await ctx.fiber.dispose();}
 }
}

async function domain(){
 const {JsonStorageBackend}=await mod('dsh-storage-json');
 const {scheduleDomain}=await import(pathToFileURL(join(runtime,'node_modules/@deepseek-ai/dsh-schedule/lib/types/storage.js')).href);
 const legacySchedule=oldVersion==='0.2.1-rc.5';
 const legacyReader=legacySchedule&&(initial||phase==='old-read-new');
 assert.equal(scheduleDomain.version,legacyReader?1:2);
 assert.deepEqual(scheduleDomain.compatibleVersions??[],legacyReader?[]:[1]);
 for(const version of legacySchedule?[1]:[1,2]){
  const backend=new JsonStorageBackend(join(root,'domain-'+version));try{
   const open=()=>backend.kv.open({name:'schedule',version:initial?version:scheduleDomain.version,compatibleVersions:initial?undefined:scheduleDomain.compatibleVersions,layout:'single',hasGlobal:false,tables:['tasks']});
   if(legacyReader&&phase==='old-read-new'){
    await assert.rejects(open(),/stored version 2 != expected 1/);
    check('v1-schedule-reader-refuses-v2-envelope',{oldStoredVersion:version,currentDescriptorVersion:scheduleDomain.version});
    continue;
   }
   const store=await open();
   if(initial)await store.putRecord('tasks','synthetic-record',{value:baseValue});
   else{assert.deepEqual((await store.loadAll()).tables.tasks['synthetic-record'],{value:baseValue});if(writing)await store.putRecord('tasks','new-record',{value:baseValue,new:true});if(updated)assert.equal((await store.loadAll()).tables.tasks['new-record'].new,true);}
   await store.close();
   const document=await json(join(root,'domain-'+version,'schedule.json'));
   assert.equal(document.unit.version,initial||phase==='new-read'?version:scheduleDomain.version);
   check('actual-schedule-descriptor-storage-envelope',{oldStoredVersion:version,currentDescriptorVersion:scheduleDomain.version,publishedVersion:document.unit.version,businessScheduleServiceStarted:false});
  }finally{await backend.close();}
 }
}

await mkdir(root,{recursive:true});
try{if(surface==='sessions')await sessions();else if(surface==='domain')await domain();else throw Error('unknown surface');result.status='PASS';console.log(JSON.stringify(result));}
catch(error){result.status='FAIL';result.error=String(error);result.stack=error.stack;console.error(JSON.stringify(result));process.exitCode=1;}

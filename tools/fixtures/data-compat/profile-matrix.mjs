#!/usr/bin/env node
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
import {join, resolve} from 'node:path';
import {mkdir, readFile, writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
const [runtime,root]=process.argv.slice(2).map(x=>resolve(x));
assert(process.env.DSHA_DATA_COMPAT_ROOT && root.startsWith(resolve(process.env.DSHA_DATA_COMPAT_ROOT)+'/'));
const require=createRequire(join(runtime,'package.json'));
const get=async name=>import(pathToFileURL(require.resolve('@deepseek-ai/'+name)).href);
const {loadProfileDirectory,loadProfile,composeEntries}=await get('dsh-app-boot');
const official=await get('dsh-official-bundles');
const {stringify}=require('yaml');
const anchor=require.resolve('@deepseek-ai/dsh/package.json');
const sha=data=>createHash('sha256').update(data).digest('hex');
const results=[];
for(const kind of ['custom','retired-bundle','missing-bundle','headless-tuple']){
 const home=join(root,kind),name=kind==='headless-tuple'?'headless':'audit',dir=join(home,'profiles',name);
 await mkdir(dir,{recursive:true});
 const bundles=kind==='retired-bundle'?['@deepseek-ai/dsh-experimental-schedule-bundle']:kind==='missing-bundle'?['audit-uninstalled-bundle']:kind==='headless-tuple'?[...official.INSTALLATION_OWNED_PROFILE_TUPLES.headless]:[];
 const manifest={private:true,description:'synthetic-only',auditExtra:{keep:true},dependencies:{'audit-package':'1.0.0'},dsh:{profile:{bundles,auditUnknown:'preserved'}}};
 const patches=[{insert:[{id:'disabled',name:'audit-not-loaded',disabled:true,config:{flag:false,nested:{x:1}}},{id:'group',name:'@deepseek-ai/cordis-plugin-group',group:true,isolate:['auditService'],config:[{id:'nested',name:'audit-not-loaded',disabled:true,config:{text:'中文'}}]}]}];
 const p=join(dir,'package.json'),y=join(dir,'cordis.patch.yml');await writeFile(p,JSON.stringify(manifest,null,2)+'\n');await writeFile(y,'# user-owned comment\n'+stringify(patches));
 const before=await readFile(p),patchBefore=await readFile(y);
 try{
  const loaded=kind==='headless-tuple'?loadProfile('audit',name,anchor,home):loadProfileDirectory('audit',dir,anchor);
  const composed=composeEntries([loaded.patches]);
  assert.deepEqual(composed.find(x=>x.id==='disabled').config,patches[0].insert[0].config);assert.equal(composed.find(x=>x.id==='disabled').disabled,true);
  const after=JSON.parse(await readFile(p,'utf8'));assert.deepEqual(after.auditExtra,{keep:true});assert.equal(after.dsh.profile.auditUnknown,'preserved');assert.deepEqual(after.dependencies,manifest.dependencies);assert.deepEqual(await readFile(y),patchBefore);
  if(kind==='retired-bundle')assert(!after.dsh.profile.bundles.includes('@deepseek-ai/dsh-experimental-schedule-bundle'));
  if(['custom','missing-bundle'].includes(kind))assert.deepEqual(await readFile(p),before);
  results.push({kind,status:'PASS',manifestRewritten:sha(before)!==sha(await readFile(p)),beforeBundles:bundles,afterBundles:after.dsh.profile.bundles,patchBytesUnchanged:true,skippedBundles:loaded.skippedBundles.map(x=>({packageName:x.packageName,reason:x.reason})),composed});
 }catch(error){results.push({kind,status:'FAIL',error:String(error),stack:error.stack,manifestRewritten:sha(before)!==sha(await readFile(p)),patchBytesUnchanged:sha(patchBefore)===sha(await readFile(y))});}
}
const result={runtime,version:require('@deepseek-ai/dsh/package.json').version,retiredBundles:[...official.RETIRED_BUNDLES],installationOwnedTuples:official.INSTALLATION_OWNED_PROFILE_TUPLES,results};
await writeFile(join(root,'result.json'),JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({version:result.version,results:results.map(({kind,status,manifestRewritten})=>({kind,status,manifestRewritten}))}));
if(results.some(r=>r.status==='FAIL'))process.exitCode=1;
